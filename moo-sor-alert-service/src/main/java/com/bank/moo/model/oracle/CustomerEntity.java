package com.bank.moo.model.oracle;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "CUSTOMERS")
public class CustomerEntity {

    @Id
    @Column(name = "ID", length = 24)
    private String id;

    @Column(name = "CUSTOMER_ID", length = 20, nullable = false)
    private String customerId;

    @Column(name = "FIRST_NAME", length = 50)
    private String firstName;

    @Column(name = "LAST_NAME", length = 50)
    private String lastName;

    @OneToMany(mappedBy = "customer", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<SubscriptionEntity> subscriptions;

    @OneToMany(mappedBy = "customer", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<ChannelPreferenceEntity> channelPreferences;

    public CustomerEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public List<SubscriptionEntity> getSubscriptions() { return subscriptions; }
    public void setSubscriptions(List<SubscriptionEntity> subscriptions) { this.subscriptions = subscriptions; }

    public List<ChannelPreferenceEntity> getChannelPreferences() { return channelPreferences; }
    public void setChannelPreferences(List<ChannelPreferenceEntity> channelPreferences) { this.channelPreferences = channelPreferences; }
}
