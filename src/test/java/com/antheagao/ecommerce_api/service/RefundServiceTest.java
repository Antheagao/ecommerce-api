package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.config.StripeProperties;
import com.antheagao.ecommerce_api.dto.RefundResponse;
import com.antheagao.ecommerce_api.entity.Order;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.exception.ServiceUnavailableException;
import com.antheagao.ecommerce_api.repository.OrderRepository;
import com.stripe.StripeClient;
import com.stripe.exception.ApiException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock
    private StripeClient stripeClient;

    // Fully-qualified, not imported: com.stripe.service.RefundService shares its simple name with
    // the class under test (this package's RefundService), so importing both is a compile error.
    @Mock
    private com.stripe.service.RefundService stripeRefundsService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderService orderService;

    private static StripeProperties configuredProperties() {
        StripeProperties props = new StripeProperties();
        props.setSecretKey("sk_test_123");
        return props;
    }

    private static Order orderWith(OrderStatus status, String paymentReference) {
        User user = User.builder().id(1L).build();
        return Order.builder()
                .id(10L)
                .user(user)
                .orderNumber("ORD-00042")
                .status(status)
                .paymentReference(paymentReference)
                .subtotal(new BigDecimal("39.98"))
                .tax(BigDecimal.ZERO)
                .shippingCost(BigDecimal.ZERO)
                .total(new BigDecimal("39.98"))
                .build();
    }

    private void stubStripeChain(Refund refund) throws Exception {
        when(stripeClient.refunds()).thenReturn(stripeRefundsService);
        when(stripeRefundsService.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(refund);
    }

    @ParameterizedTest(name = "{0} is refundable")
    @EnumSource(value = OrderStatus.class, names = {"PAID", "SHIPPED", "DELIVERED"})
    void refund_whenRefundableStatus_issuesStripeRefundAndTransitionsOrder(OrderStatus status) throws Exception {
        RefundService service = new RefundService(stripeClient, configuredProperties(), orderRepository, orderService);
        Order order = orderWith(status, "pi_test_abc");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        Refund refund = new Refund();
        refund.setId("re_test_abc");
        refund.setStatus("succeeded");
        stubStripeChain(refund);

        RefundResponse response = service.refund(10L);

        assertThat(response.getOrderId()).isEqualTo(10L);
        assertThat(response.getOrderNumber()).isEqualTo("ORD-00042");
        assertThat(response.getRefundId()).isEqualTo("re_test_abc");
        assertThat(response.getRefundStatus()).isEqualTo("succeeded");
        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);

        ArgumentCaptor<RefundCreateParams> paramsCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefundsService).create(paramsCaptor.capture(), optionsCaptor.capture());
        assertThat(paramsCaptor.getValue().getPaymentIntent()).isEqualTo("pi_test_abc");
        assertThat(optionsCaptor.getValue().getIdempotencyKey()).isEqualTo("refund-ORD-00042");

        // null paymentReference: deliberately does NOT overwrite the pi_... reference the subsequent
        // charge.refunded webhook resolves the order by.
        verify(orderService).transitionSystem(eq(10L), eq(OrderStatus.REFUNDED), isNull());
    }

    @ParameterizedTest(name = "{0} is not refundable")
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "CANCELLED", "FAILED", "REFUNDED"})
    void refund_whenNotRefundableStatus_throwsConflictAndNeverCallsStripe(OrderStatus status) {
        RefundService service = new RefundService(stripeClient, configuredProperties(), orderRepository, orderService);
        Order order = orderWith(status, "pi_test_abc");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.refund(10L))
                .isInstanceOf(ConflictException.class);

        verifyNoInteractions(stripeClient);
        verify(orderService, never()).transitionSystem(any(), any(), any());
    }

    @Test
    void refund_whenPaymentReferenceBlank_throwsConflictAndNeverCallsStripe() {
        RefundService service = new RefundService(stripeClient, configuredProperties(), orderRepository, orderService);
        Order order = orderWith(OrderStatus.PAID, "");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.refund(10L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("never paid through Stripe");

        verifyNoInteractions(stripeClient);
    }

    @Test
    void refund_whenOrderMissing_throwsResourceNotFoundException() {
        RefundService service = new RefundService(stripeClient, configuredProperties(), orderRepository, orderService);
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Order not found");

        verifyNoInteractions(stripeClient);
    }

    @Test
    void refund_whenSecretKeyBlank_throwsServiceUnavailableAndNeverCallsStripe() {
        StripeProperties props = configuredProperties();
        props.setSecretKey("");
        RefundService service = new RefundService(stripeClient, props, orderRepository, orderService);

        assertThatThrownBy(() -> service.refund(10L))
                .isInstanceOf(ServiceUnavailableException.class);

        verifyNoInteractions(stripeClient, orderRepository);
    }

    @Test
    void refund_whenStripeThrows_wrapsInServiceUnavailableException() throws Exception {
        RefundService service = new RefundService(stripeClient, configuredProperties(), orderRepository, orderService);
        Order order = orderWith(OrderStatus.PAID, "pi_test_abc");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(stripeClient.refunds()).thenReturn(stripeRefundsService);
        when(stripeRefundsService.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiException("boom", "req_123", null, 500, null));

        assertThatThrownBy(() -> service.refund(10L))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(orderService, never()).transitionSystem(any(), any(), any());
    }

    @Test
    void refund_whenChargeAlreadyRefundedOnStripe_healsOrderInsteadOfThrowing() throws Exception {
        RefundService service = new RefundService(stripeClient, configuredProperties(), orderRepository, orderService);
        Order order = orderWith(OrderStatus.PAID, "pi_test_abc");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(stripeClient.refunds()).thenReturn(stripeRefundsService);
        // Constructor verified via javap -l (LocalVariableTable) against the 33.2.0 jar:
        // InvalidRequestException(String message, String param, String requestId, String code,
        // Integer statusCode, Throwable e).
        when(stripeRefundsService.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new InvalidRequestException(
                        "The charge has already been refunded.", null, "req_123", "charge_already_refunded", 400, null));

        RefundResponse response = service.refund(10L);

        assertThat(response.getOrderId()).isEqualTo(10L);
        assertThat(response.getOrderNumber()).isEqualTo("ORD-00042");
        assertThat(response.getRefundId()).isNull();
        assertThat(response.getRefundStatus()).isEqualTo("already_refunded");
        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);

        verify(orderService).transitionSystem(eq(10L), eq(OrderStatus.REFUNDED), isNull());
    }
}
