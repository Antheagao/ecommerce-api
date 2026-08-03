package com.antheagao.ecommerce_api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.stripe.secret-key=sk_test_123",
        "app.stripe.webhook-secret=whsec_123",
        "app.stripe.success-url=http://localhost:8080/checkout-success.html",
        "app.stripe.cancel-url=http://localhost:8080/checkout-cancel.html",
        "app.stripe.currency=eur"
})
class StripePropertiesBindingTest {

    @Autowired
    private StripeProperties stripeProperties;

    @Test
    void propertiesBindFromAppStripePrefix() {
        assertThat(stripeProperties.isConfigured()).isTrue();
        assertThat(stripeProperties.getSecretKey()).isEqualTo("sk_test_123");
        assertThat(stripeProperties.getWebhookSecret()).isEqualTo("whsec_123");
        assertThat(stripeProperties.getSuccessUrl()).isEqualTo("http://localhost:8080/checkout-success.html");
        assertThat(stripeProperties.getCancelUrl()).isEqualTo("http://localhost:8080/checkout-cancel.html");
        assertThat(stripeProperties.getCurrency()).isEqualTo("eur");
    }
}
