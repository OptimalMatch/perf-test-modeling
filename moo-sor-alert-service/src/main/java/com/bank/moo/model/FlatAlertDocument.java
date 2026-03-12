package com.bank.moo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Flat MongoDB model: one document per subscription per customer.
 * Customer info and channel preferences are denormalized into each document.
 * Trade-off: simpler updates (no positional array ops), but reads require
 * querying multiple docs and reassembling.
 */
@Document(collection = "customer_alerts")
@CompoundIndexes({
        @CompoundIndex(name = "idx_customer_trigger", def = "{'customerId': 1, 'factSetTriggerId': 1}", unique = true),
        @CompoundIndex(name = "idx_customer", def = "{'customerId': 1}")
})
public class FlatAlertDocument {

    @Id
    private String id;

    // Customer fields (denormalized)
    private String customerId;  // original ObjectId hex — used as lookup key
    private String customerCode; // e.g. "CUST-0000001"
    private String firstName;
    private String lastName;
    private List<ChannelPreference> channels;

    // Subscription fields (one per document)
    private String symbol;
    private String factSetTriggerId;
    private String triggerTypeId;
    private String value;
    private String activeState;
    private Instant subscribedAt;
    private Instant dateDelivered;

    public FlatAlertDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getCustomerCode() { return customerCode; }
    public void setCustomerCode(String customerCode) { this.customerCode = customerCode; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public List<ChannelPreference> getChannels() { return channels; }
    public void setChannels(List<ChannelPreference> channels) { this.channels = channels; }

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
