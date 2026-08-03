package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.entity.Order;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.entity.ProcessedStripeEvent;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.exception.InvalidTransitionException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.OrderRepository;
import com.antheagao.ecommerce_api.repository.ProcessedStripeEventRepository;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the transactional event-processing logic. Events are real com.stripe.model.Event
 * instances built via ApiResource.GSON.fromJson(...) (the same Gson instance the SDK itself uses),
 * not hand-built/mocked -- Event's data-object deserialization plumbing
 * (getDataObjectDeserializer()) relies on package-private wiring that only a real Gson parse sets up
 * correctly. Fixtures use the SDK's current pinned api_version (com.stripe.Stripe.API_VERSION) so
 * event.getDataObjectDeserializer().getObject() takes the typed path; one test deliberately uses a
 * stale api_version to prove the raw-JSON Gson fallback (S7's deserialization-robustness requirement)
 * also works.
 */
@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceTest {

    private static final String CURRENT_API_VERSION = com.stripe.Stripe.API_VERSION;

    @Mock
    private ProcessedStripeEventRepository processedStripeEventRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderService orderService;

    private StripeWebhookService service() {
        return new StripeWebhookService(processedStripeEventRepository, orderRepository, orderService);
    }

    private static Event eventFrom(String json) {
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    private static String checkoutSessionCompletedJson(String apiVersion, String eventId, String sessionId,
                                                          long amountTotal, String orderIdMeta, String clientReferenceId,
                                                          String paymentIntentId) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "type": "checkout.session.completed",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "checkout.session",
                      "amount_total": %d,
                      "client_reference_id": "%s",
                      "metadata": %s,
                      "payment_intent": "%s"
                    }
                  }
                }
                """.formatted(eventId, apiVersion, sessionId, amountTotal, clientReferenceId,
                orderIdMeta == null ? "{}" : "{\"orderId\": \"" + orderIdMeta + "\"}", paymentIntentId);
    }

    private static Order orderWith(Long id, BigDecimal total, String stripeSessionId, OrderStatus status) {
        return Order.builder()
                .id(id)
                .user(User.builder().id(1L).build())
                .orderNumber("ORD-%05d".formatted(id))
                .status(status)
                .total(total)
                .stripeSessionId(stripeSessionId)
                .paymentReference(null)
                .build();
    }

    @Test
    void checkoutSessionCompleted_amountAndSessionMatch_transitionsToPaidWithPaymentIntent() {
        String json = checkoutSessionCompletedJson(CURRENT_API_VERSION, "evt_1", "cs_test_abc", 3998, "10", "ORD-00010", "pi_test_123");
        Event event = eventFrom(json);
        Order order = orderWith(10L, new BigDecimal("39.98"), "cs_test_abc", OrderStatus.PENDING);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        service().process(event);

        ArgumentCaptor<ProcessedStripeEvent> captor = ArgumentCaptor.forClass(ProcessedStripeEvent.class);
        verify(processedStripeEventRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo("evt_1");
        assertThat(captor.getValue().getEventType()).isEqualTo("checkout.session.completed");
        assertThat(captor.getValue().getOrderId()).isEqualTo(10L);

        verify(orderService).transitionSystem(10L, OrderStatus.PAID, "pi_test_123");
    }

    @Test
    void checkoutSessionCompleted_viaStaleApiVersion_fallsBackToRawJsonAndStillWorks() {
        // "2020-08-27" will never match Stripe.API_VERSION, forcing
        // EventDataObjectDeserializer.getObject() to Optional.empty() and exercising
        // StripeWebhookService's ApiResource.GSON.fromJson(getRawJson(), ...) fallback.
        String json = checkoutSessionCompletedJson("2020-08-27", "evt_stale", "cs_test_stale", 3998, "11", "ORD-00011", "pi_test_stale");
        Event event = eventFrom(json);
        Order order = orderWith(11L, new BigDecimal("39.98"), "cs_test_stale", OrderStatus.PENDING);
        when(orderRepository.findById(11L)).thenReturn(Optional.of(order));

        service().process(event);

        verify(orderService).transitionSystem(11L, OrderStatus.PAID, "pi_test_stale");
    }

    @Test
    void duplicateEventId_dataIntegrityViolationPropagates_noTransitionAttempted() {
        String json = checkoutSessionCompletedJson(CURRENT_API_VERSION, "evt_dup", "cs_test_abc", 3998, "10", "ORD-00010", "pi_test_123");
        Event event = eventFrom(json);
        when(processedStripeEventRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> service().process(event))
                .isInstanceOf(DataIntegrityViolationException.class);

        verifyNoInteractions(orderService);
        verify(orderRepository, never()).findById(any());
    }

    @Test
    void checkoutSessionCompleted_amountMismatch_noTransitionEventStillRecorded() {
        String json = checkoutSessionCompletedJson(CURRENT_API_VERSION, "evt_2", "cs_test_abc", 999, "10", "ORD-00010", "pi_test_123");
        Event event = eventFrom(json);
        Order order = orderWith(10L, new BigDecimal("39.98"), "cs_test_abc", OrderStatus.PENDING);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verifyNoInteractions(orderService);
    }

    @Test
    void checkoutSessionCompleted_sessionIdMismatch_noTransitionEventStillRecorded() {
        String json = checkoutSessionCompletedJson(CURRENT_API_VERSION, "evt_3", "cs_test_superseded", 3998, "10", "ORD-00010", "pi_test_123");
        Event event = eventFrom(json);
        // order's CURRENT session is a different (newer) one than the session this event completed for
        Order order = orderWith(10L, new BigDecimal("39.98"), "cs_test_current", OrderStatus.PENDING);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verifyNoInteractions(orderService);
    }

    // S8 coverage: metadata.orderId is present but not a valid Long, and client_reference_id is
    // blank -- exercises resolveOrderIdFromSession's NumberFormatException catch, falling through to
    // its final "nothing left to try" return null.
    @Test
    void checkoutSessionCompleted_nonNumericMetadataOrderId_fallsThroughToNullOrderId() {
        String json = checkoutSessionCompletedJson(CURRENT_API_VERSION, "evt_15", "cs_test_nonnumeric", 3998, "not-a-number", "", "pi_test_nonnumeric");
        Event event = eventFrom(json);

        service().process(event);

        ArgumentCaptor<ProcessedStripeEvent> captor = ArgumentCaptor.forClass(ProcessedStripeEvent.class);
        verify(processedStripeEventRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderId()).isNull();
        verifyNoInteractions(orderService);
    }

    // S8 coverage: metadata.orderId absent, client_reference_id present -- resolveOrderIdFromSession
    // falls through to the orderRepository.findByOrderNumber(clientReferenceId) lookup, which this
    // test makes throw on its FIRST call (the best-effort resolution done before the event row is
    // saved). Exercises resolveOrderIdBestEffort's own "swallow everything, record the event anyway"
    // RuntimeException catch. The second call (inside handleCheckoutSessionCompleted itself, which has
    // no such guard) is stubbed to return empty so the test isolates the best-effort path specifically.
    @Test
    void resolveOrderIdBestEffort_clientReferenceIdLookupThrows_recordsEventWithNullOrderId() {
        String json = checkoutSessionCompletedJson(CURRENT_API_VERSION, "evt_14", "cs_test_throws", 3998, null, "ORD-THROWS", "pi_test_throws");
        Event event = eventFrom(json);
        when(orderRepository.findByOrderNumber("ORD-THROWS"))
                .thenThrow(new RuntimeException("simulated repository failure"))
                .thenReturn(Optional.empty());

        service().process(event);

        ArgumentCaptor<ProcessedStripeEvent> captor = ArgumentCaptor.forClass(ProcessedStripeEvent.class);
        verify(processedStripeEventRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderId()).isNull();
        verifyNoInteractions(orderService);
    }

    @Test
    void checkoutSessionExpired_transitionsToFailed() {
        String json = """
                {
                  "id": "evt_4",
                  "object": "event",
                  "api_version": "%s",
                  "type": "checkout.session.expired",
                  "data": {
                    "object": {
                      "id": "cs_test_abc",
                      "object": "checkout.session",
                      "client_reference_id": "ORD-00010",
                      "metadata": { "orderId": "10" }
                    }
                  }
                }
                """.formatted(CURRENT_API_VERSION);
        Event event = eventFrom(json);

        service().process(event);

        verify(orderService).transitionSystem(10L, OrderStatus.FAILED, null);
    }

    @Test
    void paymentIntentPaymentFailed_recordsOnly_noTransition() {
        String json = """
                {
                  "id": "evt_5",
                  "object": "event",
                  "api_version": "%s",
                  "type": "payment_intent.payment_failed",
                  "data": { "object": { "id": "pi_test_failed", "object": "payment_intent" } }
                }
                """.formatted(CURRENT_API_VERSION);
        Event event = eventFrom(json);

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verifyNoInteractions(orderService);
    }

    @Test
    void chargeRefunded_resolvedViaPaymentReference_transitionsToRefundedWithNullPaymentReference() {
        String json = """
                {
                  "id": "evt_6",
                  "object": "event",
                  "api_version": "%s",
                  "type": "charge.refunded",
                  "data": { "object": { "id": "ch_test_1", "object": "charge", "payment_intent": "pi_test_123",
                             "refunded": true, "amount_refunded": 3998 } }
                }
                """.formatted(CURRENT_API_VERSION);
        Event event = eventFrom(json);
        Order order = orderWith(10L, new BigDecimal("39.98"), "cs_test_abc", OrderStatus.PAID);
        when(orderRepository.findByPaymentReference("pi_test_123")).thenReturn(Optional.of(order));

        service().process(event);

        // null, not charge.getId(): overwriting paymentReference with the ch_... id would clobber the
        // pi_... reference that resolveOrderIdFromCharge() matches subsequent charge.refunded events
        // against (partial-then-full refund would then be unresolvable).
        verify(orderService).transitionSystem(10L, OrderStatus.REFUNDED, null);
    }

    @Test
    void chargeRefunded_unresolvableOrder_recordsOnlyNoTransition() {
        String json = """
                {
                  "id": "evt_7",
                  "object": "event",
                  "api_version": "%s",
                  "type": "charge.refunded",
                  "data": { "object": { "id": "ch_test_2", "object": "charge", "payment_intent": "pi_test_unknown",
                             "refunded": true, "amount_refunded": 3998 } }
                }
                """.formatted(CURRENT_API_VERSION);
        Event event = eventFrom(json);
        when(orderRepository.findByPaymentReference("pi_test_unknown")).thenReturn(Optional.empty());

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verifyNoInteractions(orderService);
    }

    // S8 coverage: order IS found via findByPaymentReference, but transitionSystem itself throws
    // ResourceNotFoundException -- exercises handleChargeRefunded's own ResourceNotFoundException
    // catch, distinct from chargeRefunded_unresolvableOrder above (which never finds an order at all).
    @Test
    void chargeRefunded_resourceNotFoundDuringTransition_swallowedEventStillRecorded() {
        String json = """
                {
                  "id": "evt_12",
                  "object": "event",
                  "api_version": "%s",
                  "type": "charge.refunded",
                  "data": { "object": { "id": "ch_test_rnf", "object": "charge", "payment_intent": "pi_test_123",
                             "refunded": true, "amount_refunded": 3998 } }
                }
                """.formatted(CURRENT_API_VERSION);
        Event event = eventFrom(json);
        Order order = orderWith(10L, new BigDecimal("39.98"), "cs_test_abc", OrderStatus.PAID);
        when(orderRepository.findByPaymentReference("pi_test_123")).thenReturn(Optional.of(order));
        when(orderService.transitionSystem(10L, OrderStatus.REFUNDED, null))
                .thenThrow(new ResourceNotFoundException("Order", 10L));

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verify(orderService).transitionSystem(10L, OrderStatus.REFUNDED, null);
    }

    // S8 coverage: charge.refunded with no payment_intent at all -- resolveOrderIdFromCharge's own
    // blank-check short-circuits before ever calling orderRepository.findByPaymentReference.
    @Test
    void chargeRefunded_blankPaymentIntent_recordsOnlyNoTransition() {
        String json = """
                {
                  "id": "evt_13",
                  "object": "event",
                  "api_version": "%s",
                  "type": "charge.refunded",
                  "data": { "object": { "id": "ch_test_blankpi", "object": "charge",
                             "refunded": true, "amount_refunded": 3998 } }
                }
                """.formatted(CURRENT_API_VERSION);
        Event event = eventFrom(json);

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verify(orderRepository, never()).findByPaymentReference(any());
        verifyNoInteractions(orderService);
    }

    @Test
    void chargeRefunded_partialRefund_noTransitionEventStillRecorded() {
        // refunded=false + amount_refunded < amount (order total is 39.98 => 3998 minor units) -- a
        // goodwill partial refund, not a full refund. Must not transition the order to the terminal
        // REFUNDED status (it should still be able to ship).
        String json = """
                {
                  "id": "evt_11",
                  "object": "event",
                  "api_version": "%s",
                  "type": "charge.refunded",
                  "data": { "object": { "id": "ch_test_partial", "object": "charge", "payment_intent": "pi_test_123",
                             "refunded": false, "amount_refunded": 500 } }
                }
                """.formatted(CURRENT_API_VERSION);
        Event event = eventFrom(json);
        when(orderRepository.findByPaymentReference("pi_test_123")).thenReturn(Optional.empty());

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verifyNoInteractions(orderService);
    }

    @Test
    void unknownEventType_recordsOnly() {
        String json = """
                {
                  "id": "evt_8",
                  "object": "event",
                  "api_version": "%s",
                  "type": "customer.created",
                  "data": { "object": { "id": "cus_test_1", "object": "customer" } }
                }
                """.formatted(CURRENT_API_VERSION);
        Event event = eventFrom(json);

        service().process(event);

        ArgumentCaptor<ProcessedStripeEvent> captor = ArgumentCaptor.forClass(ProcessedStripeEvent.class);
        verify(processedStripeEventRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderId()).isNull();
        verifyNoInteractions(orderService);
    }

    @Test
    void checkoutSessionCompleted_invalidTransition_swallowedEventStillRecorded() {
        String json = checkoutSessionCompletedJson(CURRENT_API_VERSION, "evt_9", "cs_test_abc", 3998, "10", "ORD-00010", "pi_test_123");
        Event event = eventFrom(json);
        Order order = orderWith(10L, new BigDecimal("39.98"), "cs_test_abc", OrderStatus.PAID);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderService.transitionSystem(eq(10L), eq(OrderStatus.PAID), any()))
                .thenThrow(new InvalidTransitionException(OrderStatus.PAID, OrderStatus.PAID));

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verify(orderService).transitionSystem(10L, OrderStatus.PAID, "pi_test_123");
    }

    // S8 coverage: order IS found at the initial lookup (so the amount/session checks pass and
    // transitionSystem is actually invoked), but transitionSystem itself throws
    // ResourceNotFoundException -- e.g. the order was deleted between that lookup and
    // transitionSystem's own findById. Distinct from checkoutSessionExpired_unknownOrderId below
    // (which never finds the order at all): this exercises handleCheckoutSessionCompleted's own
    // ResourceNotFoundException catch specifically.
    @Test
    void checkoutSessionCompleted_resourceNotFoundDuringTransition_swallowedEventStillRecorded() {
        String json = checkoutSessionCompletedJson(CURRENT_API_VERSION, "evt_12", "cs_test_abc", 3998, "10", "ORD-00010", "pi_test_123");
        Event event = eventFrom(json);
        Order order = orderWith(10L, new BigDecimal("39.98"), "cs_test_abc", OrderStatus.PENDING);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderService.transitionSystem(10L, OrderStatus.PAID, "pi_test_123"))
                .thenThrow(new ResourceNotFoundException("Order", 10L));

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verify(orderService).transitionSystem(10L, OrderStatus.PAID, "pi_test_123");
    }

    @Test
    void checkoutSessionExpired_unknownOrderId_swallowedEventStillRecorded() {
        String json = """
                {
                  "id": "evt_10",
                  "object": "event",
                  "api_version": "%s",
                  "type": "checkout.session.expired",
                  "data": {
                    "object": {
                      "id": "cs_test_abc",
                      "object": "checkout.session",
                      "client_reference_id": "ORD-99999",
                      "metadata": { "orderId": "999" }
                    }
                  }
                }
                """.formatted(CURRENT_API_VERSION);
        Event event = eventFrom(json);
        when(orderService.transitionSystem(999L, OrderStatus.FAILED, null))
                .thenThrow(new ResourceNotFoundException("Order", 999L));

        service().process(event);

        verify(processedStripeEventRepository).saveAndFlush(any());
        verify(orderService).transitionSystem(999L, OrderStatus.FAILED, null);
    }
}
