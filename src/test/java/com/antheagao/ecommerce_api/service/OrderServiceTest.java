package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.CreateOrderRequest;
import com.antheagao.ecommerce_api.dto.OrderLineRequest;
import com.antheagao.ecommerce_api.dto.OrderResponse;
import com.antheagao.ecommerce_api.entity.*;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OrderNumberSeqRepository orderNumberSeqRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @InjectMocks
    private OrderService orderService;

    // Wires orderRepository.save / orderItemRepository.save / orderRepository.findById together so the
    // returned Order accumulates the same OrderItems the service builds, mirroring what a real
    // save-then-refetch round trip through JPA would return.
    private Order[] stubOrderPersistence() {
        Order[] holder = new Order[1];
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(200L);
            holder[0] = o;
            return o;
        });
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(inv -> {
            OrderItem oi = inv.getArgument(0);
            oi.getOrder().getItems().add(oi);
            return oi;
        });
        when(orderRepository.findById(200L)).thenAnswer(inv -> Optional.ofNullable(holder[0]));
        return holder;
    }

    @Test
    void createFromCart_whenValid_computesTotalsDecrementsStockAndClearsCart() {
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(20L).street("1 Main St").city("Springfield")
                .stateOrProvince("IL").postalCode("62701").country("US").user(user).build();
        Product productA = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(10).build();
        Product productB = Product.builder().id(101L).name("Gadget").price(new BigDecimal("19.50")).stockQuantity(5).build();
        Cart cart = Cart.builder().id(50L).user(user).build();
        // unitPrice deliberately set to a STALE value, different from the product's current price --
        // OrderService.java:81 charges p.getPrice() (the product's live price), not ci.getUnitPrice()
        // (the cart's price snapshot from whenever the item was added). If a product's price changes
        // between "add to cart" and "checkout", the customer is charged the NEW price. This is exactly
        // the cart/session amount-mismatch surface Batch 2's Stripe Checkout Session must reconcile.
        CartItem item1 = CartItem.builder().id(1L).cart(cart).product(productA).quantity(3).unitPrice(new BigDecimal("1.00")).build();
        CartItem item2 = CartItem.builder().id(2L).cart(cart).product(productB).quantity(2).unitPrice(new BigDecimal("1.00")).build();
        cart.setItems(new ArrayList<>(List.of(item1, item2)));

        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(orderNumberSeqRepository.getForUpdate()).thenReturn(OrderNumberSeq.builder().id(1L).nextVal(42L).build());
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(item1, item2));
        stubOrderPersistence();

        OrderResponse response = orderService.createFromCart(1L, req);

        BigDecimal expectedSubtotal = productA.getPrice().multiply(BigDecimal.valueOf(3))
                .add(productB.getPrice().multiply(BigDecimal.valueOf(2)));
        assertThat(response.getSubtotal()).isEqualByComparingTo(expectedSubtotal);
        assertThat(response.getTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getShippingCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getTotal()).isEqualByComparingTo(expectedSubtotal);
        assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.getOrderNumber()).isEqualTo("ORD-00042");
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getUnitPrice()).isEqualByComparingTo(productA.getPrice());
        assertThat(response.getItems().get(1).getUnitPrice()).isEqualByComparingTo(productB.getPrice());

        assertThat(productA.getStockQuantity()).isEqualTo(7);
        assertThat(productB.getStockQuantity()).isEqualTo(3);
        verify(productRepository).save(productA);
        verify(productRepository).save(productB);
        verify(cartItemRepository).delete(item1);
        verify(cartItemRepository).delete(item2);
    }

    @Test
    void createFromCart_whenUserNotFound_throwsResourceNotFoundException() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.createFromCart(999L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void createFromCart_whenAddressNotFound_throwsResourceNotFoundException() {
        User user = User.builder().id(1L).build();
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.createFromCart(1L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Address not found");
    }

    @Test
    void createFromCart_whenAddressOwnedBySomeoneElse_throwsResourceNotFoundException() {
        User user = User.builder().id(1L).build();
        User otherUser = User.builder().id(2L).build();
        Address address = Address.builder().id(20L).user(otherUser).build();
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));

        assertThatThrownBy(() -> orderService.createFromCart(1L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Address not found");
    }

    @Test
    void createFromCart_whenCartMissing_throwsResourceNotFoundException() {
        // Note: the service throws ResourceNotFoundException("Cart is empty") here (single-arg ctor,
        // literal message) rather than the "Cart not found: X" format used elsewhere -- the message text
        // is the same one used for the "cart exists but has no items" case below, even though this is a
        // genuinely different condition (no cart row was ever created for the user).
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(20L).user(user).build();
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.createFromCart(1L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Cart is empty");
    }

    @Test
    void createFromCart_whenCartEmpty_throwsConflictException() {
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(20L).user(user).build();
        Cart cart = Cart.builder().id(50L).user(user).build();
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> orderService.createFromCart(1L, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cart is empty");
    }

    @Test
    void createFromCart_whenInsufficientStockMidCart_throwsConflictException() {
        // productA has enough stock and is decremented (in-memory) before productB fails the check.
        // In production this only rolls back because the whole method runs in one @Transactional --
        // that rollback isn't exercised by a plain Mockito unit test, so we just assert the throw and
        // document that the in-memory decrement on productA survives past the exception here.
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(20L).user(user).build();
        Product productA = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(10).build();
        Product productB = Product.builder().id(101L).name("Gadget").price(new BigDecimal("19.50")).stockQuantity(1).build();
        Cart cart = Cart.builder().id(50L).user(user).build();
        CartItem item1 = CartItem.builder().id(1L).cart(cart).product(productA).quantity(3).unitPrice(productA.getPrice()).build();
        CartItem item2 = CartItem.builder().id(2L).cart(cart).product(productB).quantity(2).unitPrice(productB.getPrice()).build();
        cart.setItems(new ArrayList<>(List.of(item1, item2)));

        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(orderNumberSeqRepository.getForUpdate()).thenReturn(OrderNumberSeq.builder().id(1L).nextVal(1L).build());

        assertThatThrownBy(() -> orderService.createFromCart(1L, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Insufficient stock for: Gadget");
        assertThat(productA.getStockQuantity()).isEqualTo(7);
    }

    @Test
    void createFromCart_whenProductStockQuantityNull_throwsConflictException() {
        // p.getStockQuantity() == null is treated the same as "not enough stock" (the check is
        // `p.getStockQuantity() == null || p.getStockQuantity() < qty`), not skipped.
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(20L).user(user).build();
        Product product = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(null).build();
        Cart cart = Cart.builder().id(50L).user(user).build();
        CartItem item = CartItem.builder().id(1L).cart(cart).product(product).quantity(1).unitPrice(product.getPrice()).build();
        cart.setItems(new ArrayList<>(List.of(item)));

        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(orderNumberSeqRepository.getForUpdate()).thenReturn(OrderNumberSeq.builder().id(1L).nextVal(1L).build());

        assertThatThrownBy(() -> orderService.createFromCart(1L, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Insufficient stock for: Widget");
    }

    @Test
    void createFromItems_whenValid_computesTotalsAndDecrementsStock() {
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(20L).user(user).build();
        Product product = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(10).build();
        OrderLineRequest line = new OrderLineRequest();
        line.setProductId(100L);
        line.setQuantity(2);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        req.setItems(List.of(line));

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));
        when(orderNumberSeqRepository.getForUpdate()).thenReturn(OrderNumberSeq.builder().id(1L).nextVal(7L).build());
        stubOrderPersistence();

        OrderResponse response = orderService.createFromItems(1L, req);

        assertThat(response.getSubtotal()).isEqualByComparingTo("19.98");
        assertThat(response.getOrderNumber()).isEqualTo("ORD-00007");
        assertThat(response.getItems()).hasSize(1);
        assertThat(product.getStockQuantity()).isEqualTo(8);
    }

    @Test
    void createFromItems_whenAddressOwnedBySomeoneElse_throwsResourceNotFoundException() {
        User user = User.builder().id(1L).build();
        User otherUser = User.builder().id(2L).build();
        Address address = Address.builder().id(20L).user(otherUser).build();
        OrderLineRequest line = new OrderLineRequest();
        line.setProductId(100L);
        line.setQuantity(1);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        req.setItems(List.of(line));

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));

        assertThatThrownBy(() -> orderService.createFromItems(1L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Address not found");
    }

    @Test
    void createFromItems_whenProductNotFound_throwsResourceNotFoundException() {
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(20L).user(user).build();
        OrderLineRequest line = new OrderLineRequest();
        line.setProductId(999L);
        line.setQuantity(1);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        req.setItems(List.of(line));

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));
        when(orderNumberSeqRepository.getForUpdate()).thenReturn(OrderNumberSeq.builder().id(1L).nextVal(1L).build());
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.createFromItems(1L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    void createFromItems_whenInsufficientStock_throwsConflictException() {
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(20L).user(user).build();
        Product product = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(1).build();
        OrderLineRequest line = new OrderLineRequest();
        line.setProductId(100L);
        line.setQuantity(5);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        req.setItems(List.of(line));

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));
        when(orderNumberSeqRepository.getForUpdate()).thenReturn(OrderNumberSeq.builder().id(1L).nextVal(1L).build());
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> orderService.createFromItems(1L, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Insufficient stock for: Widget");
    }

    @Test
    void createFromItems_whenItemsNull_throwsIllegalArgumentException() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);

        assertThatThrownBy(() -> orderService.createFromItems(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Order must have items or use cart");
    }

    @Test
    void createFromItems_whenItemsEmpty_throwsIllegalArgumentException() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        req.setItems(List.of());

        assertThatThrownBy(() -> orderService.createFromItems(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Order must have items or use cart");
    }

    @Test
    void createFromItems_whenQuantityNonPositive_defaultsToOne() {
        // OrderLineRequest.quantity carries @Min(1), but that's only enforced by bean validation at the
        // controller boundary -- the service itself defensively clamps with Math.max(1, qty), so a
        // caller that bypasses validation (or a 0 default) still decrements exactly 1 unit.
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(20L).user(user).build();
        Product product = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(10).build();
        OrderLineRequest line = new OrderLineRequest();
        line.setProductId(100L);
        line.setQuantity(0);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddressId(20L);
        req.setItems(List.of(line));

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(address));
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));
        when(orderNumberSeqRepository.getForUpdate()).thenReturn(OrderNumberSeq.builder().id(1L).nextVal(1L).build());
        stubOrderPersistence();

        OrderResponse response = orderService.createFromItems(1L, req);

        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(1);
        assertThat(product.getStockQuantity()).isEqualTo(9);
    }

    @Test
    void nextOrderNumber_whenSeqNull_selfHealsAndReturnsFirstNumber() {
        ArgumentCaptor<OrderNumberSeq> seqCaptor = ArgumentCaptor.forClass(OrderNumberSeq.class);
        when(orderNumberSeqRepository.getForUpdate()).thenReturn(null);
        when(orderNumberSeqRepository.save(seqCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        String orderNumber = orderService.nextOrderNumber();

        assertThat(orderNumber).isEqualTo("ORD-00001");
        verify(orderNumberSeqRepository, times(2)).save(any(OrderNumberSeq.class));
        // First save() creates the healed seq at nextVal=1; second save() persists it incremented to 2.
        // ArgumentCaptor.getValue() returns the last capture, so this pins the post-increment state.
        assertThat(seqCaptor.getValue().getNextVal()).isEqualTo(2L);
    }

    @Test
    void nextOrderNumber_whenSeqExists_incrementsAndReturnsFormattedNumber() {
        OrderNumberSeq seq = OrderNumberSeq.builder().id(1L).nextVal(42L).build();
        when(orderNumberSeqRepository.getForUpdate()).thenReturn(seq);

        String orderNumber = orderService.nextOrderNumber();

        assertThat(orderNumber).isEqualTo("ORD-00042");
        assertThat(seq.getNextVal()).isEqualTo(43L);
        verify(orderNumberSeqRepository).save(seq);
    }

    @Test
    void findByIdAndUserId_whenWrongOwner_throwsResourceNotFoundException() {
        User otherUser = User.builder().id(2L).build();
        Order order = Order.builder().id(1L).user(otherUser).items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.findByIdAndUserId(1L, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Order not found");
    }

    @ParameterizedTest(name = "{0} -> {1} is a valid transition")
    @CsvSource({
            "PENDING, PAID",
            "PENDING, CANCELLED",
            "PENDING, FAILED",
            "PAID, SHIPPED",
            "PAID, CANCELLED",
            "PAID, REFUNDED",
            "SHIPPED, DELIVERED",
            "SHIPPED, REFUNDED",
            "DELIVERED, REFUNDED"
    })
    void updateStatus_whenValidTransition_updatesStatus(OrderStatus from, OrderStatus to) {
        User user = User.builder().id(1L).build();
        Order order = Order.builder().id(1L).user(user).status(from).items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        OrderResponse response = orderService.updateStatus(1L, 1L, to, null);

        assertThat(response.getStatus()).isEqualTo(to);
    }

    // The full invalid set: all 7x7 = 49 (from, to) pairs minus the 9 valid transitions above = 40 rows.
    // Self-transitions are included -- PAID -> PAID in particular is the shape of a duplicate/replayed
    // Stripe webhook that Batch 2's idempotent webhook handling must reject. CANCELLED/FAILED/REFUNDED
    // are terminal, so every outgoing edge from them (including to each other) is invalid.
    @ParameterizedTest(name = "{0} -> {1} is an invalid transition")
    @CsvSource({
            "PENDING, PENDING",
            "PENDING, SHIPPED",
            "PENDING, DELIVERED",
            "PENDING, REFUNDED",
            "PAID, PENDING",
            "PAID, PAID",
            "PAID, DELIVERED",
            "PAID, FAILED",
            "SHIPPED, PENDING",
            "SHIPPED, PAID",
            "SHIPPED, SHIPPED",
            "SHIPPED, CANCELLED",
            "SHIPPED, FAILED",
            "DELIVERED, PENDING",
            "DELIVERED, PAID",
            "DELIVERED, SHIPPED",
            "DELIVERED, DELIVERED",
            "DELIVERED, CANCELLED",
            "DELIVERED, FAILED",
            "CANCELLED, PENDING",
            "CANCELLED, PAID",
            "CANCELLED, SHIPPED",
            "CANCELLED, DELIVERED",
            "CANCELLED, CANCELLED",
            "CANCELLED, FAILED",
            "CANCELLED, REFUNDED",
            "FAILED, PENDING",
            "FAILED, PAID",
            "FAILED, SHIPPED",
            "FAILED, DELIVERED",
            "FAILED, CANCELLED",
            "FAILED, FAILED",
            "FAILED, REFUNDED",
            "REFUNDED, PENDING",
            "REFUNDED, PAID",
            "REFUNDED, SHIPPED",
            "REFUNDED, DELIVERED",
            "REFUNDED, CANCELLED",
            "REFUNDED, FAILED",
            "REFUNDED, REFUNDED"
    })
    void updateStatus_whenInvalidTransition_throwsIllegalArgumentException(OrderStatus from, OrderStatus to) {
        User user = User.builder().id(1L).build();
        Order order = Order.builder().id(1L).user(user).status(from).items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateStatus(1L, 1L, to, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid status transition");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void updateStatus_whenPaymentReferenceProvided_setsReference() {
        User user = User.builder().id(1L).build();
        Order order = Order.builder().id(1L).user(user).status(OrderStatus.PENDING).items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        orderService.updateStatus(1L, 1L, OrderStatus.PAID, "pi_12345");

        assertThat(order.getPaymentReference()).isEqualTo("pi_12345");
    }

    @Test
    void updateStatus_whenPaymentReferenceBlank_doesNotOverwriteExistingReference() {
        User user = User.builder().id(1L).build();
        Order order = Order.builder().id(1L).user(user).status(OrderStatus.PENDING)
                .paymentReference("pi_existing").items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        orderService.updateStatus(1L, 1L, OrderStatus.PAID, "   ");

        assertThat(order.getPaymentReference()).isEqualTo("pi_existing");
    }

    @Test
    void updateStatus_whenOrderNotFound_throwsResourceNotFoundException() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.updateStatus(999L, 1L, OrderStatus.PAID, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    void updateStatus_whenWrongOwner_throwsResourceNotFoundException() {
        User otherUser = User.builder().id(2L).build();
        Order order = Order.builder().id(1L).user(otherUser).status(OrderStatus.PENDING).items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateStatus(1L, 1L, OrderStatus.PAID, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    void transitionSystem_ordersOwnedByAnotherUser_succeedsWithNoOwnershipCheck() {
        // transitionSystem takes no userId param at all -- this is the webhook/refund/admin path,
        // which acts on behalf of the platform rather than a specific user.
        User owner = User.builder().id(1L).build();
        Order order = Order.builder().id(1L).user(owner).status(OrderStatus.PENDING).items(new ArrayList<>()).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        OrderResponse response = orderService.transitionSystem(1L, OrderStatus.PAID, null);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void transitionSystem_whenOrderNotFound_throwsResourceNotFoundException() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.transitionSystem(999L, OrderStatus.PAID, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Order not found");
    }
}
