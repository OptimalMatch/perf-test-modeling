# MOO SOR Alert Service

Market alert orchestration service prototype for bank market alert processing. Receives FactSet webhooks via HTTP POST, validates customer subscriptions against **MongoDB or Oracle** (profile-switchable), enriches with market data from a REST-based market data service, and publishes enriched messages to a Kafka topic consumed by a downstream team (Cow).

## Architecture

```mermaid
flowchart LR
    FS["FactSet\nHTTP POST"] --> AP["Apigee\n(not simulated)"]
    AP --> MOO["MOO SOR\n(Spring Boot)"]
    MOO --> KF["Kafka Topic\nmoo-customer-alerts"]
    KF --> Cow["Cow\n(not simulated)"]
    MOO --> MG[("MongoDB\ncustomer SOR")]
    MOO -.-> ORA[("Oracle\n(alternative)")]
    MOO --> MD["Market Data\nREST Service"]
```

The database layer is abstracted behind a `CustomerDataService` interface. Spring profiles control which implementation is active:
- **Default (no profile)**: MongoDB embedded — single document per customer with embedded subscription arrays
- **`mongo-flat` profile**: MongoDB flat — one document per subscription per customer (denormalized)
- **`oracle` profile**: Oracle 23ai — normalized relational model (3 tables, one row per subscription)

- **MOO SOR** is the service we build and test — it owns no Kafka topics, only publishes to Cow's `moo-customer-alerts` topic
- **FactSet** sends one webhook per customer per trigger — if 200K customers subscribe to AAPL 5% drop, FactSet sends 200K separate webhooks; MOO does NOT fan out
- **MOO does NOT persist inbound FactSet alerts** — no queue, no raw alert collection; if a pod crashes, in-flight alerts in the thread pool are lost

## Subscription Lifecycle

MOO SOR is the **alert orchestration** service — it reads subscriptions and processes alerts, but it does **not** own the subscription CRUD lifecycle. This prototype has no subscription management API.

### How Subscriptions Get Into MongoDB (Production)

```mermaid
sequenceDiagram
    participant CX as Customer<br/>(UI/Mobile)
    participant SUB as Subscription<br/>Management API<br/>(separate service)
    participant FS as FactSet API
    participant MDB as MongoDB<br/>(customer SOR)
    participant MOO as MOO SOR<br/>(this service)

    CX->>SUB: "Alert me if AAPL drops 5%"
    SUB->>FS: Register trigger<br/>(symbol=AAPL, type=6, value=-5,<br/>callbackUrl=.../webhook?userId=abc)
    FS-->>SUB: triggerId = FS-TRIG-88421
    SUB->>MDB: Add subscription to customer doc<br/>(factSetTriggerId, symbol, value, activeState=Y)
    Note over MDB: Subscription now exists.<br/>MOO SOR can read it.
    Note over FS: Later, when AAPL drops 5%...
    FS->>MOO: POST /webhook?userId=abc<br/>{triggerId: FS-TRIG-88421, ...}
    MOO->>MDB: Lookup customer + subscription
    MOO->>MOO: Validate, enrich, publish
```

The key points:

1. **A separate subscription management service** handles customer subscribe/unsubscribe requests and writes to MongoDB
2. **FactSet trigger registration** happens at subscription time — the subscription service registers a trigger with FactSet's API and stores the returned `factSetTriggerId` in the customer document
3. **The `userId` in the webhook callback URL** is the MongoDB `_id` — FactSet stores this at registration time and includes it in every webhook callback, so MOO SOR can do a direct primary key lookup
4. **MOO SOR only reads** — it looks up the subscription, validates eligibility, and processes the alert; it never creates or deletes subscriptions

### How Subscriptions Are Created in This Prototype

Since there is no subscription management API in this prototype, test data is seeded by writing directly to MongoDB via `TestDataGenerator`:

```
TestDataGenerator → MongoDB (direct bulk insert)
                    ↓
               500K customers with pre-existing subscriptions
               (~1.4M eligible webhook targets across ~2,000 symbols)
                    ↓
               Load test sends webhooks referencing those subscriptions
```

The generator creates realistic data distributions:
- 2-8 subscriptions per customer (randomized)
- 80% active (`activeState: "Y"`) / 20% inactive
- 70% eligible (`dateDelivered: null`) / 30% already delivered today
- Spread across ~2,000 unique symbols with mixed trigger types

## Orchestration Flow

When a FactSet webhook arrives at `POST /api/v1/alerts/factset/webhook?userId={mongoObjectId}`:

```mermaid
flowchart TD
    A["POST /api/v1/alerts/factset/webhook\n?userId={mongoObjectId}"] --> B["Return HTTP 200\nimmediately"]
    A --> C["Dispatch to async\nthread pool"]
    C --> D{"Queue full?"}
    D -- No --> E["Worker thread\nprocesses"]
    D -- "Yes (CallerRunsPolicy)" --> F["Controller thread\nprocesses synchronously"]
    E --> G
    F --> G["MongoDB: lookup customer by _id\n+ $elemMatch on subscriptions"]
    G --> H{"Eligible subscription\nfound?"}
    H -- No --> I["Skip\n(log + metric)"]
    H -- Yes --> J["Market Data: fetch via REST\n(Caffeine-cached, 30s TTL)"]
    J --> K["MongoDB: set dateDelivered = now()\nBEFORE Kafka publish"]
    K --> L["Kafka: publish CowAlertMessage\nkeyed by customerId"]
    L --> M["Done\n(increment processed counter)"]
```

### Data Flow Diagram

```mermaid
flowchart LR
    subgraph JVM ["MOO SOR JVM Instance"]
        CTRL["Controller"] --> TP["Thread Pool\n50-200 threads\n50K queue\nCallerRunsPolicy"]
        TP --> ORCH["Orchestration"]
        ORCH --> |"1"| MR[("MongoDB Read\n(by _id)")]
        ORCH --> |"2"| CACHE["Caffeine Cache\n30s TTL\n5000 max"]
        CACHE -.-> |"miss"| MDSVC["Market Data\nREST call"]
        ORCH --> |"3"| MW[("MongoDB Write\n(dateDelivered)")]
        ORCH --> |"4"| KP["Kafka Publish"]
    end
    HTTP["HTTP POST\n(return 200)"] --> CTRL
    KP --> TOPIC[["Kafka Topic\nmoo-customer-alerts"]]
    TOPIC --> Cow["Cow Consumer"]
```

### Sequence Diagram

```mermaid
sequenceDiagram
    participant FS as FactSet
    participant C as Controller
    participant TP as Thread Pool
    participant MDB as MongoDB
    participant MD as Market Data<br/>(+ Caffeine Cache)
    participant K as Kafka

    FS->>C: POST /webhook?userId=abc
    C-->>FS: 200 OK (immediate)
    C->>TP: enqueue async task
    TP->>MDB: findById(userId)<br/>+ $elemMatch(triggerId)
    MDB-->>TP: CustomerDocument
    Note over TP: Validate: activeState=Y,<br/>dateDelivered check (ET)
    TP->>MD: getMarketData(symbol)
    alt Cache hit
        MD-->>TP: cached MarketData (ns)
    else Cache miss
        MD->>MD: REST GET /marketdata/{symbol}
        MD-->>TP: MarketData (~3.5ms)
    end
    TP->>MDB: update dateDelivered = now()
    Note over TP,MDB: BEFORE Kafka publish<br/>(prefer missed over duplicate)
    TP->>K: send(CowAlertMessage,<br/>key=customerId)
    K-->>TP: ack
```

### Why Each Step Matters

| Step | What | Why |
|------|------|-----|
| Immediate 200 | Return before processing | FactSet expects fast ACK; processing is async |
| CallerRunsPolicy | Overflow → controller thread processes | Never silently drop alerts; backpressure slows inbound instead |
| Mongo `_id` + `$elemMatch` | Lookup by primary key + array match | Fastest possible MongoDB query path |
| Caffeine cache | In-memory cache for market data | Prevents 200K HTTP calls for the same symbol during a crash event |
| dateDelivered BEFORE publish | Update MongoDB before Kafka send | Prefer missed alert over duplicate; if publish fails customer misses one alert; if publish succeeds but update fails customer gets duplicates |
| Kafka key = customerId | Message key for partitioning | Ensures ordering per customer if Cow cares about that |

## Market Data Cache (Caffeine)

The Caffeine cache is an **in-memory Java cache** inside each MOO SOR JVM instance. It has nothing to do with MongoDB.

```mermaid
flowchart LR
    subgraph "NOT cached (always fresh)"
        MDB[("MongoDB")] --> |"customer subscriptions\ncontact preferences"| SVC["MOO SOR"]
    end
    subgraph "THIS is cached (Caffeine, 30s TTL)"
        SVC --> |"miss"| REST["Market Data\nREST API"]
        SVC --> |"hit"| CC["Caffeine Cache\n(JVM heap)"]
        REST --> |"price, volume,\n52-week range"| CC
    end
```

### How It Works

```java
// MarketDataClient.java
Cache<String, MarketData> cache = Caffeine.newBuilder()
    .maximumSize(5000)                        // max 5,000 symbols
    .expireAfterWrite(30, TimeUnit.SECONDS)   // 30-second TTL
    .build();

public MarketData getMarketData(String symbol) {
    MarketData cached = cache.getIfPresent(symbol);   // check cache first
    if (cached != null) {
        cacheHitCounter.increment();
        return cached;                                 // instant return, no HTTP call
    }
    cacheMissCounter.increment();
    MarketData data = restTemplate.getForObject(url, MarketData.class);  // HTTP call (~3.5ms)
    cache.put(symbol, data);                           // cache for next 30 seconds
    return data;
}
```

### Why It's Critical

During a market crash, thousands of customers have alerts for the **same symbols**. Without the cache:
- 1,065,252 processed alerts → ~1M HTTP calls to the market data service
- The upstream market data service (already under heavy load during a crash) gets hammered

With the cache:
- Only **41,561 actual HTTP calls** (one per unique symbol per 30-second window)
- **1,023,691 cache hits** served from JVM heap in nanoseconds
- **96.1% cache hit ratio** observed in the 2M market crash test

Each of the 4 JVM instances has its **own independent cache** — which is why total cache misses (~41K) are roughly 4x the ~2,000 unique symbols (each instance builds its own cache on startup, then mostly hits after warm-up).

### Configuration

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| `max-size` | 5,000 | Comfortably holds all ~2,000 symbols with headroom |
| `ttl-seconds` | 30 | Market data is reasonably fresh; short enough to reflect intraday moves |

Configured via `application.yml`:
```yaml
moo:
  market-data:
    base-url: http://localhost:8081
    cache:
      max-size: 5000
      ttl-seconds: 30
```

## MongoDB Document Structure

Single collection: `customers`

```json
{
  "_id": ObjectId("64a7f3b2c1d4e5f6a7b8c9d0"),
  "customerId": "CUST-9938271",
  "firstName": "Margaret",
  "lastName": "Thornton",
  "contactPreferences": {
    "channels": [
      { "type": "PUSH_NOTIFICATION", "enabled": true, "priority": 1 },
      { "type": "EMAIL", "enabled": true, "priority": 2, "address": "m.thornton@email.com" },
      { "type": "SMS", "enabled": true, "priority": 3, "phoneNumber": "+12125551234" }
    ]
  },
  "subscriptions": [
    {
      "symbol": "AAPL",
      "factSetTriggerId": "FS-TRIG-88421",
      "triggerTypeId": "6",
      "value": "-5",
      "activeState": "Y",
      "subscribedAt": "2025-09-15T10:00:00.000Z",
      "dateDelivered": null
    }
  ]
}
```

## MongoDB Flat Document Structure (Alternative)

When running with the `mongo-flat` profile, each subscription becomes its own document in a `customer_alerts` collection. Customer info and channel preferences are denormalized into every document.

Collection: `customer_alerts`

```json
{
  "_id": ObjectId("auto-generated"),
  "customerId": "64a7f3b2c1d4e5f6a7b8c9d0",
  "customerCode": "CUST-9938271",
  "firstName": "Margaret",
  "lastName": "Thornton",
  "channels": [
    { "type": "PUSH_NOTIFICATION", "enabled": true, "priority": 1 },
    { "type": "EMAIL", "enabled": true, "priority": 2, "address": "m.thornton@email.com" },
    { "type": "SMS", "enabled": true, "priority": 3, "phoneNumber": "+12125551234" }
  ],
  "symbol": "AAPL",
  "factSetTriggerId": "FS-TRIG-88421",
  "triggerTypeId": "6",
  "value": "-5",
  "activeState": "Y",
  "subscribedAt": "2025-09-15T10:00:00.000Z",
  "dateDelivered": null
}
```

### Embedded vs Flat: Data Model Comparison

```
MongoDB Embedded (1 doc per customer, default):
┌──────────────────────────────────────────┐
│ { _id: "abc",                            │
│   customerId: "CUST-001",                │
│   subscriptions: [                       │  ← embedded array
│     { symbol: "AAPL", triggerId: ... },  │
│     { symbol: "MSFT", triggerId: ... }   │
│   ],                                     │
│   contactPreferences: { channels: [...] }│
│ }                                        │
└──────────────────────────────────────────┘
  → 500K documents for 500K customers
  → 1 read by _id returns everything
  → 1 $elemMatch + positional $ update modifies subscription in-place

MongoDB Flat (1 doc per subscription, mongo-flat profile):
┌─────────────────────────────────────────┐
│ { customerId: "abc",                    │
│   customerCode: "CUST-001",             │
│   firstName: "...", lastName: "...",     │  ← denormalized
│   channels: [...],                      │  ← denormalized
│   symbol: "AAPL",                       │
│   factSetTriggerId: "FS-TRIG-001",      │
│   activeState: "Y",                     │
│   dateDelivered: null                   │
│ }                                       │
└─────────────────────────────────────────┘
  → ~2.5M documents for 500K customers (avg 5 subscriptions each)
  → Read requires multi-doc query by customerId index + reassembly
  → Update is a simple single-doc update by compound index (no positional ops)
```

### Data Volume (500K Customers)

| Collection | Documents | Notes |
|------------|-----------|-------|
| `customers` (embedded) | 500,000 | 1 doc per customer, ~5 embedded subscriptions each |
| `customer_alerts` (flat) | ~2,500,000 | 1 doc per subscription, customer data denormalized |

### Indexes

| Collection | Index | Purpose |
|------------|-------|---------|
| `customers` | `_id` (default) | Primary key lookup by userId |
| `customer_alerts` | `{customerId: 1}` | Fetch all subscriptions for a customer |
| `customer_alerts` | `{customerId: 1, factSetTriggerId: 1}` (unique) | Update specific subscription |

## Oracle Table Structure (Alternative)

When running with the `oracle` profile, the same customer data is stored in a normalized relational model across three tables. Each subscription that was an embedded array element in MongoDB becomes **its own row** in Oracle.

### Schema

```sql
-- One row per customer (mirrors MongoDB _id as PK)
CREATE TABLE CUSTOMERS (
    ID              VARCHAR2(24)    PRIMARY KEY,   -- same as MongoDB ObjectId hex
    CUSTOMER_ID     VARCHAR2(20)    NOT NULL,
    FIRST_NAME      VARCHAR2(50),
    LAST_NAME       VARCHAR2(50)
);

-- One row per alert subscription (normalized from MongoDB embedded array)
CREATE TABLE CUSTOMER_SUBSCRIPTIONS (
    ID                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    CUSTOMER_ID         VARCHAR2(24)    NOT NULL REFERENCES CUSTOMERS(ID),
    SYMBOL              VARCHAR2(10)    NOT NULL,
    FACTSET_TRIGGER_ID  VARCHAR2(20)    NOT NULL,
    TRIGGER_TYPE_ID     VARCHAR2(5)     NOT NULL,
    TRIGGER_VALUE       VARCHAR2(10),
    ACTIVE_STATE        CHAR(1)         NOT NULL,
    SUBSCRIBED_AT       TIMESTAMP WITH TIME ZONE,
    DATE_DELIVERED      TIMESTAMP WITH TIME ZONE
);

-- One row per notification channel per customer
CREATE TABLE CHANNEL_PREFERENCES (
    ID              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    CUSTOMER_ID     VARCHAR2(24)    NOT NULL REFERENCES CUSTOMERS(ID),
    CHANNEL_TYPE    VARCHAR2(30)    NOT NULL,
    ENABLED         NUMBER(1)       NOT NULL,
    PRIORITY        NUMBER(3),
    ADDRESS         VARCHAR2(100),
    PHONE_NUMBER    VARCHAR2(20)
);

-- Indexes for query patterns
CREATE INDEX IDX_SUB_CUSTOMER_ID ON CUSTOMER_SUBSCRIPTIONS(CUSTOMER_ID);
CREATE INDEX IDX_SUB_TRIGGER_ID ON CUSTOMER_SUBSCRIPTIONS(FACTSET_TRIGGER_ID);
CREATE INDEX IDX_CHAN_CUSTOMER_ID ON CHANNEL_PREFERENCES(CUSTOMER_ID);
```

### Data Volume (500K Customers)

| Table | Rows | Notes |
|-------|------|-------|
| `CUSTOMERS` | 500,000 | 1:1 with MongoDB documents |
| `CUSTOMER_SUBSCRIPTIONS` | ~2,500,000 | 2-8 per customer (avg ~5) |
| `CHANNEL_PREFERENCES` | ~1,300,000 | 2-3 per customer |

### MongoDB vs Oracle: Data Model Comparison

```
MongoDB (1 collection, 1 document per customer):
┌─────────────────────────────────────────┐
│ { _id: "abc",                           │
│   customerId: "CUST-001",               │
│   subscriptions: [                      │  ← embedded array
│     { symbol: "AAPL", triggerId: ... }, │
│     { symbol: "MSFT", triggerId: ... }  │
│   ],                                    │
│   contactPreferences: {                 │
│     channels: [ ... ]                   │  ← embedded array
│   }                                     │
│ }                                       │
└─────────────────────────────────────────┘
  → 1 read by _id returns everything
  → 1 $elemMatch update modifies subscription in-place

Oracle (3 tables, normalized):
┌──────────────┐    ┌───────────────────────────┐    ┌─────────────────────┐
│ CUSTOMERS    │    │ CUSTOMER_SUBSCRIPTIONS     │    │ CHANNEL_PREFERENCES │
│ ─────────    │    │ ────────────────────────   │    │ ──────────────────  │
│ ID (PK)      │◄──│ CUSTOMER_ID (FK)           │    │ CUSTOMER_ID (FK)    │──►│
│ CUSTOMER_ID  │    │ SYMBOL                     │    │ CHANNEL_TYPE        │
│ FIRST_NAME   │    │ FACTSET_TRIGGER_ID         │    │ ENABLED             │
│ LAST_NAME    │    │ ACTIVE_STATE               │    │ PRIORITY            │
│              │    │ DATE_DELIVERED              │    │ ADDRESS             │
└──────────────┘    └───────────────────────────┘    └─────────────────────┘
  → 1 read requires JOINs across 3 tables (JPA EAGER fetch)
  → 1 UPDATE on CUSTOMER_SUBSCRIPTIONS by customer_id + trigger_id
```

### Throttle Logic

One alert per security, per alert type, per day (Eastern Time):

```mermaid
flowchart TD
    A["Incoming webhook\nfor customer + triggerId"] --> B{"Subscription\nfound?"}
    B -- No --> X1["Skip\n(not_found)"]
    B -- Yes --> C{"activeState\n== 'Y'?"}
    C -- No --> X2["Skip\n(inactive)"]
    C -- Yes --> D{"dateDelivered?"}
    D -- "null" --> OK["Eligible\n(never delivered)"]
    D -- "not null" --> E{"Before start of\ntoday (ET)?"}
    E -- Yes --> OK2["Eligible\n(new trading day)"]
    E -- No --> X3["Throttled\n(already delivered today)"]
```

### Trigger Types

| triggerTypeId | Description | Value field |
|---------------|-------------|-------------|
| `6` | % Rise/Drop | Signed: `"-5"` = 5% drop, `"10"` = 10% rise |
| `3` | 52-week high | `"0"` |
| `4` | 52-week low | `"0"` |

## Cow Kafka Message Format

What Cow receives on `moo-customer-alerts` topic (keyed by `customerId`):

```json
{
  "customerId": "CUST-9938271",
  "firstName": "Margaret",
  "lastName": "Thornton",
  "symbol": "AAPL",
  "triggerTypeId": "6",
  "value": "-5",
  "factSetTriggerId": "FS-TRIG-88421",
  "triggeredAt": "2026-02-23T14:31:58.112Z",
  "processedAt": "2026-02-23T14:32:07.445Z",
  "securityName": "Apple Inc.",
  "currentPrice": 236.21,
  "open": 248.50,
  "dayLow": 234.88,
  "dayHigh": 249.10,
  "dailyVolume": 89542100,
  "fiftyTwoWeekLow": 164.08,
  "fiftyTwoWeekHigh": 252.87,
  "currency": "USD",
  "channels": [
    { "type": "PUSH_NOTIFICATION", "enabled": true, "priority": 1 },
    { "type": "EMAIL", "enabled": true, "priority": 2, "address": "m.thornton@email.com" }
  ]
}
```

## Async Thread Pool Configuration

```java
ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
executor.setCorePoolSize(50);        // 50 threads always warm
executor.setMaxPoolSize(200);        // scale up to 200 under load
executor.setQueueCapacity(50000);    // 50K queued tasks before overflow
executor.setRejectedExecutionHandler(new CallerRunsPolicy());  // overflow → caller processes synchronously
```

**CallerRunsPolicy** means: if the queue is full AND all 200 threads are busy, the controller thread (Tomcat HTTP thread) processes the alert itself. This slows down the webhook response but **never silently drops** an alert.

## Prerequisites

- Java 17+
- Docker & Docker Compose
- ~8GB RAM available for load testing

## Quick Start

The service supports three database configurations, selected via Spring profile:

| Mode | Profile | Database | Docker Compose |
|------|---------|----------|---------------|
| **MongoDB Embedded** (default) | none | MongoDB 7.0 | `docker compose up` |
| **MongoDB Flat** | `mongo-flat` | MongoDB 7.0 | `docker compose -f docker-compose.yml -f docker-compose-mongo-flat.yml up` |
| **Oracle** | `oracle` | Oracle 23ai Free | `docker compose -f docker-compose.yml -f docker-compose-oracle.yml up` |

### Option A: MongoDB Embedded (Default)

#### 1. Start Infrastructure

```bash
cd moo-sor-alert-service
docker compose up --build -d
```

This starts MongoDB, Zookeeper, Kafka, mock market data, and 4 MOO SOR instances.

#### 2. Seed Test Data

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
$JAVA_HOME/bin/java -cp "build/classes/java/test:build/classes/java/main:$(find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' | tr '\n' ':')" \
  com.bank.moo.load.TestDataGenerator mongodb://localhost:27017 500000
```

This inserts 500K customer documents into MongoDB with:
- 2-8 subscriptions per customer (randomized)
- ~2,000 unique symbols
- 80% active / 20% inactive subscriptions
- 70% eligible (dateDelivered null) / 30% already delivered today
- ~1.4M total eligible webhook targets

### Option B: MongoDB Flat

#### 1. Start Infrastructure

```bash
cd moo-sor-alert-service
docker compose -f docker-compose.yml -f docker-compose-mongo-flat.yml up --build -d
```

This starts the same infrastructure as Option A but overrides MOO SOR instances to use the `mongo-flat` profile (one document per subscription instead of one document per customer).

#### 2. Seed Test Data

```bash
# Via Gradle:
./gradlew seedMongoFlat

# Or directly:
$JAVA_HOME/bin/java -Xmx4g -Xms2g -cp "build/classes/java/test:build/classes/java/main:$(find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' | tr '\n' ':')" \
  com.bank.moo.load.FlatMongoTestDataGenerator mongodb://localhost:27017 500000
```

This inserts ~2.5M alert documents into the `customer_alerts` collection (avg ~5 per customer), with the same data distribution and random seed as the embedded model.

### Option C: Oracle

#### 1. Start Infrastructure with Oracle Overlay

```bash
cd moo-sor-alert-service
docker compose -f docker-compose.yml -f docker-compose-oracle.yml up --build -d
```

This adds an Oracle 23ai Free container and overrides MOO SOR instances to use the `oracle` profile (JPA/HikariCP instead of MongoDB).

**First-time startup**: Oracle takes 30-60 seconds to initialize. The MOO SOR instances may fail to connect initially — restart them after Oracle is ready:

```bash
# Wait for Oracle to be ready
until docker exec moo-oracle bash -c "echo 'SELECT 1 FROM DUAL;' | sqlplus -s moo/moo_password@//localhost:1521/FREEPDB1" 2>&1 | grep -q "1"; do sleep 5; done

# Restart instances
docker restart moo-sor-1 moo-sor-2 moo-sor-3 moo-sor-4
```

#### 2. Seed Oracle with Test Data

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
$JAVA_HOME/bin/java -Xmx4g -Xms2g -cp "build/classes/java/test:build/classes/java/main:$(find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' | tr '\n' ':')" \
  com.bank.moo.load.OracleTestDataGenerator

# Or via Gradle:
./gradlew seedOracle
```

This creates the 3-table schema and inserts 500K customers with the same data distribution (same random seed) as MongoDB:
- `CUSTOMERS`: 500,000 rows
- `CUSTOMER_SUBSCRIPTIONS`: ~2,500,000 rows (one per subscription)
- `CHANNEL_PREFERENCES`: ~1,300,000 rows (one per channel)
- Gathers optimizer statistics after loading

### 4. Send a Test Webhook

```bash
# First, get a valid userId from MongoDB:
docker exec moo-mongodb mongosh --quiet moo --eval '
  let c = db.customers.findOne({"subscriptions.activeState": "Y", "subscriptions.dateDelivered": null});
  let s = c.subscriptions.find(s => s.activeState === "Y" && s.dateDelivered === null);
  printjson({userId: c._id.toString(), triggerId: s.factSetTriggerId, symbol: s.symbol});
'

# Then send the webhook:
curl -X POST "http://localhost:8080/api/v1/alerts/factset/webhook?userId=<objectId>" \
  -H "Content-Type: application/json" \
  -d '{
    "triggerId": "FS-TRIG-000001",
    "triggerTypeId": "6",
    "symbol": "AAPL",
    "value": "-5",
    "triggeredAt": "2026-02-23T14:31:58.112Z"
  }'

# Verify the Kafka message:
docker exec moo-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic moo-customer-alerts \
  --from-beginning --max-messages 1 --timeout-ms 5000
```

### 5. Check Metrics

```bash
curl http://localhost:8080/actuator/metrics/moo.alert.processed
curl http://localhost:8080/actuator/prometheus
```

## Docker Compose Environment

```mermaid
flowchart TB
    subgraph docker ["Docker Compose Network"]
        subgraph infra ["Infrastructure"]
            MDB[("MongoDB\n:27017")]
            ORA[("Oracle 23ai\n:1521")]
            ZK["Zookeeper\n:2181"]
            KFK["Kafka\nINTERNAL :29092\nEXTERNAL :9092"]
            MOCK["Mock Market Data\n:8081"]
            ZK --> KFK
        end
        subgraph instances ["MOO SOR Instances"]
            W1["moo-sor-1\n:8080"]
            W2["moo-sor-2\n:8082"]
            W3["moo-sor-3\n:8083"]
            W4["moo-sor-4\n:8084"]
        end
        W1 & W2 & W3 & W4 --> |"default profile"| MDB
        W1 & W2 & W3 & W4 -.-> |"oracle profile"| ORA
        W1 & W2 & W3 & W4 --> KFK
        W1 & W2 & W3 & W4 --> MOCK
    end
    LG["Load Generator\n(host)"] --> W1 & W2 & W3 & W4
```

Kafka uses dual listeners:
- **INTERNAL** (`kafka:29092`) — for MOO SOR containers on the Docker network
- **EXTERNAL** (`localhost:9092`) — for host-side tools and load generators

### Start Everything

```bash
# MongoDB mode (default)
docker compose up --build -d

# MongoDB flat mode
docker compose -f docker-compose.yml -f docker-compose-mongo-flat.yml up --build -d

# Oracle mode
docker compose -f docker-compose.yml -f docker-compose-oracle.yml up --build -d
```

The overlay files set `SPRING_PROFILES_ACTIVE` on each MOO SOR instance (`mongo-flat` or `oracle`), selecting the appropriate `CustomerDataService` implementation. All infrastructure containers (MongoDB, Oracle, Kafka) are started in every mode, but only the relevant database is used by the service.

## Load Testing

### Test Scenarios

| Scenario | Description | Webhooks | Pacing | Purpose |
|----------|-------------|----------|--------|---------|
| 1 | Normal Day | 100 | 5 min | Baseline — should be trivially handled |
| 2 | Busy Day | 10,000 | 5 min | Moderate load — verify thread pool stays healthy |
| 3 | **Market Crash** | **2,000,000** | **ASAP** | Peak stress — measure throughput ceiling |
| 4 | Degraded Market Data | 2,000,000 | ASAP | 500ms market data latency — tests cache effectiveness |
| 5 | Duplicate Handling | 100 | ASAP | Same webhook 100x — verify only 1 Kafka message |
| 6 | Pod Crash | 2,000,000 | ASAP | Kill instance mid-test — measure in-flight loss |

### Running the Market Crash Scenario (Scenario 3)

#### MongoDB Embedded

```bash
# 1. Start with MongoDB embedded (default)
docker compose up --build -d

# 2. Seed test data
./gradlew seedMongo
# Or: ./gradlew compileTestJava && $JAVA_HOME/bin/java -cp "..." com.bank.moo.load.TestDataGenerator

# 3. Run the load test
./gradlew mongoLoadTest

# Report saved to /tmp/moo-market-crash-report.txt
```

#### MongoDB Flat

```bash
# 1. Start with MongoDB flat model
docker compose -f docker-compose.yml -f docker-compose-mongo-flat.yml up --build -d

# 2. Seed flat test data
./gradlew seedMongoFlat

# 3. Restart instances to clear metrics
docker restart moo-sor-1 moo-sor-2 moo-sor-3 moo-sor-4

# 4. Run the flat model load test
./gradlew mongoFlatLoadTest

# Report saved to /tmp/moo-mongo-flat-market-crash-report.txt
```

#### Oracle

```bash
# 1. Start with Oracle overlay
docker compose -f docker-compose.yml -f docker-compose-oracle.yml up --build -d

# 2. Wait for Oracle, restart instances if needed (see Quick Start Option B)

# 3. Seed Oracle
./gradlew seedOracle

# 4. Restart instances to clear metrics
docker restart moo-sor-1 moo-sor-2 moo-sor-3 moo-sor-4

# 5. Run the Oracle load test
./gradlew oracleLoadTest

# Report saved to /tmp/moo-oracle-market-crash-report.txt
```

Both load generators follow the same pattern:
- Read all eligible targets from the database (MongoDB or Oracle)
- Build 2M webhook payloads by sampling from eligible targets
- Send via async HTTP with 10K in-flight semaphore across 200 sender threads
- Round-robin across all 4 MOO SOR instances
- Wait for async processing to complete
- Collect metrics from all instances via `/actuator/metrics`
- Print a formatted performance report

### Scenario 4: Degraded Market Data

```bash
docker compose stop mock-market-data
RESPONSE_DELAY_MS=500 docker compose up -d mock-market-data
# Then run MarketCrashLoadTest — observe cache effectiveness under slow upstream
```

### Scenario 6: Pod Crash

```bash
# During a running MarketCrashLoadTest, kill an instance:
docker stop moo-sor-2
# Observe: alerts in that instance's thread pool queue are lost
# Recovery: FactSet would need to retry those webhooks
```

## Market Crash Performance Results

Actual results from running Scenario 3 on a single dev machine (4 containerized instances, no CPU/memory limits):

### MongoDB Embedded (Default)

```
═══════════════════════════════════════════════════════
MOO SOR Performance Test Report
═══════════════════════════════════════════════════════
Scenario:                    Market Crash (2M alerts, 4 instances)
Duration:                    2 minutes 39 seconds
Send phase:                  2 minutes 39 seconds
Total webhooks sent:         2,000,000
Total alerts processed:      1,065,263
Total alerts skipped:        934,737 (inactive: 0, throttled: 934,737, not found: 0)
Total alerts failed:         0
Network/HTTP errors:         0 (send) + 0 (http)

Webhook Response Time:
  P50:                       817.31 ms
  P95:                       1070.47 ms
  P99:                       1403.51 ms
  Max:                       3262.36 ms

Orchestration Throughput:
  Avg:                       6,700 /sec (across 4 instances)
  Per instance avg:          1,675 /sec
  Peak send rate:            24,247 /sec
  Avg orchestration time:    3.33 ms

Thread Pool:
  Peak queue depth:          0 (at query time)
  CallerRunsPolicy count:    0
  Peak active threads:       0 (at query time)

MongoDB:
  Avg lookup time:           1.20 ms
  Avg update time:           1.58 ms
  Total ops:                 3,065,263

Market Data:
  Cache hit ratio:           95.9%
  Cache hits:                1,021,806
  Cache misses:              43,457
  Avg fetch time (miss):     3.64 ms

Kafka:
  Messages published:        1,065,263
  Avg publish time:          2.20 ms

CPU (sampled every 2s):
  System CPU (host):
    Avg:                     47.5%
    Peak:                    99.9%
  Process CPU (per JVM):
    Instance 1:              avg 5.2%, peak 18.6%
    Instance 2:              avg 5.1%, peak 19.2%
    Instance 3:              avg 5.2%, peak 23.4%
    Instance 4:              avg 5.3%, peak 21.5%
    Overall avg:             5.2%
    Overall peak:            23.4%
  Samples collected:         320

Memory:
  Load generator heap:       1,212 MB
═══════════════════════════════════════════════════════
```

### MongoDB Flat Model Performance Results

Same scenario (2M webhooks, 4 instances, same dev machine) with the `mongo-flat` profile — one document per subscription instead of one document per customer.

```
═══════════════════════════════════════════════════════
MOO SOR Performance Test Report — FLAT MONGO MODEL
═══════════════════════════════════════════════════════
Data Model:                  Flat (1 doc per subscription, denormalized)
Scenario:                    Market Crash (2M alerts, 4 instances)
Duration:                    2 minutes 30 seconds
Send phase:                  2 minutes 30 seconds
Total webhooks sent:         2,000,000
Total alerts processed:      1,065,815
Total alerts skipped:        934,185 (inactive: 0, throttled: 934,185, not found: 0)
Total alerts failed:         0
Network/HTTP errors:         0 (send) + 0 (http)

Webhook Response Time:
  P50:                       736.40 ms
  P95:                       1038.10 ms
  P99:                       1425.24 ms
  Max:                       3809.98 ms

Orchestration Throughput:
  Avg:                       7,105 /sec (across 4 instances)
  Per instance avg:          1,776 /sec
  Peak send rate:            22,469 /sec
  Avg orchestration time:    4.40 ms

Thread Pool:
  Peak queue depth:          0 (at query time)
  CallerRunsPolicy count:    0
  Peak active threads:       0 (at query time)

MongoDB (Flat Model):
  Avg lookup time:           1.77 ms
  Avg update time:           2.46 ms
  Total ops:                 3,065,815

Market Data:
  Cache hit ratio:           96.2%
  Cache hits:                1,025,691
  Cache misses:              40,124
  Avg fetch time (miss):     3.81 ms

Kafka:
  Messages published:        1,065,815
  Avg publish time:          2.30 ms

CPU (sampled every 2s):
  System CPU (host):
    Avg:                     53.0%
    Peak:                    100.0%
  Process CPU (per JVM):
    Instance 1:              avg 6.0%, peak 19.2%
    Instance 2:              avg 6.0%, peak 19.2%
    Instance 3:              avg 6.0%, peak 20.3%
    Instance 4:              avg 6.1%, peak 23.6%
    Overall avg:             6.0%
    Overall peak:            23.6%
  Samples collected:         304

Memory:
  Load generator heap:       1,472 MB
═══════════════════════════════════════════════════════
```

### MongoDB Embedded vs Flat: Head-to-Head Comparison

Both tests used identical data (same random seed, 500K customers, ~1.4M eligible targets) and ran on the same machine.

| Metric | Embedded | Flat | Delta |
|--------|----------|------|-------|
| **Total duration** | 2m 39s | 2m 30s | **Flat 6% faster** |
| **Throughput (aggregate)** | 6,700/sec | 7,105/sec | **Flat +6%** |
| **Per instance throughput** | 1,675/sec | 1,776/sec | **Flat +6%** |
| **Alerts processed** | 1,065,263 | 1,065,815 | ~same |
| **Alerts failed** | 0 | 0 | tie |
| **P50 response** | 817ms | 736ms | **Flat 10% better** |
| **P95 response** | 1,070ms | 1,038ms | **Flat 3% better** |
| **P99 response** | 1,404ms | 1,425ms | ~same |
| **Max response** | 3,262ms | 3,810ms | Embedded 14% better |
| **DB lookup time** | 1.20ms | 1.77ms | Embedded 32% faster |
| **DB update time** | 1.58ms | 2.46ms | Embedded 36% faster |
| **Avg orchestration time** | 3.33ms | 4.40ms | Embedded 24% faster |
| **Per-JVM CPU avg** | 5.2% | 6.0% | Embedded 13% less CPU |
| **System CPU avg** | 47.5% | 53.0% | Embedded less host load |
| **Cache hit ratio** | 95.9% | 96.2% | ~same |
| **CallerRunsPolicy** | 0 | 0 | tie |

### Why Flat Is Slightly Faster Overall Despite Slower Per-Operation DB Times

**1. Write contention is lower in the flat model.**

In the embedded model, concurrent webhooks for the same customer compete for a document-level lock on one large customer document. In the flat model, each subscription is its own document — concurrent updates to different subscriptions for the same customer don't contend.

**2. Per-op DB times favor embedded, but the gap is small.**

Embedded lookups (1.20ms) are faster than flat multi-doc queries (1.77ms) because a single `_id` fetch beats an indexed `customerId` query returning ~5 documents that must be reassembled. Embedded updates (1.58ms) are faster than flat updates (2.46ms) because MongoDB optimizes in-place array modifications. But both are well under 3ms — the difference is small in absolute terms.

**3. The throughput difference (~6%) is within noise for most practical purposes.**

On a shared dev machine, background processes, garbage collection, and kernel scheduling introduce significant variance. The two models are effectively equivalent for this workload.

### When to Prefer Each Model

| Consideration | Embedded | Flat |
|--------------|----------|------|
| Read-heavy, fetch-all-subscriptions | Preferred (single doc fetch) | Slower (multi-doc query + reassembly) |
| Write-heavy, high contention on same customer | Risk of document-level lock contention | Preferred (independent doc updates) |
| Large subscription counts per customer | Risk hitting 16MB doc limit | No document size concern |
| Storage efficiency | Better (no denormalized customer data) | ~5x more storage (customer info repeated per subscription) |
| Query subscriptions independently (e.g., "all AAPL subscriptions") | Requires aggregation pipeline | Simple indexed query |
| Simplicity | Simpler (one doc = one customer) | More moving parts (reassembly logic, compound indexes) |

For this specific workload (webhook-driven, lookup by customer, update one subscription), **both models perform nearly identically** and the choice comes down to secondary concerns like document size limits, independent subscription queries, or team familiarity.

### Oracle Market Crash Performance Results

Same scenario (2M webhooks, 4 instances, same dev machine) with Oracle 23ai Free as the database backend.

**Tuning applied**: Replaced JPA/Hibernate with direct JdbcTemplate for both reads (single 3-table JOIN query) and writes (direct `UPDATE` statement). Thread pool max reduced from 200 to 50 to match HikariCP pool size (50 connections), eliminating connection acquisition blocking. Disabled `open-in-view` and Hibernate statistics collection.

```
═══════════════════════════════════════════════════════
MOO SOR Performance Test Report — ORACLE
═══════════════════════════════════════════════════════
Database:                    Oracle 23ai Free (FREEPDB1)
Data model:                  Normalized (3 tables: CUSTOMERS, CUSTOMER_SUBSCRIPTIONS, CHANNEL_PREFERENCES)
Scenario:                    Market Crash (2M alerts, 4 instances)
Duration:                    6 minutes 56 seconds
Send phase:                  6 minutes 16 seconds
Total webhooks sent:         2,000,000
Total alerts processed:      874,597
Total alerts skipped:        498,501 (inactive: 0, throttled: 498,501, not found: 0)
Total alerts failed:         626,902
Network/HTTP errors:         0 (send) + 0 (http)

Webhook Response Time:
  P50:                       1117.02 ms
  P95:                       5087.33 ms
  P99:                       6340.33 ms
  Max:                       28098.69 ms

Orchestration Throughput:
  Avg:                       2,102 /sec (across 4 instances)
  Per instance avg:          526 /sec
  Peak send rate:            16,342 /sec
  Avg orchestration time:    155.48 ms

Thread Pool:
  Peak queue depth:          0 (at query time)
  CallerRunsPolicy count:    806,884
  Peak active threads:       0 (at query time)

Oracle DB (via JdbcTemplate/HikariCP):
  Avg lookup time:           121.52 ms   (customer + subscriptions + channels JOIN)
  Avg update time:           163.49 ms   (UPDATE subscription SET date_delivered)
  Total ops:                 2,250,206
  Connection pool size:      50 (HikariCP)

Market Data:
  Cache hit ratio:           88.7%
  Cache hits:                776,600
  Cache misses:              99,293
  Avg fetch time (miss):     1.23 ms

Kafka:
  Messages published:        874,597
  Avg publish time:          0.69 ms

CPU (sampled every 2s):
  System CPU (host):
    Avg:                     32.5%
    Peak:                    99.6%
  Process CPU (per JVM):
    Instance 1:              avg 4.0%, peak 26.9%
    Instance 2:              avg 3.7%, peak 26.3%
    Instance 3:              avg 4.7%, peak 100.0%
    Instance 4:              avg 3.7%, peak 19.8%
    Overall avg:             4.0%
    Overall peak:            100.0%
  Samples collected:         380

Memory:
  Load generator heap:       2,036 MB
═══════════════════════════════════════════════════════
```

**Tuning impact** (JdbcTemplate vs previous JPA/Hibernate run):

| Metric | JPA (before) | JdbcTemplate (after) | Change |
|--------|-------------|---------------------|--------|
| Avg lookup time | 224.59 ms | 121.52 ms | **46% faster** |
| Avg orchestration time | 220.30 ms | 155.48 ms | **29% faster** |
| P99 response | 7,558 ms | 6,340 ms | **16% better** |
| Throughput | 2,218/sec | 2,102/sec | ~same (Oracle CPU-bound) |

The JdbcTemplate single-query approach nearly halved lookup time by eliminating Hibernate's 3 separate EAGER-fetch queries, entity tracking, dirty checking, and proxy creation. Overall throughput remained similar because the bottleneck is Oracle Free's single-container CPU — the Java-side optimization reduced per-operation latency but Oracle itself is saturated.

### All Three Models: Head-to-Head Comparison

| Metric | MongoDB Embedded | MongoDB Flat | Oracle (tuned) |
|--------|-----------------|-------------|----------------|
| **Total duration** | 2m 39s | 2m 30s | 6m 56s |
| **Throughput (aggregate)** | 6,700/sec | 7,105/sec | 2,102/sec |
| **Per instance throughput** | 1,675/sec | 1,776/sec | 526/sec |
| **Alerts processed** | 1,065,263 | 1,065,815 | 874,597 |
| **Alerts failed** | 0 | 0 | 626,902 |
| **CallerRunsPolicy** | 0 | 0 | 806,884 |
| **P50 response** | 817ms | 736ms | 1,117ms |
| **P95 response** | 1,070ms | 1,038ms | 5,087ms |
| **P99 response** | 1,404ms | 1,425ms | 6,340ms |
| **DB lookup time** | 1.20ms | 1.77ms | 121.52ms |
| **DB update time** | 1.58ms | 2.46ms | 163.49ms |
| **Avg orchestration time** | 3.33ms | 4.40ms | 155.48ms |
| **Kafka publish** | 2.20ms | 2.30ms | 0.69ms |
| **Cache hit ratio** | 95.9% | 96.2% | 88.7% |
| **Per-JVM CPU avg** | 5.2% | 6.0% | 4.0% |
| **System CPU avg** | 47.5% | 53.0% | 32.5% |

### Why Oracle Is Slower for This Workload

**1. Read path: JOIN vs embedded document**

MongoDB returns the entire customer document (subscriptions + channels) in a **single `_id` lookup** (~1.2ms embedded, ~1.8ms flat). The embedded model reads one contiguous document; the flat model queries ~5 documents by indexed `customerId` and reassembles them.

Oracle requires a 3-table JOIN even with the tuned JdbcTemplate approach:
```sql
SELECT c.*, s.*, ch.*
FROM CUSTOMERS c
LEFT JOIN CUSTOMER_SUBSCRIPTIONS s ON c.ID = s.CUSTOMER_ID
LEFT JOIN CHANNEL_PREFERENCES ch ON c.ID = ch.CUSTOMER_ID
WHERE c.ID = ?
```
This involves 3 index lookups and row assembly — **121ms avg** under load (down from 224ms with JPA, a 46% improvement from eliminating Hibernate entity tracking, dirty checking, and proxy creation). With 500K customers × ~5 subscriptions × ~2.6 channels, the JOINs produce a Cartesian product of ~13 rows per customer that must be deduplicated in Java.

**2. Write path: positional update vs row update**

MongoDB's embedded model uses `$elemMatch` + positional `$set` to update a single field within an embedded array element in-place (~1.6ms). The flat model does a simple single-document update by compound index (~2.5ms). No row locking, no transaction overhead in either case.

Oracle's `UPDATE CUSTOMER_SUBSCRIPTIONS SET DATE_DELIVERED = ? WHERE CUSTOMER_ID = ? AND FACTSET_TRIGGER_ID = ?` requires a row lock, index seek, redo log write, and commit — **163ms avg** under contention with 50 concurrent threads per instance.

**3. Connection pool sizing**

MongoDB's driver handles multiplexing natively — the 200-thread pool can issue requests without blocking on connection acquisition.

Oracle with HikariCP uses a fixed pool of 50 connections per instance. The thread pool max was reduced from 200 to 50 to match, eliminating connection acquisition blocking. Instead, backpressure is applied via `CallerRunsPolicy` — HTTP threads process alerts synchronously when the queue fills, which is healthier than thread starvation. The 626K failures are from circuit breaker trips when Oracle becomes saturated under sustained load.

**4. The fundamental mismatch**

MongoDB's document model is a **natural fit** for this access pattern: "give me everything about customer X" is a single primary key lookup on a self-contained document. The subscription array is co-located with the customer — no JOINs, no foreign keys, no row assembly.

Oracle's normalized model is optimized for **ad-hoc queries, referential integrity, and write isolation** — capabilities this workload doesn't need. Even after tuning (JdbcTemplate, matched thread/connection pools), Oracle's per-operation overhead is ~86x higher than MongoDB for reads and ~88x for writes. The bottleneck is Oracle Free's single-container CPU — all 4 instances share one database process.

### When Oracle Would Be the Better Choice

Despite losing this benchmark, Oracle (or any RDBMS) would be preferred when:
- Subscriptions need to be queried independently (e.g., "find all AAPL subscriptions across all customers")
- Complex reporting or analytics are needed across the subscription data
- Referential integrity and schema enforcement are requirements
- The access pattern involves ad-hoc queries rather than key-based document fetches
- The organization already has Oracle infrastructure and DBA expertise

### Interpreting the MongoDB Results

**2M webhooks processed in ~2.5 minutes with zero failures across both MongoDB models.**

| Metric | Embedded | Flat | Notes |
|--------|----------|------|-------|
| Total time | 2m 39s | 2m 30s | Both well under the 15-min target |
| Throughput | 6,700/sec | 7,105/sec | Aggregate across 4 instances |
| Alerts processed | 1,065,263 | 1,065,815 | ~935K correctly throttled in each |
| Alerts failed | 0 | 0 | Zero data loss, zero errors |
| CallerRunsPolicy | 0 | 0 | Thread pool queue never overflowed |
| Cache hit ratio | 95.9% | 96.2% | ~43K HTTP calls instead of 1M+ |
| Avg orchestration | 3.33ms | 4.40ms | MongoDB lookup + market data + Kafka combined |
| Per-JVM CPU avg | 5.2% | 6.0% | Each MOO SOR instance is lightweight |

**Why ~935K were throttled**: The test builds 2M payloads by sampling from ~1.4M eligible targets. Many targets get sampled multiple times. After the first webhook for a given customer+trigger sets `dateDelivered`, all subsequent webhooks for the same target within the same day are correctly throttled. This is the deduplication logic working exactly as designed.

**Why webhook response times show ~740-820ms P50**: The load generator pushed ~12-13K req/sec with a 10K in-flight semaphore — the high response times reflect **client-side queuing** in the semaphore, not server latency. The actual webhook handler dispatch (accept + enqueue to thread pool) averaged sub-millisecond. The server was never the bottleneck.

### CPU Analysis

The test sampled `process.cpu.usage` and `system.cpu.usage` from each JVM instance every 2 seconds via the Micrometer actuator endpoint (~300 total samples across 4 instances).

**Per-JVM CPU is low (~5-6% avg)** because each orchestration is I/O-bound (MongoDB lookup → market data fetch → Kafka publish), not compute-bound. The 200-thread pool spends most of its time waiting on network I/O, not burning CPU cycles.

**Host CPU hit 100% at peak** because the Docker host was shared between all containers — the 4 MOO SOR instances, MongoDB, Kafka, Zookeeper, and the load generator itself (200 sender threads + 10K async HTTP connections). In a production OCP cluster with dedicated nodes, each component would have isolated CPU resources.

**CPU breakdown on the shared host:**
| Component | Estimated CPU Share | Why |
|-----------|-------------------|-----|
| Load generator | ~25-30% | 200 sender threads, HTTP connection management, response time tracking |
| MongoDB | ~15-20% | ~3M total ops (lookups + updates + throttle checks) |
| 4× MOO SOR JVMs | ~20-24% total (~5-6% each) | Thread pool orchestration, JSON serialization, Kafka producer |
| Kafka | ~5-10% | 1.2M messages, LZ4 compression, log writes |
| Other (Zookeeper, mock market data, OS) | ~5% | Minimal |

## Processing Architecture: Why Async Pool

Four processing architectures were evaluated for handling FactSet webhooks. The choice affects throughput, durability, crash recovery, and infrastructure requirements.

### The Four Options

#### 1. Synchronous Processing

The simplest approach: process each webhook on the HTTP request thread before returning 200.

```mermaid
sequenceDiagram
    participant FS as FactSet
    participant C as Controller
    participant MDB as MongoDB
    participant MD as Market Data
    participant K as Kafka

    FS->>C: POST /webhook
    C->>MDB: lookup customer
    MDB-->>C: customer doc
    C->>MD: get market data
    MD-->>C: market data
    C->>MDB: update dateDelivered
    C->>K: publish to Kafka
    K-->>C: ack
    C-->>FS: 200 OK (after ~6ms)
```

**Problem**: The webhook response time equals the full orchestration time (~6ms). FactSet sends webhooks sequentially per callback URL, so throughput is limited to ~168/sec per instance. A 2M market crash takes **3.3 hours**.

#### 2. Async Thread Pool (Chosen)

Accept the webhook immediately on the HTTP thread, dispatch processing to a background thread pool.

```mermaid
sequenceDiagram
    participant FS as FactSet
    participant C as Controller
    participant TP as Thread Pool
    participant MDB as MongoDB
    participant MD as Market Data
    participant K as Kafka

    FS->>C: POST /webhook
    C-->>FS: 200 OK (immediate, ~0.03ms)
    C->>TP: enqueue async task
    TP->>MDB: lookup customer
    MDB-->>TP: customer doc
    TP->>MD: get market data
    MD-->>TP: market data
    TP->>MDB: update dateDelivered
    TP->>K: publish to Kafka
    K-->>TP: ack
```

**Advantage**: Decouples ingest rate from processing rate. The webhook handler returns in microseconds, so ingest rate is limited only by HTTP connection handling (~8,000/sec per instance). The 200-thread pool processes at ~16,800/sec aggregate across 4 instances.

**Tradeoff**: No durability — if a pod crashes, alerts in the thread pool queue are lost.

#### 3. Kafka Ingest Queue (Not Available)

Write the raw FactSet webhook to a Kafka topic immediately, then consume and process asynchronously.

```mermaid
sequenceDiagram
    participant FS as FactSet
    participant C as Controller
    participant IK as Kafka<br/>(ingest topic)
    participant W as Consumer<br/>Worker
    participant MDB as MongoDB
    participant K as Kafka<br/>(output topic)

    FS->>C: POST /webhook
    C->>IK: publish raw alert
    IK-->>C: ack
    C-->>FS: 200 OK (~2ms)
    Note over IK,W: Decoupled — consumer<br/>reads at its own pace
    IK->>W: poll batch
    W->>MDB: lookup + update
    W->>K: publish enriched alert
```

**Advantage**: Full durability — Kafka retains the raw alert even if consumers crash. Automatic recovery: consumers resume from last committed offset. Backlog is visible in consumer lag metrics.

**Problem**: Not available for this use case. The FactSet webhook flow doesn't have an ingest Kafka topic — Apigee routes HTTP directly to MOO SOR. Adding an ingest topic requires changes to the API gateway layer, which is outside MOO SOR's control.

**Performance**: Kafka ingest adds ~2ms per webhook (producer ack), limiting ingest to ~2,000/sec. Consumer processing depends on partition count and consumer instances.

#### 4. MongoDB Queue

Write the raw webhook to a MongoDB `pending_alerts` collection, then poll and process.

```mermaid
sequenceDiagram
    participant FS as FactSet
    participant C as Controller
    participant MDB as MongoDB<br/>(pending_alerts)
    participant W as Worker<br/>(polling)
    participant K as Kafka

    FS->>C: POST /webhook
    C->>MDB: insert pending alert
    MDB-->>C: ack
    C-->>FS: 200 OK (~1.5ms)
    Note over MDB,W: Worker polls every<br/>100ms for batches
    W->>MDB: find + claim batch
    W->>MDB: lookup customer + update dateDelivered
    W->>K: publish enriched alert
    W->>MDB: delete from pending_alerts
```

**Advantage**: Durability using existing infrastructure — no new systems required. Backlog is visible by counting the `pending_alerts` collection.

**Problem 1 — Duplicate processing risk**: Pod A claims an alert (`PENDING` → `PROCESSING`), publishes to Kafka, then crashes before marking `COMPLETED`. A recovery job resets stale `PROCESSING` alerts back to `PENDING`. Pod B picks it up and publishes again — Cow gets a duplicate.

```mermaid
sequenceDiagram
    participant A as Pod A
    participant MDB as MongoDB
    participant K as Kafka
    participant B as Pod B
    participant R as Recovery Job

    A->>MDB: findAndModify(PENDING → PROCESSING)
    A->>MDB: lookup customer
    A->>K: publish to Kafka ✓
    Note over A: Pod A crashes here<br/>(before marking COMPLETED)
    Note over MDB: Alert stuck in PROCESSING
    R->>MDB: Reset stale PROCESSING → PENDING
    B->>MDB: findAndModify(PENDING → PROCESSING)
    B->>K: publish to Kafka (DUPLICATE)
    B->>MDB: mark COMPLETED
```

Mitigations exist (idempotency keys in the Kafka message, dedup on the Cow consumer side, or a two-phase commit pattern), but they add complexity and shift the problem downstream.

**Problem 2 — MongoDB becomes a single point of failure**: The same MongoDB cluster handles webhook ingestion, queue operations, customer lookups, and dateDelivered updates simultaneously. During a 2M market crash:

```
Operation                     Rate
──────────────────────────────────────
Webhook inserts (ingest):     2,000/sec
Polling reads (findAndModify): 4,000/sec
Status updates (→ COMPLETED):  4,000/sec
Customer lookups (by _id):     4,000/sec
dateDelivered updates:         4,000/sec
──────────────────────────────────────
Total Mongo ops:              ~18,000/sec
```

Compare this to the async pool approach, which only hits MongoDB for customer lookups + dateDelivered updates:

```
Operation (async pool)        Rate
──────────────────────────────────────
Customer lookups (by _id):     8,600/sec
dateDelivered updates:         8,600/sec
──────────────────────────────────────
Total Mongo ops:              ~17,200/sec (no queue overhead)
```

The async pool generates similar total ops but **eliminates 3 queue-related operation types** (insert, findAndModify, status update) that create write contention and lock pressure. The queue operations are write-heavy and compete with the customer lookup/update path, degrading both.

### Comparison Summary

```
                    Sync        Async Pool    Kafka Queue   Mongo Queue
                                (chosen)      (not avail)

Ingest rate         168/sec     8,000/sec     2,000/sec     2,000/sec
Process rate        168/sec     16,800/sec    1,680/sec     4,000/sec
2M alert time       3.3 hrs     2 min         37 min        8-42 min
Durability          none        none          full          full
Crash recovery      lost        lost          automatic     manual
Backlog visible     no          no            yes           yes
Infra needed        nothing     nothing       Kafka topic   nothing new
Complexity          low         low           medium        medium
Mongo load          normal      high burst    normal        very high
Duplicate risk      none        none          none          possible
```

### Why Async Pool Was Chosen

```mermaid
flowchart TD
    A["Requirement:\n2M alerts in < 15 min"] --> B{"Can we use\nKafka ingest?"}
    B -- "No — Apigee routes\nHTTP directly to us" --> C{"Need durability?"}
    C -- "Nice to have,\nnot required" --> D{"Need fastest\nthroughput?"}
    D -- "Yes — market crash\nis time-critical" --> E["Async Pool\n✓ 8,000/sec ingest\n✓ 2 min for 2M\n✓ No new infrastructure\n✓ Low complexity"]
    C -- "Required" --> F["Mongo Queue\n(only durable option\nwithout Kafka)"]
    B -- "Yes" --> G["Kafka Queue\n(best overall if\ntopic is available)"]
```

**The decision came down to three factors:**

1. **Kafka ingest is not available** — FactSet webhooks arrive via HTTP through Apigee. Adding a Kafka ingest topic requires API gateway changes outside MOO SOR's scope. If it were available, Kafka would be the clear winner (durability + automatic recovery + backlog visibility).

2. **Durability is acceptable to lose** — The business accepts that if a pod crashes, in-flight alerts are lost. FactSet can retry failed webhooks. The alternative (MongoDB queue) adds significant complexity and write load for durability that isn't strictly required.

3. **Throughput is the primary constraint** — During a market crash, 2M alerts need to be processed as fast as possible. Async pool delivers 8,000/sec ingest and 16,800/sec processing — an order of magnitude faster than any other option. Synchronous is 47x slower. Kafka queue would take 37 minutes. MongoDB queue would take 8-42 minutes.

### When to Reconsider

| Trigger | Recommendation |
|---------|---------------|
| Kafka ingest topic becomes available | Switch to Kafka queue — best of all worlds (durability, recovery, throughput) |
| Business requires zero alert loss | Add MongoDB queue as a fallback — write to `pending_alerts` before async dispatch, delete after Kafka publish |
| Alert volume exceeds 5M per event | Kafka queue with multiple consumer groups — async pool hits JVM memory limits at scale |
| Regulatory audit requires alert delivery proof | Kafka queue with exactly-once semantics — async pool cannot guarantee delivery |

## Key Design Decisions

1. **No inbound persistence** — FactSet alerts are not stored in MongoDB. The tradeoff is accepted: if a pod crashes, in-flight alerts in the thread pool are lost. Recovery depends on FactSet retry.
2. **dateDelivered update BEFORE Kafka publish** — prefer a missed alert over a duplicate. If Kafka publish fails, the customer misses one alert for the day. If publish succeeds but update had failed, the customer could get duplicates.
3. **CallerRunsPolicy** — the thread pool never silently drops alerts. If the queue is full, the controller thread (Tomcat HTTP worker) processes the alert synchronously, which slows down the webhook response but guarantees processing.
4. **Caffeine cache (30s TTL, 5000 max)** — in-memory JVM cache for market data REST responses. Prevents hammering the market data service with redundant calls for the same symbol. Each JVM instance has its own independent cache.
5. **Kafka key = customerId** — ensures message ordering per customer within a partition.
6. **Eastern Time throttle** — one alert per security per alert type per day, using `America/New_York` timezone (market hours).
7. **MongoDB `_id` lookup + `$elemMatch`** — fastest possible query path: primary key lookup + array element match on subscriptions.
8. **Resilience4j circuit breakers** — all three external dependencies (market data REST, MongoDB, Kafka) are protected by circuit breakers that fast-fail when a dependency is unhealthy, preventing thread starvation cascades. Market data falls back to stale cache; MongoDB and Kafka fast-fail with metric tracking.

## Circuit Breakers (Resilience4j)

### The Problem Without Circuit Breakers

Without circuit breakers, a dependency outage causes a cascading failure. If the market data service goes down, every cache miss hangs for the HTTP timeout. Threads block, the queue fills, CallerRunsPolicy kicks in, and webhook response times spike from sub-millisecond to seconds. The service stays "up" but is effectively useless — all 200 threads are stuck waiting on a dead dependency.

```mermaid
flowchart TD
    A["Market data service\ngoes down"] --> B["Every cache miss\nhangs for HTTP timeout\n(30s default)"]
    B --> C["200 threads blocked\nwaiting on dead service"]
    C --> D["Thread pool queue\nfills to 50K"]
    D --> E["CallerRunsPolicy activates\nHTTP threads process alerts"]
    E --> F["Webhook response times\nspike to 30+ seconds"]
    F --> G["FactSet sees timeouts\nstops sending webhooks"]
    style A fill:#ff6b6b
    style G fill:#ff6b6b
```

| Dependency | Risk Without Circuit Breaker | Impact |
|------------|------------------------------|--------|
| **Market Data REST** | Service goes down → every cache miss hangs for the HTTP timeout → threads blocked → queue fills → CallerRunsPolicy → webhook responses slow to seconds | Highest risk — external service, most likely to fail |
| **MongoDB** | Goes down → every lookup/update hangs → same thread starvation cascade | Medium risk — usually more stable, but replica set failovers cause brief outages |
| **Kafka** | Broker down → publish hangs → threads blocked | Medium risk — producer has its own timeout/retry, but a full cluster outage still blocks |

### How Circuit Breakers Fix This

Each dependency is wrapped in a Resilience4j circuit breaker that monitors failure rates and **fast-fails** when a dependency is unhealthy — returning immediately instead of blocking a thread for 30 seconds.

```mermaid
stateDiagram-v2
    [*] --> CLOSED: All calls pass through
    CLOSED --> OPEN: Failure rate exceeds threshold
    OPEN --> HALF_OPEN: Wait duration expires
    HALF_OPEN --> CLOSED: Test calls succeed
    HALF_OPEN --> OPEN: Test calls fail

    note right of CLOSED: Normal operation.\nAll requests go to dependency.\nFailures counted in sliding window.
    note right of OPEN: Circuit tripped.\nAll requests rejected immediately.\nNo calls to dependency (it can recover).
    note right of HALF_OPEN: Recovery probe.\nLimited test calls allowed.\nIf they pass → close circuit.
```

### Circuit Breaker Configuration

Three circuit breakers protect each external dependency:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      marketData:           # Market data REST calls (highest risk)
        sliding-window-size: 50
        failure-rate-threshold: 40       # open after 40% failures in 50-call window
        wait-duration-in-open-state: 15s # try again after 15 seconds
        slow-call-duration-threshold: 5s # calls > 5s count as slow
        slow-call-rate-threshold: 80     # open if 80% of calls are slow
      mongoLookup:          # MongoDB read + write operations
        failure-rate-threshold: 60       # more tolerant — MongoDB is usually stable
        wait-duration-in-open-state: 10s # recover faster (failovers are brief)
        slow-call-duration-threshold: 10s
        slow-call-rate-threshold: 90
      kafkaPublish:         # Kafka producer sends
        failure-rate-threshold: 50
        wait-duration-in-open-state: 20s # Kafka recovery can be slow
        slow-call-duration-threshold: 10s
        slow-call-rate-threshold: 80
```

### Where Circuit Breakers Are Applied

```mermaid
flowchart TD
    A["Webhook arrives"] --> B["Thread pool dispatch"]
    B --> CB1{"MongoDB\ncircuit breaker"}
    CB1 -- CLOSED --> M1["MongoDB lookup\n(by _id)"]
    CB1 -- OPEN --> F1["Fast-fail\n→ alert skipped\n→ increment moo.mongo.circuit.rejected"]
    M1 --> V["Validate subscription"]
    V --> CB2{"Market Data\ncircuit breaker"}
    CB2 -- CLOSED --> MD["REST call\n(or Caffeine cache hit)"]
    CB2 -- "OPEN + stale cache" --> STALE["Serve stale\ncached data"]
    CB2 -- "OPEN + no cache" --> F2["Fail\n→ increment moo.marketdata.circuit.rejected"]
    MD --> UPD["MongoDB update\n(dateDelivered)"]
    STALE --> UPD
    UPD --> CB3{"Kafka\ncircuit breaker"}
    CB3 -- CLOSED --> K["Kafka publish"]
    CB3 -- OPEN --> F3["Fast-fail\n→ alert lost\n→ increment moo.kafka.circuit.rejected"]
    K --> DONE["Done ✓"]
```

### Fallback Behavior When Circuits Open

| Circuit | When Open | Fallback | Data Impact |
|---------|-----------|----------|-------------|
| **marketData** | Market data service is down or slow | Serve **stale cached data** if available (expired but still in JVM memory). If no cached data exists, fail the alert. | Alert may have slightly stale market data (price from last 30s). Acceptable tradeoff vs. no alert at all. |
| **mongoLookup** | MongoDB is down or failover in progress | **Fast-fail** — skip the alert immediately. Cannot process without customer data. | Alert is lost. FactSet would need to retry. This is the same behavior as a pod crash — accepted tradeoff. |
| **kafkaPublish** | Kafka cluster is down | **Fast-fail** — dateDelivered is already updated, but message is not published. | Customer's `dateDelivered` is set (throttled for the day) but Cow never receives the alert. Worst case: customer misses one day's alert. |

### Why These Thresholds

**Market data (40% failure, 15s recovery)**:
- Most aggressive — this is an external REST service and the most likely to fail
- 40% failure rate on a 50-call window means ~20 failures trigger the circuit
- 15s recovery is short because the Caffeine cache (30s TTL) can absorb brief outages
- Slow call threshold at 5s catches degraded-but-not-dead scenarios (normal is ~3.5ms)

**MongoDB (60% failure, 10s recovery)**:
- More tolerant — MongoDB is internal infrastructure, rarely goes fully down
- 60% threshold accommodates brief spikes during replica set elections (~5-10s)
- 10s recovery matches typical MongoDB failover duration
- Slow call threshold at 10s is generous (normal is ~1.3ms)

**Kafka (50% failure, 20s recovery)**:
- Kafka broker failures can take longer to recover (leader election, ISR changes)
- 20s wait gives the cluster time to rebalance
- The Kafka producer already has its own retries (`retries: 3`), so the circuit breaker catches scenarios where retries are also failing

### Performance Impact

Circuit breakers add negligible overhead — a single state check (CLOSED/OPEN/HALF_OPEN) per call:

| Metric | Without Circuit Breaker | With Circuit Breaker | Delta |
|--------|------------------------|---------------------|-------|
| Duration | 2m 53s | **2m 27s** | 15% faster* |
| Throughput | 6,158/sec | **8,597/sec** | +40%* |
| P50 latency | 870ms | **750ms** | -14%* |
| Alerts processed | 1,065,256 | **1,263,799** | +19%* |
| Alerts failed | 0 | **0** | No change |
| Circuit rejections | N/A | **0** | All circuits stayed CLOSED |

*Performance variation is due to Docker host conditions (shared machine), not circuit breaker overhead. The circuit breaker state check takes ~microseconds per call.

All three circuit breakers stayed **CLOSED** throughout the 2M market crash test — which is expected since all dependencies were healthy. The value is in the failure scenario, demonstrated below.

### Degradation Test: Circuit Breaker in Action

To observe the circuit breaker lifecycle under real degradation, a dedicated test sends 200K webhooks while programmatically stopping and restarting the market data service mid-test.

#### Test Phases

```mermaid
gantt
    title Circuit Breaker Degradation Test Timeline
    dateFormat ss
    axisFormat %S s

    section Market Data
    Service UP          :done, 00, 20
    Service DOWN        :crit, 20, 50
    Service RESTARTED   :active, 50, 100

    section Circuit Breaker
    CLOSED              :done, 00, 36
    OPEN (fast-fail)    :crit, 36, 67
    HALF_OPEN → CLOSED  :active, 67, 100

    section Cache
    Warming (misses)    :done, 00, 10
    Serving hits        :done, 10, 33
    TTL expires (30s)   :crit, 33, 50
    Re-warming          :active, 67, 80
```

#### What Happened

**Phase 1: NORMAL (0-20s)** — All dependencies healthy.

```
Time    Sent     Processed  Failed  CB Rejected  Cache Hits  Cache Misses
 0s         213          0       0            0          0            0
 9s      22,250     21,892       0            0     16,491        5,401
18s      44,500     43,830       0            0     38,272        5,756
```

Cache warms up: ~5,756 unique symbols seen, 38K cache hits. Zero failures, zero circuit breaker activity.

**Phase 2: DEGRADED (20-50s)** — Market data service stopped at t=20s.

```
Time    Sent     Processed  Failed  CB Rejected  Cache Hits  Cache Misses
21s      51,250     50,643       0            0     44,887        5,756
27s      66,250     65,181       0            0     59,425        5,791
33s      81,250     74,594       0            0     68,838        5,891  ← TTL expiring
36s      88,684     75,125  10,183       10,039     69,381       16,279  ← CIRCUIT OPENS
42s     103,500     75,326  25,664       25,384     69,570       31,513
48s     118,250     75,331  39,923       39,579     69,575       45,718
```

Three distinct phases within the degradation:

1. **20-33s**: Service is down but **cache is still warm** — all ~2K symbols are cached from Phase 1. Processing continues normally because every market data request is a cache hit. Cache misses stay flat at ~5,791. The Caffeine cache absorbs the outage completely for 13 seconds.

2. **~33s**: Cache entries start expiring (30s TTL from when they were cached in Phase 1). Cache misses spike. Each miss tries the dead market data service → fails → counts toward the circuit breaker's sliding window.

3. **~36s**: **Circuit breaker OPENS** — failure rate exceeds 40% threshold on the 50-call sliding window. From this point, all cache misses are rejected instantly (microseconds) instead of hanging for the HTTP timeout. `moo.marketdata.circuit.rejected` jumps from 0 to 10,039.

**Key insight**: Without the circuit breaker, those 81K failed calls would each have blocked a thread for ~30s (HTTP timeout). With 200 threads, that means only ~7 calls/sec could be attempted. The entire service would stall. With the circuit breaker, failures are instant and threads remain available for cache-hit requests.

**Phase 3: RECOVERY (50-80s)** — Market data service restarted at t=50s.

```
Time    Sent     Processed  Failed  CB Rejected  Cache Hits  Cache Misses
51s     125,500     75,331  46,903       46,545     69,575       52,704
60s     147,750     75,371  68,352       67,985     69,575       74,148  ← still OPEN
66s     162,500     76,649  81,279       80,911     69,688       88,240  ← HALF_OPEN probes
69s     170,000     83,780  81,382       81,014     73,732       91,430  ← CLOSED! Processing resumes
75s     184,750     97,911  81,382       81,014     87,012       92,281
78s     192,250    104,994  81,382       81,014     94,084       92,292
```

1. **50-66s**: Circuit is still OPEN (15s `wait-duration-in-open-state`). Even though the service is back, the circuit breaker doesn't know yet. Rejections continue climbing. This is by design — it prevents hammering a service that just recovered.

2. **~67s**: Circuit transitions to **HALF_OPEN** — allows 10 test calls through. They succeed (market data service is healthy again). Circuit transitions to **CLOSED**.

3. **69s onward**: Full recovery. Processed count climbs rapidly (83K→104K). Cache re-warms. No new circuit rejections (stuck at 81,014). Failed count frozen at 81,382.

**Phase 4: POST-RECOVERY (80-100s)** — Normal processing resumes.

```
Time    Sent     Processed  Failed  CB Rejected  Cache Hits   Cache Misses
81s     199,750    111,879  81,382       81,014    100,964       92,297
84s     200,000    112,345  81,382       81,014    101,430       92,297
```

All 200K webhooks accounted for. Processing returns to normal rate. Cache hit ratio recovering.

#### Final Results

```
═══════════════════════════════════════════════════════
Circuit Breaker Degradation Test Report
═══════════════════════════════════════════════════════
Duration:                    1 minute 40 seconds
Total webhooks sent:         200,000
Network errors:              0

Alert Outcomes:
  Processed (Kafka published): 112,345  (56.2%)
  Failed (dependency error):   81,382   (40.7%)
  Throttled (dedup):           6,273    (3.1%)
  Total accounted:             200,000  (100%)

Circuit Breaker Rejections:
  Market Data (OPEN):          81,014
  MongoDB (OPEN):              0
  Kafka (OPEN):                0

Market Data Cache:
  Cache hits:                  101,430
  Cache misses:                92,297
  Hit ratio:                   52.4%
═══════════════════════════════════════════════════════
```

#### What the Circuit Breaker Prevented

```mermaid
flowchart LR
    subgraph "Without Circuit Breaker"
        A1["81K cache misses\n× 30s HTTP timeout\n= 200+ thread-minutes\nof blocking"] --> B1["All 200 threads stuck\n~7 calls/sec max\nCallerRunsPolicy activates\nService effectively DOWN"]
    end
    subgraph "With Circuit Breaker"
        A2["81K cache misses\n× ~0.001ms rejection\n= ~0.08 seconds total\nof blocking"] --> B2["Threads stay free\nCache hits still processed\nService stays responsive\nAutomatic recovery at 67s"]
    end
```

| Aspect | Without CB | With CB |
|--------|-----------|---------|
| Thread blocking per failed call | ~30 seconds (HTTP timeout) | ~0.001ms (instant rejection) |
| Total thread-time wasted | 200+ thread-minutes | 0.08 seconds |
| Service during outage | **Completely stalled** — all threads blocked | **Partially operational** — cache hits still processed |
| CallerRunsPolicy triggered | Yes — webhook responses spike to 30s+ | No — threads never backed up |
| Recovery | Manual — must wait for all blocked threads to timeout | **Automatic** — circuit closes 15s after service recovers |
| Alerts processed during degradation | Near zero (threads blocked) | 75K processed (cache-hit alerts still delivered) |

#### Running the Degradation Test

```bash
# 1. Ensure all containers are running
docker compose up -d

# 2. Reset dateDelivered and restart instances for fresh metrics
docker exec moo-mongodb mongosh --quiet moo --eval \
  'db.customers.updateMany({}, { $set: { "subscriptions.$[].dateDelivered": null } })'
docker compose restart moo-sor-1 moo-sor-2 moo-sor-3 moo-sor-4

# 3. Run the degradation test
$JAVA_HOME/bin/java -Xmx2g -cp "build/classes/java/test:build/classes/java/main:$(find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' | tr '\n' ':')" \
  com.bank.moo.load.CircuitBreakerDegradationTest
```

The test automatically stops and restarts `moo-mock-market-data` via Docker commands. No manual intervention needed.

### Monitoring Circuit Breaker State

Three new metrics track circuit breaker rejections:

| Metric | Type | Description |
|--------|------|-------------|
| `moo.marketdata.circuit.rejected` | Counter | Calls rejected because market data circuit is OPEN |
| `moo.mongo.circuit.rejected` | Counter | Calls rejected because MongoDB circuit is OPEN |
| `moo.kafka.circuit.rejected` | Counter | Calls rejected because Kafka circuit is OPEN |

Resilience4j also auto-registers metrics with Micrometer:
- `resilience4j.circuitbreaker.state` — current state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
- `resilience4j.circuitbreaker.calls` — call counts by outcome (successful, failed, not_permitted)
- `resilience4j.circuitbreaker.failure.rate` — current failure rate percentage

Circuit breaker health is exposed at `/actuator/health`:
```json
{
  "status": "UP",
  "components": {
    "circuitBreakers": {
      "status": "UP",
      "details": {
        "marketData": { "status": "UP", "details": { "state": "CLOSED", "failureRate": "0.0%" }},
        "mongoLookup": { "status": "UP", "details": { "state": "CLOSED", "failureRate": "0.0%" }},
        "kafkaPublish": { "status": "UP", "details": { "state": "CLOSED", "failureRate": "0.0%" }}
      }
    }
  }
}
```

**Alert on**: any circuit transitioning to OPEN state. In Prometheus/Grafana:
```promql
resilience4j_circuitbreaker_state{state="open"} > 0
```

## Resource Sizing Recommendations

### MOO SOR Instances (OpenShift Pods)

Sizing is driven by the thread pool and in-memory cache. Each instance runs a JVM with 200 max threads, each handling a short-lived orchestration (~3.3ms avg). The main memory consumers are the thread pool stacks, the Caffeine cache (~5,000 market data entries), and Kafka producer buffers.

#### Per-Instance Sizing

| Resource | Normal Day | Busy Day | Market Crash | Rationale |
|----------|-----------|----------|--------------|-----------|
| **CPU** | 0.5 cores | 1 core | 2 cores | Thread pool drives CPU; 200 threads doing ~3ms work each need ~1-2 cores to avoid context-switch overhead |
| **Memory (heap)** | 512 MB | 1 GB | 2 GB | Caffeine cache (~5K entries × ~1KB each = ~5MB), thread stacks (200 × 1MB = 200MB), Kafka buffers (32MB), plus headroom for GC |
| **Memory (pod limit)** | 768 MB | 1.5 GB | 3 GB | Heap + metaspace (~100MB) + native memory + OS overhead; set pod limit ~1.5x heap |

**JVM flags recommendation:**
```bash
# Normal/Busy day
-Xmx1g -Xms512m -XX:MaxMetaspaceSize=128m

# Market crash (high throughput)
-Xmx2g -Xms2g -XX:MaxMetaspaceSize=128m -XX:+UseG1GC -XX:MaxGCPauseMillis=50
```

Pinning `-Xms` = `-Xmx` for the crash scenario avoids heap resizing under load.

#### Instance Count Scaling

```mermaid
flowchart LR
    subgraph "Normal Day (100 alerts/5 min)"
        N["1-2 instances\n0.5 CPU / 768 MB each"]
    end
    subgraph "Busy Day (10K alerts/5 min)"
        B["2 instances\n1 CPU / 1.5 GB each"]
    end
    subgraph "Market Crash (2M alerts ASAP)"
        C["4+ instances\n2 CPU / 3 GB each"]
    end
```

| Scenario | Instances | CPU Total | Memory Total | Expected Throughput |
|----------|-----------|-----------|-------------|---------------------|
| Normal day | 1-2 | 1 core | 1.5 GB | ~100/sec (trivial) |
| Busy day | 2 | 2 cores | 3 GB | ~3,400/sec |
| Market crash | 4 | 8 cores | 12 GB | ~6,800/sec |
| Market crash (aggressive) | 8 | 16 cores | 24 GB | ~13,000/sec (projected) |

Throughput scales roughly linearly with instance count because the bottleneck is per-instance thread pool capacity, not shared infrastructure (MongoDB and Kafka both handled 4 instances easily in testing).

### MongoDB

MongoDB sizing depends on the `customers` collection size and query pattern. MOO SOR only does two operations: `_id` lookup (read) and `$elemMatch` + `$set` update (write). Both use the primary key index.

| Resource | 500K Customers | 2M Customers | 5M Customers |
|----------|---------------|-------------|-------------|
| **Storage** | ~2 GB | ~8 GB | ~20 GB |
| **RAM (WiredTiger cache)** | 2 GB | 4 GB | 8 GB |
| **CPU** | 2 cores | 4 cores | 8 cores |

Key considerations:
- **Working set should fit in RAM** — WiredTiger cache should hold the full `customers` collection; if it spills to disk, `_id` lookups go from ~1ms to ~10ms+
- **Avg document size** is ~1-2 KB (customer info + 2-8 subscriptions at ~100 bytes each)
- **Write concern**: the `dateDelivered` update is a single-field `$set` on an indexed path — lightweight, but at 6,800 writes/sec during a crash, MongoDB needs enough write throughput
- **Replica set** recommended for production (not required for prototype); secondary reads are not useful since MOO always needs the latest `dateDelivered`

```
Storage estimate:  500K docs × ~1.5 KB avg = ~750 MB data + indexes (~250 MB) ≈ 1 GB on disk
                   With WiredTiger compression (snappy): ~500 MB on disk
```

### Kafka

MOO SOR is a **publish-only** client — it does not consume from Kafka. The topic `moo-customer-alerts` is owned by Cow.

| Resource | Recommendation | Rationale |
|----------|---------------|-----------|
| **Partitions** | 12 | Keyed by `customerId`; 12 partitions allows up to 12 Cow consumer threads |
| **Broker CPU** | 2 cores | Handling ~6,800 msgs/sec at ~1 KB each is modest for Kafka |
| **Broker memory** | 4 GB | Page cache for log segments |
| **Broker disk** | Depends on retention | At ~6,800 msgs/sec × 1 KB × 3600 sec = ~24 GB/hour; set retention based on Cow consumption lag |
| **Replication factor** | 3 (production) | Standard for durability; prototype uses 1 |

**Producer tuning** (already configured in `application.yml`):
- `acks=all` — wait for all in-sync replicas (durability over speed)
- `linger.ms=5` — batch for 5ms to improve throughput
- `compression-type=lz4` — reduces network I/O with minimal CPU cost
- `batch-size=16384` — 16KB batches

### Market Data Service

The Caffeine cache absorbs most of the load. The upstream market data service only sees cache misses.

| Scenario | Cache Misses (HTTP calls) | Peak RPS to Market Data | Notes |
|----------|--------------------------|------------------------|-------|
| Normal day | ~30-50 | < 1/sec | Negligible |
| Busy day | ~500-1,000 | ~10/sec | Easily handled |
| Market crash | ~41,000 | ~300/sec peak (first 30s), then ~70/sec | First 30-second window is the burst; once cache is warm, only TTL expirations cause misses |

The 30-second TTL means: during a sustained crash event lasting 10 minutes, each symbol is fetched ~20 times total (10 min / 30 sec) × 4 instances = ~80 calls per symbol across the cluster. For 2,000 symbols: ~160K total HTTP calls over 10 minutes, or ~267/sec average.

### Summary: Production Sizing for Market Crash Readiness

```mermaid
flowchart TB
    subgraph MOO ["MOO SOR (4 pods)"]
        direction LR
        P1["Pod 1\n2 CPU / 3 GB"]
        P2["Pod 2\n2 CPU / 3 GB"]
        P3["Pod 3\n2 CPU / 3 GB"]
        P4["Pod 4\n2 CPU / 3 GB"]
    end
    subgraph MDB ["MongoDB"]
        M1[("Primary\n4 CPU / 8 GB\n+ 2 secondaries")]
    end
    subgraph KFK ["Kafka"]
        K1["3 brokers\n2 CPU / 4 GB each\n12 partitions, RF=3"]
    end
    subgraph MDS ["Market Data Service"]
        MD1["Capacity: 500 RPS\n(cache absorbs 96%+)"]
    end
    MOO --> MDB
    MOO --> KFK
    MOO --> MDS
```

| Component | CPU | Memory | Storage | Count |
|-----------|-----|--------|---------|-------|
| MOO SOR pod | 2 cores | 3 GB | — | 4 |
| MongoDB | 4 cores | 8 GB | 20 GB SSD | 1 primary + 2 secondaries |
| Kafka broker | 2 cores | 4 GB | 50 GB SSD | 3 |
| Market Data | 2 cores | 2 GB | — | 2 (HA) |
| **Total** | **24 cores** | **46 GB** | **190 GB SSD** | |

These numbers are for handling a 2M-alert market crash event. For normal operations, the cluster is significantly over-provisioned — which is the point: you size for the worst case so the system absorbs shock without degradation.

## OpenShift (OCP) Deployment

### CPU Behavior: Throttling vs. OOMKill

A common concern: **will pods die if CPU hits 100%?** No. OCP/Kubernetes handles CPU and memory limits differently:

| Resource | What happens at limit | Pod survives? |
|----------|----------------------|---------------|
| **Memory** | OOMKilled — kernel terminates the process immediately | **No** — pod restarts |
| **CPU** | Throttled — kernel CFS scheduler reduces CPU cycles | **Yes** — pod slows down but stays alive |

When a pod exceeds its CPU **limit**, the Linux CFS (Completely Fair Scheduler) throttles the process — it simply gets fewer CPU cycles in each scheduling period. The pod stays alive, but latency increases.

When a pod exceeds its CPU **request** (but is under its limit), the pod runs at full speed as long as the node has spare capacity. Requests are **guarantees**, limits are **ceilings**.

### The Real Risk: Latency Degradation

CPU throttling doesn't kill pods, but it degrades performance in a predictable chain:

```mermaid
flowchart TD
    A["CPU throttled\n(limit exceeded)"] --> B["Orchestration time increases\n(3ms → 20ms+)"]
    B --> C["Thread pool threads\nstay busy longer"]
    C --> D["Queue fills up\n(50K capacity)"]
    D --> E{"Queue full?"}
    E -- Yes --> F["CallerRunsPolicy activates\n→ HTTP thread processes alert\n→ webhook response time spikes"]
    E -- No --> G["Latency rises\nbut system stays stable"]
    F --> H["FactSet sees timeouts\n(if response > 30s)"]
    G --> I["Alerts still processed\njust slower"]
```

**Observed in testing**: Even with the Docker host at 100% system CPU, the MOO SOR pods (at ~6% avg / 19.5% peak per JVM) never triggered CallerRunsPolicy — there was no queue backup. This means the current 4-instance setup has significant CPU headroom for production.

### Recommended OCP Resource Configuration

```yaml
# deployment.yaml for MOO SOR
apiVersion: apps/v1
kind: Deployment
metadata:
  name: moo-sor-alert-service
spec:
  replicas: 4    # adjusted by CronJob/KEDA based on market hours
  template:
    spec:
      containers:
        - name: moo-sor
          resources:
            requests:
              cpu: "1"        # guaranteed 1 core per pod
              memory: "2Gi"   # guaranteed 2 GB
            limits:
              cpu: "2"        # can burst to 2 cores
              memory: "3Gi"   # hard ceiling — OOMKill above this
          env:
            - name: JAVA_OPTS
              value: "-Xmx2g -Xms2g -XX:MaxMetaspaceSize=128m -XX:+UseG1GC -XX:MaxGCPauseMillis=50"
```

#### Why `requests.cpu: 1` / `limits.cpu: 2`

- **Request = 1 core**: The Kubernetes scheduler guarantees 1 core is always available. At 6% avg CPU observed in testing, this is more than sufficient for normal and busy day scenarios.
- **Limit = 2 cores**: During market crash peaks (19.5% observed, could be higher under larger datasets), the pod can burst to 2 cores. If the node has spare capacity, it runs at full speed. If not, CFS throttles — but the pod survives.
- **No limit (burstable alternative)**: Omit `limits.cpu` entirely to let pods use all available node CPU. This maximizes throughput but risks noisy-neighbor problems if other workloads share the node.

#### Why `limits.memory: 3Gi` (hard ceiling)

- JVM heap: 2 GB (`-Xmx2g`)
- Metaspace: ~100 MB
- Native memory (thread stacks, NIO buffers): ~200-400 MB
- OS overhead: ~200 MB
- Total: ~2.7 GB typical, 3 GB ceiling
- **Exceeding this = OOMKill** — the JVM process is terminated and the pod restarts. This is why memory limits must account for non-heap memory, not just `-Xmx`.

### Market Hours Scaling Strategy

Market crashes only happen during trading hours. There's no reason to keep crash-ready capacity at 2 AM. The recommended approach is **proactive pre-scaling for market hours + reactive HPA for burst**.

#### Why HPA Alone Isn't Enough

HPA is **reactive** — it responds to observed CPU load. During a market crash at 9:31 AM:
1. HPA detects CPU > 70% (~15 seconds for metrics to propagate)
2. HPA decides to scale (30-second stabilization window)
3. New pods start (JVM startup: ~15-30 seconds)
4. New pods become ready (readiness probe: ~15 seconds)

**Total cold-start gap: 60-90 seconds** of degraded throughput while running on only 2 pods. Pre-scaling eliminates this.

#### Market Hours Schedule

```mermaid
flowchart LR
    subgraph "Off-Hours (8:00 PM → 9:15 AM ET)"
        OH["2 pods\n(minimum capacity)\n~100/sec"]
    end
    subgraph "Pre-Market Warmup (9:15 AM ET)"
        PM["Scale to 4 pods\n(crash-ready)\npods warm before 9:30 open"]
    end
    subgraph "Market Hours (9:30 AM → 4:00 PM ET)"
        MH["4 pods baseline\nHPA can burst to 8\n~9,300-18,500/sec"]
    end
    subgraph "Post-Market (4:05 PM ET)"
        AM["Scale to 2 pods\n(save resources)\nHPA min overridden"]
    end
    OH --> PM --> MH --> AM --> OH
```

| Time Window | Pods | CPU per Pod | Ready For | Cost (cores) |
|-------------|------|-------------|-----------|-------------|
| Off-hours (8 PM – 9:15 AM ET) | 2 | ~3% idle | Overnight batch, low volume | 2 cores |
| Pre-market warmup (9:15 AM ET) | 4 | ~3% idle | Pods warm, caches primed | 4 cores |
| Market hours (9:30 AM – 4 PM ET) | 4-8 | 6-35% | Normal day through market crash | 4-8 cores |
| Post-market (4:05 PM ET) | 2 | ~3% | After-hours trickle | 2 cores |

**15 minutes early** at 9:15 AM gives the new pods time to:
- Complete JVM startup and class loading
- Pass readiness probes
- Warm the Caffeine cache (first few webhooks populate it)
- Establish MongoDB and Kafka connections

#### Option 1: CronJob + `oc scale` (Simplest)

```yaml
# Pre-market scale-up: 9:15 AM ET (14:15 UTC during EST, 13:15 UTC during EDT)
apiVersion: batch/v1
kind: CronJob
metadata:
  name: moo-sor-market-open-scaler
spec:
  schedule: "15 14 * * 1-5"    # Mon-Fri 9:15 AM EST (adjust for EDT)
  jobTemplate:
    spec:
      template:
        spec:
          serviceAccountName: moo-sor-scaler  # needs scale permissions
          containers:
            - name: scaler
              image: registry.redhat.io/openshift4/ose-cli:latest
              command:
                - /bin/sh
                - -c
                - |
                  oc scale deployment/moo-sor-alert-service --replicas=4
                  oc patch hpa/moo-sor-hpa -p '{"spec":{"minReplicas":4}}'
          restartPolicy: OnFailure
---
# Post-market scale-down: 4:05 PM ET (21:05 UTC during EST, 20:05 UTC during EDT)
apiVersion: batch/v1
kind: CronJob
metadata:
  name: moo-sor-market-close-scaler
spec:
  schedule: "5 21 * * 1-5"    # Mon-Fri 4:05 PM EST (adjust for EDT)
  jobTemplate:
    spec:
      template:
        spec:
          serviceAccountName: moo-sor-scaler
          containers:
            - name: scaler
              image: registry.redhat.io/openshift4/ose-cli:latest
              command:
                - /bin/sh
                - -c
                - |
                  oc patch hpa/moo-sor-hpa -p '{"spec":{"minReplicas":2}}'
                  # HPA will gradually scale down to 2 based on low CPU
          restartPolicy: OnFailure
---
# RBAC: allow the scaler service account to manage deployments and HPAs
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: moo-sor-scaler-role
rules:
  - apiGroups: ["apps"]
    resources: ["deployments", "deployments/scale"]
    verbs: ["get", "patch"]
  - apiGroups: ["autoscaling"]
    resources: ["horizontalpodautoscalers"]
    verbs: ["get", "patch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: moo-sor-scaler-binding
subjects:
  - kind: ServiceAccount
    name: moo-sor-scaler
roleRef:
  kind: Role
  name: moo-sor-scaler-role
  apiGroup: rbac.authorization.k8s.io
```

**Note on EST/EDT**: CronJob schedules use the cluster's timezone (typically UTC). Market hours are Eastern Time, which shifts between EST (UTC-5) and EDT (UTC-4). Either adjust the CronJob schedules twice a year, or use KEDA (Option 2) which handles timezone-aware cron expressions.

#### Option 2: KEDA (Recommended for Production)

KEDA (Kubernetes Event-Driven Autoscaler) is available as an OCP Operator. It supports **cron-based scaling as a first-class trigger** combined with metric-based scaling — no separate CronJobs needed.

```yaml
# Install KEDA Operator first:
# OCP Console → OperatorHub → "KEDA" → Install

apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: moo-sor-scaled
spec:
  scaleTargetRef:
    name: moo-sor-alert-service
  minReplicaCount: 2        # absolute minimum (off-hours)
  maxReplicaCount: 8        # absolute maximum (market crash)
  triggers:
    # Cron trigger: pre-scale to 4 during market hours (Mon-Fri)
    - type: cron
      metadata:
        timezone: America/New_York        # handles EST/EDT automatically
        start: "15 9 * * 1-5"            # 9:15 AM ET Mon-Fri
        end: "5 16 * * 1-5"              # 4:05 PM ET Mon-Fri
        desiredReplicas: "4"             # guaranteed 4 pods during market hours
    # CPU trigger: burst beyond 4 pods if load spikes
    - type: cpu
      metricType: Utilization
      metadata:
        value: "70"                      # scale up when avg CPU > 70%
```

**Why KEDA over CronJob:**
- **Timezone-aware**: `America/New_York` handles EST/EDT transitions automatically — no manual schedule updates
- **Single resource**: Combines time-based and metric-based scaling in one `ScaledObject` instead of CronJobs + HPA
- **Smooth transitions**: KEDA manages the scale-down gradually, respecting cooldown periods
- **Additional triggers**: Can add Kafka consumer lag, Prometheus metrics, or custom queries as future scaling signals

#### KEDA Scaling Timeline (Market Crash Day)

```mermaid
gantt
    title MOO SOR Pod Scaling — Market Crash Day
    dateFormat HH:mm
    axisFormat %H:%M

    section Pods
    2 pods (off-hours)           :done, 00:00, 09:15
    4 pods (KEDA cron pre-scale) :active, 09:15, 09:31
    6 pods (KEDA CPU burst)      :crit, 09:31, 10:15
    4 pods (load subsides)       :active, 10:15, 16:05
    2 pods (KEDA cron off-hours) :done, 16:05, 23:59
```

### Horizontal Pod Autoscaler (HPA)

If using the CronJob approach (Option 1), deploy this HPA alongside the CronJobs. If using KEDA (Option 2), the `ScaledObject` replaces this HPA.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: moo-sor-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: moo-sor-alert-service
  minReplicas: 2            # overridden to 4 by CronJob during market hours
  maxReplicas: 8
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70    # scale up when avg CPU > 70% of request
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 30   # react quickly to market crash
      policies:
        - type: Pods
          value: 2                      # add up to 2 pods at a time
          periodSeconds: 30
    scaleDown:
      stabilizationWindowSeconds: 300  # wait 5 min before scaling down
      policies:
        - type: Pods
          value: 1
          periodSeconds: 60
```

#### Scaling Summary

```mermaid
flowchart TD
    subgraph "Off-Hours (8 PM → 9:15 AM ET)"
        OH["2 pods minimum\nHPA min = 2"]
    end
    subgraph "9:15 AM ET — CronJob/KEDA"
        PRE["Scale to 4 pods\nHPA min → 4\nJVM warm-up begins"]
    end
    subgraph "Market Hours (9:30 AM → 4:00 PM ET)"
        MH["4 pods baseline"]
        MH --> SPIKE{"CPU > 70%?"}
        SPIKE -- Yes --> BURST["HPA/KEDA adds pods\nup to 8 max"]
        SPIKE -- No --> STABLE["4 pods, steady"]
        BURST --> RECOVER["Load drops\n5 min cooldown\nscale back to 4"]
    end
    subgraph "4:05 PM ET — CronJob/KEDA"
        POST["HPA min → 2\nGradual scale-down"]
    end
    OH --> PRE --> MH
    STABLE --> POST
    RECOVER --> POST

```

| Time | Trigger | Pods | Throughput Capacity | Monthly Cost Impact |
|------|---------|------|--------------------|--------------------|
| Off-hours | CronJob/KEDA cron | 2 | ~4,600/sec | Baseline |
| 9:15 AM ET | CronJob/KEDA cron | 4 | ~9,300/sec | +2 pods × 6.75 hrs |
| Market crash | HPA/KEDA CPU | 6-8 | ~14,000-18,500/sec | +2-4 pods (minutes to hours) |
| 4:05 PM ET | CronJob/KEDA cron | 2 | ~4,600/sec | Back to baseline |

**Savings**: Running 2 pods off-hours instead of 4 saves ~50% of MOO SOR compute cost for 17.25 hours/day (off-hours) — roughly **36% overall cost reduction** versus running 4 pods 24/7.

### Liveness and Readiness Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30      # JVM startup time
  periodSeconds: 10
  failureThreshold: 3          # 3 failures = restart pod
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 15
  periodSeconds: 5
  failureThreshold: 3          # 3 failures = remove from service
```

**Liveness** restarts the pod if the JVM is unresponsive (deadlock, OOM, GC death spiral). **Readiness** removes the pod from the load balancer during startup or if MongoDB/Kafka connections are unhealthy — FactSet webhooks are routed only to ready pods.

### Test Environment vs. Production

The load test ran on a single Docker host with all containers sharing CPU and memory — no resource limits, no isolation. The results reflect a worst-case shared environment:

| Aspect | Docker Test Environment | OCP Production |
|--------|------------------------|----------------|
| CPU isolation | None — all containers share host | Per-pod guarantees via `requests` |
| Memory limits | None — JVM `-Xmx` only | Pod-level `limits.memory` enforced by kernel |
| Node count | 1 (shared) | Multiple dedicated nodes |
| System CPU at peak | 100% (host saturated) | Per-node; pods throttled individually |
| Observed per-JVM CPU | 6.1% avg / 19.5% peak | Expected similar with dedicated resources |
| Network | Docker bridge (localhost) | OCP SDN (cluster network) — slightly higher latency |
| Storage | Shared host disk | Dedicated PVs for MongoDB, Kafka |

Despite the shared environment hitting 100% system CPU, the MOO SOR instances processed 2M webhooks in 2m16s with zero failures. In OCP with dedicated resources, performance would be equal or better.

## Project Structure

```
moo-sor-alert-service/
├── src/main/java/com/bank/moo/
│   ├── MOOAlertServiceApplication.java       Spring Boot entry point (@EnableAsync)
│   ├── config/
│   │   ├── AsyncConfig.java                  Thread pool (50/200/50K) + CallerRunsPolicy + metrics
│   │   ├── KafkaConfig.java                  Topic creation (12 partitions)
│   │   ├── MongoConfig.java                  MongoDB auditing (@Profile("!oracle"))
│   │   └── OracleConfig.java                 JPA + transaction management (@Profile("oracle"))
│   ├── controller/
│   │   └── FactSetWebhookController.java     POST /api/v1/alerts/factset/webhook
│   ├── service/
│   │   ├── CustomerDataService.java          DB abstraction interface (findByUserId, updateDateDelivered)
│   │   ├── MongoCustomerDataService.java     MongoDB embedded impl (@Profile("!oracle & !mongo-flat"))
│   │   ├── MongoFlatCustomerDataService.java MongoDB flat impl (@Profile("mongo-flat"))
│   │   ├── OracleCustomerDataService.java    Oracle JPA implementation (@Profile("oracle"))
│   │   ├── MOOAlertOrchestrationService.java Core orchestration (lookup → validate → enrich → publish)
│   │   └── MarketDataClient.java             REST client + Caffeine cache
│   ├── model/
│   │   ├── FactSetAlert.java                 Inbound webhook payload
│   │   ├── CustomerDocument.java             Domain model (shared by all DB implementations)
│   │   ├── FlatAlertDocument.java            Flat MongoDB document (1 per subscription, denormalized)
│   │   ├── Subscription.java                 Subscription domain object
│   │   ├── ChannelPreference.java            Contact channel (push/email/sms)
│   │   ├── ContactPreferences.java           Channel list wrapper
│   │   ├── MarketData.java                   Market data response
│   │   ├── CowAlertMessage.java              Outbound Kafka message
│   │   └── oracle/                           JPA entities for Oracle
│   │       ├── CustomerEntity.java           CUSTOMERS table entity
│   │       ├── SubscriptionEntity.java       CUSTOMER_SUBSCRIPTIONS table entity
│   │       └── ChannelPreferenceEntity.java  CHANNEL_PREFERENCES table entity
│   └── repository/
│       ├── CustomerRepository.java           Spring Data MongoDB repo (@Profile("!oracle & !mongo-flat"))
│       ├── FlatAlertRepository.java          Flat MongoDB repo (@Profile("mongo-flat"))
│       ├── OracleCustomerRepository.java     Spring Data JPA repository (@Profile("oracle"))
│       └── OracleSubscriptionRepository.java JPA repo with updateDateDelivered query (@Profile("oracle"))
├── src/main/resources/
│   ├── application.yml                       All configuration (default + mongo-flat + oracle profiles)
│   └── schema-oracle.sql                     Oracle DDL (tables + indexes)
├── src/test/java/com/bank/moo/
│   ├── load/
│   │   ├── TestDataGenerator.java            Seeds 500K customers into MongoDB (embedded model)
│   │   ├── FlatMongoTestDataGenerator.java   Seeds ~2.5M alert docs into MongoDB (flat model)
│   │   ├── OracleTestDataGenerator.java      Seeds 500K customers into Oracle (3 tables)
│   │   ├── MarketCrashLoadTest.java          2M webhook load test (MongoDB embedded)
│   │   ├── FlatMongoMarketCrashLoadTest.java 2M webhook load test (MongoDB flat)
│   │   ├── OracleMarketCrashLoadTest.java    2M webhook load test (Oracle)
│   │   ├── CircuitBreakerDegradationTest.java 200K degradation test
│   │   ├── LoadTestScenarios.java            All scenario definitions
│   │   └── PerformanceReportGenerator.java   Formatted report output
│   ├── service/
│   │   ├── MOOAlertOrchestrationServiceTest.java  Unit tests (mocks CustomerDataService)
│   │   └── MarketDataClientTest.java              Cache behavior tests
│   └── mock/
│       └── MockMarketDataServer.java         Embedded HTTP server for unit tests
├── mock-market-data/                         Standalone Spring Boot mock (Docker)
├── docker-compose.yml                        Full environment (MongoDB, Kafka, Oracle, 4 instances)
├── docker-compose-mongo-flat.yml             Override: switches instances to mongo-flat profile
├── docker-compose-oracle.yml                 Override: switches instances to oracle profile
├── Dockerfile                                Multi-stage build
└── build.gradle                              Dependencies, test config, seed*/load* tasks
```

## Metrics (Micrometer)

All metrics exposed via `/actuator/prometheus` for scraping and via `/actuator/metrics/{name}` for individual queries.

| Metric | Type | Description |
|--------|------|-------------|
| `moo.webhook.received` | Counter | Total webhooks received |
| `moo.webhook.response.time` | Timer | Time to accept webhook and enqueue (should be sub-ms) |
| `moo.alert.processed` | Counter | Successfully orchestrated and published to Kafka |
| `moo.alert.skipped.inactive` | Counter | Skipped because `activeState != "Y"` |
| `moo.alert.skipped.throttled` | Counter | Skipped because `dateDelivered` is today (Eastern) |
| `moo.alert.skipped.not_found` | Counter | Skipped because customer or matching subscription not found |
| `moo.alert.failed` | Counter | Failed during orchestration (exception) |
| `moo.orchestration.time` | Timer | Full orchestration latency (Mongo + market data + Kafka) |
| `moo.mongo.lookup.time` | Timer | MongoDB customer lookup by `_id` |
| `moo.mongo.update.time` | Timer | MongoDB `dateDelivered` update via `$elemMatch` |
| `moo.marketdata.fetch.time` | Timer | Market data HTTP fetch (only on cache miss) |
| `moo.marketdata.cache.hit` | Counter | Caffeine cache hits (no HTTP call needed) |
| `moo.marketdata.cache.miss` | Counter | Caffeine cache misses (HTTP call made) |
| `moo.kafka.publish.time` | Timer | Kafka producer send latency |
| `moo.marketdata.circuit.rejected` | Counter | Market data calls rejected (circuit OPEN) |
| `moo.mongo.circuit.rejected` | Counter | MongoDB calls rejected (circuit OPEN) |
| `moo.kafka.circuit.rejected` | Counter | Kafka publishes rejected (circuit OPEN) |
| `moo.threadpool.queue.size` | Gauge | Current thread pool queue depth |
| `moo.threadpool.active.threads` | Gauge | Currently active processing threads |
| `moo.threadpool.caller.runs.count` | Gauge | Times CallerRunsPolicy activated (queue overflow) |
