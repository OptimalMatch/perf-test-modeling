package com.bank.moo.mockmarket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/marketdata")
public class MarketDataController {

    @Value("${mock.response-delay-ms:0}")
    private long responseDelayMs;

    private final Map<String, Map<String, Object>> symbolCache = new ConcurrentHashMap<>();
    private final Random random = new Random(42);

    private static final String[] SECURITY_NAMES = {
            "Inc.", "Corp.", "Ltd.", "Group", "Holdings", "Technologies",
            "Pharmaceuticals", "Financial", "Energy", "Communications"
    };

    @GetMapping("/{symbol}")
    public Map<String, Object> getMarketData(@PathVariable String symbol) throws InterruptedException {
        if (responseDelayMs > 0) {
            Thread.sleep(responseDelayMs);
        }

        return symbolCache.computeIfAbsent(symbol, this::generateMarketData);
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
