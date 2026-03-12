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
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Load test for the flat MongoDB model (mongo-flat profile).
 * Same test scenario as MarketCrashLoadTest but reads from customer_alerts collection.
 */
public class FlatMongoMarketCrashLoadTest {

    static final int TOTAL_WEBHOOKS = 2_000_000;
    static final int SENDER_THREADS = 200;
    static final int SAMPLE_SIZE = 2_000_000;

    static final String[] BASE_URLS = {
            "http://localhost:8080",
            "http://localhost:8082",
            "http://localhost:8083",
            "http://localhost:8084"
    };

    static final String MONGO_URI = "mongodb://localhost:27017";

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("  MOO SOR Market Crash Load Test — FLAT MONGO MODEL");
        System.out.println("═══════════════════════════════════════════════════════");

        // Step 1: Wait for all instances to be healthy
        System.out.println("\n[1/5] Waiting for all MOO SOR instances to be healthy...");
        HttpClient healthClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        for (String url : BASE_URLS) {
            waitForHealth(healthClient, url);
        }
        System.out.println("  All 4 instances healthy.");

        // Step 2: Load webhook targets from flat MongoDB collection
        System.out.println("\n[2/5] Loading eligible webhook targets from MongoDB (customer_alerts)...");
        List<String[]> targets = loadTargets();
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
                    double processCpu = MarketCrashLoadTest.getGauge(healthClient, BASE_URLS[inst], "process.cpu.usage");
                    double systemCpu = MarketCrashLoadTest.getGauge(healthClient, BASE_URLS[inst], "system.cpu.usage");
                    cpuSamples.add(new double[]{inst, processCpu, systemCpu, System.currentTimeMillis()});
                }
            } catch (Exception e) { /* ignore */ }
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
        for (int i = 0; i < 10_000; i++) {
            inflight.acquire();
        }
        for (int i = 0; i < 10_000; i++) {
            inflight.release();
        }

        Instant sendEnd = Instant.now();
        cpuSampler.shutdown();
        Duration sendDuration = Duration.between(testStart, sendEnd);
        System.out.printf("  All %,d webhooks sent in %d min %d sec%n",
                sentCount.get(), sendDuration.toMinutes(), sendDuration.toSecondsPart());

        // Step 4: Wait for async processing
        System.out.println("\n[4/5] Waiting for async processing to complete...");
        MarketCrashLoadTest.waitForProcessing(healthClient, TOTAL_WEBHOOKS, Duration.ofMinutes(15));
        Instant processEnd = Instant.now();
        Duration totalDuration = Duration.between(testStart, processEnd);

        // Step 5: Collect metrics and generate report
        System.out.println("\n[5/5] Collecting metrics and generating report...");

        int rtCount = (int) Math.min(rtIndex.get(), SAMPLE_SIZE);
        long[] sorted = new long[rtCount];
        for (int i = 0; i < rtCount; i++) {
            sorted[i] = responseTimes.get(i);
        }
        Arrays.sort(sorted);

        long totalProcessed = 0, totalInactive = 0, totalThrottled = 0, totalNotFound = 0, totalFailed = 0;
        long totalReceived = 0;
        double totalOrchTime = 0; long totalOrchCount = 0;
        double totalMongoLookupTime = 0; long totalMongoLookupCount = 0;
        double totalMongoUpdateTime = 0; long totalMongoUpdateCount = 0;
        double totalKafkaTime = 0; long totalKafkaCount = 0;
        double totalMarketTime = 0; long totalMarketCount = 0;
        long totalCacheHits = 0, totalCacheMisses = 0;
        long maxQueueDepth = 0, maxActiveThreads = 0;
        long totalCallerRuns = 0;

        for (String url : BASE_URLS) {
            totalReceived += MarketCrashLoadTest.getCount(healthClient, url, "moo.webhook.received");
            totalProcessed += MarketCrashLoadTest.getCount(healthClient, url, "moo.alert.processed");
            totalInactive += MarketCrashLoadTest.getCount(healthClient, url, "moo.alert.skipped.inactive");
            totalThrottled += MarketCrashLoadTest.getCount(healthClient, url, "moo.alert.skipped.throttled");
            totalNotFound += MarketCrashLoadTest.getCount(healthClient, url, "moo.alert.skipped.not_found");
            totalFailed += MarketCrashLoadTest.getCount(healthClient, url, "moo.alert.failed");
            totalCacheHits += MarketCrashLoadTest.getCount(healthClient, url, "moo.marketdata.cache.hit");
            totalCacheMisses += MarketCrashLoadTest.getCount(healthClient, url, "moo.marketdata.cache.miss");
            totalCallerRuns += (long) MarketCrashLoadTest.getGauge(healthClient, url, "moo.threadpool.caller.runs.count");

            double[] orchStats = MarketCrashLoadTest.getTimerStats(healthClient, url, "moo.orchestration.time");
            totalOrchTime += orchStats[0]; totalOrchCount += (long) orchStats[1];

            double[] mongoLookup = MarketCrashLoadTest.getTimerStats(healthClient, url, "moo.mongo.lookup.time");
            totalMongoLookupTime += mongoLookup[0]; totalMongoLookupCount += (long) mongoLookup[1];

            double[] mongoUpdate = MarketCrashLoadTest.getTimerStats(healthClient, url, "moo.mongo.update.time");
            totalMongoUpdateTime += mongoUpdate[0]; totalMongoUpdateCount += (long) mongoUpdate[1];

            double[] kafka = MarketCrashLoadTest.getTimerStats(healthClient, url, "moo.kafka.publish.time");
            totalKafkaTime += kafka[0]; totalKafkaCount += (long) kafka[1];

            double[] market = MarketCrashLoadTest.getTimerStats(healthClient, url, "moo.marketdata.fetch.time");
            totalMarketTime += market[0]; totalMarketCount += (long) market[1];

            long qd = (long) MarketCrashLoadTest.getGauge(healthClient, url, "moo.threadpool.queue.size");
            long at = (long) MarketCrashLoadTest.getGauge(healthClient, url, "moo.threadpool.active.threads");
            if (qd > maxQueueDepth) maxQueueDepth = qd;
            if (at > maxActiveThreads) maxActiveThreads = at;
        }

        long totalSkipped = totalInactive + totalThrottled + totalNotFound;
        double cacheHitRatio = (totalCacheHits + totalCacheMisses) > 0
                ? totalCacheHits * 100.0 / (totalCacheHits + totalCacheMisses) : 0;

        double avgOrchMs = totalOrchCount > 0 ? totalOrchTime / totalOrchCount * 1000 : 0;
        double avgMongoLookupMs = totalMongoLookupCount > 0 ? totalMongoLookupTime / totalMongoLookupCount * 1000 : 0;
        double avgMongoUpdateMs = totalMongoUpdateCount > 0 ? totalMongoUpdateTime / totalMongoUpdateCount * 1000 : 0;
        double avgKafkaMs = totalKafkaCount > 0 ? totalKafkaTime / totalKafkaCount * 1000 : 0;
        double avgMarketMs = totalMarketCount > 0 ? totalMarketTime / totalMarketCount * 1000 : 0;
        long totalMongoOps = totalMongoLookupCount + totalMongoUpdateCount;
        double avgThroughput = totalDuration.getSeconds() > 0 ? (double) totalProcessed / totalDuration.getSeconds() : 0;

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
                MOO SOR Performance Test Report — FLAT MONGO MODEL
                ═══════════════════════════════════════════════════════
                Data Model:                  Flat (1 doc per subscription, denormalized)
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

                MongoDB (Flat Model):
                  Avg lookup time:           %.2f ms
                  Avg update time:           %.2f ms
                  Total ops:                 %,d

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
                MarketCrashLoadTest.percentileMs(sorted, 0.50),
                MarketCrashLoadTest.percentileMs(sorted, 0.95),
                MarketCrashLoadTest.percentileMs(sorted, 0.99),
                sorted.length > 0 ? sorted[sorted.length - 1] / 1_000_000.0 : 0,
                avgThroughput,
                avgThroughput / 4.0,
                peakPerSec.get(),
                avgOrchMs,
                maxQueueDepth,
                totalCallerRuns,
                maxActiveThreads,
                avgMongoLookupMs,
                avgMongoUpdateMs,
                totalMongoOps,
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

        try (var writer = new java.io.FileWriter("/tmp/moo-mongo-flat-market-crash-report.txt")) {
            writer.write(report);
        }
        System.out.println("Report saved to /tmp/moo-mongo-flat-market-crash-report.txt");
        System.exit(0);
    }

    static void waitForHealth(HttpClient client, String baseUrl) throws Exception {
        MarketCrashLoadTest.waitForHealth(client, baseUrl);
    }

    /**
     * Load targets from the flat customer_alerts collection.
     */
    static List<String[]> loadTargets() {
        List<String[]> targets = new ArrayList<>();
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            MongoDatabase db = mongo.getDatabase("moo");
            MongoCollection<Document> coll = db.getCollection("customer_alerts");
            for (Document doc : coll.find()) {
                if ("Y".equals(doc.getString("activeState")) && doc.get("dateDelivered") == null) {
                    targets.add(new String[]{
                            doc.getString("customerId"),
                            doc.getString("factSetTriggerId"),
                            doc.getString("triggerTypeId"),
                            doc.getString("symbol"),
                            doc.getString("value")
                    });
                }
            }
        }
        return targets;
    }
}
