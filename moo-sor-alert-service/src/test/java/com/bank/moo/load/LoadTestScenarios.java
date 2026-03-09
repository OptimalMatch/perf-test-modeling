package com.bank.moo.load;

import com.bank.moo.load.LoadTestRunner.WebhookPayload;
import com.bank.moo.load.TestDataGenerator.GeneratedData;
import com.bank.moo.load.TestDataGenerator.WebhookTarget;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class LoadTestScenarios {

    private final TestDataGenerator dataGenerator;
    private final GeneratedData generatedData;
    private final List<String> targetUrls;
    private final Random random = new Random(99);

    public LoadTestScenarios(TestDataGenerator dataGenerator, GeneratedData generatedData, List<String> targetUrls) {
        this.dataGenerator = dataGenerator;
        this.generatedData = generatedData;
        this.targetUrls = targetUrls;
    }

    private List<WebhookPayload> pickRandomWebhooks(int count) {
        List<WebhookPayload> webhooks = new ArrayList<>(count);
        List<String> symbols = new ArrayList<>(generatedData.webhookTargets().keySet());
        String now = Instant.now().toString();

        for (int i = 0; i < count; i++) {
            String symbol = symbols.get(random.nextInt(symbols.size()));
            List<WebhookTarget> targets = generatedData.webhookTargets().get(symbol);
            WebhookTarget target = targets.get(random.nextInt(targets.size()));
            webhooks.add(new WebhookPayload(
                    target.userId(), target.triggerId(), target.triggerTypeId(),
                    target.symbol(), target.value(), now
            ));
        }
        return webhooks;
    }

    private List<WebhookPayload> buildCrashWebhooks(int totalCount) {
        List<WebhookPayload> webhooks = new ArrayList<>(totalCount);
        List<String> symbols = new ArrayList<>(generatedData.webhookTargets().keySet());
        String now = Instant.now().toString();

        // Spread across ~500 symbols, each with many subscribers
        int symbolCount = Math.min(500, symbols.size());
        List<String> crashSymbols = symbols.subList(0, symbolCount);

        int perSymbol = totalCount / symbolCount;
        int remainder = totalCount % symbolCount;

        for (int s = 0; s < symbolCount; s++) {
            String symbol = crashSymbols.get(s);
            List<WebhookTarget> targets = generatedData.webhookTargets().get(symbol);
            if (targets == null || targets.isEmpty()) continue;

            int count = perSymbol + (s < remainder ? 1 : 0);
            for (int i = 0; i < count; i++) {
                WebhookTarget target = targets.get(i % targets.size());
                webhooks.add(new WebhookPayload(
                        target.userId(), target.triggerId(), target.triggerTypeId(),
                        target.symbol(), target.value(), now
                ));
            }
        }

        // Shuffle to avoid perfectly ordered by symbol
        Collections.shuffle(webhooks, random);
        return webhooks;
    }

    // Scenario 1: Normal day — 100 webhooks over 5 minutes
    public PerformanceReportGenerator runScenario1NormalDay() {
        List<WebhookPayload> webhooks = pickRandomWebhooks(100);
        LoadTestRunner runner = new LoadTestRunner(targetUrls);

        PerformanceReportGenerator report = runner.runLoadTest(
                "Normal Day (100 alerts / 5 min)",
                webhooks,
                Duration.ofMinutes(5),
                10
        );

        runner.waitForProcessingToComplete(targetUrls.get(0), 100, Duration.ofMinutes(2));
        runner.collectMetricsIntoReport(report, targetUrls.get(0));
        return report;
    }

    // Scenario 2: Busy day — 10,000 webhooks over 5 minutes
    public PerformanceReportGenerator runScenario2BusyDay() {
        List<WebhookPayload> webhooks = pickRandomWebhooks(10_000);
        LoadTestRunner runner = new LoadTestRunner(targetUrls);

        PerformanceReportGenerator report = runner.runLoadTest(
                "Busy Day (10K alerts / 5 min)",
                webhooks,
                Duration.ofMinutes(5),
                50
        );

        runner.waitForProcessingToComplete(targetUrls.get(0), 10_000, Duration.ofMinutes(5));
        runner.collectMetricsIntoReport(report, targetUrls.get(0));
        return report;
    }

    // Scenario 3: Market crash — 2M webhooks as fast as possible
    public PerformanceReportGenerator runScenario3MarketCrash() {
        List<WebhookPayload> webhooks = buildCrashWebhooks(2_000_000);
        LoadTestRunner runner = new LoadTestRunner(targetUrls);

        PerformanceReportGenerator report = runner.runLoadTest(
                "Market Crash (2M alerts)",
                webhooks,
                Duration.ZERO, // as fast as possible
                200
        );

        runner.waitForProcessingToComplete(targetUrls.get(0), 2_000_000, Duration.ofMinutes(20));
        runner.collectMetricsIntoReport(report, targetUrls.get(0));
        return report;
    }

    // Scenario 4: Market crash with degraded market data (500ms delay)
    public PerformanceReportGenerator runScenario4DegradedMarketData() {
        List<WebhookPayload> webhooks = buildCrashWebhooks(2_000_000);
        LoadTestRunner runner = new LoadTestRunner(targetUrls);

        // Note: The mock market data server must be configured with 500ms delay
        // Set RESPONSE_DELAY_MS=500 on the mock-market-data container

        PerformanceReportGenerator report = runner.runLoadTest(
                "Market Crash + Degraded Market Data (2M alerts, 500ms delay)",
                webhooks,
                Duration.ZERO,
                200
        );

        runner.waitForProcessingToComplete(targetUrls.get(0), 2_000_000, Duration.ofMinutes(30));
        runner.collectMetricsIntoReport(report, targetUrls.get(0));
        return report;
    }

    // Scenario 5: Duplicate webhook handling — same webhook 100 times
    public PerformanceReportGenerator runScenario5Duplicates() {
        // Pick one eligible target
        Map.Entry<String, List<WebhookTarget>> entry =
                generatedData.webhookTargets().entrySet().iterator().next();
        WebhookTarget target = entry.getValue().get(0);
        String now = Instant.now().toString();

        List<WebhookPayload> webhooks = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            webhooks.add(new WebhookPayload(
                    target.userId(), target.triggerId(), target.triggerTypeId(),
                    target.symbol(), target.value(), now
            ));
        }

        LoadTestRunner runner = new LoadTestRunner(targetUrls);
        PerformanceReportGenerator report = runner.runLoadTest(
                "Duplicate Webhook Handling (100 duplicates)",
                webhooks,
                Duration.ZERO,
                10
        );

        runner.waitForProcessingToComplete(targetUrls.get(0), 100, Duration.ofMinutes(1));
        runner.collectMetricsIntoReport(report, targetUrls.get(0));
        return report;
    }

    // Scenario 6: Pod crash simulation — runs scenario 3 and notes about in-flight loss
    public PerformanceReportGenerator runScenario6PodCrash() {
        // This is essentially scenario 3 with documentation about what happens
        // when a pod is killed mid-processing. In a real test, you'd kill moo-sor-2
        // via `docker stop moo-sor-2` during the test.
        List<WebhookPayload> webhooks = buildCrashWebhooks(2_000_000);
        LoadTestRunner runner = new LoadTestRunner(targetUrls);

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║  SCENARIO 6: Pod Crash Simulation                ║");
        System.out.println("║  During this test, manually kill an instance:     ║");
        System.out.println("║    docker stop moo-sor-2                          ║");
        System.out.println("║  to observe in-flight alert loss behavior.        ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        PerformanceReportGenerator report = runner.runLoadTest(
                "Pod Crash Simulation (2M alerts, kill instance mid-test)",
                webhooks,
                Duration.ZERO,
                200
        );

        runner.waitForProcessingToComplete(targetUrls.get(0), 2_000_000, Duration.ofMinutes(20));
        runner.collectMetricsIntoReport(report, targetUrls.get(0));
        return report;
    }
}
