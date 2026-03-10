package com.bank.moo.load;

import org.bson.types.ObjectId;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Populates Oracle with the same test data that TestDataGenerator creates for MongoDB.
 * Each customer gets its own row in CUSTOMERS, and each subscription gets its own row
 * in CUSTOMER_SUBSCRIPTIONS (normalized from MongoDB's embedded array model).
 */
public class OracleTestDataGenerator {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final Random random = new Random(12345); // same seed as MongoDB generator
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

    public OracleTestDataGenerator() {
        this.symbols = generateSymbols(2000);
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

    public static void seedDatabase(String jdbcUrl, String user, String password, int customerCount) {
        System.out.printf("Generating %,d customers for Oracle...%n", customerCount);
        OracleTestDataGenerator generator = new OracleTestDataGenerator();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            conn.setAutoCommit(false);

            // Run schema DDL
            System.out.println("Creating schema...");
            generator.runSchema(conn);
            conn.commit();

            Instant now = Instant.now();
            Instant todayStartEastern = LocalDate.now(EASTERN).atStartOfDay(EASTERN).toInstant();

            int totalSubs = 0;
            int totalChannels = 0;
            int totalEligible = 0;
            int batchSize = 1000;

            PreparedStatement custStmt = conn.prepareStatement(
                    "INSERT INTO CUSTOMERS (ID, CUSTOMER_ID, FIRST_NAME, LAST_NAME) VALUES (?, ?, ?, ?)");
            PreparedStatement subStmt = conn.prepareStatement(
                    "INSERT INTO CUSTOMER_SUBSCRIPTIONS (CUSTOMER_ID, SYMBOL, FACTSET_TRIGGER_ID, " +
                    "TRIGGER_TYPE_ID, TRIGGER_VALUE, ACTIVE_STATE, SUBSCRIBED_AT, DATE_DELIVERED) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            PreparedStatement chanStmt = conn.prepareStatement(
                    "INSERT INTO CHANNEL_PREFERENCES (CUSTOMER_ID, CHANNEL_TYPE, ENABLED, PRIORITY, ADDRESS, PHONE_NUMBER) " +
                    "VALUES (?, ?, ?, ?, ?, ?)");

            for (int i = 0; i < customerCount; i++) {
                ObjectId objectId = new ObjectId();
                String id = objectId.toHexString();
                String customerId = String.format("CUST-%07d", customerCounter.incrementAndGet());
                String firstName = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)];
                String lastName = LAST_NAMES[random.nextInt(LAST_NAMES.length)];

                // Insert customer
                custStmt.setString(1, id);
                custStmt.setString(2, customerId);
                custStmt.setString(3, firstName);
                custStmt.setString(4, lastName);
                custStmt.addBatch();

                // Generate 2-8 subscriptions (same logic as MongoDB generator)
                int subCount = 2 + random.nextInt(7);
                for (int j = 0; j < subCount; j++) {
                    String symbol = generator.symbols.get(random.nextInt(generator.symbols.size()));
                    String triggerTypeId = TRIGGER_TYPE_IDS[random.nextInt(TRIGGER_TYPE_IDS.length)];
                    String triggerId = String.format("FS-TRIG-%06d", triggerCounter.incrementAndGet());
                    String value;
                    if ("3".equals(triggerTypeId) || "4".equals(triggerTypeId)) {
                        value = "0";
                    } else {
                        value = TRIGGER_VALUES[random.nextInt(TRIGGER_VALUES.length)];
                    }

                    boolean active = random.nextDouble() < 0.80;
                    Timestamp dateDelivered = null;
                    if (active) {
                        double deliveredRoll = random.nextDouble();
                        if (deliveredRoll < 0.30) {
                            dateDelivered = Timestamp.from(todayStartEastern.plusSeconds(random.nextInt(36000)));
                        }
                    }

                    subStmt.setString(1, id);
                    subStmt.setString(2, symbol);
                    subStmt.setString(3, triggerId);
                    subStmt.setString(4, triggerTypeId);
                    subStmt.setString(5, value);
                    subStmt.setString(6, active ? "Y" : "N");
                    subStmt.setTimestamp(7, Timestamp.from(now.minusSeconds(random.nextInt(365 * 86400))));
                    subStmt.setTimestamp(8, dateDelivered);
                    subStmt.addBatch();
                    totalSubs++;

                    if (active && dateDelivered == null) {
                        totalEligible++;
                    }
                }

                // Generate channel preferences (same logic as MongoDB generator)
                chanStmt.setString(1, id);
                chanStmt.setString(2, "PUSH_NOTIFICATION");
                chanStmt.setInt(3, random.nextDouble() < 0.9 ? 1 : 0);
                chanStmt.setInt(4, 1);
                chanStmt.setString(5, null);
                chanStmt.setString(6, null);
                chanStmt.addBatch();
                totalChannels++;

                chanStmt.setString(1, id);
                chanStmt.setString(2, "EMAIL");
                chanStmt.setInt(3, random.nextDouble() < 0.8 ? 1 : 0);
                chanStmt.setInt(4, 2);
                chanStmt.setString(5, customerId.toLowerCase() + "@email.com");
                chanStmt.setString(6, null);
                chanStmt.addBatch();
                totalChannels++;

                if (random.nextDouble() < 0.6) {
                    chanStmt.setString(1, id);
                    chanStmt.setString(2, "SMS");
                    chanStmt.setInt(3, 1);
                    chanStmt.setInt(4, 3);
                    chanStmt.setString(5, null);
                    chanStmt.setString(6, String.format("+1212555%04d", random.nextInt(10000)));
                    chanStmt.addBatch();
                    totalChannels++;
                }

                // Execute batch periodically
                if ((i + 1) % batchSize == 0) {
                    custStmt.executeBatch();
                    subStmt.executeBatch();
                    chanStmt.executeBatch();
                    conn.commit();
                    System.out.printf("  Inserted %,d / %,d customers%n", i + 1, customerCount);
                }
            }

            // Final batch
            custStmt.executeBatch();
            subStmt.executeBatch();
            chanStmt.executeBatch();
            conn.commit();

            custStmt.close();
            subStmt.close();
            chanStmt.close();

            // Gather table stats for optimizer
            System.out.println("Gathering table statistics...");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'CUSTOMERS'); END;");
                stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'CUSTOMER_SUBSCRIPTIONS'); END;");
                stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'CHANNEL_PREFERENCES'); END;");
            }
            conn.commit();

            System.out.printf("Done. Customers: %,d, Subscriptions: %,d, Channels: %,d%n",
                    customerCount, totalSubs, totalChannels);
            System.out.printf("Eligible webhook targets: %,d%n", totalEligible);

        } catch (Exception e) {
            throw new RuntimeException("Failed to seed Oracle database", e);
        }
    }

    private void runSchema(Connection conn) throws SQLException {
        // Drop and recreate tables
        executeSafe(conn, "DROP TABLE CHANNEL_PREFERENCES CASCADE CONSTRAINTS");
        executeSafe(conn, "DROP TABLE CUSTOMER_SUBSCRIPTIONS CASCADE CONSTRAINTS");
        executeSafe(conn, "DROP TABLE CUSTOMERS CASCADE CONSTRAINTS");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE CUSTOMERS (" +
                    "ID VARCHAR2(24) PRIMARY KEY, " +
                    "CUSTOMER_ID VARCHAR2(20) NOT NULL, " +
                    "FIRST_NAME VARCHAR2(50), " +
                    "LAST_NAME VARCHAR2(50))");

            stmt.execute("CREATE TABLE CUSTOMER_SUBSCRIPTIONS (" +
                    "ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                    "CUSTOMER_ID VARCHAR2(24) NOT NULL REFERENCES CUSTOMERS(ID), " +
                    "SYMBOL VARCHAR2(10) NOT NULL, " +
                    "FACTSET_TRIGGER_ID VARCHAR2(20) NOT NULL, " +
                    "TRIGGER_TYPE_ID VARCHAR2(5) NOT NULL, " +
                    "TRIGGER_VALUE VARCHAR2(10), " +
                    "ACTIVE_STATE CHAR(1) NOT NULL, " +
                    "SUBSCRIBED_AT TIMESTAMP WITH TIME ZONE, " +
                    "DATE_DELIVERED TIMESTAMP WITH TIME ZONE)");

            stmt.execute("CREATE TABLE CHANNEL_PREFERENCES (" +
                    "ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                    "CUSTOMER_ID VARCHAR2(24) NOT NULL REFERENCES CUSTOMERS(ID), " +
                    "CHANNEL_TYPE VARCHAR2(30) NOT NULL, " +
                    "ENABLED NUMBER(1) NOT NULL, " +
                    "PRIORITY NUMBER(3), " +
                    "ADDRESS VARCHAR2(100), " +
                    "PHONE_NUMBER VARCHAR2(20))");

            stmt.execute("CREATE INDEX IDX_SUB_CUSTOMER_ID ON CUSTOMER_SUBSCRIPTIONS(CUSTOMER_ID)");
            stmt.execute("CREATE INDEX IDX_SUB_TRIGGER_ID ON CUSTOMER_SUBSCRIPTIONS(FACTSET_TRIGGER_ID)");
            stmt.execute("CREATE INDEX IDX_CHAN_CUSTOMER_ID ON CHANNEL_PREFERENCES(CUSTOMER_ID)");
        }
    }

    private void executeSafe(Connection conn, String sql) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            // Ignore "table does not exist" errors
        }
    }

    /**
     * Loads eligible webhook targets from Oracle (same format as MongoDB loader).
     */
    public static List<String[]> loadTargets(String jdbcUrl, String user, String password) {
        List<String[]> targets = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            String sql = "SELECT s.CUSTOMER_ID, s.FACTSET_TRIGGER_ID, s.TRIGGER_TYPE_ID, s.SYMBOL, s.TRIGGER_VALUE " +
                         "FROM CUSTOMER_SUBSCRIPTIONS s " +
                         "WHERE s.ACTIVE_STATE = 'Y' AND s.DATE_DELIVERED IS NULL";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    targets.add(new String[]{
                            rs.getString("CUSTOMER_ID"),
                            rs.getString("FACTSET_TRIGGER_ID"),
                            rs.getString("TRIGGER_TYPE_ID"),
                            rs.getString("SYMBOL"),
                            rs.getString("TRIGGER_VALUE")
                    });
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load targets from Oracle", e);
        }
        return targets;
    }

    public static void main(String[] args) {
        String jdbcUrl = args.length > 0 ? args[0] : "jdbc:oracle:thin:@localhost:1521/FREEPDB1";
        String user = args.length > 1 ? args[1] : "moo";
        String password = args.length > 2 ? args[2] : "moo_password";
        int customerCount = args.length > 3 ? Integer.parseInt(args[3]) : 500000;
        seedDatabase(jdbcUrl, user, password, customerCount);
    }
}
