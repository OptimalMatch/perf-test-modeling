package com.bank.moo.repository;

import com.bank.moo.model.oracle.CustomerEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@Profile("oracle")
public interface OracleCustomerRepository extends JpaRepository<CustomerEntity, String> {
}
