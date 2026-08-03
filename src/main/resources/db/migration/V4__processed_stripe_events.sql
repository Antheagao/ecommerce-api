-- S7 adds the idempotency ledger for Stripe webhook events: event_id (Stripe's own event id) is the
-- primary key, so StripeWebhookService.process()'s saveAndFlush() of a replayed event id hits this
-- constraint and throws instead of the event being silently reprocessed (and an order re-fulfilled).
-- Same IF NOT EXISTS-safe style as V1-V3 so this migration is a no-op against a volume where it
-- already ran.
CREATE TABLE IF NOT EXISTS processed_stripe_events (
    event_id varchar(255) PRIMARY KEY,
    event_type varchar(255) NOT NULL,
    received_at timestamp(6) without time zone NOT NULL,
    order_id bigint
);
