package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.AddCartItemRequest;
import com.antheagao.ecommerce_api.dto.CartResponse;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<CartResponse> get(@AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(cartService.getCart(user.id()));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(@Valid @RequestBody AddCartItemRequest request,
                                                @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(cartService.addItem(user.id(), request));
    }

    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<CartResponse> updateQuantity(@PathVariable Long cartItemId,
                                                        @RequestParam int quantity,
                                                        @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(cartService.updateItemQuantity(user.id(), cartItemId, quantity));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<Void> removeItem(@PathVariable Long cartItemId,
                                           @AuthenticationPrincipal CurrentUser user) {
        cartService.removeItem(user.id(), cartItemId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal CurrentUser user) {
        cartService.clearCart(user.id());
        return ResponseEntity.noContent().build();
    }
}
