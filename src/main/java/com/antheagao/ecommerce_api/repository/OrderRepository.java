package com.antheagao.ecommerce_api.repository;

import com.antheagao.ecommerce_api.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    // Fetch-joins user and items so the caller can read them after a short read-only tx closes --
    // used by StripeCheckoutService, which must not hold a DB connection across the Stripe network
    // call. Hibernate 6 dedupes the root entity automatically, so no `distinct` is needed even
    // though the items join fans out rows.
    @Query("select o from Order o join fetch o.user left join fetch o.items where o.id = :orderId")
    Optional<Order> findWithUserAndItemsById(@Param("orderId") Long orderId);

    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    boolean existsByOrderNumber(String orderNumber);

    Optional<Order> findByStripeSessionId(String stripeSessionId);

    Optional<Order> findByPaymentReference(String paymentReference);

    // Targeted single-column update instead of save(order): save() flushes every column from the
    // findById()-loaded snapshot, so a concurrent status transition (admin PATCH, or S7's webhook
    // handler) landing between that load and this transaction's commit would get silently overwritten
    // back to the stale status. This statement touches only stripe_session_id.
    // @Transactional here because callers (StripeCheckoutService) no longer wrap this in an outer
    // tx -- the Stripe call between the read and this write must not hold a connection, so this
    // @Modifying query needs its own short transaction rather than borrowing the caller's.
    @Transactional
    @Modifying
    @Query("UPDATE Order o SET o.stripeSessionId = :sessionId WHERE o.id = :orderId")
    int setStripeSessionId(@Param("orderId") Long orderId, @Param("sessionId") String sessionId);
}
