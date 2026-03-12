package com.bank.moo.service;

import com.bank.moo.model.*;
import com.bank.moo.repository.FlatAlertRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Flat MongoDB implementation: one document per subscription.
 * Queries multiple docs by customerId and reassembles into CustomerDocument
 * for compatibility with the orchestration service.
 */
@Service
@Profile("mongo-flat")
public class MongoFlatCustomerDataService implements CustomerDataService {

    private final FlatAlertRepository flatAlertRepository;
    private final MongoTemplate mongoTemplate;

    public MongoFlatCustomerDataService(FlatAlertRepository flatAlertRepository, MongoTemplate mongoTemplate) {
        this.flatAlertRepository = flatAlertRepository;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<CustomerDocument> findByUserId(String userId) {
        List<FlatAlertDocument> docs = flatAlertRepository.findByCustomerId(userId);
        if (docs.isEmpty()) {
            return Optional.empty();
        }

        // Reassemble into CustomerDocument from the flat docs
        FlatAlertDocument first = docs.get(0);

        CustomerDocument customer = new CustomerDocument();
        customer.setId(first.getCustomerId());
        customer.setCustomerId(first.getCustomerCode());
        customer.setFirstName(first.getFirstName());
        customer.setLastName(first.getLastName());

        ContactPreferences prefs = new ContactPreferences();
        prefs.setChannels(first.getChannels());
        customer.setContactPreferences(prefs);

        List<Subscription> subscriptions = docs.stream().map(doc -> {
            Subscription sub = new Subscription();
            sub.setSymbol(doc.getSymbol());
            sub.setFactSetTriggerId(doc.getFactSetTriggerId());
            sub.setTriggerTypeId(doc.getTriggerTypeId());
            sub.setValue(doc.getValue());
            sub.setActiveState(doc.getActiveState());
            sub.setSubscribedAt(doc.getSubscribedAt());
            sub.setDateDelivered(doc.getDateDelivered());
            return sub;
        }).collect(Collectors.toList());

        customer.setSubscriptions(subscriptions);
        return Optional.of(customer);
    }

    @Override
    public void updateDateDelivered(String userId, String triggerId) {
        // Direct update on a single document — no positional array operator needed
        Query query = new Query(Criteria.where("customerId").is(userId)
                .and("factSetTriggerId").is(triggerId));
        Update update = new Update().set("dateDelivered", Instant.now());
        mongoTemplate.updateFirst(query, update, FlatAlertDocument.class);
    }
}
