package com.antheagao.ecommerce_api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Blank-string defaults so the app boots with no Stripe env set at all --
 * actual key validation happens lazily at call sites (S6+), not here.
 */
@Data
@ConfigurationProperties(prefix = "app.stripe")
public class StripeProperties {

    private String secretKey = "";
    private String webhookSecret = "";
    private String successUrl = "";
    private String cancelUrl = "";
    private String currency = "usd";

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }
}
