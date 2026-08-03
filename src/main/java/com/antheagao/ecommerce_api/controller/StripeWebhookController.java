package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.config.StripeProperties;
import com.antheagao.ecommerce_api.exception.ServiceUnavailableException;
import com.antheagao.ecommerce_api.service.StripeWebhookService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe webhook receiver. Signature verification (below) IS the authentication for this endpoint --
 * see SecurityConfig, which permits it anonymously ahead of the blanket /api/** authenticated rule.
 */
@Slf4j
@RestController
@RequestMapping("/api/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeProperties stripeProperties;
    private final StripeWebhookService stripeWebhookService;

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            // Raw body, never a parsed DTO: HMAC verification below is byte-exact over the raw payload
            // string, and re-serializing a parsed object would not reliably reproduce the bytes Stripe
            // signed.
            @RequestBody String payload,
            // required=false + the explicit blank check below (rather than required=true) keeps a
            // missing header inside this endpoint's own 400, instead of falling through to Spring's
            // default MissingRequestHeaderException handling.
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        if (isBlank(stripeProperties.getWebhookSecret())) {
            throw new ServiceUnavailableException("Stripe webhook endpoint is not configured");
        }
        if (isBlank(sigHeader)) {
            return ResponseEntity.badRequest().build();
        }

        try {
            // Verify the signature FIRST, independently of Webhook.constructEvent below. Checked via
            // javap against stripe-java 33.2.0: Webhook.constructEvent actually deserializes the payload
            // into an Event BEFORE it verifies the signature, so relying on it alone would mean a
            // malformed body paired with a bad signature surfaces as a JSON parse error, not
            // SignatureVerificationException -- breaking the "signature failure is the only 400"
            // contract. Webhook.Signature.verifyHeader does pure HMAC-over-the-raw-payload-string
            // comparison (no JSON parsing at all), so calling it first guarantees
            // SignatureVerificationException really is the only source of the 400 below.
            Webhook.Signature.verifyHeader(payload, sigHeader, stripeProperties.getWebhookSecret(), Webhook.DEFAULT_TOLERANCE);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed", e);
            return ResponseEntity.badRequest().build();
        }

        Event event;
        try {
            // Signature is already verified above; constructEvent re-verifies it (deterministically
            // succeeds -- same payload/header/secret, microseconds later) and parses the payload into an
            // Event. A parse failure here (JSON the SDK can't map to an Event, or a v2 "thin" event --
            // constructEvent explicitly rejects those with IllegalArgumentException, telling callers to
            // use StripeClient.parseEventNotification instead) is not a signature problem: Stripe retries
            // on any non-2xx, and retrying will not fix a payload we can't parse, so this logs and
            // returns 200 rather than 400.
            event = Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret());
        } catch (SignatureVerificationException | RuntimeException e) {
            log.error("Stripe webhook payload could not be parsed after signature verification succeeded", e);
            return ResponseEntity.ok().build();
        }

        try {
            stripeWebhookService.process(event);
        } catch (DataIntegrityViolationException e) {
            // Replay of an already-processed event id (unique constraint on
            // processed_stripe_events.event_id). StripeWebhookService.process() is @Transactional and
            // rollback-only once this escapes it, so the catch has to live out here.
            log.info("Stripe webhook event {} ({}) already processed -- replay, no re-fulfillment", event.getId(), event.getType());
        }

        return ResponseEntity.ok().build();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
