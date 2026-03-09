package com.bank.moo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document(collection = "customers")
public class CustomerDocument {

    @Id
    private String id;
    private String customerId;
    private String firstName;
    private String lastName;
    private ContactPreferences contactPreferences;
    private List<Subscription> subscriptions;

    public CustomerDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public ContactPreferences getContactPreferences() { return contactPreferences; }
    public void setContactPreferences(ContactPreferences contactPreferences) { this.contactPreferences = contactPreferences; }

    public List<Subscription> getSubscriptions() { return subscriptions; }
    public void setSubscriptions(List<Subscription> subscriptions) { this.subscriptions = subscriptions; }
}
