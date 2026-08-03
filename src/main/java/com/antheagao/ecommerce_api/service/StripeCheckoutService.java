package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.config.StripeProperties;
import com.antheagao.ecommerce_api.dto.CheckoutSessionResponse;
import com.antheagao.ecommerce_api.entity.Order;
import com.antheagao.ecommerce_api.entity.OrderItem;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.exception.ServiceUnavailableException;
import com.antheagao.ecommerce_api.repository.OrderRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeCheckoutService {

    private final StripeClient stripeClient;
    private final StripeProperties stripeProperties;
    private final OrderRepository orderRepository;

    @Transactional
    public CheckoutSessionResponse createSession(Long userId, Long orderId) {
        // Gate on the specific fields this call uses, not just isConfigured() (which only checks
        // secretKey) -- a blank successUrl/cancelUrl would still build a SessionCreateParams that
        // either fails confusingly at Stripe or redirects the customer nowhere.
        if (isBlank(stripeProperties.getSecretKey()) || isBlank(stripeProperties.getSuccessUrl())
                || isBlank(stripeProperties.getCancelUrl())) {
            throw new ServiceUnavailableException("Payments are not configured");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ConflictException("Order " + order.getOrderNumber() + " is not PENDING (current status: " + order.getStatus() + ")");
        }

        SessionCreateParams params = buildParams(order);
        RequestOptions requestOptions = RequestOptions.builder()
                // Order number as the idempotency key: a double-click on "checkout" can't create two
                // Stripe sessions for the same order. If the order already has a stripeSessionId (e.g.
                // an earlier session expired), re-creating with the same key is still fine -- Stripe's
                // idempotency window is 24h, sessions themselves expire well within that, and
                // overwriting stripeSessionId below with the freshly returned id keeps
                // findByStripeSessionId coherent (S7's webhook handling keys off metadata.orderId
                // primarily anyway).
                .setIdempotencyKey(order.getOrderNumber())
                .build();

        Session session;
        try {
            session = stripeClient.checkout().sessions().create(params, requestOptions);
        } catch (StripeException e) {
            log.error("Stripe Checkout Session creation failed for order {}", order.getOrderNumber(), e);
            throw new ServiceUnavailableException("Unable to start checkout");
        }

        // Targeted update, not orderRepository.save(order): save() would flush the whole entity as
        // loaded at the top of this method, which could stomp a status transition (admin PATCH, or
        // S7's webhook handler) that lands concurrently between that load and this commit.
        orderRepository.setStripeSessionId(order.getId(), session.getId());

        return CheckoutSessionResponse.builder()
                .sessionId(session.getId())
                .url(session.getUrl())
                .build();
    }

    private SessionCreateParams buildParams(Order order) {
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setClientReferenceId(order.getOrderNumber())
                .putMetadata("orderId", order.getId().toString())
                .setSuccessUrl(stripeProperties.getSuccessUrl())
                .setCancelUrl(stripeProperties.getCancelUrl());

        for (OrderItem item : order.getItems()) {
            builder.addLineItem(lineItem(item.getProductName(), item.getUnitPrice(), item.getQuantity().longValue()));
        }
        // order.getTotal() = subtotal + tax + shippingCost. persistOrder() (OrderService) currently
        // always zeroes tax and shippingCost, so today total always equals the item line sum -- but
        // that must not silently drift if a future change makes them non-zero, since S7 compares
        // session.getAmountTotal() against order.getTotal(). Only strictly positive values become their
        // own line item (a Stripe line item can't carry a negative/zero unit_amount) -- negative
        // tax/shippingCost is rejected outright below rather than silently dropped, since dropping it
        // would make the session total exceed order.getTotal() and trip S7's amount check.
        if (isNegative(order.getTax()) || isNegative(order.getShippingCost())) {
            throw new IllegalStateException("Negative tax/shipping not supported for Stripe checkout");
        }
        if (order.getTax() != null && order.getTax().compareTo(BigDecimal.ZERO) > 0) {
            builder.addLineItem(lineItem("Tax", order.getTax(), 1L));
        }
        if (order.getShippingCost() != null && order.getShippingCost().compareTo(BigDecimal.ZERO) > 0) {
            builder.addLineItem(lineItem("Shipping", order.getShippingCost(), 1L));
        }

        return builder.build();
    }

    private SessionCreateParams.LineItem lineItem(String name, BigDecimal unitPrice, long quantity) {
        SessionCreateParams.LineItem.PriceData priceData = SessionCreateParams.LineItem.PriceData.builder()
                .setCurrency(stripeProperties.getCurrency())
                // movePointRight(2) converts major units (dollars) to minor units (cents) via exact
                // BigDecimal scale arithmetic; longValueExact() throws rather than silently truncating
                // if unitPrice ever carries more than 2 decimal places. Never doubleValue() here --
                // binary floating point can't represent currency amounts exactly.
                .setUnitAmount(unitPrice.movePointRight(2).longValueExact())
                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                        .setName(name)
                        .build())
                .build();
        return SessionCreateParams.LineItem.builder()
                .setQuantity(quantity)
                .setPriceData(priceData)
                .build();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }
}
