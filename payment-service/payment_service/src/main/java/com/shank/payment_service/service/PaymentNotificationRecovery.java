package com.shank.payment_service.service;

import com.shank.payment_service.repository.PaymentRepository;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "payments.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentNotificationRecovery {
    private static final Logger log = LoggerFactory.getLogger(PaymentNotificationRecovery.class);
    private final PaymentRepository repository;
    private final PaymentService service;
    public PaymentNotificationRecovery(PaymentRepository repository, PaymentService service) { this.repository = repository; this.service = service; }
    @Scheduled(fixedDelayString = "${payments.recovery-delay-ms:30000}")
    public void retry() {
        repository.findByNotificationPendingTrue().forEach(payment -> {
            try { service.retryNotification(payment.getId()); }
            catch (RuntimeException failure) { log.warn("Payment notification retry needed paymentId={}", payment.getId()); }
        });
    }
}