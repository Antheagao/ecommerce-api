package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.entity.Order;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.entity.ProcessedStripeEvent;
import com.antheagao.ecommerce_api.exception.InvalidTransitionException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.OrderRepository;
import com.antheagao.ecommerce_api.repository.ProcessedStripeEventRepository;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.ApiResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Processes verified Stripe webhook events. The caller (StripeWebhookController) has already
 * verified the payload's signature before this is invoked -- everything in here operates on a
 * trusted, parsed {@link Event}.
 * <p>
 * Transaction boundary: {@link #process} is the single @Transactional entry point. Per event, it
 * saveAndFlush()es a {@link ProcessedStripeEvent} row FIRST -- the flush forces the insert (and any
 * unique-constraint violation on a replayed event id) to happen immediately, inside this
 * transaction, rather than at some later, harder-to-attribute flush point. A duplicate event id
 * throws DataIntegrityViolationException, which propagates out of this method and marks the
 * transaction rollback-only; the catch for that MUST live outside this method (see
 * StripeWebhookController), since catching it in here and returning normally would just trade it for
 * Spring's UnexpectedRollbackException on return. Everything else (invalid transitions, unknown
 * orders) is caught INSIDE this method, around the specific transitionSystem call, so the event row
 * insert still commits even when the event's business outcome is "not applicable".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    /**
     * True iff eventId is already recorded in the idempotency ledger. Used by StripeWebhookController
     * to distinguish a genuine replay (expected, 200) from any other DataIntegrityViolationException
     * process() might throw (e.g. an FK/not-null violation from a future schema change) -- conflating
     * the two would silently swallow a real integrity failure as "replay" and stop Stripe from
     * retrying it.
     */
    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(String eventId) {
        return processedStripeEventRepository.existsById(eventId);
    }

    @Transactional
    public void process(Event event) {
        Long orderId = resolveOrderIdBestEffort(event);

        processedStripeEventRepository.saveAndFlush(
                new ProcessedStripeEvent(event.getId(), event.getType(), orderId));
        log.info("event=stripe.webhook.recorded type={} orderId={}", event.getType(), orderId);

        switch (event.getType()) {
            case "checkout.session.completed" -> handleCheckoutSessionCompleted(event);
            case "checkout.session.expired" -> handleCheckoutSessionExpired(event);
            case "payment_intent.payment_failed" -> handlePaymentIntentPaymentFailed(event);
            case "charge.refunded" -> handleChargeRefunded(event);
            default -> log.debug("event=stripe.webhook.unhandled type={}", event.getType());
        }
    }

    private void handleCheckoutSessionCompleted(Event event) {
        Session session = deserializeDataObject(event, Session.class);
        Long orderId = resolveOrderIdFromSession(session);
        if (orderId == null) {
            log.error("event=checkout.session.completed.unresolved sessionId={}", session.getId());
            return;
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("event=checkout.session.completed.order_not_found orderId={}", orderId);
            return;
        }

        // a) Amount check: the session actually charged what the order expects. movePointRight(2) +
        // longValueExact() mirrors StripeCheckoutService's own major-to-minor-unit conversion.
        long expectedMinorUnits = order.getTotal().movePointRight(2).longValueExact();
        Long actualMinorUnits = session.getAmountTotal();
        if (actualMinorUnits == null || actualMinorUnits != expectedMinorUnits) {
            log.error("event=checkout.session.completed.amount_mismatch orderId={} expected={} sessionId={} actual={}",
                    orderId, expectedMinorUnits, session.getId(), actualMinorUnits);
            return;
        }

        // b) Session identity check: a re-checkout overwrites order.stripeSessionId (Stripe sessions
        // live 24h), so a superseded-but-still-live session completing must not pay an order that has
        // since moved on to a newer session.
        if (!session.getId().equals(order.getStripeSessionId())) {
            log.warn("event=checkout.session.completed.superseded_session orderId={} sessionId={} currentSessionId={}",
                    orderId, session.getId(), order.getStripeSessionId());
            return;
        }

        try {
            orderService.transitionSystem(orderId, OrderStatus.PAID, session.getPaymentIntent());
        } catch (InvalidTransitionException e) {
            log.info("event=checkout.session.completed.transition_swallowed orderId={} from={} to={}",
                    orderId, e.getFrom(), e.getTo());
        } catch (ResourceNotFoundException e) {
            log.warn("event=checkout.session.completed.transition_order_not_found orderId={}", orderId);
        }
    }

    private void handleCheckoutSessionExpired(Event event) {
        Session session = deserializeDataObject(event, Session.class);
        Long orderId = resolveOrderIdFromSession(session);
        if (orderId == null) {
            log.error("event=checkout.session.expired.unresolved sessionId={}", session.getId());
            return;
        }
        try {
            orderService.transitionSystem(orderId, OrderStatus.FAILED, null);
        } catch (InvalidTransitionException e) {
            log.info("event=checkout.session.expired.transition_swallowed orderId={} from={} to={}",
                    orderId, e.getFrom(), e.getTo());
        } catch (ResourceNotFoundException e) {
            log.warn("event=checkout.session.expired.transition_order_not_found orderId={}", orderId);
        }
    }

    private void handlePaymentIntentPaymentFailed(Event event) {
        PaymentIntent paymentIntent = deserializeDataObject(event, PaymentIntent.class);
        // Deliberately NO transition here -- this deviates from the original task-board spec, which
        // mapped this event to FAILED. Review override (S4-review carry-in): a declined attempt inside
        // a still-live Checkout Session is retryable by the customer (Stripe lets them try another card
        // on the same session); marking the order FAILED here would brick it if they then succeed on a
        // later attempt. Record-only (already done by the saveAndFlush above) + WARN log.
        log.warn("event=payment_intent.payment_failed paymentIntentId={}", paymentIntent.getId());
    }

    private void handleChargeRefunded(Event event) {
        Charge charge = deserializeDataObject(event, Charge.class);
        // charge.refunded also fires for PARTIAL refunds (a goodwill credit, say) -- getRefunded() is
        // true ONLY when the charge has been refunded in full (confirmed against the 33.2.0 Charge
        // model). Transitioning to the terminal REFUNDED status on a partial refund would permanently
        // brick an order that should still ship. Record-only + INFO for partial refunds.
        if (!Boolean.TRUE.equals(charge.getRefunded())) {
            log.info("event=charge.refunded.partial chargeId={} amountRefunded={}",
                    charge.getId(), charge.getAmountRefunded());
            return;
        }
        Long orderId = resolveOrderIdFromCharge(charge);
        if (orderId == null) {
            log.warn("event=charge.refunded.unresolved chargeId={} paymentIntent={}",
                    charge.getId(), charge.getPaymentIntent());
            return;
        }
        try {
            // null, not charge.getId(): overwriting paymentReference with the ch_... id would clobber
            // the pi_... reference that resolveOrderIdFromCharge() matches subsequent charge.refunded
            // events against -- exactly the case a partial refund followed later by the full refund
            // hits. paymentReference already records the payment (set to the PaymentIntent id at PAID),
            // so there's nothing meaningful to overwrite it with here anyway.
            orderService.transitionSystem(orderId, OrderStatus.REFUNDED, null);
        } catch (InvalidTransitionException e) {
            log.info("event=charge.refunded.transition_swallowed orderId={} from={} to={}",
                    orderId, e.getFrom(), e.getTo());
        } catch (ResourceNotFoundException e) {
            log.warn("event=charge.refunded.transition_order_not_found orderId={}", orderId);
        }
    }

    /**
     * Best-effort orderId resolution for the ProcessedStripeEvent row itself, done before the row is
     * inserted. Deliberately swallows everything: the row must still get inserted (and 200 returned)
     * even when this event's payload can't be resolved to an order here -- the type-specific handler
     * below hits the same data and logs the real problem.
     */
    private Long resolveOrderIdBestEffort(Event event) {
        try {
            return switch (event.getType()) {
                case "checkout.session.completed", "checkout.session.expired" ->
                        resolveOrderIdFromSession(deserializeDataObject(event, Session.class));
                case "charge.refunded" ->
                        resolveOrderIdFromCharge(deserializeDataObject(event, Charge.class));
                default -> null;
            };
        } catch (RuntimeException e) {
            log.warn("event=stripe.webhook.order_resolution_failed type={}", event.getType(), e);
            return null;
        }
    }

    private Long resolveOrderIdFromSession(Session session) {
        Map<String, String> metadata = session.getMetadata();
        String metaOrderId = metadata != null ? metadata.get("orderId") : null;
        if (metaOrderId != null && !metaOrderId.isBlank()) {
            try {
                return Long.valueOf(metaOrderId);
            } catch (NumberFormatException e) {
                log.warn("event=checkout.session.metadata_invalid sessionId={} metaOrderId={}", session.getId(), metaOrderId);
            }
        }
        String clientReferenceId = session.getClientReferenceId();
        if (clientReferenceId != null && !clientReferenceId.isBlank()) {
            return orderRepository.findByOrderNumber(clientReferenceId).map(Order::getId).orElse(null);
        }
        return null;
    }

    private Long resolveOrderIdFromCharge(Charge charge) {
        String paymentIntentId = charge.getPaymentIntent();
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return null;
        }
        // Order.paymentReference is set to the PaymentIntent id at the PAID transition (see
        // handleCheckoutSessionCompleted above), so matching the refunded charge's paymentIntent
        // against it is how a charge.refunded event finds its way back to an order.
        return orderRepository.findByPaymentReference(paymentIntentId).map(Order::getId).orElse(null);
    }

    /**
     * event.getDataObjectDeserializer().getObject() can be an empty Optional when the Stripe Dashboard
     * webhook endpoint's configured API version differs from the version this SDK build is pinned to
     * (verified via javap against stripe-java 33.2.0's EventDataObjectDeserializer: getObject() returns
     * empty whenever its internal apiVersionMatch() check fails, or the gson deserialization itself
     * throws JsonParseException). Falling back to getRawJson() + the SDK's own bundled Gson instance
     * (ApiResource.GSON) to parse the raw JSON directly into the expected model class handles that case
     * without pulling in a second, unrelated JSON library (this app doesn't otherwise touch gson).
     */
    @SuppressWarnings("unchecked")
    private <T extends StripeObject> T deserializeDataObject(Event event, Class<T> type) {
        return event.getDataObjectDeserializer().getObject()
                .map(obj -> (T) obj)
                .orElseGet(() -> ApiResource.GSON.fromJson(event.getDataObjectDeserializer().getRawJson(), type));
    }
}
