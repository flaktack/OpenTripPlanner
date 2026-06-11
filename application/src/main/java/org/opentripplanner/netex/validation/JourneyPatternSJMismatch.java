package org.opentripplanner.netex.validation;

import java.util.Set;
import java.util.stream.Collectors;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.rutebanken.netex.model.EntityStructure;
import org.rutebanken.netex.model.JourneyPattern_VersionStructure;
import org.rutebanken.netex.model.PointInLinkSequence_VersionedChildStructure;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.StopPointInJourneyPattern;
import org.rutebanken.netex.model.StopUseEnumeration;
import org.rutebanken.netex.model.TimetabledPassingTime;

/**
 * Validates that the number of passing times in the journey and the number of stop points in the
 * pattern are equal.
 * <p>
 * Points set to stopUse=passthrough are excluded from both counts: some profiles omit them from the
 * journey (no passing time), while others (e.g. the Swiss/OPENOV feed) do give them a passing time.
 * Excluding them on both sides accepts either modelling without flagging a false mismatch.
 */
class JourneyPatternSJMismatch extends AbstractHMapValidationRule<String, ServiceJourney> {

  @Override
  public Status validate(ServiceJourney sj) {
    JourneyPattern_VersionStructure journeyPattern = index
      .getJourneyPatternsById()
      .lookup(getPatternId(sj));

    var points = journeyPattern
      .getPointsInSequence()
      .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();

    Set<String> passThroughPointIds = points
      .stream()
      .filter(JourneyPatternSJMismatch::isPassThrough)
      .map(EntityStructure::getId)
      .collect(Collectors.toSet());

    int nStopPointsInJourneyPattern = points.size() - passThroughPointIds.size();

    long nTimetablePassingTimes = sj
      .getPassingTimes()
      .getTimetabledPassingTime()
      .stream()
      .filter(passingTime -> !referencesPassThrough(passingTime, passThroughPointIds))
      .count();

    return nStopPointsInJourneyPattern != nTimetablePassingTimes ? Status.DISCARD : Status.OK;
  }

  /**
   * Does this passing time refer to a stop point that the vehicle passes through? Such passing
   * times are optional, so they are not counted against the (passthrough-excluded) pattern size.
   */
  private static boolean referencesPassThrough(
    TimetabledPassingTime passingTime,
    Set<String> passThroughPointIds
  ) {
    var ref = passingTime.getPointInJourneyPatternRef();
    return (
      ref != null && ref.getValue() != null && passThroughPointIds.contains(ref.getValue().getRef())
    );
  }

  /**
   * Does the stop point in the sequence represent a stop where the vehicle passes through without
   * stopping?
   */
  private static boolean isPassThrough(PointInLinkSequence_VersionedChildStructure point) {
    return (
      point instanceof StopPointInJourneyPattern spijp &&
      spijp.getStopUse() == StopUseEnumeration.PASSTHROUGH
    );
  }

  @Override
  public DataImportIssue logMessage(String key, ServiceJourney sj) {
    return new StopPointsMismatch(sj.getId(), getPatternId(sj));
  }

  private String getPatternId(ServiceJourney sj) {
    return sj.getJourneyPatternRef().getValue().getRef();
  }

  private static class StopPointsMismatch implements DataImportIssue {

    private final String sjId;
    private final String patternId;

    public StopPointsMismatch(String sjId, String patternId) {
      this.sjId = sjId;
      this.patternId = patternId;
    }

    @Override
    public String getMessage() {
      return (
        "Mismatch in stop points between ServiceJourney and JourneyPattern. " +
        "ServiceJourney will be skipped. " +
        " ServiceJourney=" +
        sjId +
        ", JourneyPattern= " +
        patternId
      );
    }
  }
}
