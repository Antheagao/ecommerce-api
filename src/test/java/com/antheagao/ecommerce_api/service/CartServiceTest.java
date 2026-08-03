package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.AddCartItemRequest;
import com.antheagao.ecommerce_api.dto.CartResponse;
import com.antheagao.ecommerce_api.entity.Cart;
import com.antheagao.ecommerce_api.entity.CartItem;
import com.antheagao.ecommerce_api.entity.Product;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.CartItemRepository;
import com.antheagao.ecommerce_api.repository.CartRepository;
import com.antheagao.ecommerce_api.repository.ProductRepository;
import com.antheagao.ecommerce_api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CartService cartService;

    @Test
    void getCart_whenAbsent_createsCartOnDemand() {
        User user = User.builder().id(1L).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.empty());
        Cart created = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        when(cartRepository.save(any(Cart.class))).thenReturn(created);
        when(userRepository.save(any(User.class))).thenReturn(user);

        CartResponse response = cartService.getCart(1L);

        assertThat(response.getId()).isEqualTo(50L);
        assertThat(response.getItems()).isEmpty();
        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    void addItem_whenNewProduct_addsItem() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        Product product = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(5).build();
        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(100L);
        req.setQuantity(2);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));
        when(cartItemRepository.findByCartIdAndProductId(50L, 100L)).thenReturn(Optional.empty());
        CartItem savedItem = CartItem.builder().id(1L).cart(cart).product(product).quantity(2).unitPrice(product.getPrice()).build();
        when(cartItemRepository.save(any(CartItem.class))).thenReturn(savedItem);
        Cart cartWithItem = Cart.builder().id(50L).user(user).items(List.of(savedItem)).build();
        when(cartRepository.findById(50L)).thenReturn(Optional.of(cartWithItem));

        CartResponse response = cartService.addItem(1L, req);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getProductId()).isEqualTo(100L);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(2);
        verify(cartItemRepository).save(argThat(item -> item.getQuantity() == 2));
    }

    @Test
    void addItem_whenExistingProduct_mergesQuantity() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        Product product = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(10).build();
        CartItem existing = CartItem.builder().id(1L).cart(cart).product(product).quantity(3).unitPrice(product.getPrice()).build();
        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(100L);
        req.setQuantity(2);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));
        when(cartItemRepository.findByCartIdAndProductId(50L, 100L)).thenReturn(Optional.of(existing));
        Cart cartWithItem = Cart.builder().id(50L).user(user).items(List.of(existing)).build();
        when(cartRepository.findById(50L)).thenReturn(Optional.of(cartWithItem));

        cartService.addItem(1L, req);

        assertThat(existing.getQuantity()).isEqualTo(5);
        verify(cartItemRepository).save(existing);
    }

    @Test
    void addItem_whenProductNotFound_throwsResourceNotFoundException() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(999L);
        req.setQuantity(1);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addItem(1L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    void addItem_whenInsufficientStock_throwsConflictException() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        Product product = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(1).build();
        AddCartItemRequest req = new AddCartItemRequest();
        req.setProductId(100L);
        req.setQuantity(5);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> cartService.addItem(1L, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Insufficient stock for product: Widget");
    }

    @Test
    void updateItemQuantity_whenPositive_updatesQuantity() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        Product product = Product.builder().id(100L).name("Widget").price(new BigDecimal("9.99")).build();
        CartItem item = CartItem.builder().id(1L).cart(cart).product(product).quantity(1).unitPrice(product.getPrice()).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(1L)).thenReturn(Optional.of(item));
        Cart cartWithItem = Cart.builder().id(50L).user(user).items(List.of(item)).build();
        when(cartRepository.findById(50L)).thenReturn(Optional.of(cartWithItem));

        cartService.updateItemQuantity(1L, 1L, 4);

        assertThat(item.getQuantity()).isEqualTo(4);
        verify(cartItemRepository).save(item);
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void updateItemQuantity_whenZeroOrLess_deletesItem() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        Product product = Product.builder().id(100L).build();
        CartItem item = CartItem.builder().id(1L).cart(cart).product(product).quantity(1).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(1L)).thenReturn(Optional.of(item));
        Cart emptyCart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        when(cartRepository.findById(50L)).thenReturn(Optional.of(emptyCart));

        cartService.updateItemQuantity(1L, 1L, 0);

        verify(cartItemRepository).delete(item);
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void updateItemQuantity_whenWrongCartOwnership_throwsResourceNotFoundException() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        Cart otherCart = Cart.builder().id(99L).build();
        CartItem item = CartItem.builder().id(1L).cart(otherCart).quantity(1).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(1L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> cartService.updateItemQuantity(1L, 1L, 2))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Cart item not found");
    }

    @Test
    void removeItem_whenOwnedByUser_deletesItem() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        CartItem item = CartItem.builder().id(1L).cart(cart).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(1L)).thenReturn(Optional.of(item));

        cartService.removeItem(1L, 1L);

        verify(cartItemRepository).delete(item);
    }

    @Test
    void removeItem_whenWrongCartOwnership_throwsResourceNotFoundException() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        Cart otherCart = Cart.builder().id(99L).build();
        CartItem item = CartItem.builder().id(1L).cart(otherCart).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(1L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> cartService.removeItem(1L, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Cart item not found");
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void clearCart_deletesAllItems() {
        User user = User.builder().id(1L).build();
        Cart cart = Cart.builder().id(50L).user(user).items(new ArrayList<>()).build();
        CartItem item1 = CartItem.builder().id(1L).cart(cart).build();
        CartItem item2 = CartItem.builder().id(2L).cart(cart).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(item1, item2));

        cartService.clearCart(1L);

        verify(cartItemRepository).delete(item1);
        verify(cartItemRepository).delete(item2);
    }
}
