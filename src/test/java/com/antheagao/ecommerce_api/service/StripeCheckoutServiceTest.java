package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.config.StripeProperties;
import com.antheagao.ecommerce_api.dto.CheckoutSessionResponse;
import com.antheagao.ecommerce_api.entity.Order;
import com.antheagao.ecommerce_api.entity.OrderItem;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.exception.ServiceUnavailableException;
import com.antheagao.ecommerce_api.repository.OrderRepository;
import com.stripe.StripeClient;
import com.stripe.exception.ApiException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.checkout.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeCheckoutServiceTest {

    @Mock
    private StripeClient stripeClient;

    @Mock
    private CheckoutService checkoutService;

    @Mock
    private SessionService sessionService;

    @Mock
    private OrderRepository orderRepository;

    private static StripeProperties configuredProperties() {
        StripeProperties props = new StripeProperties();
        props.setSecretKey("sk_test_123");
        props.setSuccessUrl("http://localhost:8080/checkout-success.html");
        props.setCancelUrl("http://localhost:8080/checkout-cancel.html");
        props.setCurrency("usd");
        return props;
    }

    private static Order orderWith(BigDecimal tax, BigDecimal shippingCost) {
        User user = User.builder().id(1L).build();
        Order order = Order.builder()
                .id(10L)
                .user(user)
                .orderNumber("ORD-00042")
                .status(OrderStatus.PENDING)
                .subtotal(new BigDecimal("39.98"))
                .tax(tax)
                .shippingCost(shippingCost)
                .total(new BigDecimal("39.98").add(tax == null ? BigDecimal.ZERO : tax).add(shippingCost == null ? BigDecimal.ZERO : shippingCost))
                .items(new ArrayList<>())
                .build();
        OrderItem item1 = OrderItem.builder().id(100L).order(order).productName("Widget")
                .quantity(2).unitPrice(new BigDecimal("19.99")).subtotal(new BigDecimal("39.98")).build();
        order.getItems().add(item1);
        return order;
    }

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
    void createSession_whenValid_buildsSessionAndPersistsId() throws Exception {
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, configuredProperties(), orderRepository);
        Order order = orderWith(BigDecimal.ZERO, BigDecimal.ZERO);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        Session session = new Session();
        session.setId("cs_test_abc");
        session.setUrl("https://checkout.stripe.com/pay/cs_test_abc");
        stubStripeChain(session);

        CheckoutSessionResponse response = service.createSession(1L, 10L);

        assertThat(response.getSessionId()).isEqualTo("cs_test_abc");
        assertThat(response.getUrl()).isEqualTo("https://checkout.stripe.com/pay/cs_test_abc");
        // Persisted via the targeted setStripeSessionId(...) update, not save(order) -- save() would
        // flush the whole findById()-loaded snapshot and could stomp a concurrent status transition.
        verify(orderRepository).setStripeSessionId(10L, "cs_test_abc");
        verify(orderRepository, never()).save(any());

        ArgumentCaptor<SessionCreateParams> paramsCaptor = ArgumentCaptor.forClass(SessionCreateParams.class);
        ArgumentCaptor<RequestOptions> optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(sessionService).create(paramsCaptor.capture(), optionsCaptor.capture());

        SessionCreateParams params = paramsCaptor.getValue();
        assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.PAYMENT);
        assertThat(params.getClientReferenceId()).isEqualTo("ORD-00042");
        assertThat(params.getMetadata()).containsEntry("orderId", "10");
        assertThat(params.getSuccessUrl()).isEqualTo("http://localhost:8080/checkout-success.html");
        assertThat(params.getCancelUrl()).isEqualTo("http://localhost:8080/checkout-cancel.html");
        assertThat(params.getLineItems()).hasSize(1);
        assertThat(params.getLineItems().get(0).getQuantity()).isEqualTo(2L);
        assertThat(params.getLineItems().get(0).getPriceData().getCurrency()).isEqualTo("usd");
        // 19.99 major units -> 1999 minor units, via movePointRight(2), never doubleValue().
        assertThat(params.getLineItems().get(0).getPriceData().getUnitAmount()).isEqualTo(1999L);
        assertThat(params.getLineItems().get(0).getPriceData().getProductData().getName()).isEqualTo("Widget");

        assertThat(optionsCaptor.getValue().getIdempotencyKey()).isEqualTo("ORD-00042");
    }

    @Test
    void createSession_whenTaxAndShippingNonZero_addsLineItemsSummingToTotal() throws Exception {
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, configuredProperties(), orderRepository);
        Order order = orderWith(new BigDecimal("3.50"), new BigDecimal("5.00"));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        Session session = new Session();
        session.setId("cs_test_abc");
        session.setUrl("https://checkout.stripe.com/pay/cs_test_abc");
        stubStripeChain(session);

        service.createSession(1L, 10L);

        ArgumentCaptor<SessionCreateParams> paramsCaptor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(paramsCaptor.capture(), any(RequestOptions.class));
        List<SessionCreateParams.LineItem> lineItems = paramsCaptor.getValue().getLineItems();

        assertThat(lineItems).hasSize(3);
        long sumMinorUnits = lineItems.stream()
                .mapToLong(li -> li.getPriceData().getUnitAmount() * li.getQuantity())
                .sum();
        // order.getTotal() = 39.98 + 3.50 + 5.00 = 48.48 -> 4848 minor units.
        assertThat(sumMinorUnits).isEqualTo(order.getTotal().movePointRight(2).longValueExact());
        assertThat(lineItems.get(1).getPriceData().getProductData().getName()).isEqualTo("Tax");
        assertThat(lineItems.get(1).getPriceData().getUnitAmount()).isEqualTo(350L);
        assertThat(lineItems.get(2).getPriceData().getProductData().getName()).isEqualTo("Shipping");
        assertThat(lineItems.get(2).getPriceData().getUnitAmount()).isEqualTo(500L);
    }

    @Test
    void createSession_whenSecretKeyBlank_throwsServiceUnavailableAndNeverCallsStripe() {
        StripeProperties props = configuredProperties();
        props.setSecretKey("");
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, props, orderRepository);

        assertThatThrownBy(() -> service.createSession(1L, 10L))
                .isInstanceOf(ServiceUnavailableException.class);

        verifyNoInteractions(stripeClient, orderRepository);
    }

    @Test
    void createSession_whenSuccessUrlBlank_throwsServiceUnavailableAndNeverCallsStripe() {
        StripeProperties props = configuredProperties();
        props.setSuccessUrl("");
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, props, orderRepository);

        assertThatThrownBy(() -> service.createSession(1L, 10L))
                .isInstanceOf(ServiceUnavailableException.class);

        verifyNoInteractions(stripeClient, orderRepository);
    }

    @Test
    void createSession_whenCancelUrlBlank_throwsServiceUnavailableAndNeverCallsStripe() {
        StripeProperties props = configuredProperties();
        props.setCancelUrl("");
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, props, orderRepository);

        assertThatThrownBy(() -> service.createSession(1L, 10L))
                .isInstanceOf(ServiceUnavailableException.class);

        verifyNoInteractions(stripeClient, orderRepository);
    }

    @Test
    void createSession_whenOrderMissing_throwsResourceNotFoundException() {
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, configuredProperties(), orderRepository);
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSession(1L, 999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Order not found");

        verifyNoInteractions(stripeClient);
    }

    @Test
    void createSession_whenWrongOwner_throwsResourceNotFoundException() {
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, configuredProperties(), orderRepository);
        Order order = orderWith(BigDecimal.ZERO, BigDecimal.ZERO);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createSession(999L, 10L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Order not found");

        verifyNoInteractions(stripeClient);
    }

    @Test
    void createSession_whenOrderNotPending_throwsConflictException() {
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, configuredProperties(), orderRepository);
        Order order = orderWith(BigDecimal.ZERO, BigDecimal.ZERO);
        order.setStatus(OrderStatus.PAID);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createSession(1L, 10L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not PENDING");

        verifyNoInteractions(stripeClient);
    }

    @Test
    void createSession_whenStripeThrows_wrapsInServiceUnavailableException() throws Exception {
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, configuredProperties(), orderRepository);
        Order order = orderWith(BigDecimal.ZERO, BigDecimal.ZERO);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(stripeClient.checkout()).thenReturn(checkoutService);
        when(checkoutService.sessions()).thenReturn(sessionService);
        when(sessionService.create(any(SessionCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiException("boom", "req_123", null, 500, null));

        assertThatThrownBy(() -> service.createSession(1L, 10L))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(orderRepository, never()).setStripeSessionId(any(), any());
    }

    @Test
    void createSession_whenTaxNegative_throwsIllegalStateExceptionAndNeverCallsStripe() {
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, configuredProperties(), orderRepository);
        Order order = orderWith(new BigDecimal("-1.00"), BigDecimal.ZERO);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createSession(1L, 10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Negative tax/shipping");

        verifyNoInteractions(stripeClient);
        verify(orderRepository, never()).setStripeSessionId(any(), any());
    }

    @Test
    void createSession_whenShippingNegative_throwsIllegalStateExceptionAndNeverCallsStripe() {
        StripeCheckoutService service = new StripeCheckoutService(stripeClient, configuredProperties(), orderRepository);
        Order order = orderWith(BigDecimal.ZERO, new BigDecimal("-2.00"));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createSession(1L, 10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Negative tax/shipping");

        verifyNoInteractions(stripeClient);
        verify(orderRepository, never()).setStripeSessionId(any(), any());
    }
}
