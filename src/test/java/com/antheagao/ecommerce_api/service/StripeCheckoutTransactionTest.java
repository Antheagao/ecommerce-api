package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.CheckoutSessionResponse;
import com.antheagao.ecommerce_api.entity.Order;
import com.antheagao.ecommerce_api.entity.OrderItem;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.entity.Product;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.repository.OrderRepository;
import com.antheagao.ecommerce_api.repository.ProductRepository;
import com.antheagao.ecommerce_api.repository.UserRepository;
import com.stripe.StripeClient;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.checkout.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REAL transaction-manager regression test for StripeCheckoutService.createSession(): unlike
 * StripeCheckoutServiceTest (pure Mockito, which hands back a fully-materialized mock Order so
 * lazy-loading is never actually exercised), this boots the full Spring context with real
 * repositories. No test-level @Transactional and no web slice (no OSIV filter in a non-web
 * @SpringBootTest), so detached-entity access after the read transaction closes is a genuine
 * LazyInitializationException if the fetch-join is incomplete, exactly like production.
 * <p>
 * This is what L1 is about: createSession() used to hold @Transactional across the Stripe network
 * call, pinning a Hikari connection for the duration. Now the read (findWithUserAndItemsById) and
 * the persist (setStripeSessionId) are each their own short transaction, with no tx held across the
 * Stripe call in between -- so order.getUser() and order.getItems(), touched by buildParams() after
 * the read tx has already closed, must have been eagerly fetched by the join, not lazily loaded.
 */
@SpringBootTest(properties = {
        "app.stripe.secret-key=sk_test_x",
        "app.stripe.success-url=http://localhost:8080/checkout-success.html",
        "app.stripe.cancel-url=http://localhost:8080/checkout-cancel.html"
})
class StripeCheckoutTransactionTest {

    @Autowired
    private StripeCheckoutService stripeCheckoutService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProductRepository productRepository;

    @MockitoBean
    private StripeClient stripeClient;

    private final CheckoutService checkoutService = mock(CheckoutService.class);
    private final SessionService sessionService = mock(SessionService.class);

    private void stubStripeChain(Session session) {
        when(stripeClient.checkout()).thenReturn(checkoutService);
        when(checkoutService.sessions()).thenReturn(sessionService);
        try {
            when(sessionService.create(any(SessionCreateParams.class), any(RequestOptions.class))).thenReturn(session);
        } catch (com.stripe.exception.StripeException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createSession_afterReadTransactionCloses_doesNotLazyInitFail() {
        User user = userRepository.save(User.builder()
                .email("txtrap-checkout@test.com").passwordHash("x").role("USER").build());
        Product widget = productRepository.save(Product.builder()
                .name("Widget").price(new BigDecimal("19.99")).stockQuantity(10).build());
        Product gadget = productRepository.save(Product.builder()
                .name("Gadget").price(new BigDecimal("5.00")).stockQuantity(10).build());

        // Two items on purpose: the fetch-join fans the order out to multiple rows, so this also
        // exercises Hibernate 6's root deduplication -- with one item, findWithUserAndItemsById
        // returning into an Optional could never fail with IncorrectResultSizeDataAccessException
        // and the "no distinct needed" claim on the finder would go untested.
        Order order = Order.builder()
                .user(user).orderNumber("ORD-TXTRAP1").status(OrderStatus.PENDING)
                .subtotal(new BigDecimal("44.98")).tax(BigDecimal.ZERO).shippingCost(BigDecimal.ZERO)
                .total(new BigDecimal("44.98")).items(new ArrayList<>())
                .build();
        order.getItems().add(OrderItem.builder()
                .order(order).product(widget).productName("Widget")
                .quantity(2).unitPrice(new BigDecimal("19.99")).subtotal(new BigDecimal("39.98")).build());
        order.getItems().add(OrderItem.builder()
                .order(order).product(gadget).productName("Gadget")
                .quantity(1).unitPrice(new BigDecimal("5.00")).subtotal(new BigDecimal("5.00")).build());
        order = orderRepository.save(order);

        Session session = new Session();
        session.setId("cs_test_txtrap1");
        session.setUrl("https://checkout.stripe.com/pay/cs_test_txtrap1");
        stubStripeChain(session);

        Long orderId = order.getId();
        CheckoutSessionResponse[] responseHolder = new CheckoutSessionResponse[1];
        assertThatCode(() -> responseHolder[0] = stripeCheckoutService.createSession(user.getId(), orderId))
                .as("buildParams() walks order.getUser() and order.getItems() after the read tx has "
                        + "closed -- LazyInitializationException here means the fetch-join is incomplete")
                .doesNotThrowAnyException();

        CheckoutSessionResponse response = responseHolder[0];
        assertThat(response.getSessionId()).isEqualTo("cs_test_txtrap1");
        assertThat(response.getUrl()).isEqualTo("https://checkout.stripe.com/pay/cs_test_txtrap1");

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getStripeSessionId()).isEqualTo("cs_test_txtrap1");
        // The persist step is a targeted single-column update -- it must not touch status.
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.PENDING);
    }
}
