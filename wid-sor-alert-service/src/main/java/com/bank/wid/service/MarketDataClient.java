package com.bank.wid.service;

import com.bank.wid.model.MarketData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

@Service
public class MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final Timer fetchTimer;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    @Value("${wid.market-data.base-url:http://localhost:8081}")
    private String baseUrl;

    @Value("${wid.market-data.cache.max-size:5000}")
    private int cacheMaxSize;

    @Value("${wid.market-data.cache.ttl-seconds:30}")
    private int cacheTtlSeconds;

    private Cache<String, MarketData> cache;

    public MarketDataClient(MeterRegistry meterRegistry) {
        this.fetchTimer = meterRegistry.timer("wid.marketdata.fetch.time");
        this.cacheHitCounter = meterRegistry.counter("wid.marketdata.cache.hit");
        this.cacheMissCounter = meterRegistry.counter("wid.marketdata.cache.miss");
    }

    @PostConstruct
    public void initCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(cacheMaxSize)
                .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
                .build();
    }

    public MarketData getMarketData(String symbol) {
        MarketData cached = cache.getIfPresent(symbol);
        if (cached != null) {
            cacheHitCounter.increment();
            return cached;
        }

        cacheMissCounter.increment();
        MarketData data = fetchTimer.record(() -> {
            String url = baseUrl + "/api/v1/marketdata/" + symbol;
            return restTemplate.getForObject(url, MarketData.class);
        });

        if (data != null) {
            cache.put(symbol, data);
        }
        return data;
    }
}
