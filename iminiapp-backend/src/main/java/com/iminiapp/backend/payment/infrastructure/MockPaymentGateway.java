package com.iminiapp.backend.payment.infrastructure;

import com.iminiapp.backend.payment.application.PaymentGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public PaymentExecution execute(PaymentCommand command) {
        return new PaymentExecution(
                "MOCK",
                "MOCK:" + command.orderId() + ":" + command.idempotencyKey(),
                "IDEA_MOCK_PAYMENT",
                true);
    }
}
