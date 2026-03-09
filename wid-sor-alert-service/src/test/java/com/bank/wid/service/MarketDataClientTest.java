package com.bank.wid.service;

import com.bank.wid.mock.MockMarketDataServer;
import com.bank.wid.model.MarketData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataClientTest {

    private static MockMarketDataServer mockServer;
    private MarketDataClient client;
    private SimpleMeterRegistry meterRegistry;

    @BeforeAll
    static void startMockServer() throws IOException {
        mockServer = new MockMarketDataServer(18081);
        mockServer.start();
    }

    @AfterAll
    static void stopMockServer() {
        mockServer.stop();
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        client = new MarketDataClient(meterRegistry);
        ReflectionTestUtils.setField(client, "baseUrl", "http://localhost:18081");
        ReflectionTestUtils.setField(client, "cacheMaxSize", 5000);
        ReflectionTestUtils.setField(client, "cacheTtlSeconds", 30);
        client.initCache();
    }

    @Test
    void shouldFetchMarketData() {
        MarketData data = client.getMarketData("AAPL");
        assertNotNull(data);
        assertEquals("AAPL", data.getSymbol());
        assertNotNull(data.getSecurityName());
        assertTrue(data.getCurrentPrice() > 0);
        assertEquals("USD", data.getCurrency());
    }

    @Test
    void shouldCacheMarketData() {
        // First call: cache miss
        client.getMarketData("MSFT");
        assertEquals(1.0, meterRegistry.counter("wid.marketdata.cache.miss").count());
        assertEquals(0.0, meterRegistry.counter("wid.marketdata.cache.hit").count());

        // Second call: cache hit
        client.getMarketData("MSFT");
        assertEquals(1.0, meterRegistry.counter("wid.marketdata.cache.miss").count());
        assertEquals(1.0, meterRegistry.counter("wid.marketdata.cache.hit").count());

        // Third call: still cache hit
        client.getMarketData("MSFT");
        assertEquals(1.0, meterRegistry.counter("wid.marketdata.cache.miss").count());
        assertEquals(2.0, meterRegistry.counter("wid.marketdata.cache.hit").count());
    }

    @Test
    void shouldReturnConsistentDataFromCache() {
        MarketData first = client.getMarketData("GOOGL");
        MarketData second = client.getMarketData("GOOGL");

        assertEquals(first.getCurrentPrice(), second.getCurrentPrice());
        assertEquals(first.getSecurityName(), second.getSecurityName());
    }
}
