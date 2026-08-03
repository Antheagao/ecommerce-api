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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static com.antheagao.ecommerce_api.StripeWebhookFixtures.chargeRefundedJson;
import static com.antheagao.ecommerce_api.StripeWebhookFixtures.checkoutSessionCompletedJson;
import static com.antheagao.ecommerce_api.StripeWebhookFixtures.checkoutSessionExpiredJson;
import static com.antheagao.ecommerce_api.StripeWebhookFixtures.signedHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S8 gap (h): StripeWebhookFailureModeTest's scenarios re-run against a REAL Postgres container
 * (matching the postgres:16-alpine image used by docker-compose), Flyway-migrated, instead of H2.
 * H2's SQLState translation for a unique-constraint violation differs from Postgres's, and that
 * translation is exactly what StripeWebhookController's DataIntegrityViolationException catch
 * depends on for replay handling -- so the idempotency path is only really proven once it's been
 * driven against the database that matters. Property overrides mirror MigrationSmokeTest's.
 * <p>
 * Scope is the DB-semantics-sensitive scenarios: duplicate-event replay (the unique-violation path
 * itself), the expired-after-paid rollback trap (F1 -- transaction-manager behavior can differ by
 * database), and the partial/full refund pair (two sequential commits resolving via the same
 * paymentReference). The full amount/session-mismatch/bad-signature matrix is DB-agnostic and
 * already covered on H2 in StripeWebhookFailureModeTest.
 */
@Testcontainers
@SpringBootTest(properties = {
        "app.stripe.webhook-secret=whsec_test_pgreplay_secret",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureMockMvc
class StripeWebhookPostgresReplayTest {

    private static final String SECRET = "whsec_test_pgreplay_secret";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProcessedStripeEventRepository eventRepository;

    private User testUser() {
        return userRepository.findByEmail("s8pgreplay@test.com").orElseGet(() ->
                userRepository.save(User.builder().email("s8pgreplay@test.com").passwordHash("x").role("USER").build()));
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

    // (a)/(h): identical signed event delivered twice against real Postgres -> 200 both times,
    // order PAID once, exactly one event row, paymentReference set from payment_intent. The second
    // delivery's saveAndFlush() must hit Postgres's actual unique-violation SQLState and have that
    // correctly translate to Spring's DataIntegrityViolationException for the controller to catch --
    // this is the scenario H2 cannot fully stand in for.
    @Test
    void duplicateEvent_bothReturn200_orderPaidOnce_eventRecordedOnce_realPostgresUniqueViolation() throws Exception {
        Order order = seedOrder(OrderStatus.PENDING, "cs_pg_dup", "ORD-PGDUP1", null);
        String payload = checkoutSessionCompletedJson("evt_pg_dup", "cs_pg_dup", order.getId(), 3998, "pi_pg_dup");
        String header = signedHeader(payload, SECRET);

        mockMvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header).content(payload))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header).content(payload))
                .andExpect(status().isOk());

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(after.getPaymentReference()).isEqualTo("pi_pg_dup");
        assertThat(eventRepository.findById("evt_pg_dup")).isPresent();
    }

    // (c)/F1: order already PAID via session B; session A's expired event arrives -> 200, order
    // STAYS PAID, event row recorded. Pins that transitionSystem's noRollbackFor keeps the shared
    // REQUIRED transaction committable under Postgres's real transaction manager too, not just H2's.
    @Test
    void sessionExpiredAfterOrderPaidViaDifferentSession_staysPaid_eventRecorded_realPostgres() throws Exception {
        Order order = seedOrder(OrderStatus.PAID, "cs_pg_sessionB", "ORD-PGSESSB", "pi_pg_sessionb");
        String payload = checkoutSessionExpiredJson("evt_pg_expired_sessionA", "cs_pg_sessionA", order.getId());

        postWebhookExpectOk(payload, SECRET);

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(eventRepository.findById("evt_pg_expired_sessionA")).isPresent();
    }

    // (i): partial refund (stays PAID) followed by a full refund (distinct event id) -> REFUNDED,
    // across two separate committed transactions against real Postgres.
    @Test
    void partialRefundThenFullRefund_staysPaidThenRefunded_realPostgres() throws Exception {
        Order order = seedOrder(OrderStatus.PAID, "cs_pg_refund", "ORD-PGREFUND", "pi_pg_refund");

        String partialPayload = chargeRefundedJson("evt_pg_refund_partial", "pi_pg_refund", false, 500);
        postWebhookExpectOk(partialPayload, SECRET);

        Order afterPartial = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(afterPartial.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(eventRepository.findById("evt_pg_refund_partial")).isPresent();

        String fullPayload = chargeRefundedJson("evt_pg_refund_full", "pi_pg_refund", true, 3998);
        postWebhookExpectOk(fullPayload, SECRET);

        Order afterFull = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(afterFull.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(eventRepository.findById("evt_pg_refund_full")).isPresent();
    }
}
