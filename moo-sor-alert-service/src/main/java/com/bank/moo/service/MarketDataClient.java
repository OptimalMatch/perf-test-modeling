package com.bank.moo.service;

import com.bank.moo.model.MarketData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final Timer fetchTimer;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter circuitOpenCounter;
    private final CircuitBreaker circuitBreaker;

    @Value("${moo.market-data.base-url:http://localhost:8081}")
    private String baseUrl;

    @Value("${moo.market-data.cache.max-size:5000}")
    private int cacheMaxSize;

    @Value("${moo.market-data.cache.ttl-seconds:30}")
    private int cacheTtlSeconds;

    private Cache<String, MarketData> cache;

    public MarketDataClient(MeterRegistry meterRegistry, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.fetchTimer = meterRegistry.timer("moo.marketdata.fetch.time");
        this.cacheHitCounter = meterRegistry.counter("moo.marketdata.cache.hit");
        this.cacheMissCounter = meterRegistry.counter("moo.marketdata.cache.miss");
        this.circuitOpenCounter = meterRegistry.counter("moo.marketdata.circuit.rejected");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("marketData");
    }

    @PostConstruct
    public void initCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(cacheMaxSize)
                .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
                .build();
    }

    public MarketData getMarketData(String symbol) {
        // Check cache first — cache is outside the circuit breaker
        MarketData cached = cache.getIfPresent(symbol);
        if (cached != null) {
            cacheHitCounter.increment();
            return cached;
        }

        cacheMissCounter.increment();

        // Circuit breaker wraps only the HTTP call
        try {
            Supplier<MarketData> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () ->
                    fetchTimer.record(() -> {
                        String url = baseUrl + "/api/v1/marketdata/" + symbol;
                        return restTemplate.getForObject(url, MarketData.class);
                    })
            );

            MarketData data = decoratedSupplier.get();
            if (data != null) {
                cache.put(symbol, data);
            }
            return data;

        } catch (CallNotPermittedException e) {
            // Circuit is OPEN — try to serve stale cached data
            circuitOpenCounter.increment();
            MarketData stale = cache.getIfPresent(symbol);
            if (stale != null) {
                log.debug("Market data circuit OPEN, serving stale cache for symbol={}", symbol);
                return stale;
            }
            log.warn("Market data circuit OPEN and no cached data for symbol={}", symbol);
            return null;
        } catch (Exception e) {
            log.error("Market data fetch failed for symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
