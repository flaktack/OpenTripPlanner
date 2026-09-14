package org.opentripplanner.netex.mapping;

import com.google.common.collect.Multimap;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.adapters.HexBinaryAdapter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.netex.index.api.NetexEntityIndexReadOnlyView;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.netex.mapping.support.NetexMainAndSubMode;
import org.opentripplanner.transit.model.framework.EntityById;
import org.opentripplanner.transit.model.network.BikeAccess;
import org.opentripplanner.transit.model.network.GroupOfRoutes;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.RouteBuilder;
import org.opentripplanner.transit.model.organization.Agency;
import org.opentripplanner.transit.model.organization.Branding;
import org.opentripplanner.transit.model.organization.Operator;
import org.rutebanken.netex.model.AllVehicleModesOfTransportEnumeration;
import org.rutebanken.netex.model.AuthorityRefStructure;
import org.rutebanken.netex.model.BrandingRefStructure;
import org.rutebanken.netex.model.FlexibleLine_VersionStructure;
import org.rutebanken.netex.model.GroupOfLinesRefStructure;
import org.rutebanken.netex.model.Line_VersionStructure;
import org.rutebanken.netex.model.Network;
import org.rutebanken.netex.model.OperatorRefStructure;
import org.rutebanken.netex.model.PresentationStructure;
import org.rutebanken.netex.model.TransportOrganisationRefStructure;
import org.rutebanken.netex.model.TransportOrganisationRefs_RelStructure;

/**
 * Maps NeTEx line to OTP Route.
 */
class RouteMapper {

  private final DataImportIssueStore issueStore;
  private final HexBinaryAdapter hexBinaryAdapter = new HexBinaryAdapter();
  private final TransportModeMapper transportModeMapper = new TransportModeMapper();

  private final FeedScopedIdFactory idFactory;
  private final EntityById<Agency> agenciesById;
  private final EntityById<Operator> operatorsById;
  private final EntityById<Branding> brandingsById;
  private final Multimap<FeedScopedId, GroupOfRoutes> groupsOfLinesByRouteId;
  private final EntityById<GroupOfRoutes> groupOfRoutesById;
  private final NetexEntityIndexReadOnlyView netexIndex;
  private final AuthorityToAgencyMapper authorityMapper;
  private final Set<String> ferryIdsNotAllowedForBicycle;

  RouteMapper(
    DataImportIssueStore issueStore,
    FeedScopedIdFactory idFactory,
    EntityById<Agency> agenciesById,
    EntityById<Operator> operatorsById,
    EntityById<Branding> brandingsById,
    Multimap<FeedScopedId, GroupOfRoutes> groupsOfLinesByRouteId,
    EntityById<GroupOfRoutes> groupOfRoutesById,
    NetexEntityIndexReadOnlyView netexIndex,
    String timeZone,
    Set<String> ferryIdsNotAllowedForBicycle
  ) {
    this.issueStore = issueStore;
    this.idFactory = idFactory;
    this.agenciesById = agenciesById;
    this.operatorsById = operatorsById;
    this.brandingsById = brandingsById;
    this.groupsOfLinesByRouteId = groupsOfLinesByRouteId;
    this.groupOfRoutesById = groupOfRoutesById;
    this.netexIndex = netexIndex;
    this.authorityMapper = new AuthorityToAgencyMapper(idFactory, timeZone);
    this.ferryIdsNotAllowedForBicycle = ferryIdsNotAllowedForBicycle;
  }

  Route mapRoute(Line_VersionStructure line) {
    RouteBuilder builder = Route.of(idFactory.createId(line.getId()));

    builder.getGroupsOfRoutes().addAll(getGroupOfRoutes(line));
    builder.withAgency(findOrCreateAuthority(line));
    builder.withOperator(findOperator(line));
    builder.withBranding(findBranding(line));
    NonLocalizedString longName = NonLocalizedString.ofNullable(line.getName().getValue());
    builder.withLongName(longName);
    builder.withShortName(line.getPublicCode());

    NetexMainAndSubMode mode;
    try {
      mode = transportModeMapper.map(line.getTransportMode(), line.getTransportSubmode());
    } catch (TransportModeMapper.UnsupportedModeException e) {
      issueStore.add(
        "UnsupportedModeInLine",
        "Unsupported mode in Line. Mode: %s, line: %s",
        e.mode,
        line.getId()
      );
      return null;
    }

    builder.withMode(mode.mainMode());
    builder.withNetexSubmode(mode.subMode());

    if (line instanceof FlexibleLine_VersionStructure) {
      builder.withFlexibleLineType(
        ((FlexibleLine_VersionStructure) line).getFlexibleLineType().value()
      );
    }

    if (line.getPresentation() != null) {
      PresentationStructure presentation = line.getPresentation();
      if (presentation.getColour() != null) {
        builder.withColor(hexBinaryAdapter.marshal(presentation.getColour()));
      }
      if (presentation.getTextColour() != null) {
        builder.withTextColor(hexBinaryAdapter.marshal(presentation.getTextColour()));
      }
    }

    // we would love to read this information from the actual feed but it's unclear where
    // this information would be located.
    // the standard defines the following enum for baggage
    // https://github.com/NeTEx-CEN/NeTEx/blob/0436cb774778ae68a682c28e0a21013e4886c883/xsd/netex_part_3/part3_fares/netex_usageParameterLuggage_support.xsd#L120
    // and there is a usage of that in one of the Entur examples
    // https://github.com/entur/profile-examples/blob/7f45e036c870205102d96ef58c7ce5008f4edcf1/netex/fares-sales/Entur_PARTIAL_EXAMPLE_INCOMPLETE_FinnmarkFullExample.xml#L1598-L1611
    // but currently it doesn't look it is being parsed.
    // until there is better information from the operators we assume that all ferries allow
    // bicycles on board.
    if (line.getTransportMode().equals(AllVehicleModesOfTransportEnumeration.WATER)) {
      if (ferryIdsNotAllowedForBicycle.contains(line.getId())) {
        builder.withBikesAllowed(BikeAccess.NOT_ALLOWED);
      } else {
        builder.withBikesAllowed(BikeAccess.ALLOWED);
      }
    }

    return builder.build();
  }

  private Collection<GroupOfRoutes> getGroupOfRoutes(Line_VersionStructure line) {
    // Relation can be set both on Line or on GroupOfLines
    // Look on both indexes so that nothing is omitted

    FeedScopedId lineId = idFactory.createId(line.getId());
    // Use set in case ref is set on both sides
    Set<GroupOfRoutes> groupsOfRoutes = new HashSet<>(groupsOfLinesByRouteId.get(lineId));

    GroupOfLinesRefStructure representedByGroupRef = line.getRepresentedByGroupRef();

    if (representedByGroupRef != null) {
      FeedScopedId groupOfRoutesId = idFactory.createId(representedByGroupRef.getRef());
      if (this.groupOfRoutesById.containsKey(groupOfRoutesId)) {
        groupsOfRoutes.add(groupOfRoutesById.get(groupOfRoutesId));
      }
    }

    return groupsOfRoutes;
  }

  /**
   * Find an agency by mapping the GroupOfLines/Network Authority, the line's own AuthorityRef, or an
   * AuthorityRef among its additionalOperators. If no authority is found a dummy agency is created
   * and returned.
   * <p>
   * Each source is tried in turn rather than chosen by an if/else: a RepresentedByGroupRef that
   * resolves to nothing is not a statement that the line has no authority, and the EU Passenger
   * Information Profile allows a line's AUTHORITY to be carried in additionalOperators when its
   * OPERATOR occupies the primary reference (EPIP §7.2.11).
   */
  private Agency findOrCreateAuthority(Line_VersionStructure line) {
    Agency agency = findAuthorityInNetwork(line);

    if (agency == null && line.getAuthorityRef() != null) {
      agency = agenciesById.get(idFactory.createId(line.getAuthorityRef().getRef()));
    }
    if (agency == null) {
      AuthorityRefStructure additional = findAdditional(line, AuthorityRefStructure.class);
      if (additional != null) {
        agency = agenciesById.get(idFactory.createId(additional.getRef()));
      }
    }
    // No authority found in Network, GroupOfLines or on the line itself.
    // Use the dummy agency, create if necessary
    return agency != null ? agency : createOrGetDummyAgency(line);
  }

  @Nullable
  private Agency findAuthorityInNetwork(Line_VersionStructure line) {
    GroupOfLinesRefStructure representedByGroupRef = line.getRepresentedByGroupRef();

    if (representedByGroupRef == null) {
      return null;
    }
    // Find authority, first in *GroupOfLines* and then if not found look in *Network*
    Network network = netexIndex.lookupNetworkForLine(representedByGroupRef.getRef());

    // TransportOrganisationRef is optional on a NETWORK (EPIP Table 62)
    if (network == null || network.getTransportOrganisationRef() == null) {
      return null;
    }
    String orgRef = network.getTransportOrganisationRef().getValue().getRef();
    return agenciesById.get(idFactory.createId(orgRef));
  }

  /**
   * The first reference of the given type among the line's additionalOperators, or {@code null}.
   * OperatorRef and AuthorityRef are both substitutable for TransportOrganisationRef, so the element
   * name is recovered from the type of the value rather than from the list.
   */
  @Nullable
  private static <T extends TransportOrganisationRefStructure> T findAdditional(
    Line_VersionStructure line,
    Class<T> type
  ) {
    TransportOrganisationRefs_RelStructure additionalOperators = line.getAdditionalOperators();

    if (additionalOperators == null) {
      return null;
    }
    for (JAXBElement<? extends TransportOrganisationRefStructure> ref : additionalOperators.getTransportOrganisationRef()) {
      if (type.isInstance(ref.getValue())) {
        return type.cast(ref.getValue());
      }
    }
    return null;
  }

  private Agency createOrGetDummyAgency(Line_VersionStructure line) {
    issueStore.add("LineWithoutAuthority", "No authority found for %s", line.getId());

    Agency agency = agenciesById.get(idFactory.createId(authorityMapper.dummyAgencyId()));

    if (agency == null) {
      agency = authorityMapper.createDummyAgency();
      agenciesById.add(agency);
    }
    return agency;
  }

  /**
   * The line's OPERATOR, taken from its primary OperatorRef or, failing that, from its
   * additionalOperators. A profile that permits only one primary TRANSPORT ORGANISATION on a LINE
   * puts the other one in additionalOperators, so a line whose AUTHORITY is primary carries its
   * OPERATOR there (EPIP Table 59).
   */
  @Nullable
  private Operator findOperator(Line_VersionStructure line) {
    OperatorRefStructure opeRef = line.getOperatorRef();

    if (opeRef == null) {
      opeRef = findAdditional(line, OperatorRefStructure.class);
    }
    if (opeRef == null) {
      return null;
    }
    return operatorsById.get(idFactory.createId(opeRef.getRef()));
  }

  @Nullable
  private Branding findBranding(Line_VersionStructure line) {
    BrandingRefStructure brandingRef = line.getBrandingRef();

    if (brandingRef == null) {
      return null;
    }

    return brandingsById.get(idFactory.createId(brandingRef.getRef()));
  }
}
