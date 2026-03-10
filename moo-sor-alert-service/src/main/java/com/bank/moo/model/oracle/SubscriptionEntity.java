package com.bank.moo.model.oracle;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "CUSTOMER_SUBSCRIPTIONS", indexes = {
        @Index(name = "IDX_SUB_CUSTOMER_ID", columnList = "CUSTOMER_ID"),
        @Index(name = "IDX_SUB_TRIGGER_ID", columnList = "FACTSET_TRIGGER_ID")
})
public class SubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CUSTOMER_ID", referencedColumnName = "ID", nullable = false)
    private CustomerEntity customer;

    @Column(name = "SYMBOL", length = 10, nullable = false)
    private String symbol;

    @Column(name = "FACTSET_TRIGGER_ID", length = 20, nullable = false)
    private String factSetTriggerId;

    @Column(name = "TRIGGER_TYPE_ID", length = 5, nullable = false)
    private String triggerTypeId;

    @Column(name = "TRIGGER_VALUE", length = 10)
    private String value;

    @Column(name = "ACTIVE_STATE", length = 1, nullable = false)
    private String activeState;

    @Column(name = "SUBSCRIBED_AT")
    private Instant subscribedAt;

    @Column(name = "DATE_DELIVERED")
    private Instant dateDelivered;

    public SubscriptionEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public CustomerEntity getCustomer() { return customer; }
    public void setCustomer(CustomerEntity customer) { this.customer = customer; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getFactSetTriggerId() { return factSetTriggerId; }
    public void setFactSetTriggerId(String factSetTriggerId) { this.factSetTriggerId = factSetTriggerId; }

    public String getTriggerTypeId() { return triggerTypeId; }
    public void setTriggerTypeId(String triggerTypeId) { this.triggerTypeId = triggerTypeId; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getActiveState() { return activeState; }
    public void setActiveState(String activeState) { this.activeState = activeState; }

    public Instant getSubscribedAt() { return subscribedAt; }
    public void setSubscribedAt(Instant subscribedAt) { this.subscribedAt = subscribedAt; }

    public Instant getDateDelivered() { return dateDelivered; }
    public void setDateDelivered(Instant dateDelivered) { this.dateDelivered = dateDelivered; }
}
