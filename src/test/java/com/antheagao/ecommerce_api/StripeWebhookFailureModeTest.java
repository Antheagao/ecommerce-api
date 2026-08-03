package com.antheagao.ecommerce_api;

import com.antheagao.ecommerce_api.entity.Order;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.repository.OrderRepository;
import com.antheagao.ecommerce_api.repository.ProcessedStripeEventRepository;
import com.antheagao.ecommerce_api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static com.antheagao.ecommerce_api.StripeWebhookFixtures.chargeRefundedJson;
import static com.antheagao.ecommerce_api.StripeWebhookFixtures.checkoutSessionCompletedJson;
import static com.antheagao.ecommerce_api.StripeWebhookFixtures.checkoutSessionCompletedJsonNoOrderIdentifiers;
import static com.antheagao.ecommerce_api.StripeWebhookFixtures.checkoutSessionExpiredJson;
import static com.antheagao.ecommerce_api.StripeWebhookFixtures.checkoutSessionExpiredJsonNoOrderIdentifiers;
import static com.antheagao.ecommerce_api.StripeWebhookFixtures.signedHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S8: end-to-end failure-mode suite for the Stripe webhook path, driving the REAL composed stack --
 * MockMvc HTTP -> real StripeWebhookController -> real StripeWebhookService -> real OrderService ->
 * real repositories, on H2. Nothing is mocked (no Stripe network call happens in the webhook path
 * anyway).
 * <p>
 * This is deliberately NOT StripeWebhookControllerTest (mocks StripeWebhookService, so it only pins
 * the controller's own signature-verification/error-mapping logic) or StripeWebhookServiceTest
 * (mocks OrderRepository/OrderService, so it only pins the service's per-event branching in
 * isolation). Both of those are layer-isolated by design; this class pins the outcome that only
 * exists once every layer is wired together and asserted via real DB state.
 * <p>
 * StripeWebhookPostgresReplayTest re-runs the DB-semantics-sensitive scenarios (duplicate-event
 * unique-violation, the expired-after-paid rollback trap, and the partial/full refund pair) against
 * a real Postgres container, since H2's SQLState translation for a unique-constraint violation
 * differs from Postgres's (gap (h) from the S7 review).
 */
@SpringBootTest(properties = {
        "app.stripe.webhook-secret=whsec_test_failuremode_secret"
})
@AutoConfigureMockMvc
class StripeWebhookFailureModeTest {

    private static final String SECRET = "whsec_test_failuremode_secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProcessedStripeEventRepository eventRepository;

    private User testUser() {
        return userRepository.findByEmail("s8failuremode@test.com").orElseGet(() ->
                userRepository.save(User.builder().email("s8failuremode@test.com").passwordHash("x").role("USER").build()));
    }

    private Order seedOrder(OrderStatus status, String sessionId, String orderNumber, String paymentReference) {
        return orderRepository.save(Order.builder()
                .user(testUser()).orderNumber(orderNumber).status(status)
                .subtotal(new BigDecimal("39.98")).tax(BigDecimal.ZERO).shippingCost(BigDecimal.ZERO)
                .total(new BigDecimal("39.98")).stripeSessionId(sessionId).paymentReference(paymentReference)
                .build());
    }

    private void postWebhookExpectOk(String payload, String secret) throws Exception {
        mockMvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signedHeader(payload, secret))
                        .content(payload))
                .andExpect(status().isOk());
    }

    // (a) duplicate event: identical signed event delivered twice -> 200 both times, order PAID
    // once, exactly one processed_stripe_events row for that event id, paymentReference set from
    // payment_intent. Asserted via DB state, not mocks.
    @Test
    void duplicateEvent_bothReturn200_orderPaidOnce_eventRecordedOnce() throws Exception {
        Order order = seedOrder(OrderStatus.PENDING, "cs_s8_dup", "ORD-S8DUP1", null);
        String payload = checkoutSessionCompletedJson("evt_s8_dup", "cs_s8_dup", order.getId(), 3998, "pi_s8_dup");
        String header = signedHeader(payload, SECRET);

        mockMvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header).content(payload))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header).content(payload))
                .andExpect(status().isOk());

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(after.getPaymentReference()).isEqualTo("pi_s8_dup");
        assertThat(eventRepository.findById("evt_s8_dup")).isPresent();
    }

    // (b) webhook-before-redirect: order is PENDING, a single completed event arrives (nothing else
    // has touched the order) -> PAID purely from the webhook.
    @Test
    void webhookArrivesBeforeAnyRedirect_orderPaidFromWebhookAlone() throws Exception {
        Order order = seedOrder(OrderStatus.PENDING, "cs_s8_b4redirect", "ORD-S8B4RDR", null);
        String payload = checkoutSessionCompletedJson("evt_s8_b4redirect", "cs_s8_b4redirect", order.getId(), 3998, "pi_s8_b4redirect");

        postWebhookExpectOk(payload, SECRET);

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(after.getPaymentReference()).isEqualTo("pi_s8_b4redirect");
    }

    // (c) order already PAID via session B; session A's expired event (distinct event id) arrives ->
    // 200, order STAYS PAID, event row recorded. This is the F1 regression at HTTP level --
    // UnexpectedRollbackException would surface as a 400 here if noRollbackFor regressed.
    @Test
    void sessionExpiredAfterOrderPaidViaDifferentSession_staysPaid_eventRecorded() throws Exception {
        Order order = seedOrder(OrderStatus.PAID, "cs_s8_sessionB", "ORD-S8SESSB", "pi_s8_sessionb");
        String payload = checkoutSessionExpiredJson("evt_s8_expired_sessionA", "cs_s8_sessionA", order.getId());

        postWebhookExpectOk(payload, SECRET);

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(eventRepository.findById("evt_s8_expired_sessionA")).isPresent();
    }

    // (d) signed completed event whose amount_total != order total -> 200, order stays PENDING,
    // event recorded.
    @Test
    void amountMismatch_orderStaysPending_eventRecorded() throws Exception {
        Order order = seedOrder(OrderStatus.PENDING, "cs_s8_amtmismatch", "ORD-S8AMT", null);
        String payload = checkoutSessionCompletedJson("evt_s8_amtmismatch", "cs_s8_amtmismatch", order.getId(), 999, "pi_s8_amtmismatch");

        postWebhookExpectOk(payload, SECRET);

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(eventRepository.findById("evt_s8_amtmismatch")).isPresent();
    }

    // Also worth including: completed event for a session id != order.stripeSessionId (a superseded
    // session that's still live completing after the order has moved on to a newer one) -> 200,
    // stays PENDING, recorded.
    @Test
    void sessionIdentityMismatch_supersededSessionCompletes_staysPending_eventRecorded() throws Exception {
        Order order = seedOrder(OrderStatus.PENDING, "cs_s8_current", "ORD-S8SESSMISMATCH", null);
        String payload = checkoutSessionCompletedJson("evt_s8_sessmismatch", "cs_s8_superseded", order.getId(), 3998, "pi_s8_sessmismatch");

        postWebhookExpectOk(payload, SECRET);

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(eventRepository.findById("evt_s8_sessmismatch")).isPresent();
    }

    // (e) bad signature -> 400 and zero processed rows.
    @Test
    void badSignature_returns400_eventNotRecorded() throws Exception {
        String payload = checkoutSessionCompletedJson("evt_s8_badsig", "cs_s8_badsig", 1L, 3998, "pi_s8_badsig");

        mockMvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1700000000,v1=not_a_real_signature")
                        .content(payload))
                .andExpect(status().isBadRequest());

        assertThat(eventRepository.findById("evt_s8_badsig")).isEmpty();
    }

    // (e) absent signature header -> 400, zero rows.
    @Test
    void missingSignatureHeader_returns400_eventNotRecorded() throws Exception {
        String payload = checkoutSessionCompletedJson("evt_s8_nosig", "cs_s8_nosig", 1L, 3998, "pi_s8_nosig");

        mockMvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        assertThat(eventRepository.findById("evt_s8_nosig")).isEmpty();
    }

    // (e) a well-formed HMAC header, computed with the WRONG secret -> 400, zero rows. Distinct from
    // badSignature above (a garbage header value): this proves signature verification checks the
    // actual secret, not just header shape.
    @Test
    void validSignatureWrongSecret_returns400_eventNotRecorded() throws Exception {
        String payload = checkoutSessionCompletedJson("evt_s8_wrongsecret", "cs_s8_wrongsecret", 1L, 3998, "pi_s8_wrongsecret");
        String header = signedHeader(payload, "whsec_test_a_totally_different_secret");

        mockMvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header).content(payload))
                .andExpect(status().isBadRequest());

        assertThat(eventRepository.findById("evt_s8_wrongsecret")).isEmpty();
    }

    // (f) metadata.orderId points at an order that doesn't exist -> 200, event recorded, no order
    // touched, no 500.
    @Test
    void unknownOrderIdInMetadata_returns200_eventRecorded_noOrderTouched() throws Exception {
        String payload = checkoutSessionCompletedJson("evt_s8_unknownorder", "cs_s8_unknownorder", 999999L, 3998, "pi_s8_unknownorder");

        postWebhookExpectOk(payload, SECRET);

        assertThat(eventRepository.findById("evt_s8_unknownorder")).isPresent();
        assertThat(orderRepository.findById(999999L)).isEmpty();
    }

    // No metadata.orderId AND no client_reference_id on a completed session -> resolveOrderIdFromSession
    // has nothing to resolve at all -> 200, event recorded with a null orderId, no order touched, no 500.
    // Distinct from unknownOrderIdInMetadata above (metadata.orderId present but pointing nowhere).
    @Test
    void checkoutSessionCompleted_noResolvableOrderIdentifiers_eventRecordedWithNullOrderId_no500() throws Exception {
        String payload = checkoutSessionCompletedJsonNoOrderIdentifiers("evt_s8_noidcompleted", "cs_s8_noidcompleted", 3998, "pi_s8_noidcompleted");

        postWebhookExpectOk(payload, SECRET);

        var recorded = eventRepository.findById("evt_s8_noidcompleted");
        assertThat(recorded).isPresent();
        assertThat(recorded.get().getOrderId()).isNull();
    }

    // Same, for checkout.session.expired.
    @Test
    void checkoutSessionExpired_noResolvableOrderIdentifiers_eventRecordedWithNullOrderId_no500() throws Exception {
        String payload = checkoutSessionExpiredJsonNoOrderIdentifiers("evt_s8_noidexpired", "cs_s8_noidexpired");

        postWebhookExpectOk(payload, SECRET);

        var recorded = eventRepository.findById("evt_s8_noidexpired");
        assertThat(recorded).isPresent();
        assertThat(recorded.get().getOrderId()).isNull();
    }

    // charge.refunded (full) resolving to an order that's already REFUNDED (a terminal state) ->
    // transitionSystem throws InvalidTransitionException, swallowed -> 200, order stays REFUNDED,
    // event recorded. Exercises handleChargeRefunded's InvalidTransitionException catch, the one
    // branch of the state-machine-guard trio (completed/expired/refunded) not otherwise hit above.
    @Test
    void chargeRefundedOnAlreadyRefundedOrder_staysRefunded_swallowedEventRecorded() throws Exception {
        Order order = seedOrder(OrderStatus.REFUNDED, "cs_s8_alreadyrefunded", "ORD-S8ALREADYREFUNDED", "pi_s8_alreadyrefunded");
        String payload = chargeRefundedJson("evt_s8_alreadyrefunded", "pi_s8_alreadyrefunded", true, 3998);

        postWebhookExpectOk(payload, SECRET);

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(eventRepository.findById("evt_s8_alreadyrefunded")).isPresent();
    }

    // (i) charge.refunded with refunded=false, amount_refunded < amount -> 200, order stays PAID,
    // event recorded. Followed by a full refund (refunded=true, distinct event id) -> REFUNDED,
    // proving the pi_ reference used to resolve the order still works after a partial refund (F3).
    @Test
    void partialRefundThenFullRefund_staysPaidThenRefunded() throws Exception {
        Order order = seedOrder(OrderStatus.PAID, "cs_s8_refund", "ORD-S8REFUND", "pi_s8_refund");

        String partialPayload = chargeRefundedJson("evt_s8_refund_partial", "pi_s8_refund", false, 500);
        postWebhookExpectOk(partialPayload, SECRET);

        Order afterPartial = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(afterPartial.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(eventRepository.findById("evt_s8_refund_partial")).isPresent();

        String fullPayload = chargeRefundedJson("evt_s8_refund_full", "pi_s8_refund", true, 3998);
        postWebhookExpectOk(fullPayload, SECRET);

        Order afterFull = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(afterFull.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(eventRepository.findById("evt_s8_refund_full")).isPresent();
    }
}
