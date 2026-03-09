# WID SOR Alert Service

Market alert orchestration service prototype. Receives FactSet webhooks, validates customer subscriptions against MongoDB, enriches with market data, and publishes to Kafka for downstream consumption by DSP.

## Architecture

```
FactSet HTTP POST → WID SOR (Spring Boot + MongoDB) → Kafka → DSP
```

## Prerequisites

- Java 17+
- Docker & Docker Compose
- ~8GB RAM available for load testing

## Quick Start

### 1. Start Infrastructure

```bash
cd wid-sor-alert-service
docker compose up -d mongodb kafka zookeeper mock-market-data
```

Wait ~15 seconds for Kafka to be ready.

### 2. Build & Run the Service

```bash
./gradlew bootRun
```

The service starts on port 8080.

### 3. Seed Test Data

```bash
./gradlew -q --console=plain runTestDataGenerator
```

Or run `TestDataGenerator.main()` directly from your IDE. This inserts 500K customer documents into MongoDB.

### 4. Send a Test Webhook

```bash
curl -X POST "http://localhost:8080/api/v1/alerts/factset/webhook?userId=<objectId>" \
  -H "Content-Type: application/json" \
  -d '{
    "triggerId": "FS-TRIG-000001",
    "triggerTypeId": "6",
    "symbol": "AAPL",
    "value": "-5",
    "triggeredAt": "2026-02-23T14:31:58.112Z"
  }'
```

### 5. Check Metrics

```bash
curl http://localhost:8080/actuator/metrics/wid.alert.processed
curl http://localhost:8080/actuator/prometheus
```

## Load Testing

### Run All 4 WID Instances

```bash
docker compose up -d
```

This starts 4 WID SOR instances on ports 8080, 8082, 8083, 8084.

### Test Scenarios

Run load tests via the `LoadTestScenarios` class. Each scenario can be invoked independently:

| Scenario | Description | Webhooks | Duration |
|----------|-------------|----------|----------|
| 1 | Normal Day | 100 | 5 min |
| 2 | Busy Day | 10,000 | 5 min |
| 3 | Market Crash | 2,000,000 | ASAP |
| 4 | Degraded Market Data | 2,000,000 | ASAP |
| 5 | Duplicate Handling | 100 | ASAP |
| 6 | Pod Crash | 2,000,000 | ASAP |

For Scenario 4 (degraded market data), restart mock-market-data with delay:

```bash
docker compose stop mock-market-data
RESPONSE_DELAY_MS=500 docker compose up -d mock-market-data
```

For Scenario 6 (pod crash), kill an instance mid-test:

```bash
docker stop wid-sor-2
```

### Performance Report

Each scenario generates a console report showing:
- Webhook response time percentiles (P50/P95/P99)
- Orchestration throughput
- Thread pool stats (queue depth, CallerRunsPolicy activations)
- MongoDB ops/latency
- Market data cache hit ratio
- Kafka publish throughput
- Memory/GC stats

## Key Design Decisions

1. **No inbound persistence** — FactSet alerts are not stored. Tradeoff: pod crash loses in-flight alerts.
2. **dateDelivered update before Kafka publish** — prefer missed alert over duplicate.
3. **CallerRunsPolicy** — thread pool never silently drops; controller thread processes synchronously when queue is full.
4. **Caffeine cache (30s TTL)** — prevents 200K HTTP calls for the same symbol during a crash event.
5. **Kafka key = customerId** — ensures ordering per customer for DSP.
6. **Eastern Time throttle** — one alert per security per alert type per day (America/New_York).

## Project Structure

```
src/main/java/com/bank/wid/
├── WIDAlertServiceApplication.java
├── config/          AsyncConfig, KafkaConfig, MongoConfig
├── controller/      FactSetWebhookController
├── service/         WIDAlertOrchestrationService, MarketDataClient
├── model/           FactSetAlert, CustomerDocument, DSPAlertMessage, etc.
└── repository/      CustomerRepository

src/test/java/com/bank/wid/
├── load/            TestDataGenerator, LoadTestRunner, LoadTestScenarios, PerformanceReportGenerator
├── service/         Unit tests
└── mock/            MockMarketDataServer
```

## Metrics (Micrometer)

All metrics exposed via `/actuator/prometheus`:

| Metric | Type | Description |
|--------|------|-------------|
| `wid.webhook.received` | Counter | Webhooks received |
| `wid.webhook.response.time` | Timer | Webhook response latency |
| `wid.alert.processed` | Counter | Successfully processed alerts |
| `wid.alert.skipped.inactive` | Counter | Skipped (subscription inactive) |
| `wid.alert.skipped.throttled` | Counter | Skipped (already delivered today) |
| `wid.alert.skipped.not_found` | Counter | Skipped (customer/subscription not found) |
| `wid.alert.failed` | Counter | Failed alerts |
| `wid.orchestration.time` | Timer | End-to-end orchestration time |
| `wid.mongo.lookup.time` | Timer | MongoDB customer lookup |
| `wid.mongo.update.time` | Timer | MongoDB dateDelivered update |
| `wid.marketdata.fetch.time` | Timer | Market data HTTP fetch (cache miss) |
| `wid.marketdata.cache.hit` | Counter | Cache hits |
| `wid.marketdata.cache.miss` | Counter | Cache misses |
| `wid.kafka.publish.time` | Timer | Kafka publish latency |
| `wid.threadpool.queue.size` | Gauge | Thread pool queue depth |
| `wid.threadpool.active.threads` | Gauge | Active processing threads |
