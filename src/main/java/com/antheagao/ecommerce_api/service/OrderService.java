package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.*;
import com.antheagao.ecommerce_api.entity.*;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String ORDER_NUMBER_PREFIX = "ORD-";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderNumberSeqRepository orderNumberSeqRepository;
    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    @Transactional(readOnly = true)
    public List<OrderResponse> findByUserId(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> findByUserId(Long userId, Pageable pageable) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse findByIdAndUserId(Long id, Long userId) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order", id);
        }
        return toResponse(order);
    }

    @Transactional
    public OrderResponse createFromCart(Long userId, CreateOrderRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Address address = addressRepository.findById(req.getShippingAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address", req.getShippingAddressId()));
        if (!address.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Address", req.getShippingAddressId());
        }

        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart is empty"));
        if (cart.getItems().isEmpty()) {
            throw new ConflictException("Cart is empty");
        }

        String orderNumber = nextOrderNumber();
        List<OrderLine> lines = cart.getItems().stream()
                .map(ci -> new OrderLine(ci.getProduct(), ci.getQuantity()))
                .toList();

        Order order = persistOrder(orderNumber, user, address, lines);
        cartItemRepository.findByCartId(cart.getId()).forEach(cartItemRepository::delete);

        return toResponse(orderRepository.findById(order.getId()).orElseThrow());
    }

    @Transactional
    public OrderResponse createFromItems(Long userId, CreateOrderRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order must have items or use cart");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Address address = addressRepository.findById(req.getShippingAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address", req.getShippingAddressId()));
        if (!address.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Address", req.getShippingAddressId());
        }

        String orderNumber = nextOrderNumber();
        List<OrderLine> lines = new ArrayList<>();
        for (OrderLineRequest line : req.getItems()) {
            Product p = productRepository.findById(line.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", line.getProductId()));
            lines.add(new OrderLine(p, Math.max(1, line.getQuantity())));
        }

        Order order = persistOrder(orderNumber, user, address, lines);
        return toResponse(orderRepository.findById(order.getId()).orElseThrow());
    }

    /**
     * Resolved order line: the product to charge/decrement and the quantity being ordered.
     */
    private record OrderLine(Product product, int quantity) {
    }

    /**
     * Shared order-creation core for {@link #createFromCart} and {@link #createFromItems}: validates and
     * decrements stock per line, computes totals, and persists the order and its items. Callers are
     * responsible for allocating the order number, resolving their own lines, and any post-persist steps
     * (e.g. clearing the cart).
     */
    private Order persistOrder(String orderNumber, User user, Address address, List<OrderLine> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();

        for (OrderLine line : lines) {
            Product p = line.product();
            int qty = line.quantity();
            if (p.getStockQuantity() == null || p.getStockQuantity() < qty) {
                throw new ConflictException("Insufficient stock for: " + p.getName());
            }
            BigDecimal lineTotal = p.getPrice().multiply(BigDecimal.valueOf(qty));
            subtotal = subtotal.add(lineTotal);
            OrderItem oi = OrderItem.builder()
                    .productName(p.getName())
                    .quantity(qty)
                    .unitPrice(p.getPrice())
                    .subtotal(lineTotal)
                    .product(p)
                    .build();
            orderItems.add(oi);
            p.setStockQuantity(p.getStockQuantity() - qty);
            productRepository.save(p);
        }

        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal shippingCost = BigDecimal.ZERO;
        BigDecimal total = subtotal.add(tax).add(shippingCost);

        Order order = Order.builder()
                .user(user)
                .orderNumber(orderNumber)
                .status(OrderStatus.PENDING)
                .shippingStreet(address.getStreet())
                .shippingCity(address.getCity())
                .shippingState(address.getStateOrProvince())
                .shippingPostalCode(address.getPostalCode())
                .shippingCountry(address.getCountry())
                .subtotal(subtotal)
                .tax(tax)
                .shippingCost(shippingCost)
                .total(total)
                .build();
        order = orderRepository.save(order);
        for (OrderItem oi : orderItems) {
            oi.setOrder(order);
            orderItemRepository.save(oi);
        }
        return order;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String nextOrderNumber() {
        OrderNumberSeq seq = orderNumberSeqRepository.getForUpdate();
        if (seq == null) {
            // V1__baseline.sql seeds this row on Postgres, so this should never fire there;
            // log if it does so a missed/failed migration surfaces instead of healing silently.
            log.warn("order_number_seq row (id=1) missing; self-healing by creating it. " +
                    "This should not happen on Postgres if V1__baseline.sql ran.");
            seq = orderNumberSeqRepository.save(OrderNumberSeq.builder().id(1L).nextVal(1L).build());
        }
        long n = seq.getNextVal();
        seq.setNextVal(n + 1);
        orderNumberSeqRepository.save(seq);
        return ORDER_NUMBER_PREFIX + String.format("%05d", n);
    }

    private OrderResponse toResponse(Order o) {
        List<OrderItemResponse> items = o.getItems().stream()
                .map(i -> OrderItemResponse.builder()
                        .id(i.getId())
                        .productId(i.getProduct() != null ? i.getProduct().getId() : null)
                        .productName(i.getProductName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .subtotal(i.getSubtotal())
                        .build())
                .toList();
        return OrderResponse.builder()
                .id(o.getId())
                .orderNumber(o.getOrderNumber())
                .status(o.getStatus())
                .shippingStreet(o.getShippingStreet())
                .shippingCity(o.getShippingCity())
                .shippingState(o.getShippingState())
                .shippingPostalCode(o.getShippingPostalCode())
                .shippingCountry(o.getShippingCountry())
                .subtotal(o.getSubtotal())
                .tax(o.getTax())
                .shippingCost(o.getShippingCost())
                .total(o.getTotal())
                .createdAt(o.getCreatedAt())
                .paymentReference(o.getPaymentReference())
                .items(items)
                .build();
    }

    @Transactional
    public OrderResponse updateStatus(Long orderId, Long userId, OrderStatus newStatus, String paymentReference) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        order = applyTransition(order, newStatus, paymentReference);
        order = orderRepository.save(order);
        return toResponse(order);
    }

    /**
     * System-driven status transition with NO ownership check: the caller (webhook handler, refund
     * endpoint, admin controller) is acting on behalf of the platform rather than a specific user, so
     * there is no user to scope the order to.
     */
    @Transactional
    public OrderResponse transitionSystem(Long orderId, OrderStatus newStatus, String paymentReference) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        order = applyTransition(order, newStatus, paymentReference);
        order = orderRepository.save(order);
        return toResponse(order);
    }

    /**
     * Order state machine: PENDING -> {PAID, CANCELLED, FAILED}; PAID -> {SHIPPED, CANCELLED, REFUNDED};
     * SHIPPED -> {DELIVERED, REFUNDED}; DELIVERED -> {REFUNDED}; CANCELLED/FAILED/REFUNDED are terminal.
     */
    private Order applyTransition(Order order, OrderStatus newStatus, String paymentReference) {
        OrderStatus current = order.getStatus();
        boolean valid = switch (current) {
            case PENDING -> newStatus == OrderStatus.PAID || newStatus == OrderStatus.CANCELLED || newStatus == OrderStatus.FAILED;
            case PAID -> newStatus == OrderStatus.SHIPPED || newStatus == OrderStatus.CANCELLED || newStatus == OrderStatus.REFUNDED;
            case SHIPPED -> newStatus == OrderStatus.DELIVERED || newStatus == OrderStatus.REFUNDED;
            case DELIVERED -> newStatus == OrderStatus.REFUNDED;
            case CANCELLED, FAILED, REFUNDED -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid status transition from " + current + " to " + newStatus);
        }
        order.setStatus(newStatus);
        if (paymentReference != null && !paymentReference.isBlank()) {
            order.setPaymentReference(paymentReference);
        }
        return order;
    }
}
