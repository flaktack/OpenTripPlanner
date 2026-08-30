package org.opentripplanner.netex.mapping;

import jakarta.xml.bind.JAXBElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.opengis.gml._3.DirectPositionType;
import net.opengis.gml._3.LineStringType;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.issues.MissingProjectionInServiceLink;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMap;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMapById;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.geometry.SphericalDistanceLibrary;
import org.opentripplanner.transit.model.framework.ImmutableEntityById;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.StopLocation;
import org.rutebanken.netex.model.JourneyPattern_VersionStructure;
import org.rutebanken.netex.model.LinkInLinkSequence_VersionedChildStructure;
import org.rutebanken.netex.model.LinkSequenceProjection_VersionStructure;
import org.rutebanken.netex.model.PointInLinkSequence_VersionedChildStructure;
import org.rutebanken.netex.model.ServiceLink;
import org.rutebanken.netex.model.ServiceLinkInJourneyPattern_VersionedChildStructure;
import org.rutebanken.netex.model.StopPointInJourneyPattern;

/**
 * Maps NeTEx ServiceLinks to arrays of LineStrings, one per hop of a journey pattern.
 * <p>
 * Both places NeTEx allows a producer to put this are read: a pattern's {@code linksInSequence} or
 * the per-point {@code OnwardServiceLinkRef}, and a link's {@code projections} or its direct
 * {@code gml:LineString}. See {@link #serviceLinkRefs} and {@link #findLineString}.
 */
class ServiceLinkMapper {

  private static final GeometryFactory GEOMETRY_FACTORY = GeometryUtils.getGeometryFactory();
  private final FeedScopedIdFactory idFactory;
  private final ReadOnlyHierarchicalMapById<ServiceLink> serviceLinkById;
  private final ReadOnlyHierarchicalMap<String, String> quayIdByStopPointRef;
  private final Map<String, RegularStop> stopByStopPointRefViaStopPlace;
  private final ImmutableEntityById<RegularStop> stopById;
  private final DataImportIssueStore issueStore;
  private final double maxStopToShapeSnapDistance;

  ServiceLinkMapper(
    FeedScopedIdFactory idFactory,
    ReadOnlyHierarchicalMapById<ServiceLink> serviceLinkById,
    ReadOnlyHierarchicalMap<String, String> quayIdByStopPointRef,
    Map<String, RegularStop> stopByStopPointRefViaStopPlace,
    ImmutableEntityById<RegularStop> stopById,
    DataImportIssueStore issueStore,
    double maxStopToShapeSnapDistance
  ) {
    this.idFactory = idFactory;
    this.serviceLinkById = serviceLinkById;
    this.quayIdByStopPointRef = quayIdByStopPointRef;
    this.stopByStopPointRefViaStopPlace = stopByStopPointRefViaStopPlace;
    this.stopById = stopById;
    this.issueStore = issueStore;
    this.maxStopToShapeSnapDistance = maxStopToShapeSnapDistance;
  }

  List<LineString> getGeometriesByJourneyPattern(
    JourneyPattern_VersionStructure journeyPattern,
    StopPattern stopPattern
  ) {
    LineString[] geometries = generateGeometriesFromServiceLinks(journeyPattern, stopPattern);

    // Make sure all geometries are generated
    for (int i = 0; i < stopPattern.getSize() - 1; ++i) {
      if (geometries[i] == null) {
        geometries[i] = createSimpleGeometry(stopPattern.getStop(i), stopPattern.getStop(i + 1));
      }
    }
    return Arrays.asList(geometries);
  }

  private LineString[] generateGeometriesFromServiceLinks(
    JourneyPattern_VersionStructure journeyPattern,
    StopPattern stopPattern
  ) {
    LineString[] geometries = new LineString[stopPattern.getSize() - 1];
    List<String> serviceLinkRefs = serviceLinkRefs(journeyPattern);
    if (serviceLinkRefs.isEmpty()) {
      return geometries;
    }

    if (serviceLinkRefs.size() != stopPattern.getSize() - 1) {
      issueStore.add(
        "WrongNumberOfServiceLinks",
        "The journey pattern %s has %d ServiceLinks but should have exactly %d",
        journeyPattern.getId(),
        serviceLinkRefs.size(),
        stopPattern.getSize() - 1
      );
      return geometries;
    }

    for (int i = 0; i < serviceLinkRefs.size(); i++) {
      String serviceLinkRef = serviceLinkRefs.get(i);
      if (serviceLinkRef == null) {
        continue;
      }
      ServiceLink serviceLink = serviceLinkById.lookup(serviceLinkRef);

      if (serviceLink != null) {
        geometries[i] = mapServiceLink(serviceLink, stopPattern, i);
      } else {
        issueStore.add(
          "MissingServiceLink",
          "ServiceLink %s not found in journey pattern %s",
          serviceLinkRef,
          journeyPattern.getId()
        );
      }
    }
    return geometries;
  }

  /**
   * The ServiceLinks of a journey pattern, in order, one per hop, with {@code null} where a hop has
   * none.
   * <p>
   * NeTEx offers two ways to say this and a profile may use either: the pattern-level
   * {@code linksInSequence}, or an {@code OnwardServiceLinkRef} on each
   * {@link StopPointInJourneyPattern} pointing at the link that leaves it. Both are optional
   * (netex_journeyPattern_version.xsd), so reading only the first silently loses the geometry of
   * every producer that publishes only the second.
   * <p>
   * {@code linksInSequence} wins when both are present: it is the container this method's caller
   * counts against the stop pattern, and the two agree in practice.
   */
  private List<String> serviceLinkRefs(JourneyPattern_VersionStructure journeyPattern) {
    if (journeyPattern.getLinksInSequence() != null) {
      List<String> refs = new ArrayList<>();
      for (LinkInLinkSequence_VersionedChildStructure link : journeyPattern
        .getLinksInSequence()
        .getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern()) {
        // A TimingLinkInJourneyPattern shares this collection and carries no geometry. It still
        // occupies a hop, so it is kept as a null rather than skipped.
        refs.add(
          link instanceof
            ServiceLinkInJourneyPattern_VersionedChildStructure serviceLinkInJourneyPattern &&
            serviceLinkInJourneyPattern.getServiceLinkRef() != null
            ? serviceLinkInJourneyPattern.getServiceLinkRef().getRef()
            : null
        );
      }
      return refs;
    }
    if (journeyPattern.getPointsInSequence() == null) {
      return List.of();
    }
    // The onward ref belongs to the point it leaves, so the last point never has one and the list
    // is one shorter than the points -- which is exactly one per hop.
    List<PointInLinkSequence_VersionedChildStructure> points = journeyPattern
      .getPointsInSequence()
      .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
    List<String> refs = new ArrayList<>();
    for (int i = 0; i < points.size() - 1; i++) {
      refs.add(
        points.get(i) instanceof StopPointInJourneyPattern stopPoint &&
          stopPoint.getOnwardServiceLinkRef() != null
          ? stopPoint.getOnwardServiceLinkRef().getRef()
          : null
      );
    }
    return refs.stream().anyMatch(Objects::nonNull) ? refs : List.of();
  }

  @Nullable
  private LineString mapServiceLink(
    ServiceLink serviceLink,
    StopPattern stopPattern,
    int stopIndex
  ) {
    LineStringType lineString = findLineString(serviceLink);
    if (lineString == null) {
      issueStore.add(new MissingProjectionInServiceLink(serviceLink.getId()));
      return null;
    } else if (!isFromToPointRefsValid(serviceLink, stopPattern, stopIndex)) {
      return null;
    }

    if (!isProjectionValid(lineString, serviceLink.getId())) {
      return null;
    }

    List<Double> positionList = getLineStringCoordinates(lineString);
    Coordinate[] coordinates = new Coordinate[positionList.size() / 2];
    for (int i = 0; i < positionList.size(); i += 2) {
      coordinates[i / 2] = new Coordinate(positionList.get(i + 1), positionList.get(i));
    }
    final LineString geometry = GEOMETRY_FACTORY.createLineString(coordinates);

    if (
      !isGeometryValid(geometry, serviceLink.getId()) ||
      !areEndpointsWithinTolerance(
        geometry,
        stopPattern.getStop(stopIndex),
        stopPattern.getStop(stopIndex + 1),
        serviceLink.getId()
      )
    ) {
      return null;
    }
    return geometry;
  }

  /**
   * The geometry of a ServiceLink, from either of the two places NeTEx allows it to be.
   * <p>
   * {@code LinkGroup} (netex_pointAndLink_version.xsd) declares {@code gml:LineString} and
   * {@code projections} as optional siblings, so a link may carry its geometry directly as well as
   * through a {@link LinkSequenceProjection_VersionStructure}. Reading only the projection loses the
   * geometry of every producer that uses the direct form.
   * <p>
   * The projection is preferred when both are present, since it is the more specific statement.
   */
  @Nullable
  private LineStringType findLineString(ServiceLink serviceLink) {
    var projections = serviceLink.getProjections();
    if (projections != null && projections.getProjectionRefOrProjection() != null) {
      for (JAXBElement<?> projectionElement : projections.getProjectionRefOrProjection()) {
        if (
          projectionElement.getValue() instanceof
            LinkSequenceProjection_VersionStructure linkSequenceProjection &&
          linkSequenceProjection.getLineString() != null
        ) {
          return linkSequenceProjection.getLineString();
        }
      }
    }
    return serviceLink.getLineString();
  }

  /** create a 2-point linestring (a straight line segment) between the two stops */
  private LineString createSimpleGeometry(StopLocation s0, StopLocation s1) {
    Coordinate[] coordinates = new Coordinate[] {
      s0.getCoordinate().asJtsCoordinate(),
      s1.getCoordinate().asJtsCoordinate(),
    };
    CoordinateSequence sequence = new PackedCoordinateSequence.Double(coordinates, 2);

    return GEOMETRY_FACTORY.createLineString(sequence);
  }

  private boolean isFromToPointRefsValid(
    ServiceLink serviceLink,
    StopPattern stopPattern,
    int stopIndex
  ) {
    String fromPointRef = serviceLink.getFromPointRef().getRef();
    RegularStop fromPointStop = lookupStop(fromPointRef);

    String toPointRef = serviceLink.getToPointRef().getRef();
    RegularStop toPointStop = lookupStop(toPointRef);

    if (fromPointStop == null || toPointStop == null) {
      issueStore.add(
        "ServiceLinkWithoutQuay",
        "Service link with missing or unknown quays. Link: %s",
        serviceLink
      );
      return false;
    } else if (!fromPointStop.equals(stopPattern.getStop(stopIndex))) {
      issueStore.add(
        "ServiceLinkQuayMismatch",
        "Service link %s with quays different from point in journey pattern. Link point: %s, journey pattern point: %s",
        serviceLink,
        stopPattern.getStop(stopIndex).getId().getId(),
        fromPointRef
      );
      return false;
    } else if (!toPointStop.equals(stopPattern.getStop(stopIndex + 1))) {
      issueStore.add(
        "ServiceLinkQuayMismatch",
        "Service link %s with quays different to point in journey pattern. Link point: %s, journey pattern point: %s",
        serviceLink,
        stopPattern.getStop(stopIndex).getId().getId(),
        toPointRef
      );
      return false;
    }
    return true;
  }

  /**
   * Resolve the stop for a scheduled stop point, preferring the Quay-based assignment and falling
   * back to a StopPlace-based assignment (see {@code NetexMapper#mapStopPlacesToScheduledStopPoints}).
   */
  @Nullable
  private RegularStop lookupStop(String stopPointRef) {
    String quayId = quayIdByStopPointRef.lookup(stopPointRef);
    if (quayId != null) {
      RegularStop stop = stopById.get(idFactory.createId(quayId));
      if (stop != null) {
        return stop;
      }
    }
    return stopByStopPointRefViaStopPlace.get(stopPointRef);
  }

  private List<Double> getLineStringCoordinates(LineStringType lineString) {
    if (lineString.getPosList() != null) {
      return lineString.getPosList().getValue();
    }
    var list = new ArrayList<Double>();
    for (Object o : lineString.getPosOrPointProperty()) {
      if (o instanceof DirectPositionType directPosition) {
        var values = directPosition.getValue();
        if (values == null || values.size() != 2) {
          continue;
        }
        list.addAll(values);
      } else {
        issueStore.add(
          "BadLineStringElementType",
          "Unhandled and unknown lineString element type: %s",
          o.getClass().getName()
        );
      }
    }
    return list;
  }

  private boolean isProjectionValid(LineStringType lineString, String id) {
    if (lineString == null) {
      issueStore.add(
        "ServiceLinkWithoutLineString",
        "Ignore linkSequenceProjection without linestring for: %s",
        id
      );
      return false;
    }
    List<Double> coordinates = getLineStringCoordinates(lineString);
    if (coordinates.size() < 4) {
      issueStore.add(
        "ServiceLinkGeometryError",
        "Ignore linkSequenceProjection with invalid linestring, " +
          "containing fewer than two coordinates for: %s",
        id
      );
      return false;
    } else if (coordinates.size() % 2 != 0) {
      issueStore.add(
        "ServiceLinkGeometryError",
        "Ignore linkSequenceProjection with invalid linestring, " +
          "containing odd number of values for coordinates: %s",
        id
      );
      return false;
    }
    return true;
  }

  private boolean isGeometryValid(Geometry geometry, String id) {
    Coordinate[] coordinates = geometry.getCoordinates();
    if (coordinates.length < 2) {
      issueStore.add(
        "ServiceLinkGeometryError",
        "Ignore linkSequenceProjection with invalid linestring, " +
          "containing fewer than two coordinates for: %s",
        id
      );
      return false;
    }
    if (geometry.getLength() == 0) {
      issueStore.add(
        "ServiceLinkGeometryError",
        "Ignore linkSequenceProjection with invalid linestring, having distance of 0 for: %s",
        id
      );
      return false;
    }
    for (Coordinate coordinate : coordinates) {
      if (Double.isNaN(coordinate.x) || Double.isNaN(coordinate.y)) {
        issueStore.add(
          "ServiceLinkGeometryError",
          "Ignore linkSequenceProjection with invalid linestring, " +
            "containing coordinate with NaN for: %s",
          id
        );
        return false;
      }
    }
    return true;
  }

  private boolean areEndpointsWithinTolerance(
    Geometry geometry,
    StopLocation fromStop,
    StopLocation toStop,
    String id
  ) {
    Coordinate[] coordinates = geometry.getCoordinates();
    Coordinate geometryStartCoordinate = coordinates[0];
    Coordinate geometryEndCoordinate = coordinates[coordinates.length - 1];

    Coordinate startCoordinate = fromStop.getCoordinate().asJtsCoordinate();
    Coordinate endCoordinate = toStop.getCoordinate().asJtsCoordinate();
    if (
      SphericalDistanceLibrary.fastDistance(startCoordinate, geometryStartCoordinate) >
      maxStopToShapeSnapDistance
    ) {
      issueStore.add(
        "ServiceLinkGeometryTooFar",
        "Ignore linkSequenceProjection with too long distance between stop and start of linestring, " +
          " stop %s, distance: %s, link id: %s",
        fromStop,
        SphericalDistanceLibrary.fastDistance(startCoordinate, geometryStartCoordinate),
        id
      );
      return false;
    } else if (
      SphericalDistanceLibrary.fastDistance(endCoordinate, geometryEndCoordinate) >
      maxStopToShapeSnapDistance
    ) {
      issueStore.add(
        "ServiceLinkGeometryTooFar",
        "Ignore linkSequenceProjection with too long distance between stop and end of linestring, " +
          " stop %s, distance: %s, link id: %s",
        toStop,
        SphericalDistanceLibrary.fastDistance(endCoordinate, geometryEndCoordinate),
        id
      );
      return false;
    }
    return true;
  }
}
