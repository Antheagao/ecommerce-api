package com.antheagao.ecommerce_api.repository;

import com.antheagao.ecommerce_api.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    boolean existsByOrderNumber(String orderNumber);

    Optional<Order> findByStripeSessionId(String stripeSessionId);

    // Targeted single-column update instead of save(order): save() flushes every column from the
    // findById()-loaded snapshot, so a concurrent status transition (admin PATCH, or S7's webhook
    // handler) landing between that load and this transaction's commit would get silently overwritten
    // back to the stale status. This statement touches only stripe_session_id.
    @Modifying
    @Query("UPDATE Order o SET o.stripeSessionId = :sessionId WHERE o.id = :orderId")
    int setStripeSessionId(@Param("orderId") Long orderId, @Param("sessionId") String sessionId);
}
