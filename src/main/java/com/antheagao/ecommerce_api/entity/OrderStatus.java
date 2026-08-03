package com.antheagao.ecommerce_api.entity;

/**
 * Order lifecycle state. SHIPPED and DELIVERED both carry the "FULFILLED" semantics from the
 * payment-flow state machine: PENDING -> PAID -> FULFILLED / REFUNDED / FAILED. SHIPPED and
 * DELIVERED are the two granular fulfillment sub-states tracked here instead of a single
 * FULFILLED value.
 */
public enum OrderStatus {
    PENDING,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    FAILED,
    REFUNDED
}
