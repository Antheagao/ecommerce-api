package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.OrderResponse;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    private static CurrentUser regularUser() {
        return new CurrentUser(1L, "u@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static CurrentUser adminUser() {
        return new CurrentUser(2L, "admin@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static OrderResponse sampleResponse() {
        return OrderResponse.builder()
                .id(1L)
                .orderNumber("ORD-00001")
                .status(OrderStatus.PENDING)
                .subtotal(BigDecimal.TEN)
                .tax(BigDecimal.ZERO)
                .shippingCost(BigDecimal.ZERO)
                .total(BigDecimal.TEN)
                .items(List.of())
                .build();
    }

    @Test
    void list_authenticated_returns200() throws Exception {
        when(orderService.findByUserId(eq(1L))).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/orders").with(user(regularUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void get_authenticated_returns200() throws Exception {
        when(orderService.findByIdAndUserId(eq(1L), eq(1L))).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/orders/1").with(user(regularUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-00001"));
    }

    @Test
    void get_foreignOrder_returns404() throws Exception {
        when(orderService.findByIdAndUserId(eq(1L), eq(1L)))
                .thenThrow(new ResourceNotFoundException("Order", 1L));

        mockMvc.perform(get("/api/orders/1").with(user(regularUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void create_withoutItems_delegatesToCreateFromCart() throws Exception {
        when(orderService.createFromCart(eq(1L), any())).thenReturn(sampleResponse());

        String body = """
                {
                  "shippingAddressId": 1
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));

        verify(orderService, times(1)).createFromCart(eq(1L), any());
    }

    @Test
    void create_withItems_delegatesToCreateFromItems() throws Exception {
        when(orderService.createFromItems(eq(1L), any())).thenReturn(sampleResponse());

        String body = """
                {
                  "shippingAddressId": 1,
                  "items": [
                    { "productId": 5, "quantity": 2 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));

        verify(orderService, times(1)).createFromItems(eq(1L), any());
    }

    @Test
    void create_nullShippingAddressId_returns400() throws Exception {
        String body = """
                {
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.shippingAddressId").exists());
    }

    @Test
    void create_invalidItemQuantity_returns400() throws Exception {
        String body = """
                {
                  "shippingAddressId": 1,
                  "items": [
                    { "productId": 5, "quantity": 0 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void create_unauthenticated_returns401() throws Exception {
        String body = """
                {
                  "shippingAddressId": 1
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateStatus_asRegularUser_returns403() throws Exception {
        String body = """
                {
                  "status": "SHIPPED"
                }
                """;

        mockMvc.perform(patch("/api/orders/1/status")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void updateStatus_asAdmin_validTransition_returns200() throws Exception {
        OrderResponse shipped = OrderResponse.builder()
                .id(1L)
                .orderNumber("ORD-00001")
                .status(OrderStatus.SHIPPED)
                .items(List.of())
                .build();
        when(orderService.transitionSystem(eq(1L), eq(OrderStatus.SHIPPED), isNull())).thenReturn(shipped);

        String body = """
                {
                  "status": "SHIPPED"
                }
                """;

        mockMvc.perform(patch("/api/orders/1/status")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void updateStatus_asAdmin_setsPaid_returns400() throws Exception {
        // PAID (like FAILED/REFUNDED) is payment-derived and may only arrive via a webhook event --
        // the controller rejects it before ever calling orderService.transitionSystem.
        String body = """
                {
                  "status": "PAID"
                }
                """;

        mockMvc.perform(patch("/api/orders/1/status")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void updateStatus_invalidTransition_returns400() throws Exception {
        when(orderService.transitionSystem(eq(1L), eq(OrderStatus.DELIVERED), isNull()))
                .thenThrow(new IllegalArgumentException("Invalid status transition from PENDING to DELIVERED"));

        String body = """
                {
                  "status": "DELIVERED"
                }
                """;

        mockMvc.perform(patch("/api/orders/1/status")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void updateStatus_nullStatus_returns400() throws Exception {
        String body = """
                {
                }
                """;

        mockMvc.perform(patch("/api/orders/1/status")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.status").exists());
    }
}
