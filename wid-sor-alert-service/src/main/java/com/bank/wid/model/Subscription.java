package com.bank.wid.model;

import java.time.Instant;

public class Subscription {

    private String symbol;
    private String factSetTriggerId;
    private String triggerTypeId;
    private String value;
    private String activeState;
    private Instant subscribedAt;
    private Instant dateDelivered;

    public Subscription() {}

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
