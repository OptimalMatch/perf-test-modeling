package com.bank.moo.model.oracle;

import jakarta.persistence.*;

@Entity
@Table(name = "CHANNEL_PREFERENCES", indexes = {
        @Index(name = "IDX_CHAN_CUSTOMER_ID", columnList = "CUSTOMER_ID")
})
public class ChannelPreferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CUSTOMER_ID", referencedColumnName = "ID", nullable = false)
    private CustomerEntity customer;

    @Column(name = "CHANNEL_TYPE", length = 30, nullable = false)
    private String type;

    @Column(name = "ENABLED", nullable = false)
    private boolean enabled;

    @Column(name = "PRIORITY")
    private int priority;

    @Column(name = "ADDRESS", length = 100)
    private String address;

    @Column(name = "PHONE_NUMBER", length = 20)
    private String phoneNumber;

    public ChannelPreferenceEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public CustomerEntity getCustomer() { return customer; }
    public void setCustomer(CustomerEntity customer) { this.customer = customer; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
}
