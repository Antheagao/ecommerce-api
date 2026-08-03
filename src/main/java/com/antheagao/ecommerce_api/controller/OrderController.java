package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.CreateOrderRequest;
import com.antheagao.ecommerce_api.dto.OrderResponse;
import com.antheagao.ecommerce_api.dto.OrderStatusUpdateRequest;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal CurrentUser user,
                                  @PageableDefault(size = 20) Pageable pageable,
                                  @RequestParam(required = false, defaultValue = "false") boolean paged) {
        if (paged) {
            return ResponseEntity.ok(orderService.findByUserId(user.id(), pageable));
        }
        return ResponseEntity.ok(orderService.findByUserId(user.id()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(@PathVariable Long id, @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(orderService.findByIdAndUserId(id, user.id()));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
                                                  @AuthenticationPrincipal CurrentUser user) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createFromCart(user.id(), request));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createFromItems(user.id(), request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderResponse> updateStatus(@PathVariable Long id,
                                                      @Valid @RequestBody OrderStatusUpdateRequest request) {
        OrderStatus status = request.getStatus();
        if (status == OrderStatus.PAID || status == OrderStatus.FAILED || status == OrderStatus.REFUNDED) {
            throw new IllegalArgumentException("Status " + status + " can only be set by payment events");
        }
        return ResponseEntity.ok(orderService.transitionSystem(id, status, request.getPaymentReference()));
    }
}
