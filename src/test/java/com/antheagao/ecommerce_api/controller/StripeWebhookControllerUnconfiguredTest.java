package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.service.StripeWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Separate SpringBootTest context (default, unconfigured app.stripe.webhook-secret -- blank, per
 * StripeProperties/test application.properties) so this doesn't need to fight
 * StripeWebhookControllerTest's context, which deliberately sets a real secret.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StripeWebhookControllerUnconfiguredTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StripeWebhookService stripeWebhookService;

    @Test
    void blankWebhookSecret_returns503() throws Exception {
        mockMvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=whatever")
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENTS_UNAVAILABLE"));
    }
}
