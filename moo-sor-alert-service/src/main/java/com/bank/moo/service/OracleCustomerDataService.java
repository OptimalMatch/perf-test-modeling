package com.bank.moo.service;

import com.bank.moo.model.*;
import com.bank.moo.model.oracle.ChannelPreferenceEntity;
import com.bank.moo.model.oracle.CustomerEntity;
import com.bank.moo.model.oracle.SubscriptionEntity;
import com.bank.moo.repository.OracleCustomerRepository;
import com.bank.moo.repository.OracleSubscriptionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Profile("oracle")
public class OracleCustomerDataService implements CustomerDataService {

    private final OracleCustomerRepository customerRepository;
    private final OracleSubscriptionRepository subscriptionRepository;

    public OracleCustomerDataService(OracleCustomerRepository customerRepository,
                                     OracleSubscriptionRepository subscriptionRepository) {
        this.customerRepository = customerRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Override
    public Optional<CustomerDocument> findByUserId(String userId) {
        return customerRepository.findById(userId).map(this::toCustomerDocument);
    }

    @Override
    public void updateDateDelivered(String userId, String triggerId) {
        subscriptionRepository.updateDateDelivered(userId, triggerId, Instant.now());
    }

    private CustomerDocument toCustomerDocument(CustomerEntity entity) {
        CustomerDocument doc = new CustomerDocument();
        doc.setId(entity.getId());
        doc.setCustomerId(entity.getCustomerId());
        doc.setFirstName(entity.getFirstName());
        doc.setLastName(entity.getLastName());

        if (entity.getSubscriptions() != null) {
            List<Subscription> subs = entity.getSubscriptions().stream()
                    .map(this::toSubscription)
                    .collect(Collectors.toList());
            doc.setSubscriptions(subs);
        }

        if (entity.getChannelPreferences() != null) {
            List<ChannelPreference> channels = entity.getChannelPreferences().stream()
                    .map(this::toChannelPreference)
                    .collect(Collectors.toList());
            ContactPreferences prefs = new ContactPreferences();
            prefs.setChannels(channels);
            doc.setContactPreferences(prefs);
        }

        return doc;
    }

    private Subscription toSubscription(SubscriptionEntity entity) {
        Subscription sub = new Subscription();
        sub.setSymbol(entity.getSymbol());
        sub.setFactSetTriggerId(entity.getFactSetTriggerId());
        sub.setTriggerTypeId(entity.getTriggerTypeId());
        sub.setValue(entity.getValue());
        sub.setActiveState(entity.getActiveState());
        sub.setSubscribedAt(entity.getSubscribedAt());
        sub.setDateDelivered(entity.getDateDelivered());
        return sub;
    }

    private ChannelPreference toChannelPreference(ChannelPreferenceEntity entity) {
        ChannelPreference pref = new ChannelPreference();
        pref.setType(entity.getType());
        pref.setEnabled(entity.isEnabled());
        pref.setPriority(entity.getPriority());
        pref.setAddress(entity.getAddress());
        pref.setPhoneNumber(entity.getPhoneNumber());
        return pref;
    }
}
