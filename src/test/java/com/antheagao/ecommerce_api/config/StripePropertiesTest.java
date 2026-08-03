package com.antheagao.ecommerce_api.config;

import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StripePropertiesTest {

    @Autowired
    private StripeProperties stripeProperties;

    @Autowired
    private StripeClient stripeClient;

    @Test
    void bootsWithNoStripeEnv_beanExistsAndUnconfigured() {
        assertThat(stripeClient).isNotNull();
        assertThat(stripeProperties.isConfigured()).isFalse();
        assertThat(stripeProperties.getCurrency()).isEqualTo("usd");
    }
}
