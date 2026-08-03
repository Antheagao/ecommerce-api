package com.antheagao.ecommerce_api;

import com.stripe.net.Webhook;

/**
 * Shared payload/signature builders for the S8 failure-mode webhook suites
 * (StripeWebhookFailureModeTest on H2, StripeWebhookPostgresReplayTest on real Postgres). Pure
 * string templates and HMAC signing -- no Spring wiring -- so both @SpringBootTest classes (which
 * do NOT share an application context, since one runs on H2 and the other on a Testcontainers
 * Postgres) can use it without duplicating the JSON. Mirrors the fixture shape already used by
 * StripeWebhookServiceTest/StripeWebhookTransactionTest, pinned to the SDK's current api_version so
 * event.getDataObjectDeserializer().getObject() takes the typed path (the raw-JSON Gson fallback is
 * already covered by StripeWebhookServiceTest).
 */
final class StripeWebhookFixtures {

    static final String API_VERSION = com.stripe.Stripe.API_VERSION;

    private StripeWebhookFixtures() {
    }

    static String signedHeader(String payload, String secret) {
        try {
            long ts = Webhook.Util.getTimeNow();
            String signature = Webhook.Util.computeHmacSha256(secret, ts + "." + payload);
            return "t=" + ts + ",v1=" + signature;
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    static String checkoutSessionCompletedJson(String eventId, String sessionId, long orderId, long amountTotal, String paymentIntent) {
        return """
                {"id":"%s","object":"event","api_version":"%s","type":"checkout.session.completed",
                 "data":{"object":{"id":"%s","object":"checkout.session","amount_total":%d,
                 "metadata":{"orderId":"%d"},"payment_intent":"%s"}}}
                """.formatted(eventId, API_VERSION, sessionId, amountTotal, orderId, paymentIntent);
    }

    static String checkoutSessionExpiredJson(String eventId, String sessionId, long orderId) {
        return """
                {"id":"%s","object":"event","api_version":"%s","type":"checkout.session.expired",
                 "data":{"object":{"id":"%s","object":"checkout.session","metadata":{"orderId":"%d"}}}}
                """.formatted(eventId, API_VERSION, sessionId, orderId);
    }

    // No metadata.orderId AND no client_reference_id -- resolveOrderIdFromSession has nothing to
    // resolve an order from at all (distinct from "unknown orderId in metadata", where metadata.orderId
    // IS present but points at a nonexistent order).
    static String checkoutSessionCompletedJsonNoOrderIdentifiers(String eventId, String sessionId, long amountTotal, String paymentIntent) {
        return """
                {"id":"%s","object":"event","api_version":"%s","type":"checkout.session.completed",
                 "data":{"object":{"id":"%s","object":"checkout.session","amount_total":%d,
                 "metadata":{},"payment_intent":"%s"}}}
                """.formatted(eventId, API_VERSION, sessionId, amountTotal, paymentIntent);
    }

    static String checkoutSessionExpiredJsonNoOrderIdentifiers(String eventId, String sessionId) {
        return """
                {"id":"%s","object":"event","api_version":"%s","type":"checkout.session.expired",
                 "data":{"object":{"id":"%s","object":"checkout.session","metadata":{}}}}
                """.formatted(eventId, API_VERSION, sessionId);
    }

    static String chargeRefundedJson(String eventId, String paymentIntent, boolean refunded, long amountRefunded) {
        return """
                {"id":"%s","object":"event","api_version":"%s","type":"charge.refunded",
                 "data":{"object":{"id":"ch_%s","object":"charge","payment_intent":"%s",
                 "refunded":%b,"amount_refunded":%d}}}
                """.formatted(eventId, API_VERSION, eventId, paymentIntent, refunded, amountRefunded);
    }
}
