package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.service.StripeWebhookService;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack (@SpringBootTest + MockMvc, real security filter chain) tests for the webhook endpoint,
 * using a genuinely configured webhook secret so signature verification actually runs. All requests
 * here are anonymous (no Authorization header) -- a 200/400 rather than a 401 on any of them is
 * itself proof that SecurityConfig's permitAll on /api/stripe/webhook works.
 */
@SpringBootTest(properties = {
        "app.stripe.webhook-secret=whsec_test_abcdef1234567890"
})
@AutoConfigureMockMvc
class StripeWebhookControllerTest {

    private static final String SECRET = "whsec_test_abcdef1234567890";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StripeWebhookService stripeWebhookService;

    private static String signedHeader(String payload, String secret) throws Exception {
        long ts = Webhook.Util.getTimeNow();
        String signature = Webhook.Util.computeHmacSha256(secret, ts + "." + payload);
        return "t=" + ts + ",v1=" + signature;
    }

    private static String samplePayload() {
        return """
                {
                  "id": "evt_test_1",
                  "object": "event",
                  "type": "checkout.session.completed",
                  "data": { "object": { "id": "cs_test_1", "object": "checkout.session" } }
                }
                """;
    }

    @Test
    void validSignature_returns200AndProcessesEvent() throws Exception {
        String payload = samplePayload();
        String header = signedHeader(payload, SECRET);

        mockMvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header)
                        .content(payload))
                .andExpect(status().isOk());

        verify(stripeWebhookService).process(any(Event.class));
    }

    @Test
    void badSignature_returns400AndNeverProcesses() throws Exception {
        String payload = samplePayload();
        String header = signedHeader(payload, "whsec_wrong_secret");

        mockMvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header)
                        .content(payload))
                .andExpect(status().isBadRequest());

        verify(stripeWebhookService, never()).process(any());
    }

    @Test
    void missingSignatureHeader_returns400AndNeverProcesses() throws Exception {
        mockMvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isBadRequest());

        verify(stripeWebhookService, never()).process(any());
    }

    @Test
    void unparseablePayloadWithValidSignature_returns200AndNeverProcesses() throws Exception {
        String payload = "not valid json at all";
        String header = signedHeader(payload, SECRET);

        mockMvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header)
                        .content(payload))
                .andExpect(status().isOk());

        verify(stripeWebhookService, never()).process(any());
    }

    @Test
    void replayedEvent_dataIntegrityViolationFromService_stillReturns200() throws Exception {
        String payload = samplePayload();
        String header = signedHeader(payload, SECRET);
        doThrow(new DataIntegrityViolationException("duplicate event_id")).when(stripeWebhookService).process(any(Event.class));

        mockMvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header)
                        .content(payload))
                .andExpect(status().isOk());
    }
}
