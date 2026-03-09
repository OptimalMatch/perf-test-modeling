package com.bank.wid.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FactSetAlert {

    private String triggerId;
    private String triggerTypeId;
    private String symbol;
    private String value;
    private String triggeredAt;

    public FactSetAlert() {}

    public FactSetAlert(String triggerId, String triggerTypeId, String symbol, String value, String triggeredAt) {
        this.triggerId = triggerId;
        this.triggerTypeId = triggerTypeId;
        this.symbol = symbol;
        this.value = value;
        this.triggeredAt = triggeredAt;
    }

    public String getTriggerId() { return triggerId; }
    public void setTriggerId(String triggerId) { this.triggerId = triggerId; }

    public String getTriggerTypeId() { return triggerTypeId; }
    public void setTriggerTypeId(String triggerTypeId) { this.triggerTypeId = triggerTypeId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(String triggeredAt) { this.triggeredAt = triggeredAt; }
}
