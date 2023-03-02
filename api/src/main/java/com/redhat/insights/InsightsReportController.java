/* Copyright (C) Red Hat 2022-2023 */
package com.redhat.insights;

import static com.redhat.insights.http.InsightsHttpClient.gzipReport;
import static com.redhat.insights.jars.JarUtils.computeSha512;

import com.redhat.insights.config.InsightsConfiguration;
import com.redhat.insights.http.InsightsHttpClient;
import com.redhat.insights.jars.JarInfo;
import com.redhat.insights.logging.InsightsLogger;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * The controller class has primarily responsibility for managing the upload of {@code CONNECT} and
 * {@code UPDATE} events.
 *
 * <p>Client code must explicitly manage the lifecycle of the controller object, and shut it down at
 * application exit.
 */
public final class InsightsReportController {

  // FIXME review usage of these
  // This will not resolve unless on the Red Hat Corporate VPN and is only needed in Staging
  private static final String CORP_PROXY = "squid.corp.redhat.com";
  private static final int CORP_PROXY_PORT = 3128;

  private final InsightsLogger logger;

  private final InsightsConfiguration configuration;

  private final InsightsReport report;

  private final Supplier<InsightsHttpClient> httpClientSupplier;

  private final InsightsScheduler scheduler;

  private final Filtering masking;

  private final CompletableFuture<String> idHashHolder;

  private final BlockingQueue<JarInfo> jarsToSend;

  private InsightsReportController(
      InsightsLogger logger,
      InsightsConfiguration configuration,
      InsightsReport report,
      Supplier<InsightsHttpClient> httpClientSupplier,
      InsightsScheduler scheduler,
      BlockingQueue<JarInfo> jarsToSend) {
    this.logger = logger;
    this.configuration = configuration;
    this.report = report;
    this.httpClientSupplier = httpClientSupplier;
    this.scheduler = scheduler;
    this.jarsToSend = jarsToSend;

    this.masking = Filtering.DEFAULT;
    this.idHashHolder = new CompletableFuture<>();
  }

  public static InsightsReportController of(
      InsightsLogger logger,
      InsightsConfiguration configuration,
      InsightsReport report,
      Supplier<InsightsHttpClient> httpClientSupplier) {
    return new InsightsReportController(
        logger,
        configuration,
        report,
        httpClientSupplier,
        InsightsCustomScheduledExecutor.of(logger, configuration),
        new LinkedBlockingQueue<>());
  }

  public static InsightsReportController of(
      InsightsLogger logger,
      InsightsConfiguration configuration,
      InsightsReport report,
      Supplier<InsightsHttpClient> httpClientSupplier,
      BlockingQueue<JarInfo> jarsToSend) {
    return new InsightsReportController(
        logger,
        configuration,
        report,
        httpClientSupplier,
        InsightsCustomScheduledExecutor.of(logger, configuration),
        jarsToSend);
  }

  public static InsightsReportController of(
      InsightsLogger logger,
      InsightsConfiguration configuration,
      InsightsReport report,
      Supplier<InsightsHttpClient> httpClientSupplier,
      InsightsScheduler scheduler,
      BlockingQueue<JarInfo> jarsToSend) {
    return new InsightsReportController(
        logger, configuration, report, httpClientSupplier, scheduler, jarsToSend);
  }

  /** Generates the report (including subreports), computes identifying hash and schedules sends */
  public void generate() {
    try {

      if (configuration.isOptingOut()) {
        throw new InsightsException("Opting out of the Red Hat Insights client");
      }
      final InsightsReport updateReport = new UpdateReportImpl(jarsToSend, logger);

      // Schedule initial event
      Runnable sendConnect =
          () -> {
            InsightsHttpClient httpClient = httpClientSupplier.get();
            if (httpClient.isReadyToSend()) {
              generateConnectReport();
              httpClient.sendInsightsReport(getIdHash() + "_connect.gz", report);
            }
          };
      scheduler.scheduleConnect(sendConnect);

      // Schedule a possible Jar send (every few mins? Defaults to 5 min)
      Runnable sendNewJarsIfAny =
          () -> {
            InsightsHttpClient httpClient = httpClientSupplier.get();
            if (httpClient.isReadyToSend() || !jarsToSend.isEmpty()) {
              updateReport.setIdHash(getIdHash());
              updateReport.generateReport(masking);
              httpClient.sendInsightsReport(getIdHash() + "_update.gz", updateReport);
            }
          };
      scheduler.scheduleJarUpdate(sendNewJarsIfAny);

    } catch (InsightsException isx) {
      scheduler.shutdown();
      throw isx;
    }
  }

  void generateConnectReport() {
    report.generateReport(masking);
    generateAndSetReportIdHash();
  }

  /** Forward the shutdown-related calls to the scheduler */
  public void shutdown() {
    scheduler.shutdown();
  }

  public boolean isShutdown() {
    return scheduler.isShutdown();
  }

  /**
   * Compute identifying hash and store it. Note that:
   *
   * <p>1.) the report already contains the report generation time, so we don't need to add a
   * timestamp for uniqueness here. 2.) this method mutates both the controller and report objects
   */
  void generateAndSetReportIdHash() {
    String reportJsonNoHash = report.serialize();
    try {
      if (!idHashHolder.isDone()) {
        String hash = computeSha512(gzipReport(reportJsonNoHash));
        idHashHolder.complete(hash);
        report.setIdHash(hash);
      }
    } catch (NoSuchAlgorithmException | IOException x) {
      throw new InsightsException("Exception when generating ID Hash: ", x);
    }
  }

  String getIdHash() {
    try {
      return idHashHolder.get();
    } catch (InterruptedException | ExecutionException x) {
      throw new InsightsException("Exception while trying to compute ID Hash: ", x);
    }
  }

  public BlockingQueue<JarInfo> getJarsToSend() {
    return jarsToSend;
  }

  public InsightsScheduler getScheduler() {
    return scheduler;
  }
}
