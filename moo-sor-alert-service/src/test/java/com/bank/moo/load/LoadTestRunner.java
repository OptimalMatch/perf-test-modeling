package com.bank.moo.load;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class LoadTestRunner {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> targetUrls; // base URLs of MOO SOR instances

    public LoadTestRunner(List<String> targetUrls) {
        this.targetUrls = targetUrls;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(200))
                .build();
    }

    public record WebhookPayload(
            String userId,
            String triggerId,
            String triggerTypeId,
            String symbol,
            String value,
            String triggeredAt
    ) {}

    public PerformanceReportGenerator runLoadTest(
            String scenarioName,
            List<WebhookPayload> webhooks,
            Duration spreadOver,
            int concurrentSenders) {

        PerformanceReportGenerator report = new PerformanceReportGenerator(scenarioName, webhooks.size());
        ExecutorService senderPool = Executors.newFixedThreadPool(concurrentSenders);
        AtomicLong sentCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);

        long totalWebhooks = webhooks.size();
        long delayBetweenNanos = spreadOver.isZero() ? 0 :
                spreadOver.toNanos() / totalWebhooks;

        System.out.printf("Starting load test: %s%n", scenarioName);
        System.out.printf("  Webhooks: %,d | Spread: %s | Senders: %d%n",
                totalWebhooks, spreadOver, concurrentSenders);

        CountDownLatch latch = new CountDownLatch(webhooks.size());
        Instant testStart = Instant.now();

        for (int i = 0; i < webhooks.size(); i++) {
            WebhookPayload webhook = webhooks.get(i);
            int targetIndex = i % targetUrls.size();
            String baseUrl = targetUrls.get(targetIndex);

            // Pace the requests if spread duration is non-zero
            if (delayBetweenNanos > 0 && i > 0) {
                long expectedElapsedNanos = (long) i * delayBetweenNanos;
                long actualElapsedNanos = Duration.between(testStart, Instant.now()).toNanos();
                long sleepNanos = expectedElapsedNanos - actualElapsedNanos;
                if (sleepNanos > 0) {
                    try {
                        Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            senderPool.submit(() -> {
                try {
                    String json = objectMapper.writeValueAsString(
                            new java.util.HashMap<>() {{
                                put("triggerId", webhook.triggerId());
                                put("triggerTypeId", webhook.triggerTypeId());
                                put("symbol", webhook.symbol());
                                put("value", webhook.value());
                                put("triggeredAt", webhook.triggeredAt());
                            }});

                    String url = baseUrl + "/api/v1/alerts/factset/webhook?userId=" + webhook.userId();

                    long startNanos = System.nanoTime();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .timeout(Duration.ofSeconds(30))
                            .build();

                    HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                    long elapsed = System.nanoTime() - startNanos;

                    report.recordResponseTime(elapsed);

                    if (response.statusCode() != 200) {
                        errorCount.incrementAndGet();
                    }

                    long sent = sentCount.incrementAndGet();
                    if (sent % 50000 == 0) {
                        System.out.printf("  Sent: %,d / %,d (errors: %,d)%n",
                                sent, totalWebhooks, errorCount.get());
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        report.markComplete();
        senderPool.shutdown();

        System.out.printf("Load test complete. Sent: %,d, Errors: %,d%n",
                sentCount.get(), errorCount.get());

        return report;
    }

    public void waitForProcessingToComplete(String baseUrl, long expectedTotal, Duration timeout) {
        System.out.println("Waiting for async processing to complete...");
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/actuator/metrics/moo.alert.processed"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    // Parse metric value from actuator response
                    var node = objectMapper.readTree(response.body());
                    var measurements = node.get("measurements");
                    if (measurements != null && measurements.isArray()) {
                        for (var m : measurements) {
                            if ("COUNT".equals(m.get("statistic").asText())) {
                                long count = (long) m.get("value").asDouble();
                                // Also check skipped + failed
                                long totalHandled = getTotalHandled(baseUrl);
                                System.out.printf("  Processed so far: %,d (total handled: %,d / %,d)%n",
                                        count, totalHandled, expectedTotal);
                                if (totalHandled >= expectedTotal * 0.99) { // 99% threshold
                                    System.out.println("Processing complete.");
                                    return;
                                }
                            }
                        }
                    }
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }
        }
        System.out.println("Timeout waiting for processing to complete.");
    }

    private long getTotalHandled(String baseUrl) {
        try {
            long total = 0;
            String[] metrics = {
                    "moo.alert.processed",
                    "moo.alert.skipped.inactive",
                    "moo.alert.skipped.throttled",
                    "moo.alert.skipped.not_found",
                    "moo.alert.failed"
            };
            for (String metric : metrics) {
                total += getMetricCount(baseUrl, metric);
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    public long getMetricCount(String baseUrl, String metricName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/actuator/metrics/" + metricName))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var node = objectMapper.readTree(response.body());
                var measurements = node.get("measurements");
                if (measurements != null && measurements.isArray()) {
                    for (var m : measurements) {
                        if ("COUNT".equals(m.get("statistic").asText())) {
                            return (long) m.get("value").asDouble();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    public double getMetricMeanMs(String baseUrl, String metricName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/actuator/metrics/" + metricName))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var node = objectMapper.readTree(response.body());
                var measurements = node.get("measurements");
                if (measurements != null && measurements.isArray()) {
                    for (var m : measurements) {
                        if ("MEAN".equals(m.get("statistic").asText())) {
                            return m.get("value").asDouble() * 1000.0; // seconds to ms
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    public double getGaugeValue(String baseUrl, String metricName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/actuator/metrics/" + metricName))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var node = objectMapper.readTree(response.body());
                var measurements = node.get("measurements");
                if (measurements != null && measurements.isArray()) {
                    for (var m : measurements) {
                        if ("VALUE".equals(m.get("statistic").asText())) {
                            return m.get("value").asDouble();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    public void collectMetricsIntoReport(PerformanceReportGenerator report, String baseUrl) {
        report.setAlertsProcessed(getMetricCount(baseUrl, "moo.alert.processed"));
        report.setAlertsSkippedInactive(getMetricCount(baseUrl, "moo.alert.skipped.inactive"));
        report.setAlertsSkippedThrottled(getMetricCount(baseUrl, "moo.alert.skipped.throttled"));
        report.setAlertsSkippedNotFound(getMetricCount(baseUrl, "moo.alert.skipped.not_found"));
        report.setAlertsFailed(getMetricCount(baseUrl, "moo.alert.failed"));

        report.setAvgMongoLookupMs(getMetricMeanMs(baseUrl, "moo.mongo.lookup.time"));
        report.setAvgMongoUpdateMs(getMetricMeanMs(baseUrl, "moo.mongo.update.time"));
        long mongoLookups = getMetricCount(baseUrl, "moo.mongo.lookup.time");
        long mongoUpdates = getMetricCount(baseUrl, "moo.mongo.update.time");
        report.setTotalMongoOps(mongoLookups + mongoUpdates);

        long cacheHits = getMetricCount(baseUrl, "moo.marketdata.cache.hit");
        long cacheMisses = getMetricCount(baseUrl, "moo.marketdata.cache.miss");
        double hitRatio = (cacheHits + cacheMisses) > 0 ? (cacheHits * 100.0 / (cacheHits + cacheMisses)) : 0;
        report.setCacheHitRatio(hitRatio);
        report.setAvgFetchTimeMissMs(getMetricMeanMs(baseUrl, "moo.marketdata.fetch.time"));

        report.setKafkaMessagesPublished(getMetricCount(baseUrl, "moo.alert.processed"));
        report.setAvgKafkaPublishMs(getMetricMeanMs(baseUrl, "moo.kafka.publish.time"));

        report.setAvgOrchestrationTimeMs(getMetricMeanMs(baseUrl, "moo.orchestration.time"));
        report.setCallerRunsCount((long) getGaugeValue(baseUrl, "moo.threadpool.caller.runs.count"));

        // Memory metrics
        Runtime runtime = Runtime.getRuntime();
        report.setPeakHeapMB((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
    }
}
