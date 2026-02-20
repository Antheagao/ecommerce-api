package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.AddCartItemRequest;
import com.antheagao.ecommerce_api.dto.CartItemResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        Cart cart = getOrCreateCart(userId);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse addItem(Long userId, AddCartItemRequest req) {
        Cart cart = getOrCreateCart(userId);
        Product product = productRepository.findById(req.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", req.getProductId()));
        int qty = Math.max(1, req.getQuantity());
        if (product.getStockQuantity() != null && product.getStockQuantity() < qty) {
            throw new ConflictException("Insufficient stock for product: " + product.getName());
        }
        CartItem existing = cartItemRepository.findByCartIdAndProductId(cart.getId(), product.getId()).orElse(null);
        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + qty);
            cartItemRepository.save(existing);
        } else {
            CartItem item = CartItem.builder()
                    .cart(cart)
                    .product(product)
                    .quantity(qty)
                    .unitPrice(product.getPrice())
                    .build();
            cartItemRepository.save(item);
        }
        cart = cartRepository.findById(cart.getId()).orElseThrow();
        return toResponse(cart);
    }

    @Transactional
    public CartResponse updateItemQuantity(Long userId, Long cartItemId, int quantity) {
        Cart cart = getOrCreateCart(userId);
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item", cartItemId));
        if (!item.getCart().getId().equals(cart.getId())) {
            throw new ResourceNotFoundException("Cart item", cartItemId);
        }
        if (quantity <= 0) {
            cartItemRepository.delete(item);
        } else {
            item.setQuantity(quantity);
            cartItemRepository.save(item);
        }
        cart = cartRepository.findById(cart.getId()).orElseThrow();
        return toResponse(cart);
    }

    @Transactional
    public void removeItem(Long userId, Long cartItemId) {
        Cart cart = getOrCreateCart(userId);
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item", cartItemId));
        if (!item.getCart().getId().equals(cart.getId())) {
            throw new ResourceNotFoundException("Cart item", cartItemId);
        }
        cartItemRepository.delete(item);
    }

    @Transactional
    public void clearCart(Long userId) {
        Cart cart = getOrCreateCart(userId);
        cartItemRepository.findByCartId(cart.getId()).forEach(cartItemRepository::delete);
    }

    private Cart getOrCreateCart(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return cartRepository.findByUserId(userId).orElseGet(() -> {
            Cart c = Cart.builder().user(user).build();
            c = cartRepository.save(c);
            user.setCart(c);
            userRepository.save(user);
            return c;
        });
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(i -> {
                    BigDecimal subtotal = i.getUnitPrice() != null
                            ? i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity()))
                            : BigDecimal.ZERO;
                    return CartItemResponse.builder()
                            .id(i.getId())
                            .productId(i.getProduct().getId())
                            .productName(i.getProduct().getName())
                            .quantity(i.getQuantity())
                            .unitPrice(i.getUnitPrice())
                            .subtotal(subtotal)
                            .build();
                })
                .collect(Collectors.toList());
        BigDecimal total = items.stream()
                .map(CartItemResponse::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return CartResponse.builder()
                .id(cart.getId())
                .items(items)
                .total(total)
                .build();
    }
}
