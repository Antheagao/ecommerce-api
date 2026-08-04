package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.config.StripeProperties;
import com.antheagao.ecommerce_api.dto.RefundResponse;
import com.antheagao.ecommerce_api.entity.Order;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.exception.ServiceUnavailableException;
import com.antheagao.ecommerce_api.repository.OrderRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * Admin-initiated refund: issues a Stripe refund against an order's payment intent, then moves the
 * order to REFUNDED in the same transaction.
 * <p>
 * Stock is NOT restored on refund -- inventory reservation/restocking on refund is out of scope for
 * this batch. A refunded order stays decremented in stock exactly as it was when placed.
 * <p>
 * Design note: if this transaction fails AFTER the Stripe refund call above succeeds (e.g. the
 * transitionSystem call or the commit itself throws), the refund exists on Stripe's side but the
 * order is not REFUNDED locally -- that state isn't silently lost. Stripe's charge.refunded webhook
 * (StripeWebhookService.handleChargeRefunded) is the reconciliation path: it resolves the order via
 * paymentReference independently of this call and applies the REFUNDED transition whenever Stripe
 * delivers (or redelivers) that event. This is exactly why webhook tolerance -- swallowing
 * InvalidTransitionException on an already-REFUNDED order -- matters even for admin-initiated
 * refunds, not just customer checkouts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final Set<OrderStatus> REFUNDABLE = EnumSet.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    private final StripeClient stripeClient;
    private final StripeProperties stripeProperties;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Transactional
    public RefundResponse refund(Long orderId) {
        // Gate on the specific field this call uses (secretKey), not isConfigured() -- same
        // convention as StripeCheckoutService/S5-review: check exactly what this call needs.
        if (isBlank(stripeProperties.getSecretKey())) {
            throw new ServiceUnavailableException("Payments are not configured");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        MDC.put("orderNumber", order.getOrderNumber());
        try {
            if (!REFUNDABLE.contains(order.getStatus())) {
                throw new ConflictException("Order " + order.getOrderNumber() + " cannot be refunded (current status: " + order.getStatus() + ")");
            }
            if (isBlank(order.getPaymentReference())) {
                throw new ConflictException("Order " + order.getOrderNumber() + " was never paid through Stripe");
            }
            log.info("event=refund.requested orderId={}", orderId);

            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(order.getPaymentReference())
                    .build();
            RequestOptions requestOptions = RequestOptions.builder()
                    // Order number as the idempotency key: an admin double-click on "refund" can't
                    // create two refunds for the same order.
                    .setIdempotencyKey("refund-" + order.getOrderNumber())
                    .build();

            Refund refund;
            try {
                refund = stripeClient.refunds().create(params, requestOptions);
            } catch (StripeException e) {
                log.error("event=refund.failed", e);
                throw new ServiceUnavailableException("Unable to process refund");
            }

            log.info("event=refund.created refundId={} status={}", refund.getId(), refund.getStatus());

            // null, not refund.getId(): overwriting paymentReference with the re_... id would clobber the
            // pi_... reference that StripeWebhookService.resolveOrderIdFromCharge matches the subsequent
            // charge.refunded event against. paymentReference already records the payment (set to the
            // PaymentIntent id at PAID), so there's nothing meaningful to overwrite it with here. This
            // deviates from the original task-board wording ("transitionSystem(REFUNDED, refundId)")
            // deliberately, mirroring the F3 convention already applied in
            // StripeWebhookService.handleChargeRefunded.
            orderService.transitionSystem(orderId, OrderStatus.REFUNDED, null);

            return RefundResponse.builder()
                    .orderId(order.getId())
                    .orderNumber(order.getOrderNumber())
                    .refundId(refund.getId())
                    .refundStatus(refund.getStatus())
                    .orderStatus(OrderStatus.REFUNDED)
                    .build();
        } finally {
            MDC.remove("orderNumber");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
