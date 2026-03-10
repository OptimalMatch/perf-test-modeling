package com.bank.moo.repository;

import com.bank.moo.model.oracle.SubscriptionEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
@Profile("oracle")
public interface OracleSubscriptionRepository extends JpaRepository<SubscriptionEntity, Long> {

    @Modifying
    @Transactional
    @Query("UPDATE SubscriptionEntity s SET s.dateDelivered = :now " +
           "WHERE s.customer.id = :customerId AND s.factSetTriggerId = :triggerId")
    int updateDateDelivered(@Param("customerId") String customerId,
                            @Param("triggerId") String triggerId,
                            @Param("now") Instant now);
}
