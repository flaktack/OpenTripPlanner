package org.opentripplanner.netex.mapping;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.model.impl.TransitDataImportBuilder;
import org.opentripplanner.netex.NetexTestDataSupport;
import org.opentripplanner.netex.index.NetexEntityIndex;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.service.SiteRepository;
import org.rutebanken.netex.model.AllVehicleModesOfTransportEnumeration;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.StopPlace;

class NetexMapperTest {

  private static final String QUAY_ID = "quay-1";
  private static final String SSP_ID = "ssp-1";
  private static final String STOP_PLACE_ID = "sp-1";
  private static final String FEED_ID = "sta";
  private static final RegularStop STOP = RegularStop.of(
    new FeedScopedId(FEED_ID, QUAY_ID),
    () -> 1
  ).build();
  private static final Deduplicator DEDUPLICATOR = new Deduplicator();

  @Test
  void sspWithAssignment() {
    var issueStore = new DefaultDataImportIssueStore();
    var transitBuilder = new TransitDataImportBuilder(SiteRepository.of().build(), issueStore);
    transitBuilder.siteRepository().withRegularStop(STOP);

    var netexMapper = new NetexMapper(
      transitBuilder,
      FEED_ID,
      DEDUPLICATOR,
      issueStore,
      Set.of(),
      Set.of(),
      10,
      false
    );

    var index = new NetexEntityIndex();
    index.quayById.add(new Quay().withId(QUAY_ID));
    index.quayIdByStopPointRef.add(SSP_ID, QUAY_ID);
    netexMapper.mapNetexToOtp(index.readOnlyView());

    assertEquals(
      STOP,
      transitBuilder.stopsByScheduledStopPoints().get(new FeedScopedId(FEED_ID, SSP_ID))
    );
  }

  @Test
  void sspPointsToUnknownId() {
    var issueStore = new DefaultDataImportIssueStore();

    var netexMapper = new NetexMapper(
      new TransitDataImportBuilder(SiteRepository.of().build(), issueStore),
      FEED_ID,
      DEDUPLICATOR,
      issueStore,
      Set.of(),
      Set.of(),
      10,
      false
    );

    var index = new NetexEntityIndex();
    index.quayById.add(new Quay().withId(QUAY_ID));
    index.quayIdByStopPointRef.add(SSP_ID, QUAY_ID);
    netexMapper.mapNetexToOtp(index.readOnlyView());

    var issueTypes = issueStore.listIssues().stream().map(DataImportIssue::getType).toList();

    assertThat(issueTypes).contains("ScheduledStopPointAssignedToUnknownQuay");
  }

  @Test
  void sspAssignedToStopPlaceWithSingleQuay() {
    var issueStore = new DefaultDataImportIssueStore();
    var transitBuilder = new TransitDataImportBuilder(SiteRepository.of().build(), issueStore);
    var netexMapper = newNetexMapper(transitBuilder, issueStore);

    Quay quay = NetexTestDataSupport.createQuay(QUAY_ID, "Quay", "1", 60.0, 10.0, "A");
    StopPlace stopPlace = NetexTestDataSupport.createStopPlace(
      STOP_PLACE_ID,
      "Stop place",
      "1",
      60.0,
      10.0,
      AllVehicleModesOfTransportEnumeration.BUS,
      quay
    );

    var index = new NetexEntityIndex();
    index.stopPlaceById.add(stopPlace);
    index.quayById.add(quay);
    index.stopPlaceIdByStopPointRef.add(SSP_ID, STOP_PLACE_ID);
    netexMapper.mapNetexToOtp(index.readOnlyView());

    var resolved = transitBuilder
      .stopsByScheduledStopPoints()
      .get(new FeedScopedId(FEED_ID, SSP_ID));
    assertNotNull(resolved);
    // The single quay is reused, no synthetic stop is created.
    assertEquals(new FeedScopedId(FEED_ID, QUAY_ID), resolved.getId());
    assertThat(issueTypes(issueStore)).doesNotContain("PassengerStopAssignmentStopPlaceNotFound");
  }

  @Test
  void sspAssignedToStopPlaceWithoutQuayCreatesSyntheticStop() {
    var issueStore = new DefaultDataImportIssueStore();
    var transitBuilder = new TransitDataImportBuilder(SiteRepository.of().build(), issueStore);
    var netexMapper = newNetexMapper(transitBuilder, issueStore);

    StopPlace stopPlace = NetexTestDataSupport.createStopPlace(
      STOP_PLACE_ID,
      "Stop place",
      "1",
      60.0,
      10.0,
      AllVehicleModesOfTransportEnumeration.BUS
    );

    var index = new NetexEntityIndex();
    index.stopPlaceById.add(stopPlace);
    index.stopPlaceIdByStopPointRef.add(SSP_ID, STOP_PLACE_ID);
    netexMapper.mapNetexToOtp(index.readOnlyView());

    var resolved = transitBuilder
      .stopsByScheduledStopPoints()
      .get(new FeedScopedId(FEED_ID, SSP_ID));
    assertNotNull(resolved);
    assertEquals(new FeedScopedId(FEED_ID, STOP_PLACE_ID + ":centroid"), resolved.getId());
    assertNotNull(resolved.getParentStation());
    assertEquals(new FeedScopedId(FEED_ID, STOP_PLACE_ID), resolved.getParentStation().getId());
    assertThat(issueTypes(issueStore)).doesNotContain("PassengerStopAssignmentNotFound");
  }

  @Test
  void sspAssignedToUnknownStopPlace() {
    var issueStore = new DefaultDataImportIssueStore();
    var transitBuilder = new TransitDataImportBuilder(SiteRepository.of().build(), issueStore);
    var netexMapper = newNetexMapper(transitBuilder, issueStore);

    var index = new NetexEntityIndex();
    index.stopPlaceIdByStopPointRef.add(SSP_ID, "missing-stop-place");
    netexMapper.mapNetexToOtp(index.readOnlyView());

    assertThat(issueTypes(issueStore)).contains("PassengerStopAssignmentStopPlaceNotFound");
  }

  private static NetexMapper newNetexMapper(
    TransitDataImportBuilder transitBuilder,
    DefaultDataImportIssueStore issueStore
  ) {
    return new NetexMapper(
      transitBuilder,
      FEED_ID,
      DEDUPLICATOR,
      issueStore,
      Set.of(),
      Set.of(),
      10,
      false
    );
  }

  private static List<String> issueTypes(DefaultDataImportIssueStore issueStore) {
    return issueStore.listIssues().stream().map(DataImportIssue::getType).toList();
  }
}
