package com.bank.moo.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class MockMarketDataServer {

    private final HttpServer server;
    private final Map<String, Map<String, Object>> symbolCache = new ConcurrentHashMap<>();
    private final Random random = new Random(42);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private long responseDelayMs = 0;

    private static final String[] SECURITY_NAMES = {
            "Inc.", "Corp.", "Ltd.", "Group", "Holdings", "Technologies",
            "Pharmaceuticals", "Financial", "Energy", "Communications"
    };

    public MockMarketDataServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.createContext("/api/v1/marketdata/", exchange -> {
            try {
                if (responseDelayMs > 0) {
                    Thread.sleep(responseDelayMs);
                }
                String path = exchange.getRequestURI().getPath();
                String symbol = path.substring(path.lastIndexOf('/') + 1);
                Map<String, Object> data = symbolCache.computeIfAbsent(symbol, this::generateMarketData);
                byte[] response = objectMapper.writeValueAsBytes(data);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            }
        });
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public void setResponseDelayMs(long delayMs) {
        this.responseDelayMs = delayMs;
    }

    private Map<String, Object> generateMarketData(String symbol) {
        double basePrice = 50 + random.nextDouble() * 450;
        double open = basePrice * (0.97 + random.nextDouble() * 0.06);
        double dayLow = Math.min(basePrice, open) * (0.95 + random.nextDouble() * 0.05);
        double dayHigh = Math.max(basePrice, open) * (1.0 + random.nextDouble() * 0.05);
        double fiftyTwoWeekLow = basePrice * (0.5 + random.nextDouble() * 0.4);
        double fiftyTwoWeekHigh = basePrice * (1.1 + random.nextDouble() * 0.5);

        return Map.of(
                "symbol", symbol,
                "securityName", symbol + " " + SECURITY_NAMES[Math.abs(symbol.hashCode()) % SECURITY_NAMES.length],
                "currentPrice", Math.round(basePrice * 100.0) / 100.0,
                "open", Math.round(open * 100.0) / 100.0,
                "dayLow", Math.round(dayLow * 100.0) / 100.0,
                "dayHigh", Math.round(dayHigh * 100.0) / 100.0,
                "dailyVolume", 1000000L + random.nextLong(200000000L),
                "fiftyTwoWeekLow", Math.round(fiftyTwoWeekLow * 100.0) / 100.0,
                "fiftyTwoWeekHigh", Math.round(fiftyTwoWeekHigh * 100.0) / 100.0,
                "currency", "USD"
        );
    }
}
