package com.bank.moo.repository;

import com.bank.moo.model.FlatAlertDocument;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Profile("mongo-flat")
public interface FlatAlertRepository extends MongoRepository<FlatAlertDocument, String> {

    List<FlatAlertDocument> findByCustomerId(String customerId);
}
