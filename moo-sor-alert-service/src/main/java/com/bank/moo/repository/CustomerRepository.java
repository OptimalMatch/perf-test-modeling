package com.bank.moo.repository;

import com.bank.moo.model.CustomerDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!oracle")
public interface CustomerRepository extends MongoRepository<CustomerDocument, String> {
}
