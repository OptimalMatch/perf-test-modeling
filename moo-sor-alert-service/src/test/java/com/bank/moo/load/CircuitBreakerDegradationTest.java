package com.bank.moo.load;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Circuit breaker degradation test.
 *
 * Sends 200K webhooks across 4 instances while programmatically stopping
 * and restarting the mock market data service mid-test. Observes:
 *
 * Phase 1 (0-20s):   Normal — all dependencies healthy, caches warm up
 * Phase 2 (20-50s):  Degradation — market data service stopped, circuit breaker opens
 * Phase 3 (50-80s):  Recovery — market data service restarted, circuit breaker closes
 * Phase 4 (80s+):    Post-recovery — verify normal processing resumes
 *
 * Collects circuit breaker state, rejection counts, and alert outcomes per phase.
 */
public class CircuitBreakerDegradationTest {

    static final int TOTAL_WEBHOOKS = 200_000;
    static final int SENDER_THREADS = 100;

    static final String[] BASE_URLS = {
            "http://localhost:8080",
            "http://localhost:8082",
            "http://localhost:8083",
            "http://localhost:8084"
    };

    static final String MONGO_URI = "mongodb://localhost:27017";

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("  Circuit Breaker Degradation Test (200K webhooks)");
        System.out.println("═══════════════════════════════════════════════════════");

        HttpClient healthClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        // Step 1: Wait for all instances to be healthy
        System.out.println("\n[1/6] Waiting for all MOO SOR instances to be healthy...");
        for (String url : BASE_URLS) {
            waitForHealth(healthClient, url);
        }
        System.out.println("  All 4 instances healthy.");

        // Step 2: Load targets
        System.out.println("\n[2/6] Loading eligible webhook targets from MongoDB...");
        List<String[]> targets = loadTargets();
        System.out.printf("  Loaded %,d eligible targets. Building %,d webhook payloads...%n",
                targets.size(), TOTAL_WEBHOOKS);

        List<String[]> payloads = new ArrayList<>(TOTAL_WEBHOOKS);
        Random rng = new Random(42);
        for (int i = 0; i < TOTAL_WEBHOOKS; i++) {
            payloads.add(targets.get(rng.nextInt(targets.size())));
        }
        System.out.printf("  %,d payloads ready.%n", payloads.size());

        // Step 3: Collect baseline metrics
        System.out.println("\n[3/6] Collecting baseline metrics...");
        Map<String, Long> baselineMetrics = collectAllMetrics(healthClient);
        printMetricSnapshot("BASELINE", healthClient);

        // Step 4: Start sending webhooks with phased degradation
        System.out.println("\n[4/6] Starting phased degradation test...");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(SENDER_THREADS))
                .build();

        AtomicLong sentCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        Semaphore inflight = new Semaphore(5_000);
        Instant testStart = Instant.now();

        // Metric snapshots per phase
        List<String[]> phaseLog = Collections.synchronizedList(new ArrayList<>());

        // Background metric sampler — every 3 seconds
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> {
            try {
                long elapsed = Duration.between(testStart, Instant.now()).getSeconds();
                long processed = 0, failed = 0, throttled = 0;
                long mdCircuit = 0, mongoCircuit = 0, kafkaCircuit = 0;
                long cacheHits = 0, cacheMisses = 0;

                for (String url : BASE_URLS) {
                    processed += getCount(healthClient, url, "moo.alert.processed");
                    failed += getCount(healthClient, url, "moo.alert.failed");
                    throttled += getCount(healthClient, url, "moo.alert.skipped.throttled");
                    mdCircuit += getCount(healthClient, url, "moo.marketdata.circuit.rejected");
                    mongoCircuit += getCount(healthClient, url, "moo.mongo.circuit.rejected");
                    kafkaCircuit += getCount(healthClient, url, "moo.kafka.circuit.rejected");
                    cacheHits += getCount(healthClient, url, "moo.marketdata.cache.hit");
                    cacheMisses += getCount(healthClient, url, "moo.marketdata.cache.miss");
                }

                String phase;
                if (elapsed < 20) phase = "NORMAL";
                else if (elapsed < 50) phase = "DEGRADED";
                else if (elapsed < 80) phase = "RECOVERY";
                else phase = "POST-RECOVERY";

                String line = String.format(
                        "  [%3ds] %-14s | sent: %,7d | processed: %,7d | failed: %,5d | throttled: %,7d | " +
                                "CB rejected [md: %,d  mongo: %,d  kafka: %,d] | cache [hit: %,d  miss: %,d]",
                        elapsed, phase, sentCount.get(), processed, failed, throttled,
                        mdCircuit, mongoCircuit, kafkaCircuit, cacheHits, cacheMisses);
                System.out.println(line);
                phaseLog.add(new String[]{String.valueOf(elapsed), phase,
                        String.valueOf(processed), String.valueOf(failed),
                        String.valueOf(mdCircuit), String.valueOf(mongoCircuit), String.valueOf(kafkaCircuit)});
            } catch (Exception e) { /* ignore */ }
        }, 0, 3, TimeUnit.SECONDS);

        // Send webhooks at a controlled rate across all phases
        // ~2,500/sec to keep it manageable and see circuit breaker transitions clearly
        int batchSize = 250;
        long batchIntervalMs = 100; // 250 per 100ms = 2,500/sec

        // Phase 1: Normal (0-20s)
        System.out.println("\n  ── Phase 1: NORMAL (0-20s) ──────────────────────────");
        sendForDuration(client, payloads, BASE_URLS, inflight, sentCount, errorCount,
                testStart, Duration.ofSeconds(20), batchSize, batchIntervalMs);

        // Phase 2: Stop market data service (20-50s)
        System.out.println("\n  ── Phase 2: DEGRADED (20-50s) — Stopping mock-market-data ──");
        Runtime.getRuntime().exec(new String[]{"docker", "stop", "moo-mock-market-data"}).waitFor();
        System.out.println("  mock-market-data STOPPED");

        sendForDuration(client, payloads, BASE_URLS, inflight, sentCount, errorCount,
                testStart, Duration.ofSeconds(50), batchSize, batchIntervalMs);

        // Phase 3: Restart market data service (50-80s)
        System.out.println("\n  ── Phase 3: RECOVERY (50-80s) — Restarting mock-market-data ──");
        Runtime.getRuntime().exec(new String[]{"docker", "start", "moo-mock-market-data"}).waitFor();
        System.out.println("  mock-market-data RESTARTED");

        sendForDuration(client, payloads, BASE_URLS, inflight, sentCount, errorCount,
                testStart, Duration.ofSeconds(80), batchSize, batchIntervalMs);

        // Phase 4: Post-recovery (80-100s)
        System.out.println("\n  ── Phase 4: POST-RECOVERY (80-100s) ─────────────────");
        sendForDuration(client, payloads, BASE_URLS, inflight, sentCount, errorCount,
                testStart, Duration.ofSeconds(100), batchSize, batchIntervalMs);

        // Drain in-flight
        System.out.println("\n  Draining in-flight requests...");
        for (int i = 0; i < 5_000; i++) inflight.acquire();
        for (int i = 0; i < 5_000; i++) inflight.release();

        // Wait for async processing
        System.out.println("\n[5/6] Waiting for async processing to complete...");
        waitForProcessing(healthClient, sentCount.get(), Duration.ofMinutes(5));

        sampler.shutdown();
        Instant testEnd = Instant.now();

        // Step 6: Final report
        System.out.println("\n[6/6] Collecting final metrics and generating report...");
        printFinalReport(healthClient, testStart, testEnd, sentCount.get(), errorCount.get());

        // Write to file
        System.out.println("\nReport saved to /tmp/moo-circuit-breaker-report.txt");
        System.exit(0);
    }

    static void sendForDuration(HttpClient client, List<String[]> payloads, String[] urls,
                                Semaphore inflight, AtomicLong sentCount, AtomicLong errorCount,
                                Instant testStart, Duration untilElapsed,
                                int batchSize, long batchIntervalMs) throws Exception {
        int payloadIdx = (int) (sentCount.get() % payloads.size());

        while (Duration.between(testStart, Instant.now()).compareTo(untilElapsed) < 0) {
            for (int b = 0; b < batchSize && sentCount.get() < 200_000; b++) {
                String[] p = payloads.get(payloadIdx % payloads.size());
                payloadIdx++;
                String baseUrl = urls[(int) (sentCount.get() % urls.length)];
                String url = baseUrl + "/api/v1/alerts/factset/webhook?userId=" + p[0];
                String json = String.format(
                        "{\"triggerId\":\"%s\",\"triggerTypeId\":\"%s\",\"symbol\":\"%s\",\"value\":\"%s\",\"triggeredAt\":\"%s\"}",
                        p[1], p[2], p[3], p[4], Instant.now().toString());

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                inflight.acquire();
                client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                        .whenComplete((resp, ex) -> {
                            inflight.release();
                            if (ex != null) errorCount.incrementAndGet();
                            sentCount.incrementAndGet();
                        });
            }
            Thread.sleep(batchIntervalMs);
        }
    }

    static void printMetricSnapshot(String label, HttpClient client) {
        System.out.printf("  [%s]%n", label);
        for (String url : BASE_URLS) {
            String cbState = getCircuitBreakerState(client, url, "marketData");
            System.out.printf("    %s — marketData CB: %s%n", url, cbState);
        }
    }

    static String getCircuitBreakerState(HttpClient client, String baseUrl, String cbName) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/actuator/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                int cbIdx = body.indexOf(cbName);
                if (cbIdx > 0) {
                    int stateIdx = body.indexOf("state", cbIdx);
                    if (stateIdx > 0) {
                        int colon = body.indexOf(":", stateIdx);
                        int quote1 = body.indexOf("\"", colon + 1);
                        int quote2 = body.indexOf("\"", quote1 + 1);
                        if (quote1 > 0 && quote2 > quote1) {
                            return body.substring(quote1 + 1, quote2);
                        }
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
        return "UNKNOWN";
    }

    static void printFinalReport(HttpClient client, Instant start, Instant end,
                                 long totalSent, long networkErrors) {
        Duration duration = Duration.between(start, end);

        long totalProcessed = 0, totalFailed = 0, totalThrottled = 0;
        long totalInactive = 0, totalNotFound = 0;
        long mdCircuit = 0, mongoCircuit = 0, kafkaCircuit = 0;
        long cacheHits = 0, cacheMisses = 0;
        double totalOrchTime = 0; long totalOrchCount = 0;

        for (String url : BASE_URLS) {
            totalProcessed += getCount(client, url, "moo.alert.processed");
            totalFailed += getCount(client, url, "moo.alert.failed");
            totalThrottled += getCount(client, url, "moo.alert.skipped.throttled");
            totalInactive += getCount(client, url, "moo.alert.skipped.inactive");
            totalNotFound += getCount(client, url, "moo.alert.skipped.not_found");
            mdCircuit += getCount(client, url, "moo.marketdata.circuit.rejected");
            mongoCircuit += getCount(client, url, "moo.mongo.circuit.rejected");
            kafkaCircuit += getCount(client, url, "moo.kafka.circuit.rejected");
            cacheHits += getCount(client, url, "moo.marketdata.cache.hit");
            cacheMisses += getCount(client, url, "moo.marketdata.cache.miss");

            double[] orchStats = getTimerStats(client, url, "moo.orchestration.time");
            totalOrchTime += orchStats[0]; totalOrchCount += (long) orchStats[1];
        }

        long totalSkipped = totalInactive + totalThrottled + totalNotFound;
        double avgOrchMs = totalOrchCount > 0 ? totalOrchTime / totalOrchCount * 1000 : 0;

        String report = String.format("""

                ═══════════════════════════════════════════════════════
                Circuit Breaker Degradation Test Report
                ═══════════════════════════════════════════════════════
                Duration:                    %d minutes %d seconds
                Total webhooks sent:         %,d
                Network errors:              %,d

                Alert Outcomes:
                  Processed (Kafka published): %,d
                  Failed (dependency error):   %,d
                  Throttled (dedup):           %,d
                  Skipped (inactive/not found): %,d
                  Total accounted:             %,d

                Circuit Breaker Rejections:
                  Market Data (OPEN):          %,d
                  MongoDB (OPEN):              %,d
                  Kafka (OPEN):                %,d
                  Total CB rejections:         %,d

                Market Data Cache:
                  Cache hits:                  %,d
                  Cache misses:                %,d
                  Hit ratio:                   %.1f%%

                Orchestration:
                  Avg time:                    %.2f ms

                Circuit Breaker Final State:
                """,
                duration.toMinutes(), duration.toSecondsPart(),
                totalSent,
                networkErrors,
                totalProcessed,
                totalFailed,
                totalThrottled,
                totalInactive + totalNotFound,
                totalProcessed + totalFailed + totalSkipped,
                mdCircuit, mongoCircuit, kafkaCircuit,
                mdCircuit + mongoCircuit + kafkaCircuit,
                cacheHits, cacheMisses,
                (cacheHits + cacheMisses) > 0 ? cacheHits * 100.0 / (cacheHits + cacheMisses) : 0,
                avgOrchMs);

        System.out.print(report);

        // Print circuit breaker states
        for (String url : BASE_URLS) {
            String mdState = getCircuitBreakerState(client, url, "marketData");
            String mongoState = getCircuitBreakerState(client, url, "mongoLookup");
            String kafkaState = getCircuitBreakerState(client, url, "kafkaPublish");
            System.out.printf("  %s — marketData: %-11s  mongoLookup: %-11s  kafkaPublish: %-11s%n",
                    url, mdState, mongoState, kafkaState);
        }
        System.out.println("═══════════════════════════════════════════════════════");

        // Write to file
        try (var writer = new java.io.FileWriter("/tmp/moo-circuit-breaker-report.txt")) {
            writer.write(report);
        } catch (Exception e) {
            System.err.println("Failed to write report: " + e.getMessage());
        }
    }

    static Map<String, Long> collectAllMetrics(HttpClient client) {
        Map<String, Long> metrics = new HashMap<>();
        String[] metricNames = {"moo.alert.processed", "moo.alert.failed",
                "moo.alert.skipped.throttled", "moo.marketdata.circuit.rejected",
                "moo.mongo.circuit.rejected", "moo.kafka.circuit.rejected"};
        for (String metric : metricNames) {
            long total = 0;
            for (String url : BASE_URLS) total += getCount(client, url, metric);
            metrics.put(metric, total);
        }
        return metrics;
    }

    // Reuse helper methods from MarketCrashLoadTest
    static void waitForHealth(HttpClient client, String baseUrl) throws Exception {
        for (int i = 0; i < 60; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    System.out.println("  " + baseUrl + " -> UP");
                    return;
                }
            } catch (Exception e) { /* retry */ }
            Thread.sleep(2000);
        }
        throw new RuntimeException("Timeout waiting for " + baseUrl);
    }

    static List<String[]> loadTargets() {
        List<String[]> targets = new ArrayList<>();
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            MongoDatabase db = mongo.getDatabase("moo");
            MongoCollection<Document> coll = db.getCollection("customers");
            for (Document doc : coll.find()) {
                String userId = doc.getObjectId("_id").toHexString();
                List<Document> subs = doc.getList("subscriptions", Document.class);
                if (subs == null) continue;
                for (Document sub : subs) {
                    if ("Y".equals(sub.getString("activeState")) && sub.get("dateDelivered") == null) {
                        targets.add(new String[]{
                                userId,
                                sub.getString("factSetTriggerId"),
                                sub.getString("triggerTypeId"),
                                sub.getString("symbol"),
                                sub.getString("value")
                        });
                    }
                }
            }
        }
        return targets;
    }

    static void waitForProcessing(HttpClient client, long expected, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        long lastTotal = 0;
        int staleCount = 0;
        while (Instant.now().isBefore(deadline)) {
            long total = 0;
            for (String url : BASE_URLS) {
                total += getCount(client, url, "moo.alert.processed");
                total += getCount(client, url, "moo.alert.skipped.inactive");
                total += getCount(client, url, "moo.alert.skipped.throttled");
                total += getCount(client, url, "moo.alert.skipped.not_found");
                total += getCount(client, url, "moo.alert.failed");
            }
            System.out.printf("  Processing: %,d / %,d (%.1f%%)%n", total, expected, total * 100.0 / expected);
            if (total >= expected * 0.99) {
                System.out.println("  Processing complete.");
                return;
            }
            if (total == lastTotal) {
                staleCount++;
                if (staleCount > 10) {
                    System.out.println("  Processing stalled. Moving on.");
                    return;
                }
            } else {
                staleCount = 0;
            }
            lastTotal = total;
            Thread.sleep(3000);
        }
        System.out.println("  Timeout. Moving on.");
    }

    static long getCount(HttpClient client, String baseUrl, String metric) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/actuator/metrics/" + metric))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                int idx = body.indexOf("\"COUNT\"");
                if (idx > 0) {
                    int valIdx = body.indexOf("\"value\"", idx);
                    if (valIdx > 0) {
                        int colon = body.indexOf(":", valIdx);
                        int end = body.indexOf("}", colon);
                        return (long) Double.parseDouble(body.substring(colon + 1, end).trim());
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    static double[] getTimerStats(HttpClient client, String baseUrl, String metric) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/actuator/metrics/" + metric))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                double totalTime = 0, count = 0;
                int ttIdx = body.indexOf("\"TOTAL_TIME\"");
                if (ttIdx > 0) {
                    int valIdx = body.indexOf("\"value\"", ttIdx);
                    int colon = body.indexOf(":", valIdx);
                    int end = body.indexOf("}", colon);
                    totalTime = Double.parseDouble(body.substring(colon + 1, end).trim());
                }
                int cIdx = body.indexOf("\"COUNT\"");
                if (cIdx > 0) {
                    int valIdx = body.indexOf("\"value\"", cIdx);
                    int colon = body.indexOf(":", valIdx);
                    int end = body.indexOf("}", colon);
                    count = Double.parseDouble(body.substring(colon + 1, end).trim());
                }
                return new double[]{totalTime, count};
            }
        } catch (Exception e) { /* ignore */ }
        return new double[]{0, 0};
    }
}
