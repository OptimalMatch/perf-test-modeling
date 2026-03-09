package com.bank.wid.load;

import com.bank.wid.model.*;
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

public class TestDataGenerator {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final Random random = new Random(12345);
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
    private static final String[] TRIGGER_TYPE_IDS = {"6", "6", "6", "6", "3", "4"}; // weighted toward type 6

    private final List<String> symbols;

    public TestDataGenerator() {
        this.symbols = generateSymbols(2000);
    }

    public List<String> getSymbols() {
        return symbols;
    }

    private static List<String> generateSymbols(int count) {
        Set<String> symbolSet = new LinkedHashSet<>();
        // Add well-known symbols first
        symbolSet.addAll(Arrays.asList(
                "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "BRK.B",
                "JPM", "V", "JNJ", "WMT", "PG", "MA", "UNH", "HD", "DIS", "BAC",
                "XOM", "PFE", "KO", "PEP", "CSCO", "INTC", "NFLX", "ADBE", "CRM",
                "ABT", "ACN", "TMO"
        ));
        // Generate random symbols for the rest
        while (symbolSet.size() < count) {
            StringBuilder sb = new StringBuilder();
            int len = 2 + random.nextInt(3); // 2-4 chars
            for (int i = 0; i < len; i++) {
                sb.append((char) ('A' + random.nextInt(26)));
            }
            symbolSet.add(sb.toString());
        }
        return new ArrayList<>(symbolSet);
    }

    public record GeneratedData(
            List<Document> customerDocuments,
            Map<String, List<WebhookTarget>> webhookTargets // symbol -> list of (userId, triggerId)
    ) {}

    public record WebhookTarget(String userId, String triggerId, String triggerTypeId, String symbol, String value) {}

    public GeneratedData generateCustomers(int count) {
        List<Document> docs = new ArrayList<>(count);
        Map<String, List<WebhookTarget>> targets = new HashMap<>();
        Instant now = Instant.now();
        Instant todayStartEastern = LocalDate.now(EASTERN).atStartOfDay(EASTERN).toInstant();

        for (int i = 0; i < count; i++) {
            ObjectId objectId = new ObjectId();
            String id = objectId.toHexString();
            String customerId = String.format("CUST-%07d", customerCounter.incrementAndGet());

            // Generate 2-8 subscriptions
            int subCount = 2 + random.nextInt(7);
            List<Document> subs = new ArrayList<>(subCount);

            for (int j = 0; j < subCount; j++) {
                String symbol = symbols.get(random.nextInt(symbols.size()));
                String triggerTypeId = TRIGGER_TYPE_IDS[random.nextInt(TRIGGER_TYPE_IDS.length)];
                String triggerId = String.format("FS-TRIG-%06d", triggerCounter.incrementAndGet());

                String value;
                if ("3".equals(triggerTypeId) || "4".equals(triggerTypeId)) {
                    value = "0"; // 52-week high/low don't use value the same way
                } else {
                    value = TRIGGER_VALUES[random.nextInt(TRIGGER_VALUES.length)];
                }

                boolean active = random.nextDouble() < 0.80; // 80% active
                Instant dateDelivered = null;
                if (active) {
                    double deliveredRoll = random.nextDouble();
                    if (deliveredRoll < 0.30) {
                        // 30% already delivered today (should be throttled)
                        dateDelivered = todayStartEastern.plusSeconds(random.nextInt(36000));
                    }
                    // 70% null (eligible)
                }

                Document sub = new Document()
                        .append("symbol", symbol)
                        .append("factSetTriggerId", triggerId)
                        .append("triggerTypeId", triggerTypeId)
                        .append("value", value)
                        .append("activeState", active ? "Y" : "N")
                        .append("subscribedAt", Date.from(now.minusSeconds(random.nextInt(365 * 86400))))
                        .append("dateDelivered", dateDelivered != null ? Date.from(dateDelivered) : null);

                subs.add(sub);

                // Track webhook targets for eligible subscriptions
                if (active && dateDelivered == null) {
                    targets.computeIfAbsent(symbol, k -> new ArrayList<>())
                            .add(new WebhookTarget(id, triggerId, triggerTypeId, symbol, value));
                }
            }

            // Generate contact preferences
            List<Document> channels = new ArrayList<>();
            channels.add(new Document()
                    .append("type", "PUSH_NOTIFICATION")
                    .append("enabled", random.nextDouble() < 0.9)
                    .append("priority", 1));
            channels.add(new Document()
                    .append("type", "EMAIL")
                    .append("enabled", random.nextDouble() < 0.8)
                    .append("priority", 2)
                    .append("address", customerId.toLowerCase() + "@email.com"));
            if (random.nextDouble() < 0.6) {
                channels.add(new Document()
                        .append("type", "SMS")
                        .append("enabled", true)
                        .append("priority", 3)
                        .append("phoneNumber", String.format("+1212555%04d", random.nextInt(10000))));
            }

            Document customer = new Document()
                    .append("_id", objectId)
                    .append("customerId", customerId)
                    .append("firstName", FIRST_NAMES[random.nextInt(FIRST_NAMES.length)])
                    .append("lastName", LAST_NAMES[random.nextInt(LAST_NAMES.length)])
                    .append("contactPreferences", new Document("channels", channels))
                    .append("subscriptions", subs);

            docs.add(customer);
        }

        return new GeneratedData(docs, targets);
    }

    public static void seedDatabase(String mongoUri, int customerCount) {
        System.out.printf("Generating %,d customers...%n", customerCount);
        TestDataGenerator generator = new TestDataGenerator();
        GeneratedData data = generator.generateCustomers(customerCount);

        System.out.printf("Inserting %,d customers into MongoDB...%n", data.customerDocuments().size());
        try (MongoClient client = MongoClients.create(mongoUri)) {
            MongoDatabase db = client.getDatabase("wid");
            MongoCollection<Document> collection = db.getCollection("customers");
            collection.drop();

            // Batch insert
            int batchSize = 10000;
            List<Document> batch = new ArrayList<>(batchSize);
            int inserted = 0;
            for (Document doc : data.customerDocuments()) {
                batch.add(doc);
                if (batch.size() >= batchSize) {
                    collection.insertMany(batch);
                    inserted += batch.size();
                    System.out.printf("  Inserted %,d / %,d%n", inserted, customerCount);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                collection.insertMany(batch);
                inserted += batch.size();
            }

            System.out.printf("Done. Total customers: %,d%n", inserted);
            System.out.printf("Eligible webhook targets across %,d symbols%n", data.webhookTargets().size());

            long totalTargets = data.webhookTargets().values().stream().mapToLong(List::size).sum();
            System.out.printf("Total eligible targets: %,d%n", totalTargets);
        }
    }

    public static void main(String[] args) {
        String mongoUri = args.length > 0 ? args[0] : "mongodb://localhost:27017";
        int customerCount = args.length > 1 ? Integer.parseInt(args[1]) : 500000;
        seedDatabase(mongoUri, customerCount);
    }
}
