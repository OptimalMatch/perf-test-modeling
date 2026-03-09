package com.bank.wid.model;

import java.util.List;

public class DSPAlertMessage {

    private String customerId;
    private String firstName;
    private String lastName;
    private String symbol;
    private String triggerTypeId;
    private String value;
    private String factSetTriggerId;
    private String triggeredAt;
    private String processedAt;
    private String securityName;
    private double currentPrice;
    private double open;
    private double dayLow;
    private double dayHigh;
    private long dailyVolume;
    private double fiftyTwoWeekLow;
    private double fiftyTwoWeekHigh;
    private String currency;
    private List<ChannelPreference> channels;

    public DSPAlertMessage() {}

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getTriggerTypeId() { return triggerTypeId; }
    public void setTriggerTypeId(String triggerTypeId) { this.triggerTypeId = triggerTypeId; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getFactSetTriggerId() { return factSetTriggerId; }
    public void setFactSetTriggerId(String factSetTriggerId) { this.factSetTriggerId = factSetTriggerId; }

    public String getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(String triggeredAt) { this.triggeredAt = triggeredAt; }

    public String getProcessedAt() { return processedAt; }
    public void setProcessedAt(String processedAt) { this.processedAt = processedAt; }

    public String getSecurityName() { return securityName; }
    public void setSecurityName(String securityName) { this.securityName = securityName; }

    public double getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(double currentPrice) { this.currentPrice = currentPrice; }

    public double getOpen() { return open; }
    public void setOpen(double open) { this.open = open; }

    public double getDayLow() { return dayLow; }
    public void setDayLow(double dayLow) { this.dayLow = dayLow; }

    public double getDayHigh() { return dayHigh; }
    public void setDayHigh(double dayHigh) { this.dayHigh = dayHigh; }

    public long getDailyVolume() { return dailyVolume; }
    public void setDailyVolume(long dailyVolume) { this.dailyVolume = dailyVolume; }

    public double getFiftyTwoWeekLow() { return fiftyTwoWeekLow; }
    public void setFiftyTwoWeekLow(double fiftyTwoWeekLow) { this.fiftyTwoWeekLow = fiftyTwoWeekLow; }

    public double getFiftyTwoWeekHigh() { return fiftyTwoWeekHigh; }
    public void setFiftyTwoWeekHigh(double fiftyTwoWeekHigh) { this.fiftyTwoWeekHigh = fiftyTwoWeekHigh; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public List<ChannelPreference> getChannels() { return channels; }
    public void setChannels(List<ChannelPreference> channels) { this.channels = channels; }
}
