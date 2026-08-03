package com.antheagao.ecommerce_api.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

/**
 * Idempotency ledger for Stripe webhook events (S7). eventId is Stripe's own event id, used as the
 * primary key: {@link com.antheagao.ecommerce_api.service.StripeWebhookService#process} inserts a
 * row for every event it receives, and a replayed event id hits the PK's unique constraint instead
 * of being silently reprocessed.
 */
@Entity
@Table(name = "processed_stripe_events")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedStripeEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "order_id")
    private Long orderId;

    public ProcessedStripeEvent(String eventId, String eventType, Long orderId) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.orderId = orderId;
    }

    @PrePersist
    protected void onCreate() {
        receivedAt = LocalDateTime.now();
    }

    // eventId is an assigned (not generated) natural id, always non-null by the time this entity is
    // constructed. Spring Data's default isNew() check treats a non-null @Id as "already exists" and
    // routes repository.save()/saveAndFlush() through EntityManager.merge() instead of persist() --
    // merge() on a duplicate id would SELECT the existing row, find it, and silently UPDATE it rather
    // than throwing. That would break the whole idempotency mechanism (StripeWebhookService relies on
    // saveAndFlush() throwing DataIntegrityViolationException on a replayed event id). Always
    // returning true here forces persist(), which actually attempts the INSERT and lets the DB's
    // primary key constraint do its job. Correct because these rows are write-once and never updated.
    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
