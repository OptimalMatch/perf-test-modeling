package com.bank.wid.load;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public class PerformanceReportGenerator {

    private final String scenarioName;
    private final long totalWebhooksSent;
    private final Instant startTime;
    private Instant endTime;

    // Webhook response times (in nanoseconds)
    private final long[] responseTimes;
    private int responseTimeIndex = 0;

    // Counters
    private final AtomicLong alertsProcessed = new AtomicLong(0);
    private final AtomicLong alertsSkippedInactive = new AtomicLong(0);
    private final AtomicLong alertsSkippedThrottled = new AtomicLong(0);
    private final AtomicLong alertsSkippedNotFound = new AtomicLong(0);
    private final AtomicLong alertsFailed = new AtomicLong(0);

    // Thread pool
    private long peakQueueDepth = 0;
    private long callerRunsCount = 0;
    private long peakActiveThreads = 0;

    // MongoDB
    private double avgMongoLookupMs = 0;
    private double avgMongoUpdateMs = 0;
    private long totalMongoOps = 0;

    // Market data
    private double cacheHitRatio = 0;
    private double avgFetchTimeMissMs = 0;
    private double avgFetchTimeHitMs = 0;

    // Kafka
    private long kafkaMessagesPublished = 0;
    private double avgKafkaPublishMs = 0;

    // Memory
    private long peakHeapMB = 0;
    private long gcPauseCount = 0;
    private long gcPauseTotalMs = 0;

    // Orchestration
    private double avgOrchestrationTimeMs = 0;
    private double peakOrchestrationPerSec = 0;

    public PerformanceReportGenerator(String scenarioName, long totalWebhooksSent) {
        this.scenarioName = scenarioName;
        this.totalWebhooksSent = totalWebhooksSent;
        this.startTime = Instant.now();
        this.responseTimes = new long[(int) Math.min(totalWebhooksSent, 10_000_000)];
    }

    public synchronized void recordResponseTime(long nanos) {
        if (responseTimeIndex < responseTimes.length) {
            responseTimes[responseTimeIndex++] = nanos;
        }
    }

    public void markComplete() {
        this.endTime = Instant.now();
    }

    // Setters for metrics collected after test
    public void setAlertsProcessed(long val) { alertsProcessed.set(val); }
    public void setAlertsSkippedInactive(long val) { alertsSkippedInactive.set(val); }
    public void setAlertsSkippedThrottled(long val) { alertsSkippedThrottled.set(val); }
    public void setAlertsSkippedNotFound(long val) { alertsSkippedNotFound.set(val); }
    public void setAlertsFailed(long val) { alertsFailed.set(val); }
    public void setPeakQueueDepth(long val) { peakQueueDepth = val; }
    public void setCallerRunsCount(long val) { callerRunsCount = val; }
    public void setPeakActiveThreads(long val) { peakActiveThreads = val; }
    public void setAvgMongoLookupMs(double val) { avgMongoLookupMs = val; }
    public void setAvgMongoUpdateMs(double val) { avgMongoUpdateMs = val; }
    public void setTotalMongoOps(long val) { totalMongoOps = val; }
    public void setCacheHitRatio(double val) { cacheHitRatio = val; }
    public void setAvgFetchTimeMissMs(double val) { avgFetchTimeMissMs = val; }
    public void setAvgFetchTimeHitMs(double val) { avgFetchTimeHitMs = val; }
    public void setKafkaMessagesPublished(long val) { kafkaMessagesPublished = val; }
    public void setAvgKafkaPublishMs(double val) { avgKafkaPublishMs = val; }
    public void setPeakHeapMB(long val) { peakHeapMB = val; }
    public void setGcPauseCount(long val) { gcPauseCount = val; }
    public void setGcPauseTotalMs(long val) { gcPauseTotalMs = val; }
    public void setAvgOrchestrationTimeMs(double val) { avgOrchestrationTimeMs = val; }
    public void setPeakOrchestrationPerSec(double val) { peakOrchestrationPerSec = val; }

    public String generateReport() {
        if (endTime == null) endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        long durationSec = duration.getSeconds();
        long minutes = durationSec / 60;
        long seconds = durationSec % 60;

        // Calculate percentiles
        long[] sorted = Arrays.copyOf(responseTimes, responseTimeIndex);
        Arrays.sort(sorted);
        double p50 = percentileMs(sorted, 0.50);
        double p95 = percentileMs(sorted, 0.95);
        double p99 = percentileMs(sorted, 0.99);
        double maxMs = sorted.length > 0 ? sorted[sorted.length - 1] / 1_000_000.0 : 0;

        double avgThroughput = durationSec > 0 ? (double) alertsProcessed.get() / durationSec : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append("WID SOR Performance Test Report\n");
        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append(String.format("Scenario:                    %s%n", scenarioName));
        sb.append(String.format("Duration:                    %d minutes %d seconds%n", minutes, seconds));
        sb.append(String.format("Total webhooks sent:         %,d%n", totalWebhooksSent));
        sb.append(String.format("Total alerts processed:      %,d%n", alertsProcessed.get()));
        sb.append(String.format("Total alerts skipped:        %,d (inactive: %,d, throttled: %,d, not found: %,d)%n",
                alertsSkippedInactive.get() + alertsSkippedThrottled.get() + alertsSkippedNotFound.get(),
                alertsSkippedInactive.get(), alertsSkippedThrottled.get(), alertsSkippedNotFound.get()));
        sb.append(String.format("Total alerts failed:         %,d%n", alertsFailed.get()));
        sb.append("\n");
        sb.append("Webhook Response Time:\n");
        sb.append(String.format("  P50:                       %.2f ms%n", p50));
        sb.append(String.format("  P95:                       %.2f ms%n", p95));
        sb.append(String.format("  P99:                       %.2f ms%n", p99));
        sb.append(String.format("  Max:                       %.2f ms%n", maxMs));
        sb.append("\n");
        sb.append("Orchestration Throughput:\n");
        sb.append(String.format("  Avg:                       %.0f /sec%n", avgThroughput));
        sb.append(String.format("  Peak:                      %.0f /sec%n", peakOrchestrationPerSec));
        sb.append(String.format("  Avg time:                  %.2f ms%n", avgOrchestrationTimeMs));
        sb.append("\n");
        sb.append("Thread Pool:\n");
        sb.append(String.format("  Peak queue depth:          %,d%n", peakQueueDepth));
        sb.append(String.format("  CallerRunsPolicy count:    %,d%n", callerRunsCount));
        sb.append(String.format("  Peak active threads:       %,d%n", peakActiveThreads));
        sb.append("\n");
        sb.append("MongoDB:\n");
        sb.append(String.format("  Avg lookup time:           %.2f ms%n", avgMongoLookupMs));
        sb.append(String.format("  Avg update time:           %.2f ms%n", avgMongoUpdateMs));
        sb.append(String.format("  Total ops:                 %,d%n", totalMongoOps));
        sb.append("\n");
        sb.append("Market Data:\n");
        sb.append(String.format("  Cache hit ratio:           %.1f%%%n", cacheHitRatio));
        sb.append(String.format("  Avg fetch time (miss):     %.2f ms%n", avgFetchTimeMissMs));
        sb.append(String.format("  Avg fetch time (hit):      0.00 ms%n"));
        sb.append("\n");
        sb.append("Kafka:\n");
        sb.append(String.format("  Messages published:        %,d%n", kafkaMessagesPublished));
        sb.append(String.format("  Avg publish time:          %.2f ms%n", avgKafkaPublishMs));
        sb.append("\n");
        sb.append("Memory:\n");
        sb.append(String.format("  Peak heap usage:           %,d MB%n", peakHeapMB));
        sb.append(String.format("  GC pause count:            %,d%n", gcPauseCount));
        sb.append(String.format("  GC pause total time:       %,d ms%n", gcPauseTotalMs));
        sb.append("═══════════════════════════════════════════════════════\n");

        return sb.toString();
    }

    public void writeReportToFile(String filePath) {
        String report = generateReport();
        System.out.print(report);
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            writer.print(report);
            System.out.println("Report written to: " + filePath);
        } catch (IOException e) {
            System.err.println("Failed to write report to file: " + e.getMessage());
        }
    }

    private static double percentileMs(long[] sorted, double percentile) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        index = Math.max(0, Math.min(index, sorted.length - 1));
        return sorted[index] / 1_000_000.0;
    }
}
