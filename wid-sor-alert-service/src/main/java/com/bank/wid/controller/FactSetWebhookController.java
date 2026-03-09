package com.bank.wid.controller;

import com.bank.wid.model.FactSetAlert;
import com.bank.wid.service.WIDAlertOrchestrationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/alerts/factset")
public class FactSetWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FactSetWebhookController.class);

    private final WIDAlertOrchestrationService orchestrationService;
    private final Counter receivedCounter;
    private final Timer responseTimer;

    public FactSetWebhookController(WIDAlertOrchestrationService orchestrationService, MeterRegistry meterRegistry) {
        this.orchestrationService = orchestrationService;
        this.receivedCounter = meterRegistry.counter("wid.webhook.received");
        this.responseTimer = meterRegistry.timer("wid.webhook.response.time");
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestParam("userId") String userId,
            @RequestBody FactSetAlert alert) {

        return responseTimer.record(() -> {
            receivedCounter.increment();
            log.debug("Webhook received: userId={}, triggerId={}", userId, alert.getTriggerId());
            orchestrationService.processAlertAsync(userId, alert);
            return ResponseEntity.ok().build();
        });
    }
}
