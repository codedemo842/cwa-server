

package app.coronawarn.server.services.submission.controller;

import app.coronawarn.server.common.persistence.domain.DiagnosisKey;
import app.coronawarn.server.common.persistence.domain.config.TrlDerivations;
import app.coronawarn.server.common.persistence.service.DiagnosisKeyService;
import app.coronawarn.server.common.persistence.service.TraceTimeIntervalWarningService;
import app.coronawarn.server.common.persistence.service.utils.checkins.CheckinsDateSpecification;
import app.coronawarn.server.common.protocols.external.exposurenotification.TemporaryExposureKey;
import app.coronawarn.server.common.protocols.internal.SubmissionPayload;
import app.coronawarn.server.common.protocols.internal.pt.CheckIn;
import app.coronawarn.server.services.submission.checkins.EventCheckinDataFilter;
import app.coronawarn.server.services.submission.checkins.TooManyCheckInsAtSameDay;
import app.coronawarn.server.services.submission.config.SubmissionServiceConfig;
import app.coronawarn.server.services.submission.monitoring.SubmissionMonitor;
import app.coronawarn.server.services.submission.normalization.SubmissionKeyNormalizer;
import app.coronawarn.server.services.submission.validation.ValidSubmissionPayload;
import app.coronawarn.server.services.submission.verification.TanVerifier;
import io.micrometer.core.annotation.Timed;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StopWatch;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/version/v1")
@Validated
public class SubmissionController {

  /**
   * The route to the submission endpoint (version agnostic).
   */
  public static final String SUBMISSION_ROUTE = "/diagnosis-keys";
  private static final Logger logger = LoggerFactory.getLogger(SubmissionController.class);

  public static final Marker EVENT = MarkerFactory.getMarker("EVENT");

  private final SubmissionMonitor submissionMonitor;
  private final DiagnosisKeyService diagnosisKeyService;
  private final TanVerifier tanVerifier;
  private final Integer retentionDays;
  private final Integer randomKeyPaddingMultiplier;
  private final FakeDelayManager fakeDelayManager;
  private final SubmissionServiceConfig submissionServiceConfig;
  private final EventCheckinDataFilter checkinsDataFilter;
  private final TraceTimeIntervalWarningService traceTimeIntervalWarningSevice;
  private final TrlDerivations trlDerivations;

  SubmissionController(DiagnosisKeyService diagnosisKeyService, TanVerifier tanVerifier,
      FakeDelayManager fakeDelayManager, SubmissionServiceConfig submissionServiceConfig,
      SubmissionMonitor submissionMonitor, EventCheckinDataFilter checkinsDataFilter,
      TraceTimeIntervalWarningService traceTimeIntervalWarningSevice) {
    this.diagnosisKeyService = diagnosisKeyService;
    this.tanVerifier = tanVerifier;
    this.submissionMonitor = submissionMonitor;
    this.fakeDelayManager = fakeDelayManager;
    this.submissionServiceConfig = submissionServiceConfig;
    this.retentionDays = submissionServiceConfig.getRetentionDays();
    this.randomKeyPaddingMultiplier = submissionServiceConfig.getRandomKeyPaddingMultiplier();
    this.checkinsDataFilter = checkinsDataFilter;
    this.traceTimeIntervalWarningSevice = traceTimeIntervalWarningSevice;
    this.trlDerivations = submissionServiceConfig.getTrlDerivations();

  }

  private static byte[] generateRandomKeyData() {
    byte[] randomKeyData = new byte[16];
    new SecureRandom().nextBytes(randomKeyData);
    return randomKeyData;
  }

  /**
   * Handles diagnosis key submission requests.
   *
   * @param exposureKeys The unmarshalled protocol buffers submission payload.
   * @param tan          A tan for diagnosis verification.
   * @return An empty response body.
   */
  @PostMapping(value = SUBMISSION_ROUTE, headers = {"cwa-fake=0"})
  @Timed(description = "Time spent handling submission.")
  public DeferredResult<ResponseEntity<Void>> submitDiagnosisKey(
      @ValidSubmissionPayload @RequestBody SubmissionPayload exposureKeys,
      @RequestHeader("cwa-authorization") String tan) {
    submissionMonitor.incrementRequestCounter();
    submissionMonitor.incrementRealRequestCounter();
    return buildRealDeferredResult(exposureKeys, tan);
  }

  private DeferredResult<ResponseEntity<Void>> buildRealDeferredResult(SubmissionPayload submissionPayload,
      String tan) {
    DeferredResult<ResponseEntity<Void>> deferredResult = new DeferredResult<>();

    StopWatch stopWatch = new StopWatch();
    stopWatch.start();
    try {
      if (!this.tanVerifier.verifyTan(tan)) {
        submissionMonitor.incrementInvalidTanRequestCounter();
        deferredResult.setResult(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
      } else {
        extractAndStoreDiagnosisKeys(submissionPayload);
        CheckinsStorageResult checkinsStorageResult = extractAndStoreEventCheckins(submissionPayload);
        deferredResult.setResult(ResponseEntity.ok()
            .header("cwa-filtered-checkins", String.valueOf(checkinsStorageResult.getNumberOfFilteredCheckins()))
            .header("cwa-saved-checkins", String.valueOf(checkinsStorageResult.getNumberOfSavedCheckins()))
            .build());
      }
    } catch (Exception e) {
      deferredResult.setErrorResult(e);
    } finally {
      stopWatch.stop();
      fakeDelayManager.updateFakeRequestDelay(stopWatch.getTotalTimeMillis());
    }
    return deferredResult;
  }

  protected CheckinsStorageResult extractAndStoreEventCheckins(SubmissionPayload submissionPayload) {
    // need a container object that reflects how many checkins were filtered even if storage fails
    AtomicInteger numberOfFilteredCheckins = new AtomicInteger(0);
    AtomicInteger numberOfSavedCheckins = new AtomicInteger(0);
    try {
      checkinsDataFilter.validateCheckInsByDate(submissionPayload.getCheckInsList());
      List<CheckIn> checkins = checkinsDataFilter.filter(submissionPayload.getCheckInsList());
      numberOfFilteredCheckins.set(submissionPayload.getCheckInsList().size() - checkins.size());
      numberOfSavedCheckins.set(traceTimeIntervalWarningSevice.saveCheckinsWithFakeData(checkins,
          submissionServiceConfig.getRandomCheckinsPaddingMultiplier(),
          submissionServiceConfig.getRandomCheckinsPaddingPepperAsByteArray(),
          CheckinsDateSpecification.HOUR_SINCE_EPOCH_DERIVATION.apply(Instant.now().getEpochSecond()),
          submissionPayload.getSubmissionType()));
    } catch (final TooManyCheckInsAtSameDay e) {
      logger.error(EVENT, e.getMessage());
    } catch (final Exception e) {
      // Any check-in data processing related error must not interrupt the submission flow or interfere
      // with storing of the diagnosis keys
      logger.error(EVENT, "An error has occured while trying to store the event checkin data", e);
    }
    return new CheckinsStorageResult(numberOfFilteredCheckins.get(), numberOfSavedCheckins.get());
  }

  private void extractAndStoreDiagnosisKeys(SubmissionPayload submissionPayload) {
    List<DiagnosisKey> diagnosisKeys = extractValidDiagnosisKeysFromPayload(
        enhanceWithDefaultValuesIfMissing(submissionPayload));
    for (DiagnosisKey diagnosisKey : diagnosisKeys) {
      mapTrasmissionRiskValue(diagnosisKey);
    }
    diagnosisKeyService.saveDiagnosisKeys(padDiagnosisKeys(diagnosisKeys));
  }

  private void mapTrasmissionRiskValue(DiagnosisKey diagnosisKey) {
    diagnosisKey.setTransmissionRiskLevel(
        trlDerivations.mapFromTrlSubmittedToTrlToStore(diagnosisKey.getTransmissionRiskLevel()));
  }

  private List<DiagnosisKey> extractValidDiagnosisKeysFromPayload(SubmissionPayload submissionPayload) {
    List<TemporaryExposureKey> protoBufferKeys = submissionPayload.getKeysList();

    List<DiagnosisKey> diagnosisKeys = protoBufferKeys.stream()
        .map(protoBufferKey -> DiagnosisKey.builder()
            .fromTemporaryExposureKeyAndMetadata(
                protoBufferKey,
                submissionPayload.getSubmissionType(),
                submissionPayload.getVisitedCountriesList(),
                submissionPayload.getOrigin(),
                submissionPayload.getConsentToFederation())
            .withFieldNormalization(new SubmissionKeyNormalizer(submissionServiceConfig))
            .build()
        )
        .filter(diagnosisKey -> diagnosisKey.isYoungerThanRetentionThreshold(retentionDays))
        .collect(Collectors.toList());

    if (protoBufferKeys.size() > diagnosisKeys.size()) {
      logger.warn("Not persisting {} diagnosis key(s), as it is outdated beyond retention threshold.",
          protoBufferKeys.size() - diagnosisKeys.size());
    }
    return diagnosisKeys;
  }

  private SubmissionPayload enhanceWithDefaultValuesIfMissing(SubmissionPayload submissionPayload) {
    String originCountry = defaultIfEmptyOriginCountry(submissionPayload.getOrigin());

    return SubmissionPayload.newBuilder()
        .addAllKeys(submissionPayload.getKeysList())
        .setRequestPadding(submissionPayload.getRequestPadding())
        .addAllVisitedCountries(submissionPayload.getVisitedCountriesList())
        .setOrigin(originCountry)
        .setConsentToFederation(submissionPayload.getConsentToFederation())
        .build();
  }

  private String defaultIfEmptyOriginCountry(String originCountry) {
    return StringUtils.defaultIfBlank(originCountry, submissionServiceConfig.getDefaultOriginCountry());
  }

  private List<DiagnosisKey> padDiagnosisKeys(List<DiagnosisKey> diagnosisKeys) {
    List<DiagnosisKey> paddedDiagnosisKeys = new ArrayList<>();
    diagnosisKeys.forEach(diagnosisKey -> {
      paddedDiagnosisKeys.add(diagnosisKey);
      IntStream.range(1, randomKeyPaddingMultiplier)
          .mapToObj(index -> DiagnosisKey.builder()
              .withKeyDataAndSubmissionType(generateRandomKeyData(), diagnosisKey.getSubmissionType())
              .withRollingStartIntervalNumber(diagnosisKey.getRollingStartIntervalNumber())
              .withTransmissionRiskLevel(diagnosisKey.getTransmissionRiskLevel())
              .withRollingPeriod(diagnosisKey.getRollingPeriod())
              .withVisitedCountries(diagnosisKey.getVisitedCountries())
              .withCountryCode(diagnosisKey.getOriginCountry())
              .withReportType(diagnosisKey.getReportType())
              .withConsentToFederation(diagnosisKey.isConsentToFederation())
              .withDaysSinceOnsetOfSymptoms(diagnosisKey.getDaysSinceOnsetOfSymptoms())
              .build())
          .forEach(paddedDiagnosisKeys::add);
    });
    return paddedDiagnosisKeys;
  }
}
