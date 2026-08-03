package com.antheagao.ecommerce_api.exception;

import com.antheagao.ecommerce_api.entity.OrderStatus;

/**
 * Thrown by OrderService.applyTransition instead of a bare IllegalArgumentException. Extends
 * IllegalArgumentException (not RuntimeException directly) so GlobalExceptionHandler's existing
 * IllegalArgumentException -> 400 mapping, and every OrderServiceTest assertion of the form
 * {@code isInstanceOf(IllegalArgumentException.class)} with message "Invalid status transition",
 * keep passing unchanged. Carrying (from, to) lets StripeWebhookService catch this specifically and
 * log the attempted transition instead of just its message.
 */
public class InvalidTransitionException extends IllegalArgumentException {

    private final OrderStatus from;
    private final OrderStatus to;

    public InvalidTransitionException(OrderStatus from, OrderStatus to) {
        super("Invalid status transition from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus getFrom() {
        return from;
    }

    public OrderStatus getTo() {
        return to;
    }
}
