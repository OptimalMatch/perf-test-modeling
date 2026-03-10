package com.bank.moo.service;

import com.bank.moo.model.CustomerDocument;

import java.util.Optional;

/**
 * Abstracts customer data access to allow swapping between MongoDB and Oracle (or other stores).
 * Both implementations return the same domain model for compatibility with the orchestration service.
 */
public interface CustomerDataService {

    Optional<CustomerDocument> findByUserId(String userId);

    void updateDateDelivered(String userId, String triggerId);
}
