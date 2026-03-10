package com.bank.moo.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Market crash load test against MOO SOR instances running with Oracle profile.
 * Identical scenario to MarketCrashLoadTest (2M webhooks, 4 instances) but loads
 * eligible targets from Oracle instead of MongoDB, and reports Oracle-specific
 * DB metrics (lookup/update times reflect JPA+Oracle instead of MongoDB).
 */
public class OracleMarketCrashLoadTest {

    static final int TOTAL_WEBHOOKS = 2_000_000;
    static final int SENDER_THREADS = 200;
    static final int SAMPLE_SIZE = 2_000_000;

    // Same ports — service instances are rebuilt with oracle profile
    static final String[] BASE_URLS = {
            "http://localhost:8080",
            "http://localhost:8082",
            "http://localhost:8083",
            "http://localhost:8084"
    };

    static final String ORACLE_JDBC_URL = "jdbc:oracle:thin:@localhost:1521/FREEPDB1";
    static final String ORACLE_USER = "moo";
    static final String ORACLE_PASSWORD = "moo_password";

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("  MOO SOR Market Crash Load Test — ORACLE (2M webhooks)");
        System.out.println("═══════════════════════════════════════════════════════");

        // Step 1: Wait for all instances to be healthy
        System.out.println("\n[1/5] Waiting for all MOO SOR instances to be healthy...");
        HttpClient healthClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        for (String url : BASE_URLS) {
            waitForHealth(healthClient, url);
        }
        System.out.println("  All 4 instances healthy.");

        // Step 2: Load webhook targets from Oracle
        System.out.println("\n[2/5] Loading eligible webhook targets from Oracle...");
        List<String[]> targets = OracleTestDataGenerator.loadTargets(ORACLE_JDBC_URL, ORACLE_USER, ORACLE_PASSWORD);
        System.out.printf("  Loaded %,d eligible targets. Building %,d webhook payloads...%n",
                targets.size(), TOTAL_WEBHOOKS);

        List<String[]> payloads = new ArrayList<>(TOTAL_WEBHOOKS);
        Random rng = new Random(42);
        for (int i = 0; i < TOTAL_WEBHOOKS; i++) {
            payloads.add(targets.get(rng.nextInt(targets.size())));
        }
        System.out.printf("  %,d payloads ready.%n", payloads.size());

        // Step 3: Send webhooks
        System.out.println("\n[3/5] Sending 2,000,000 webhooks across 4 instances...");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(SENDER_THREADS))
                .build();

        AtomicLong sentCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        AtomicLong httpErrorCount = new AtomicLong(0);
        AtomicLongArray responseTimes = new AtomicLongArray(SAMPLE_SIZE);
        AtomicLong rtIndex = new AtomicLong(0);

        AtomicLong windowStart = new AtomicLong(0);
        AtomicLong windowCount = new AtomicLong(0);
        AtomicLong peakPerSec = new AtomicLong(0);

        List<double[]> cpuSamples = Collections.synchronizedList(new ArrayList<>());
        ScheduledExecutorService cpuSampler = Executors.newSingleThreadScheduledExecutor();
        cpuSampler.scheduleAtFixedRate(() -> {
            try {
                for (int inst = 0; inst < BASE_URLS.length; inst++) {
                    double processCpu = getGauge(healthClient, BASE_URLS[inst], "process.cpu.usage");
                    double systemCpu = getGauge(healthClient, BASE_URLS[inst], "system.cpu.usage");
                    cpuSamples.add(new double[]{inst, processCpu, systemCpu, System.currentTimeMillis()});
                }
            } catch (Exception e) { /* ignore sampling errors */ }
        }, 0, 2, TimeUnit.SECONDS);

        Semaphore inflight = new Semaphore(10_000);
        Instant testStart = Instant.now();
        windowStart.set(testStart.toEpochMilli());

        for (int i = 0; i < payloads.size(); i++) {
            String[] p = payloads.get(i);
            String baseUrl = BASE_URLS[i % BASE_URLS.length];
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
            long reqStart = System.nanoTime();

            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, ex) -> {
                        long elapsed = System.nanoTime() - reqStart;
                        inflight.release();

                        long idx = rtIndex.getAndIncrement();
                        if (idx < SAMPLE_SIZE) {
                            responseTimes.set((int) idx, elapsed);
                        }

                        if (ex != null) {
                            errorCount.incrementAndGet();
                        } else if (resp.statusCode() != 200) {
                            httpErrorCount.incrementAndGet();
                        }

                        long s = sentCount.incrementAndGet();

                        long now = System.currentTimeMillis();
                        long ws = windowStart.get();
                        long wc = windowCount.incrementAndGet();
                        if (now - ws >= 1000) {
                            long peak = peakPerSec.get();
                            if (wc > peak) peakPerSec.set(wc);
                            windowStart.set(now);
                            windowCount.set(0);
                        }

                        if (s % 100_000 == 0) {
                            double secs = Duration.between(testStart, Instant.now()).toMillis() / 1000.0;
                            System.out.printf("  Sent: %,d / %,d  (%.0f/sec, errors: %d, http_errors: %d)%n",
                                    s, (long) TOTAL_WEBHOOKS, s / secs, errorCount.get(), httpErrorCount.get());
                        }
                    });
        }

        System.out.println("  All requests submitted. Waiting for in-flight to drain...");
        for (int i = 0; i < 10_000; i++) inflight.acquire();
        for (int i = 0; i < 10_000; i++) inflight.release();

        Instant sendEnd = Instant.now();
        cpuSampler.shutdown();
        Duration sendDuration = Duration.between(testStart, sendEnd);
        System.out.printf("  All %,d webhooks sent in %d min %d sec%n",
                sentCount.get(), sendDuration.toMinutes(), sendDuration.toSecondsPart());

        // Step 4: Wait for async processing to complete
        System.out.println("\n[4/5] Waiting for async processing to complete...");
        waitForProcessing(healthClient, TOTAL_WEBHOOKS, Duration.ofMinutes(15));
        Instant processEnd = Instant.now();
        Duration totalDuration = Duration.between(testStart, processEnd);

        // Step 5: Collect metrics and generate report
        System.out.println("\n[5/5] Collecting metrics and generating report...");

        int rtCount = (int) Math.min(rtIndex.get(), SAMPLE_SIZE);
        long[] sorted = new long[rtCount];
        for (int i = 0; i < rtCount; i++) sorted[i] = responseTimes.get(i);
        Arrays.sort(sorted);

        long totalProcessed = 0, totalInactive = 0, totalThrottled = 0, totalNotFound = 0, totalFailed = 0;
        long totalReceived = 0;
        double totalOrchTime = 0; long totalOrchCount = 0;
        double totalDbLookupTime = 0; long totalDbLookupCount = 0;
        double totalDbUpdateTime = 0; long totalDbUpdateCount = 0;
        double totalKafkaTime = 0; long totalKafkaCount = 0;
        double totalMarketTime = 0; long totalMarketCount = 0;
        long totalCacheHits = 0, totalCacheMisses = 0;
        long maxQueueDepth = 0, maxActiveThreads = 0;
        long totalCallerRuns = 0;

        for (String url : BASE_URLS) {
            totalReceived += getCount(healthClient, url, "moo.webhook.received");
            totalProcessed += getCount(healthClient, url, "moo.alert.processed");
            totalInactive += getCount(healthClient, url, "moo.alert.skipped.inactive");
            totalThrottled += getCount(healthClient, url, "moo.alert.skipped.throttled");
            totalNotFound += getCount(healthClient, url, "moo.alert.skipped.not_found");
            totalFailed += getCount(healthClient, url, "moo.alert.failed");
            totalCacheHits += getCount(healthClient, url, "moo.marketdata.cache.hit");
            totalCacheMisses += getCount(healthClient, url, "moo.marketdata.cache.miss");
            totalCallerRuns += (long) getGauge(healthClient, url, "moo.threadpool.caller.runs.count");

            double[] orchStats = getTimerStats(healthClient, url, "moo.orchestration.time");
            totalOrchTime += orchStats[0]; totalOrchCount += (long) orchStats[1];

            // These metrics are named "mongo" in the meter registry but measure whichever DB is active
            double[] dbLookup = getTimerStats(healthClient, url, "moo.mongo.lookup.time");
            totalDbLookupTime += dbLookup[0]; totalDbLookupCount += (long) dbLookup[1];

            double[] dbUpdate = getTimerStats(healthClient, url, "moo.mongo.update.time");
            totalDbUpdateTime += dbUpdate[0]; totalDbUpdateCount += (long) dbUpdate[1];

            double[] kafka = getTimerStats(healthClient, url, "moo.kafka.publish.time");
            totalKafkaTime += kafka[0]; totalKafkaCount += (long) kafka[1];

            double[] market = getTimerStats(healthClient, url, "moo.marketdata.fetch.time");
            totalMarketTime += market[0]; totalMarketCount += (long) market[1];

            long qd = (long) getGauge(healthClient, url, "moo.threadpool.queue.size");
            long at = (long) getGauge(healthClient, url, "moo.threadpool.active.threads");
            if (qd > maxQueueDepth) maxQueueDepth = qd;
            if (at > maxActiveThreads) maxActiveThreads = at;
        }

        long totalSkipped = totalInactive + totalThrottled + totalNotFound;
        double cacheHitRatio = (totalCacheHits + totalCacheMisses) > 0
                ? totalCacheHits * 100.0 / (totalCacheHits + totalCacheMisses) : 0;

        double avgOrchMs = totalOrchCount > 0 ? totalOrchTime / totalOrchCount * 1000 : 0;
        double avgDbLookupMs = totalDbLookupCount > 0 ? totalDbLookupTime / totalDbLookupCount * 1000 : 0;
        double avgDbUpdateMs = totalDbUpdateCount > 0 ? totalDbUpdateTime / totalDbUpdateCount * 1000 : 0;
        double avgKafkaMs = totalKafkaCount > 0 ? totalKafkaTime / totalKafkaCount * 1000 : 0;
        double avgMarketMs = totalMarketCount > 0 ? totalMarketTime / totalMarketCount * 1000 : 0;
        long totalDbOps = totalDbLookupCount + totalDbUpdateCount;
        double avgThroughput = totalDuration.getSeconds() > 0 ? (double) totalProcessed / totalDuration.getSeconds() : 0;

        // CPU metrics
        double[] peakProcessCpu = new double[BASE_URLS.length];
        double[] avgProcessCpu = new double[BASE_URLS.length];
        int[] sampleCounts = new int[BASE_URLS.length];
        double peakSystemCpu = 0;
        double totalSystemCpu = 0;
        int systemCpuCount = 0;

        for (double[] sample : cpuSamples) {
            int inst = (int) sample[0];
            double processCpu = sample[1];
            double systemCpu = sample[2];
            if (processCpu > peakProcessCpu[inst]) peakProcessCpu[inst] = processCpu;
            avgProcessCpu[inst] += processCpu;
            sampleCounts[inst]++;
            if (systemCpu > peakSystemCpu) peakSystemCpu = systemCpu;
            totalSystemCpu += systemCpu;
            systemCpuCount++;
        }

        double overallPeakProcessCpu = 0;
        double overallAvgProcessCpu = 0;
        int instancesWithSamples = 0;
        for (int i = 0; i < BASE_URLS.length; i++) {
            if (sampleCounts[i] > 0) {
                avgProcessCpu[i] /= sampleCounts[i];
                if (peakProcessCpu[i] > overallPeakProcessCpu) overallPeakProcessCpu = peakProcessCpu[i];
                overallAvgProcessCpu += avgProcessCpu[i];
                instancesWithSamples++;
            }
        }
        if (instancesWithSamples > 0) overallAvgProcessCpu /= instancesWithSamples;
        double avgSystemCpu = systemCpuCount > 0 ? totalSystemCpu / systemCpuCount : 0;

        Runtime rt = Runtime.getRuntime();
        long heapMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

        String report = String.format("""

                ═══════════════════════════════════════════════════════
                MOO SOR Performance Test Report — ORACLE
                ═══════════════════════════════════════════════════════
                Database:                    Oracle 23ai Free (FREEPDB1)
                Data model:                  Normalized (3 tables: CUSTOMERS, CUSTOMER_SUBSCRIPTIONS, CHANNEL_PREFERENCES)
                Scenario:                    Market Crash (2M alerts, 4 instances)
                Duration:                    %d minutes %d seconds
                Send phase:                  %d minutes %d seconds
                Total webhooks sent:         %,d
                Total alerts processed:      %,d
                Total alerts skipped:        %,d (inactive: %,d, throttled: %,d, not found: %,d)
                Total alerts failed:         %,d
                Network/HTTP errors:         %,d (send) + %,d (http)

                Webhook Response Time:
                  P50:                       %.2f ms
                  P95:                       %.2f ms
                  P99:                       %.2f ms
                  Max:                       %.2f ms

                Orchestration Throughput:
                  Avg:                       %.0f /sec (across 4 instances)
                  Per instance avg:          %.0f /sec
                  Peak send rate:            %,d /sec
                  Avg orchestration time:    %.2f ms

                Thread Pool:
                  Peak queue depth:          %,d (at query time)
                  CallerRunsPolicy count:    %,d
                  Peak active threads:       %,d (at query time)

                Oracle DB (via JPA/HikariCP):
                  Avg lookup time:           %.2f ms   (customer + subscriptions + channels JOIN)
                  Avg update time:           %.2f ms   (UPDATE subscription SET date_delivered)
                  Total ops:                 %,d
                  Connection pool size:      50 (HikariCP)

                Market Data:
                  Cache hit ratio:           %.1f%%
                  Cache hits:                %,d
                  Cache misses:              %,d
                  Avg fetch time (miss):     %.2f ms

                Kafka:
                  Messages published:        %,d
                  Avg publish time:          %.2f ms

                CPU (sampled every 2s):
                  System CPU (host):
                    Avg:                     %.1f%%
                    Peak:                    %.1f%%
                  Process CPU (per JVM):
                    Instance 1:              avg %.1f%%, peak %.1f%%
                    Instance 2:              avg %.1f%%, peak %.1f%%
                    Instance 3:              avg %.1f%%, peak %.1f%%
                    Instance 4:              avg %.1f%%, peak %.1f%%
                    Overall avg:             %.1f%%
                    Overall peak:            %.1f%%
                  Samples collected:         %d

                Memory:
                  Load generator heap:       %,d MB
                ═══════════════════════════════════════════════════════
                """,
                totalDuration.toMinutes(), totalDuration.toSecondsPart(),
                sendDuration.toMinutes(), sendDuration.toSecondsPart(),
                sentCount.get(),
                totalProcessed,
                totalSkipped, totalInactive, totalThrottled, totalNotFound,
                totalFailed,
                errorCount.get(), httpErrorCount.get(),
                percentileMs(sorted, 0.50),
                percentileMs(sorted, 0.95),
                percentileMs(sorted, 0.99),
                sorted.length > 0 ? sorted[sorted.length - 1] / 1_000_000.0 : 0,
                avgThroughput,
                avgThroughput / 4.0,
                peakPerSec.get(),
                avgOrchMs,
                maxQueueDepth,
                totalCallerRuns,
                maxActiveThreads,
                avgDbLookupMs,
                avgDbUpdateMs,
                totalDbOps,
                cacheHitRatio,
                totalCacheHits,
                totalCacheMisses,
                avgMarketMs,
                totalProcessed,
                avgKafkaMs,
                avgSystemCpu * 100, peakSystemCpu * 100,
                avgProcessCpu[0] * 100, peakProcessCpu[0] * 100,
                avgProcessCpu[1] * 100, peakProcessCpu[1] * 100,
                avgProcessCpu[2] * 100, peakProcessCpu[2] * 100,
                avgProcessCpu[3] * 100, peakProcessCpu[3] * 100,
                overallAvgProcessCpu * 100, overallPeakProcessCpu * 100,
                cpuSamples.size(),
                heapMB
        );

        System.out.print(report);

        try (var writer = new java.io.FileWriter("/tmp/moo-oracle-market-crash-report.txt")) {
            writer.write(report);
        }
        System.out.println("Report saved to /tmp/moo-oracle-market-crash-report.txt");
        System.exit(0);
    }

    // --- Utility methods (same as MarketCrashLoadTest) ---

    static void waitForHealth(HttpClient client, String baseUrl) throws Exception {
        for (int i = 0; i < 60; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body().contains("UP")) {
                    System.out.println("  " + baseUrl + " -> UP");
                    return;
                }
            } catch (Exception e) { /* retry */ }
            Thread.sleep(2000);
        }
        throw new RuntimeException("Timeout waiting for " + baseUrl);
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
            if (total >= expected * 0.995) {
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

    static double getGauge(HttpClient client, String baseUrl, String metric) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/actuator/metrics/" + metric))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                int idx = body.indexOf("\"VALUE\"");
                if (idx > 0) {
                    int valIdx = body.indexOf("\"value\"", idx);
                    if (valIdx > 0) {
                        int colon = body.indexOf(":", valIdx);
                        int end = body.indexOf("}", colon);
                        return Double.parseDouble(body.substring(colon + 1, end).trim());
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

    static double percentileMs(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx] / 1_000_000.0;
    }
}
