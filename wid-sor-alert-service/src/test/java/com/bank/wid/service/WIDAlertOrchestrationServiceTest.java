package com.bank.wid.service;

import com.bank.wid.model.*;
import com.bank.wid.repository.CustomerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WIDAlertOrchestrationServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private MarketDataClient marketDataClient;
    @Mock private KafkaTemplate<String, DSPAlertMessage> kafkaTemplate;

    private WIDAlertOrchestrationService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new WIDAlertOrchestrationService(
                customerRepository, mongoTemplate, marketDataClient, kafkaTemplate, meterRegistry);
        ReflectionTestUtils.setField(service, "kafkaTopic", "wid-customer-alerts");
    }

    @Test
    void shouldProcessEligibleAlert() {
        // Given
        String userId = "64a7f3b2c1d4e5f6a7b8c9d0";
        FactSetAlert alert = new FactSetAlert("FS-TRIG-88421", "6", "AAPL", "-5.2", "2026-02-23T14:31:58.112Z");

        CustomerDocument customer = buildCustomer(userId, "Y", null);
        when(customerRepository.findById(userId)).thenReturn(Optional.of(customer));

        MarketData marketData = buildMarketData();
        when(marketDataClient.getMarketData("AAPL")).thenReturn(marketData);

        CompletableFuture<SendResult<String, DSPAlertMessage>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), any(DSPAlertMessage.class))).thenReturn(future);

        // When
        service.processAlertAsync(userId, alert);

        // Then (allow async to complete — since @Async won't work in unit test, it runs synchronously)
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(CustomerDocument.class));
        verify(kafkaTemplate).send(eq("wid-customer-alerts"), eq("CUST-9938271"), any(DSPAlertMessage.class));
    }

    @Test
    void shouldSkipInactiveSubscription() {
        String userId = "64a7f3b2c1d4e5f6a7b8c9d0";
        FactSetAlert alert = new FactSetAlert("FS-TRIG-88421", "6", "AAPL", "-5.2", "2026-02-23T14:31:58.112Z");

        CustomerDocument customer = buildCustomer(userId, "N", null);
        when(customerRepository.findById(userId)).thenReturn(Optional.of(customer));

        service.processAlertAsync(userId, alert);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(DSPAlertMessage.class));
        assertEquals(1.0, meterRegistry.counter("wid.alert.skipped.inactive").count());
    }

    @Test
    void shouldThrottleAlreadyDeliveredToday() {
        String userId = "64a7f3b2c1d4e5f6a7b8c9d0";
        FactSetAlert alert = new FactSetAlert("FS-TRIG-88421", "6", "AAPL", "-5.2", "2026-02-23T14:31:58.112Z");

        // Delivered today
        Instant todayDelivery = LocalDate.now(ZoneId.of("America/New_York"))
                .atStartOfDay(ZoneId.of("America/New_York")).toInstant().plusSeconds(3600);
        CustomerDocument customer = buildCustomer(userId, "Y", todayDelivery);
        when(customerRepository.findById(userId)).thenReturn(Optional.of(customer));

        service.processAlertAsync(userId, alert);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(DSPAlertMessage.class));
        assertEquals(1.0, meterRegistry.counter("wid.alert.skipped.throttled").count());
    }

    @Test
    void shouldAllowRedeliveryAfterPreviousDay() {
        String userId = "64a7f3b2c1d4e5f6a7b8c9d0";
        FactSetAlert alert = new FactSetAlert("FS-TRIG-88421", "6", "AAPL", "-5.2", "2026-02-23T14:31:58.112Z");

        // Delivered yesterday
        Instant yesterdayDelivery = LocalDate.now(ZoneId.of("America/New_York"))
                .minusDays(1).atStartOfDay(ZoneId.of("America/New_York")).toInstant().plusSeconds(3600);
        CustomerDocument customer = buildCustomer(userId, "Y", yesterdayDelivery);
        when(customerRepository.findById(userId)).thenReturn(Optional.of(customer));

        MarketData marketData = buildMarketData();
        when(marketDataClient.getMarketData("AAPL")).thenReturn(marketData);

        CompletableFuture<SendResult<String, DSPAlertMessage>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), any(DSPAlertMessage.class))).thenReturn(future);

        service.processAlertAsync(userId, alert);

        verify(kafkaTemplate).send(eq("wid-customer-alerts"), eq("CUST-9938271"), any(DSPAlertMessage.class));
    }

    @Test
    void shouldSkipWhenCustomerNotFound() {
        String userId = "nonexistent";
        FactSetAlert alert = new FactSetAlert("FS-TRIG-88421", "6", "AAPL", "-5.2", "2026-02-23T14:31:58.112Z");

        when(customerRepository.findById(userId)).thenReturn(Optional.empty());

        service.processAlertAsync(userId, alert);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(DSPAlertMessage.class));
        assertEquals(1.0, meterRegistry.counter("wid.alert.skipped.not_found").count());
    }

    @Test
    void shouldBuildCorrectDSPMessage() {
        String userId = "64a7f3b2c1d4e5f6a7b8c9d0";
        FactSetAlert alert = new FactSetAlert("FS-TRIG-88421", "6", "AAPL", "-5.2", "2026-02-23T14:31:58.112Z");

        CustomerDocument customer = buildCustomer(userId, "Y", null);
        when(customerRepository.findById(userId)).thenReturn(Optional.of(customer));

        MarketData marketData = buildMarketData();
        when(marketDataClient.getMarketData("AAPL")).thenReturn(marketData);

        CompletableFuture<SendResult<String, DSPAlertMessage>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), any(DSPAlertMessage.class))).thenReturn(future);

        service.processAlertAsync(userId, alert);

        ArgumentCaptor<DSPAlertMessage> captor = ArgumentCaptor.forClass(DSPAlertMessage.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());

        DSPAlertMessage msg = captor.getValue();
        assertEquals("CUST-9938271", msg.getCustomerId());
        assertEquals("Margaret", msg.getFirstName());
        assertEquals("AAPL", msg.getSymbol());
        assertEquals("Apple Inc.", msg.getSecurityName());
        assertEquals(236.21, msg.getCurrentPrice());
        assertNotNull(msg.getProcessedAt());
        assertEquals(2, msg.getChannels().size()); // only enabled channels
    }

    private CustomerDocument buildCustomer(String id, String activeState, Instant dateDelivered) {
        CustomerDocument customer = new CustomerDocument();
        customer.setId(id);
        customer.setCustomerId("CUST-9938271");
        customer.setFirstName("Margaret");
        customer.setLastName("Thornton");

        Subscription sub = new Subscription();
        sub.setSymbol("AAPL");
        sub.setFactSetTriggerId("FS-TRIG-88421");
        sub.setTriggerTypeId("6");
        sub.setValue("-5");
        sub.setActiveState(activeState);
        sub.setSubscribedAt(Instant.parse("2025-09-15T10:00:00.000Z"));
        sub.setDateDelivered(dateDelivered);
        customer.setSubscriptions(List.of(sub));

        ChannelPreference push = new ChannelPreference();
        push.setType("PUSH_NOTIFICATION");
        push.setEnabled(true);
        push.setPriority(1);

        ChannelPreference email = new ChannelPreference();
        email.setType("EMAIL");
        email.setEnabled(true);
        email.setPriority(2);
        email.setAddress("m.thornton@email.com");

        ChannelPreference sms = new ChannelPreference();
        sms.setType("SMS");
        sms.setEnabled(false);
        sms.setPriority(3);

        ContactPreferences prefs = new ContactPreferences();
        prefs.setChannels(Arrays.asList(push, email, sms));
        customer.setContactPreferences(prefs);

        return customer;
    }

    private MarketData buildMarketData() {
        MarketData md = new MarketData();
        md.setSymbol("AAPL");
        md.setSecurityName("Apple Inc.");
        md.setCurrentPrice(236.21);
        md.setOpen(248.50);
        md.setDayLow(234.88);
        md.setDayHigh(249.10);
        md.setDailyVolume(89542100);
        md.setFiftyTwoWeekLow(164.08);
        md.setFiftyTwoWeekHigh(252.87);
        md.setCurrency("USD");
        return md;
    }
}
