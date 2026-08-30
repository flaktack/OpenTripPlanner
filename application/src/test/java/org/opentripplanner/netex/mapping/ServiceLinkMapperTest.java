package org.opentripplanner.netex.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.netex.mapping.MappingSupport.ID_FACTORY;

import jakarta.xml.bind.JAXBElement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.opengis.gml._3.DirectPositionListType;
import net.opengis.gml._3.DirectPositionType;
import net.opengis.gml._3.LineStringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.core.model.accessibility.Accessibility;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.netex.index.hierarchy.HierarchicalMap;
import org.opentripplanner.netex.index.hierarchy.HierarchicalMapById;
import org.opentripplanner.netex.mapping.support.NetexMainAndSubMode;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.framework.DefaultEntityById;
import org.opentripplanner.transit.model.framework.EntityById;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.service.SiteRepository;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.LinkSequenceProjection;
import org.rutebanken.netex.model.LinkSequenceProjection_VersionStructure;
import org.rutebanken.netex.model.LinksInJourneyPattern_RelStructure;
import org.rutebanken.netex.model.LocationStructure;
import org.rutebanken.netex.model.PointsInJourneyPattern_RelStructure;
import org.rutebanken.netex.model.Projections_RelStructure;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.ScheduledStopPointRefStructure;
import org.rutebanken.netex.model.ServiceLink;
import org.rutebanken.netex.model.ServiceLinkInJourneyPattern_VersionedChildStructure;
import org.rutebanken.netex.model.ServiceLinkRefStructure;
import org.rutebanken.netex.model.SimplePoint_VersionStructure;
import org.rutebanken.netex.model.StopPointInJourneyPattern;

class ServiceLinkMapperTest {

  private static final Double[] SERVICE_LINKS_COORDINATES = {
    59.90929,
    10.74527,
    59.90893,
    10.74493,
    59.90870,
    10.74585,
  };

  private static final Double[] QUAY1_COORDINATES = { 59.9093, 10.7453 };
  private static final Double[] QUAY2_COORDINATES = { 59.9089, 10.7449 };
  private static final Double[] QUAY3_COORDINATES = { 59.9087, 10.7459 };
  public static final double FLOATING_POINT_COMPARISON_PRECISION = 0.000001;
  private StopPattern.StopPatternBuilder stopPatternBuilder;
  private ServiceLinkMapper serviceLinkMapper;
  private DataImportIssueStore issueStore;
  private HierarchicalMap<String, String> quayIdByStopPointRef;
  private EntityById<RegularStop> stopsById;

  @BeforeEach
  void setUpTestData() {
    ServiceLink serviceLink1 = createServiceLink(
      "RUT:ServiceLink:1",
      "RUT:StopPoint:1",
      "RUT:StopPoint:2",
      new Double[] {
        SERVICE_LINKS_COORDINATES[0],
        SERVICE_LINKS_COORDINATES[1],
        SERVICE_LINKS_COORDINATES[2],
        SERVICE_LINKS_COORDINATES[3],
      }
    );
    ServiceLink serviceLink2 = createAlternativeServiceLink(
      "RUT:ServiceLink:2",
      "RUT:StopPoint:2",
      "RUT:StopPoint:3",
      new Double[] {
        SERVICE_LINKS_COORDINATES[2],
        SERVICE_LINKS_COORDINATES[3],
        SERVICE_LINKS_COORDINATES[4],
        SERVICE_LINKS_COORDINATES[5],
      }
    );

    HierarchicalMapById<ServiceLink> serviceLinksById = new HierarchicalMapById<>();
    serviceLinksById.add(serviceLink1);
    serviceLinksById.add(serviceLink2);

    Quay quay1 = new Quay()
      .withId("NSR:Quay:1")
      .withCentroid(getLocation(QUAY1_COORDINATES[0], QUAY1_COORDINATES[1]));
    Quay quay2 = new Quay()
      .withId("NSR:Quay:2")
      .withCentroid(getLocation(QUAY2_COORDINATES[0], QUAY2_COORDINATES[1]));
    Quay quay3 = new Quay()
      .withId("NSR:Quay:3")
      .withCentroid(getLocation(QUAY3_COORDINATES[0], QUAY3_COORDINATES[1]));

    List<Quay> quaysById = List.of(quay1, quay2, quay3);

    quayIdByStopPointRef = new HierarchicalMap<>();
    quayIdByStopPointRef.add("RUT:StopPoint:1", "NSR:Quay:1");
    quayIdByStopPointRef.add("RUT:StopPoint:2", "NSR:Quay:2");
    quayIdByStopPointRef.add("RUT:StopPoint:3", "NSR:Quay:3");

    stopsById = new DefaultEntityById<>();
    issueStore = new DefaultDataImportIssueStore();

    QuayMapper quayMapper = new QuayMapper(
      ID_FACTORY,
      issueStore,
      new SiteRepository().withContext()
    );
    stopPatternBuilder = StopPattern.create(3);

    Station parentStation = Station.of(ID_FACTORY.createId("NSR:StopArea:1"))
      .withName(NonLocalizedString.ofNullable("Parent Station"))
      .withCoordinate(59.908, 10.745)
      .build();

    for (int i = 0; i < quaysById.size(); i++) {
      RegularStop stop = quayMapper.mapQuayToStop(
        quaysById.get(i),
        parentStation,
        List.of(),
        new NetexMainAndSubMode(TransitMode.BUS, "UNKNOWN"),
        Accessibility.NO_INFORMATION
      );
      stopPatternBuilder.stops.with(i, stop);
      stopsById.add(stop);
    }

    serviceLinkMapper = new ServiceLinkMapper(
      ID_FACTORY,
      serviceLinksById,
      quayIdByStopPointRef,
      Map.of(),
      stopsById,
      issueStore,
      150
    );
  }

  @Test
  void testMapValidServiceLinks() {
    JourneyPattern journeyPattern = new JourneyPattern().withId("RUT:JourneyPattern:1300");
    journeyPattern.setLinksInSequence(
      new LinksInJourneyPattern_RelStructure()
        .withServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern(
          new ServiceLinkInJourneyPattern_VersionedChildStructure().withServiceLinkRef(
            new ServiceLinkRefStructure().withRef("RUT:ServiceLink:1")
          )
        )
        .withServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern(
          new ServiceLinkInJourneyPattern_VersionedChildStructure().withServiceLinkRef(
            new ServiceLinkRefStructure().withRef("RUT:ServiceLink:2")
          )
        )
    );

    List<LineString> shape = serviceLinkMapper.getGeometriesByJourneyPattern(
      journeyPattern,
      stopPatternBuilder.build()
    );

    assertEquals(0, issueStore.listIssues().size());

    Coordinate[] coordinates = shape.get(0).getCoordinates();

    assertEquals(
      SERVICE_LINKS_COORDINATES[0],
      coordinates[0].getY(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
    assertEquals(
      SERVICE_LINKS_COORDINATES[1],
      coordinates[0].getX(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
    assertEquals(
      SERVICE_LINKS_COORDINATES[2],
      coordinates[1].getY(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
    assertEquals(
      SERVICE_LINKS_COORDINATES[3],
      coordinates[1].getX(),
      FLOATING_POINT_COMPARISON_PRECISION
    );

    coordinates = shape.get(1).getCoordinates();

    assertEquals(
      SERVICE_LINKS_COORDINATES[2],
      coordinates[0].getY(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
    assertEquals(
      SERVICE_LINKS_COORDINATES[3],
      coordinates[0].getX(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
    assertEquals(
      SERVICE_LINKS_COORDINATES[4],
      coordinates[1].getY(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
    assertEquals(
      SERVICE_LINKS_COORDINATES[5],
      coordinates[1].getX(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
  }

  @Test
  void testMapWrongNumberOfServiceLinks() {
    JourneyPattern journeyPattern = new JourneyPattern().withId("RUT:JourneyPattern:1300");

    journeyPattern.setLinksInSequence(
      new LinksInJourneyPattern_RelStructure()
        .withServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern(
          new ServiceLinkInJourneyPattern_VersionedChildStructure().withServiceLinkRef(
            new ServiceLinkRefStructure().withRef("RUT:ServiceLink:1")
          )
        )
        .withServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern(
          new ServiceLinkInJourneyPattern_VersionedChildStructure().withServiceLinkRef(
            new ServiceLinkRefStructure().withRef("RUT:ServiceLink:2")
          )
        )
        .withServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern(
          new ServiceLinkInJourneyPattern_VersionedChildStructure().withServiceLinkRef(
            new ServiceLinkRefStructure().withRef("RUT:ServiceLink:2")
          )
        )
    );

    List<LineString> shape = serviceLinkMapper.getGeometriesByJourneyPattern(
      journeyPattern,
      stopPatternBuilder.build()
    );

    assertEquals(1, issueStore.listIssues().size());

    Coordinate[] coordinates = shape.get(0).getCoordinates();

    // when the provided service links are invalid, the mapper falls back to
    // generating straight-line ServiceLinks between the stops.
    assertEquals(QUAY1_COORDINATES[0], coordinates[0].getY(), FLOATING_POINT_COMPARISON_PRECISION);
    assertEquals(QUAY1_COORDINATES[1], coordinates[0].getX(), FLOATING_POINT_COMPARISON_PRECISION);
    assertEquals(QUAY2_COORDINATES[0], coordinates[1].getY(), FLOATING_POINT_COMPARISON_PRECISION);
    assertEquals(QUAY2_COORDINATES[1], coordinates[1].getX(), FLOATING_POINT_COMPARISON_PRECISION);

    coordinates = shape.get(1).getCoordinates();

    assertEquals(QUAY2_COORDINATES[0], coordinates[0].getY(), FLOATING_POINT_COMPARISON_PRECISION);
    assertEquals(QUAY2_COORDINATES[1], coordinates[0].getX(), FLOATING_POINT_COMPARISON_PRECISION);
    assertEquals(QUAY3_COORDINATES[0], coordinates[1].getY(), FLOATING_POINT_COMPARISON_PRECISION);
    assertEquals(QUAY3_COORDINATES[1], coordinates[1].getX(), FLOATING_POINT_COMPARISON_PRECISION);
  }

  /**
   * NeTEx lets a link carry its geometry directly as well as through a LinkSequenceProjection --
   * {@code LinkGroup} declares the two as optional siblings -- and the Austrian and Italian national
   * feeds use the direct form exclusively. Reading only the projection turns every one of those into
   * a straight line.
   */
  @Test
  void testMapServiceLinkWithDirectLineString() {
    var serviceLinksById = new HierarchicalMapById<ServiceLink>();
    serviceLinksById.add(
      withDirectLineString(
        createServiceLink("RUT:ServiceLink:1", "RUT:StopPoint:1", "RUT:StopPoint:2", new Double[] {
          SERVICE_LINKS_COORDINATES[0],
          SERVICE_LINKS_COORDINATES[1],
          SERVICE_LINKS_COORDINATES[2],
          SERVICE_LINKS_COORDINATES[3],
        })
      )
    );

    List<LineString> shape = mapperFor(serviceLinksById).getGeometriesByJourneyPattern(
      journeyPatternWithLinks("RUT:ServiceLink:1"),
      twoStopPattern()
    );

    assertEquals(0, issueStore.listIssues().size());
    Coordinate[] coordinates = shape.get(0).getCoordinates();
    assertEquals(
      SERVICE_LINKS_COORDINATES[0],
      coordinates[0].getY(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
    assertEquals(
      SERVICE_LINKS_COORDINATES[3],
      coordinates[1].getX(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
  }

  /**
   * A link with neither encoding is still reported, so removing the projection requirement does not
   * make a genuinely geometry-less link silent.
   */
  @Test
  void testServiceLinkWithoutAnyGeometryIsReported() {
    var serviceLinksById = new HierarchicalMapById<ServiceLink>();
    serviceLinksById.add(
      new ServiceLink()
        .withId("RUT:ServiceLink:1")
        .withFromPointRef(new ScheduledStopPointRefStructure().withRef("RUT:StopPoint:1"))
        .withToPointRef(new ScheduledStopPointRefStructure().withRef("RUT:StopPoint:2"))
    );

    List<LineString> shape = mapperFor(serviceLinksById).getGeometriesByJourneyPattern(
      journeyPatternWithLinks("RUT:ServiceLink:1"),
      twoStopPattern()
    );

    assertEquals(1, issueStore.listIssues().size());
    assertEquals(
      "MissingProjectionInServiceLink",
      issueStore.listIssues().get(0).getType(),
      "a link with no geometry at all is still an issue"
    );
    // Straight line between the two quays.
    Coordinate[] coordinates = shape.get(0).getCoordinates();
    assertEquals(QUAY1_COORDINATES[0], coordinates[0].getY(), FLOATING_POINT_COMPARISON_PRECISION);
    assertEquals(QUAY2_COORDINATES[1], coordinates[1].getX(), FLOATING_POINT_COMPARISON_PRECISION);
  }

  /**
   * The Italian national feeds publish no linksInSequence at all -- they put the link on each point
   * as an OnwardServiceLinkRef, which is equally valid. Reading only linksInSequence loses their
   * geometry silently, without even an issue to show for it.
   */
  @Test
  void testMapServiceLinksFromOnwardServiceLinkRef() {
    JourneyPattern journeyPattern = new JourneyPattern().withId("RUT:JourneyPattern:1300");
    journeyPattern.setPointsInSequence(
      new PointsInJourneyPattern_RelStructure().withPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern(
        new StopPointInJourneyPattern()
          .withId("RUT:StopPointInJourneyPattern:1")
          .withOnwardServiceLinkRef(new ServiceLinkRefStructure().withRef("RUT:ServiceLink:1")),
        new StopPointInJourneyPattern()
          .withId("RUT:StopPointInJourneyPattern:2")
          .withOnwardServiceLinkRef(new ServiceLinkRefStructure().withRef("RUT:ServiceLink:2")),
        new StopPointInJourneyPattern().withId("RUT:StopPointInJourneyPattern:3")
      )
    );

    List<LineString> shape = serviceLinkMapper.getGeometriesByJourneyPattern(
      journeyPattern,
      stopPatternBuilder.build()
    );

    assertEquals(0, issueStore.listIssues().size());
    // The same two hops testMapValidServiceLinks asserts, reached through the other encoding.
    assertEquals(
      SERVICE_LINKS_COORDINATES[0],
      shape.get(0).getCoordinates()[0].getY(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
    assertEquals(
      SERVICE_LINKS_COORDINATES[4],
      shape.get(1).getCoordinates()[1].getY(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
  }

  /**
   * linksInSequence wins when a pattern carries both: it is the container the hop count is checked
   * against.
   */
  @Test
  void testLinksInSequenceTakesPrecedenceOverOnwardServiceLinkRef() {
    JourneyPattern journeyPattern = journeyPatternWithLinks(
      "RUT:ServiceLink:1",
      "RUT:ServiceLink:2"
    );
    journeyPattern.setPointsInSequence(
      new PointsInJourneyPattern_RelStructure().withPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern(
        new StopPointInJourneyPattern()
          .withId("RUT:StopPointInJourneyPattern:1")
          .withOnwardServiceLinkRef(
            new ServiceLinkRefStructure().withRef("RUT:ServiceLink:doesNotExist")
          ),
        new StopPointInJourneyPattern().withId("RUT:StopPointInJourneyPattern:2")
      )
    );

    List<LineString> shape = serviceLinkMapper.getGeometriesByJourneyPattern(
      journeyPattern,
      stopPatternBuilder.build()
    );

    assertEquals(0, issueStore.listIssues().size(), "the bad onward ref was never read");
    assertEquals(
      SERVICE_LINKS_COORDINATES[0],
      shape.get(0).getCoordinates()[0].getY(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
  }

  /** A pattern with neither encoding still falls back to straight lines, and stays silent. */
  @Test
  void testPatternWithoutAnyLinksIsSilent() {
    List<LineString> shape = serviceLinkMapper.getGeometriesByJourneyPattern(
      new JourneyPattern().withId("RUT:JourneyPattern:1300"),
      stopPatternBuilder.build()
    );

    assertEquals(0, issueStore.listIssues().size());
    assertEquals(2, shape.size());
    assertEquals(
      QUAY1_COORDINATES[0],
      shape.get(0).getCoordinates()[0].getY(),
      FLOATING_POINT_COMPARISON_PRECISION
    );
  }

  private ServiceLinkMapper mapperFor(HierarchicalMapById<ServiceLink> serviceLinksById) {
    return new ServiceLinkMapper(
      ID_FACTORY,
      serviceLinksById,
      quayIdByStopPointRef,
      Map.of(),
      stopsById,
      issueStore,
      150
    );
  }

  private StopPattern twoStopPattern() {
    StopPattern.StopPatternBuilder builder = StopPattern.create(2);
    builder.stops.with(0, stopsById.get(ID_FACTORY.createId("NSR:Quay:1")));
    builder.stops.with(1, stopsById.get(ID_FACTORY.createId("NSR:Quay:2")));
    return builder.build();
  }

  private JourneyPattern journeyPatternWithLinks(String... serviceLinkRefs) {
    var links = new LinksInJourneyPattern_RelStructure();
    for (String ref : serviceLinkRefs) {
      links.withServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern(
        new ServiceLinkInJourneyPattern_VersionedChildStructure().withServiceLinkRef(
          new ServiceLinkRefStructure().withRef(ref)
        )
      );
    }
    JourneyPattern journeyPattern = new JourneyPattern().withId("RUT:JourneyPattern:1300");
    journeyPattern.setLinksInSequence(links);
    return journeyPattern;
  }

  /** Move a link's geometry out of its projection and onto the link itself. */
  private ServiceLink withDirectLineString(ServiceLink serviceLink) {
    var lineString = (
      (LinkSequenceProjection) serviceLink
        .getProjections()
        .getProjectionRefOrProjection()
        .get(0)
        .getValue()
    ).getLineString();
    return serviceLink.withProjections(null).withLineString(lineString);
  }

  private SimplePoint_VersionStructure getLocation(double latitude, double longitude) {
    return new SimplePoint_VersionStructure().withLocation(
      new LocationStructure()
        .withLongitude(BigDecimal.valueOf(longitude))
        .withLatitude(BigDecimal.valueOf(latitude))
    );
  }

  private ServiceLink createServiceLink(String id, String from, String to, Double[] coordinates) {
    DirectPositionListType directPositionListType = new DirectPositionListType().withValue(
      coordinates
    );
    LinkSequenceProjection linkSequenceProjection = new LinkSequenceProjection().withLineString(
      new LineStringType().withPosList(directPositionListType)
    );
    JAXBElement<LinkSequenceProjection_VersionStructure> linkSequenceProjection_versionStructure =
      MappingSupport.createJaxbElement(linkSequenceProjection);
    Projections_RelStructure projections_relStructure =
      new Projections_RelStructure().withProjectionRefOrProjection(
        linkSequenceProjection_versionStructure
      );

    return new ServiceLink()
      .withId(id)
      .withFromPointRef(new ScheduledStopPointRefStructure().withRef(from))
      .withToPointRef(new ScheduledStopPointRefStructure().withRef(to))
      .withProjections(projections_relStructure);
  }

  private ServiceLink createAlternativeServiceLink(
    String id,
    String from,
    String to,
    Double[] coordinates
  ) {
    var posOrPoints = new ArrayList<>();
    for (int i = 0; i < coordinates.length; i += 2) {
      posOrPoints.add(new DirectPositionType().withValue(coordinates[i], coordinates[i + 1]));
    }
    var lineString = new LineStringType().withPosOrPointProperty(posOrPoints);
    var linkSequenceProjection = new LinkSequenceProjection().withLineString(lineString);
    var linkSequenceProjection_versionStructure = MappingSupport.createJaxbElement(
      linkSequenceProjection
    );
    var projections_relStructure = new Projections_RelStructure().withProjectionRefOrProjection(
      linkSequenceProjection_versionStructure
    );

    return new ServiceLink()
      .withId(id)
      .withFromPointRef(new ScheduledStopPointRefStructure().withRef(from))
      .withToPointRef(new ScheduledStopPointRefStructure().withRef(to))
      .withProjections(projections_relStructure);
  }
}
