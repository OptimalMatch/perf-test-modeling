package com.bank.moo.service;

import com.bank.moo.model.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
@Profile("oracle")
public class OracleCustomerDataService implements CustomerDataService {

    private static final String LOOKUP_SQL = """
            SELECT c.ID, c.CUSTOMER_ID, c.FIRST_NAME, c.LAST_NAME,
                   s.FACTSET_TRIGGER_ID, s.SYMBOL, s.TRIGGER_TYPE_ID,
                   s.TRIGGER_VALUE, s.ACTIVE_STATE, s.SUBSCRIBED_AT, s.DATE_DELIVERED,
                   ch.CHANNEL_TYPE, ch.ENABLED, ch.PRIORITY, ch.ADDRESS, ch.PHONE_NUMBER
            FROM CUSTOMERS c
            LEFT JOIN CUSTOMER_SUBSCRIPTIONS s ON c.ID = s.CUSTOMER_ID
            LEFT JOIN CHANNEL_PREFERENCES ch ON c.ID = ch.CUSTOMER_ID
            WHERE c.ID = ?
            """;

    private static final String UPDATE_SQL =
            "UPDATE CUSTOMER_SUBSCRIPTIONS SET DATE_DELIVERED = ? WHERE CUSTOMER_ID = ? AND FACTSET_TRIGGER_ID = ?";

    private final JdbcTemplate jdbcTemplate;

    public OracleCustomerDataService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CustomerDocument> findByUserId(String userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(LOOKUP_SQL, userId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mapRowsToCustomerDocument(rows));
    }

    @Override
    public void updateDateDelivered(String userId, String triggerId) {
        jdbcTemplate.update(UPDATE_SQL, Timestamp.from(Instant.now()), userId, triggerId);
    }

    private CustomerDocument mapRowsToCustomerDocument(List<Map<String, Object>> rows) {
        Map<String, Object> first = rows.get(0);

        CustomerDocument doc = new CustomerDocument();
        doc.setId((String) first.get("ID"));
        doc.setCustomerId((String) first.get("CUSTOMER_ID"));
        doc.setFirstName((String) first.get("FIRST_NAME"));
        doc.setLastName((String) first.get("LAST_NAME"));

        // Deduplicate subscriptions and channels from the Cartesian product rows
        Map<String, Subscription> subsMap = new LinkedHashMap<>();
        Map<String, ChannelPreference> chansMap = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            String triggerId = (String) row.get("FACTSET_TRIGGER_ID");
            if (triggerId != null && !subsMap.containsKey(triggerId)) {
                Subscription sub = new Subscription();
                sub.setSymbol((String) row.get("SYMBOL"));
                sub.setFactSetTriggerId(triggerId);
                sub.setTriggerTypeId((String) row.get("TRIGGER_TYPE_ID"));
                sub.setValue((String) row.get("TRIGGER_VALUE"));
                sub.setActiveState((String) row.get("ACTIVE_STATE"));
                Timestamp subscribedAt = (Timestamp) row.get("SUBSCRIBED_AT");
                if (subscribedAt != null) sub.setSubscribedAt(subscribedAt.toInstant());
                Timestamp dateDelivered = (Timestamp) row.get("DATE_DELIVERED");
                if (dateDelivered != null) sub.setDateDelivered(dateDelivered.toInstant());
                subsMap.put(triggerId, sub);
            }

            String chanType = (String) row.get("CHANNEL_TYPE");
            if (chanType != null && !chansMap.containsKey(chanType)) {
                ChannelPreference pref = new ChannelPreference();
                pref.setType(chanType);
                Number enabled = (Number) row.get("ENABLED");
                pref.setEnabled(enabled != null && enabled.intValue() == 1);
                Number priority = (Number) row.get("PRIORITY");
                pref.setPriority(priority != null ? priority.intValue() : 0);
                pref.setAddress((String) row.get("ADDRESS"));
                pref.setPhoneNumber((String) row.get("PHONE_NUMBER"));
                chansMap.put(chanType, pref);
            }
        }

        doc.setSubscriptions(new ArrayList<>(subsMap.values()));

        if (!chansMap.isEmpty()) {
            ContactPreferences prefs = new ContactPreferences();
            prefs.setChannels(new ArrayList<>(chansMap.values()));
            doc.setContactPreferences(prefs);
        }

        return doc;
    }
}
