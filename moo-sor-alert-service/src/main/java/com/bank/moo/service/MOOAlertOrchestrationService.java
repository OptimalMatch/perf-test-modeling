package com.bank.moo.service;

import com.bank.moo.model.*;
import com.bank.moo.repository.CustomerRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MOOAlertOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(MOOAlertOrchestrationService.class);
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private final CustomerRepository customerRepository;
    private final MongoTemplate mongoTemplate;
    private final MarketDataClient marketDataClient;
    private final KafkaTemplate<String, CowAlertMessage> kafkaTemplate;

    private final Counter processedCounter;
    private final Counter skippedInactiveCounter;
    private final Counter skippedThrottledCounter;
    private final Counter skippedNotFoundCounter;
    private final Counter failedCounter;
    private final Timer orchestrationTimer;
    private final Timer mongoLookupTimer;
    private final Timer mongoUpdateTimer;
    private final Timer kafkaPublishTimer;

    @Value("${moo.kafka.topic:moo-customer-alerts}")
    private String kafkaTopic;

    public MOOAlertOrchestrationService(
            CustomerRepository customerRepository,
            MongoTemplate mongoTemplate,
            MarketDataClient marketDataClient,
            KafkaTemplate<String, CowAlertMessage> kafkaTemplate,
            MeterRegistry meterRegistry) {
        this.customerRepository = customerRepository;
        this.mongoTemplate = mongoTemplate;
        this.marketDataClient = marketDataClient;
        this.kafkaTemplate = kafkaTemplate;

        this.processedCounter = meterRegistry.counter("moo.alert.processed");
        this.skippedInactiveCounter = meterRegistry.counter("moo.alert.skipped.inactive");
        this.skippedThrottledCounter = meterRegistry.counter("moo.alert.skipped.throttled");
        this.skippedNotFoundCounter = meterRegistry.counter("moo.alert.skipped.not_found");
        this.failedCounter = meterRegistry.counter("moo.alert.failed");
        this.orchestrationTimer = meterRegistry.timer("moo.orchestration.time");
        this.mongoLookupTimer = meterRegistry.timer("moo.mongo.lookup.time");
        this.mongoUpdateTimer = meterRegistry.timer("moo.mongo.update.time");
        this.kafkaPublishTimer = meterRegistry.timer("moo.kafka.publish.time");
    }

    @Async("alertProcessingExecutor")
    public void processAlertAsync(String userId, FactSetAlert alert) {
        orchestrationTimer.record(() -> processAlert(userId, alert));
    }

    private void processAlert(String userId, FactSetAlert alert) {
        try {
            // Step 1: Lookup customer by _id
            CustomerDocument customer = mongoLookupTimer.record(() ->
                    customerRepository.findById(userId).orElse(null));

            if (customer == null) {
                log.debug("Customer not found for userId={}", userId);
                skippedNotFoundCounter.increment();
                return;
            }

            // Step 2: Find matching eligible subscription
            Optional<Subscription> eligibleSub = findEligibleSubscription(customer, alert);
            if (eligibleSub.isEmpty()) {
                return; // counters incremented in findEligibleSubscription
            }

            Subscription subscription = eligibleSub.get();

            // Step 3: Fetch market data (cached)
            MarketData marketData = marketDataClient.getMarketData(alert.getSymbol());
            if (marketData == null) {
                log.warn("Market data unavailable for symbol={}", alert.getSymbol());
                failedCounter.increment();
                return;
            }

            // Step 4: Update dateDelivered BEFORE publishing to Kafka
            mongoUpdateTimer.record(() -> updateDateDelivered(userId, alert.getTriggerId()));

            // Step 5: Build and publish to Kafka
            CowAlertMessage message = buildCowMessage(customer, subscription, alert, marketData);
            kafkaPublishTimer.record(() -> {
                try {
                    kafkaTemplate.send(kafkaTopic, customer.getCustomerId(), message).get();
                } catch (Exception e) {
                    throw new RuntimeException("Kafka publish failed", e);
                }
            });

            processedCounter.increment();
            log.debug("Alert processed: customerId={}, triggerId={}", customer.getCustomerId(), alert.getTriggerId());

        } catch (Exception e) {
            log.error("Failed to process alert for userId={}, triggerId={}: {}",
                    userId, alert.getTriggerId(), e.getMessage());
            failedCounter.increment();
        }
    }

    private Optional<Subscription> findEligibleSubscription(CustomerDocument customer, FactSetAlert alert) {
        if (customer.getSubscriptions() == null) {
            skippedNotFoundCounter.increment();
            return Optional.empty();
        }

        for (Subscription sub : customer.getSubscriptions()) {
            if (!alert.getTriggerId().equals(sub.getFactSetTriggerId())) {
                continue;
            }

            if (!"Y".equals(sub.getActiveState())) {
                log.debug("Subscription inactive: customerId={}, triggerId={}", customer.getCustomerId(), alert.getTriggerId());
                skippedInactiveCounter.increment();
                return Optional.empty();
            }

            if (sub.getDateDelivered() != null) {
                LocalDate deliveredDate = sub.getDateDelivered().atZone(EASTERN).toLocalDate();
                LocalDate today = LocalDate.now(EASTERN);
                if (!deliveredDate.isBefore(today)) {
                    log.debug("Subscription throttled: customerId={}, triggerId={}", customer.getCustomerId(), alert.getTriggerId());
                    skippedThrottledCounter.increment();
                    return Optional.empty();
                }
            }

            return Optional.of(sub);
        }

        log.debug("No matching subscription: customerId={}, triggerId={}", customer.getCustomerId(), alert.getTriggerId());
        skippedNotFoundCounter.increment();
        return Optional.empty();
    }

    private void updateDateDelivered(String userId, String triggerId) {
        Query query = new Query(Criteria.where("_id").is(userId)
                .and("subscriptions").elemMatch(Criteria.where("factSetTriggerId").is(triggerId)));
        Update update = new Update().set("subscriptions.$.dateDelivered", Instant.now());
        mongoTemplate.updateFirst(query, update, CustomerDocument.class);
    }

    private CowAlertMessage buildCowMessage(CustomerDocument customer, Subscription subscription,
                                             FactSetAlert alert, MarketData marketData) {
        CowAlertMessage msg = new CowAlertMessage();
        msg.setCustomerId(customer.getCustomerId());
        msg.setFirstName(customer.getFirstName());
        msg.setLastName(customer.getLastName());
        msg.setSymbol(alert.getSymbol());
        msg.setTriggerTypeId(alert.getTriggerTypeId());
        msg.setValue(alert.getValue());
        msg.setFactSetTriggerId(alert.getTriggerId());
        msg.setTriggeredAt(alert.getTriggeredAt());
        msg.setProcessedAt(Instant.now().toString());
        msg.setSecurityName(marketData.getSecurityName());
        msg.setCurrentPrice(marketData.getCurrentPrice());
        msg.setOpen(marketData.getOpen());
        msg.setDayLow(marketData.getDayLow());
        msg.setDayHigh(marketData.getDayHigh());
        msg.setDailyVolume(marketData.getDailyVolume());
        msg.setFiftyTwoWeekLow(marketData.getFiftyTwoWeekLow());
        msg.setFiftyTwoWeekHigh(marketData.getFiftyTwoWeekHigh());
        msg.setCurrency(marketData.getCurrency());

        if (customer.getContactPreferences() != null && customer.getContactPreferences().getChannels() != null) {
            List<ChannelPreference> enabledChannels = customer.getContactPreferences().getChannels().stream()
                    .filter(ChannelPreference::isEnabled)
                    .collect(Collectors.toList());
            msg.setChannels(enabledChannels);
        }

        return msg;
    }
}
