package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.entity.Order;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.repository.OrderRepository;
import com.antheagao.ecommerce_api.repository.ProcessedStripeEventRepository;
import com.antheagao.ecommerce_api.repository.UserRepository;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REAL transaction-manager regression test for StripeWebhookService.process(): unlike
 * StripeWebhookServiceTest (which mocks OrderService, so no proxy ever joins the transaction and
 * nothing ever gets marked rollback-only), this boots the full Spring context so
 * OrderService.transitionSystem runs through its real @Transactional proxy on the SAME physical
 * transaction as process(). No test-level @Transactional either: each service.process() call
 * opens/commits its own transaction, exactly like the real controller request path.
 * <p>
 * This is what caught the F1 bug: InvalidTransitionException/ResourceNotFoundException thrown by
 * transitionSystem (a separate proxied bean joining the same REQUIRED transaction) mark that shared
 * transaction rollback-only the instant they cross transitionSystem's proxy boundary -- before
 * process()'s catch block ever runs. Catching the exception doesn't undo that: the transaction is
 * already rollback-only, so committing on return throws UnexpectedRollbackException regardless. The
 * fix is OrderService.transitionSystem's
 * @Transactional(noRollbackFor = {InvalidTransitionException.class, ResourceNotFoundException.class}).
 * A routine trigger: session A is abandoned, the customer pays via session B (order goes PAID), then
 * session A expires 24h later -- the checkout.session.expired event hits an already-PAID order,
 * which is exactly expiredEventOnPaidOrder_claimIsEventRowStillCommits below.
 */
@SpringBootTest
class StripeWebhookTransactionTest {

    private static final String API = com.stripe.Stripe.API_VERSION;

    @Autowired
    private StripeWebhookService service;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProcessedStripeEventRepository eventRepository;

    private Order savedOrder(OrderStatus status, String sessionId, String orderNumber) {
        User u = userRepository.findByEmail("txtrap@test.com").orElseGet(() ->
                userRepository.save(User.builder().email("txtrap@test.com").passwordHash("x").role("USER").build()));
        return orderRepository.save(Order.builder()
                .user(u).orderNumber(orderNumber).status(status)
                .subtotal(new BigDecimal("39.98")).tax(BigDecimal.ZERO).shippingCost(BigDecimal.ZERO)
                .total(new BigDecimal("39.98")).stripeSessionId(sessionId)
                .build());
    }

    private static Event event(String json) {
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    private static String expiredJson(String evtId, String sessionId, long orderId) {
        return """
                {"id":"%s","object":"event","api_version":"%s","type":"checkout.session.expired",
                 "data":{"object":{"id":"%s","object":"checkout.session","metadata":{"orderId":"%d"}}}}
                """.formatted(evtId, API, sessionId, orderId);
    }

    private static String completedJson(String evtId, String sessionId, long orderId, long amount, String pi) {
        return """
                {"id":"%s","object":"event","api_version":"%s","type":"checkout.session.completed",
                 "data":{"object":{"id":"%s","object":"checkout.session","amount_total":%d,
                 "metadata":{"orderId":"%d"},"payment_intent":"%s"}}}
                """.formatted(evtId, API, sessionId, amount, orderId, pi);
    }

    @Test
    void expiredEventOnPaidOrder_claimIsEventRowStillCommits() {
        Order order = savedOrder(OrderStatus.PAID, "cs_trap_1", "ORD-TRAP1");
        Event evt = event(expiredJson("evt_trap_1", "cs_trap_1", order.getId()));

        assertThatCode(() -> service.process(evt))
                .as("process() should swallow InvalidTransitionException and commit the event row")
                .doesNotThrowAnyException();

        assertThat(eventRepository.findById("evt_trap_1"))
                .as("event row must be committed even though the transition was not applicable")
                .isPresent();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    void expiredEventOnMissingOrder_claimIsEventRowStillCommits() {
        Event evt = event(expiredJson("evt_trap_2", "cs_trap_2", 999999L));

        assertThatCode(() -> service.process(evt))
                .as("process() should swallow ResourceNotFoundException and commit the event row")
                .doesNotThrowAnyException();

        assertThat(eventRepository.findById("evt_trap_2")).isPresent();
    }

    @Test
    void replay_secondDeliveryThrowsDataIntegrityViolation_firstOutcomeIntact() {
        Order order = savedOrder(OrderStatus.PENDING, "cs_trap_3", "ORD-TRAP3");
        long amount = 3998L;
        Event first = event(completedJson("evt_trap_3", "cs_trap_3", order.getId(), amount, "pi_trap_3"));
        Event replay = event(completedJson("evt_trap_3", "cs_trap_3", order.getId(), amount, "pi_trap_3"));

        service.process(first);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);

        assertThatThrownBy(() -> service.process(replay))
                .isInstanceOf(DataIntegrityViolationException.class);

        // replay tx rolled back cleanly; first outcome intact
        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(after.getPaymentReference()).isEqualTo("pi_trap_3");
        assertThat(eventRepository.count()).isGreaterThanOrEqualTo(1);
    }
}
