package com.antheagao.ecommerce_api.config;

import com.stripe.StripeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StripeProperties.class)
@RequiredArgsConstructor
public class StripeConfig {

    private final StripeProperties stripeProperties;

    // stripe-java's StripeClient(String) only rejects a null key, not a blank
    // one, so this boots fine with secretKey="" -- no key is required until a
    // call site (S6+) actually talks to Stripe.
    @Bean
    public StripeClient stripeClient() {
        return new StripeClient(stripeProperties.getSecretKey());
    }
}
