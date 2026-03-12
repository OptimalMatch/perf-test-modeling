package com.bank.moo.load;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates test data for the flat MongoDB model (one document per subscription).
 * Uses the same random seed and data distribution as TestDataGenerator for fair comparison.
 */
public class FlatMongoTestDataGenerator {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final Random random = new Random(12345); // same seed as embedded model
    private static final AtomicInteger triggerCounter = new AtomicInteger(0);
    private static final AtomicInteger customerCounter = new AtomicInteger(0);

    private static final String[] FIRST_NAMES = {
            "James", "Mary", "Robert", "Patricia", "John", "Jennifer", "Michael", "Linda",
            "David", "Elizabeth", "William", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
            "Thomas", "Sarah", "Charles", "Karen", "Christopher", "Lisa", "Daniel", "Nancy",
            "Margaret", "Mark", "Betty", "Donald", "Sandra", "Steven"
    };

    private static final String[] LAST_NAMES = {
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
            "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson",
            "Thomas", "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez", "Thompson",
            "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson"
    };

    private static final String[] TRIGGER_VALUES = {"-5", "5", "-10", "10"};
    private static final String[] TRIGGER_TYPE_IDS = {"6", "6", "6", "6", "3", "4"};

    private final List<String> symbols;

    public FlatMongoTestDataGenerator() {
        this.symbols = generateSymbols(2000);
    }

    public List<String> getSymbols() {
        return symbols;
    }

    private static List<String> generateSymbols(int count) {
        Set<String> symbolSet = new LinkedHashSet<>();
        symbolSet.addAll(Arrays.asList(
                "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "BRK.B",
                "JPM", "V", "JNJ", "WMT", "PG", "MA", "UNH", "HD", "DIS", "BAC",
                "XOM", "PFE", "KO", "PEP", "CSCO", "INTC", "NFLX", "ADBE", "CRM",
                "ABT", "ACN", "TMO"
        ));
        while (symbolSet.size() < count) {
            StringBuilder sb = new StringBuilder();
            int len = 2 + random.nextInt(3);
            for (int i = 0; i < len; i++) {
                sb.append((char) ('A' + random.nextInt(26)));
            }
            symbolSet.add(sb.toString());
        }
        return new ArrayList<>(symbolSet);
    }

    public record WebhookTarget(String userId, String triggerId, String triggerTypeId, String symbol, String value) {}

    public record GeneratedData(
            List<Document> alertDocuments,
            Map<String, List<WebhookTarget>> webhookTargets
    ) {}

    public GeneratedData generateCustomers(int count) {
        List<Document> docs = new ArrayList<>();
        Map<String, List<WebhookTarget>> targets = new HashMap<>();
        Instant now = Instant.now();
        Instant todayStartEastern = LocalDate.now(EASTERN).atStartOfDay(EASTERN).toInstant();

        for (int i = 0; i < count; i++) {
            ObjectId objectId = new ObjectId();
            String customerId = objectId.toHexString();
            String customerCode = String.format("CUST-%07d", customerCounter.incrementAndGet());
            String firstName = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)];
            String lastName = LAST_NAMES[random.nextInt(LAST_NAMES.length)];

            // Generate channel preferences (denormalized into each doc)
            List<Document> channels = new ArrayList<>();
            channels.add(new Document()
                    .append("type", "PUSH_NOTIFICATION")
                    .append("enabled", random.nextDouble() < 0.9)
                    .append("priority", 1));
            channels.add(new Document()
                    .append("type", "EMAIL")
                    .append("enabled", random.nextDouble() < 0.8)
                    .append("priority", 2)
                    .append("address", customerCode.toLowerCase() + "@email.com"));
            if (random.nextDouble() < 0.6) {
                channels.add(new Document()
                        .append("type", "SMS")
                        .append("enabled", true)
                        .append("priority", 3)
                        .append("phoneNumber", String.format("+1212555%04d", random.nextInt(10000))));
            }

            // Generate 2-8 subscriptions — each becomes its own document
            int subCount = 2 + random.nextInt(7);
            for (int j = 0; j < subCount; j++) {
                String symbol = symbols.get(random.nextInt(symbols.size()));
                String triggerTypeId = TRIGGER_TYPE_IDS[random.nextInt(TRIGGER_TYPE_IDS.length)];
                String triggerId = String.format("FS-TRIG-%06d", triggerCounter.incrementAndGet());

                String value;
                if ("3".equals(triggerTypeId) || "4".equals(triggerTypeId)) {
                    value = "0";
                } else {
                    value = TRIGGER_VALUES[random.nextInt(TRIGGER_VALUES.length)];
                }

                boolean active = random.nextDouble() < 0.80;
                Instant dateDelivered = null;
                if (active) {
                    double deliveredRoll = random.nextDouble();
                    if (deliveredRoll < 0.30) {
                        dateDelivered = todayStartEastern.plusSeconds(random.nextInt(36000));
                    }
                }

                Document alertDoc = new Document()
                        .append("customerId", customerId)
                        .append("customerCode", customerCode)
                        .append("firstName", firstName)
                        .append("lastName", lastName)
                        .append("channels", channels)
                        .append("symbol", symbol)
                        .append("factSetTriggerId", triggerId)
                        .append("triggerTypeId", triggerTypeId)
                        .append("value", value)
                        .append("activeState", active ? "Y" : "N")
                        .append("subscribedAt", Date.from(now.minusSeconds(random.nextInt(365 * 86400))))
                        .append("dateDelivered", dateDelivered != null ? Date.from(dateDelivered) : null);

                docs.add(alertDoc);

                if (active && dateDelivered == null) {
                    targets.computeIfAbsent(symbol, k -> new ArrayList<>())
                            .add(new WebhookTarget(customerId, triggerId, triggerTypeId, symbol, value));
                }
            }
        }

        return new GeneratedData(docs, targets);
    }

    public static void seedDatabase(String mongoUri, int customerCount) {
        System.out.printf("Generating flat alert docs for %,d customers...%n", customerCount);
        FlatMongoTestDataGenerator generator = new FlatMongoTestDataGenerator();
        GeneratedData data = generator.generateCustomers(customerCount);

        System.out.printf("Inserting %,d alert documents into MongoDB (customer_alerts collection)...%n",
                data.alertDocuments().size());
        try (MongoClient client = MongoClients.create(mongoUri)) {
            MongoDatabase db = client.getDatabase("moo");
            MongoCollection<Document> collection = db.getCollection("customer_alerts");
            collection.drop();

            // Batch insert
            int batchSize = 10000;
            List<Document> batch = new ArrayList<>(batchSize);
            int inserted = 0;
            for (Document doc : data.alertDocuments()) {
                batch.add(doc);
                if (batch.size() >= batchSize) {
                    collection.insertMany(batch);
                    inserted += batch.size();
                    System.out.printf("  Inserted %,d / %,d%n", inserted, data.alertDocuments().size());
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                collection.insertMany(batch);
                inserted += batch.size();
            }

            // Create indexes
            System.out.println("Creating indexes...");
            collection.createIndex(new Document("customerId", 1));
            collection.createIndex(new Document("customerId", 1).append("factSetTriggerId", 1));

            System.out.printf("Done. Total alert documents: %,d (from %,d customers)%n", inserted, customerCount);
            System.out.printf("Avg docs per customer: %.1f%n", (double) inserted / customerCount);

            long totalTargets = data.webhookTargets().values().stream().mapToLong(List::size).sum();
            System.out.printf("Eligible webhook targets across %,d symbols: %,d%n",
                    data.webhookTargets().size(), totalTargets);
        }
    }

    public static void main(String[] args) {
        String mongoUri = args.length > 0 ? args[0] : "mongodb://localhost:27017";
        int customerCount = args.length > 1 ? Integer.parseInt(args[1]) : 500000;
        seedDatabase(mongoUri, customerCount);
    }
}
