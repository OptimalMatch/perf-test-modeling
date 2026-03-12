package com.bank.moo.service;

import com.bank.moo.model.CustomerDocument;
import com.bank.moo.repository.CustomerRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@Profile("!oracle & !mongo-flat")
public class MongoCustomerDataService implements CustomerDataService {

    private final CustomerRepository customerRepository;
    private final MongoTemplate mongoTemplate;

    public MongoCustomerDataService(CustomerRepository customerRepository, MongoTemplate mongoTemplate) {
        this.customerRepository = customerRepository;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<CustomerDocument> findByUserId(String userId) {
        return customerRepository.findById(userId);
    }

    @Override
    public void updateDateDelivered(String userId, String triggerId) {
        Query query = new Query(Criteria.where("_id").is(userId)
                .and("subscriptions").elemMatch(Criteria.where("factSetTriggerId").is(triggerId)));
        Update update = new Update().set("subscriptions.$.dateDelivered", Instant.now());
        mongoTemplate.updateFirst(query, update, CustomerDocument.class);
    }
}
