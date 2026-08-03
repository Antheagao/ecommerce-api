package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.CartResponse;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.CartService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    private static CurrentUser regularUser() {
        return new CurrentUser(1L, "u@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static CartResponse sampleCart() {
        return CartResponse.builder()
                .id(1L)
                .items(List.of())
                .total(BigDecimal.ZERO)
                .build();
    }

    @Test
    void get_authenticated_returns200WithCart() throws Exception {
        when(cartService.getCart(eq(1L))).thenReturn(sampleCart());

        mockMvc.perform(get("/api/cart").with(user(regularUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void get_unauthenticated_returns403() throws Exception {
        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isForbidden());
    }

    @Test
    void addItem_validPayload_returns200() throws Exception {
        when(cartService.addItem(eq(1L), any())).thenReturn(sampleCart());

        String body = """
                {
                  "productId": 5,
                  "quantity": 2
                }
                """;

        mockMvc.perform(post("/api/cart/items")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void addItem_nullProductId_returns400() throws Exception {
        String body = """
                {
                  "quantity": 2
                }
                """;

        mockMvc.perform(post("/api/cart/items")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.productId").exists());
    }

    @Test
    void addItem_zeroQuantity_returns400() throws Exception {
        String body = """
                {
                  "productId": 5,
                  "quantity": 0
                }
                """;

        mockMvc.perform(post("/api/cart/items")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.quantity").exists());
    }

    @Test
    void addItem_insufficientStock_returns409() throws Exception {
        when(cartService.addItem(eq(1L), any())).thenThrow(new ConflictException("Insufficient stock for product: Widget"));

        String body = """
                {
                  "productId": 5,
                  "quantity": 2
                }
                """;

        mockMvc.perform(post("/api/cart/items")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void updateQuantity_authenticated_returns200() throws Exception {
        when(cartService.updateItemQuantity(eq(1L), eq(10L), anyInt())).thenReturn(sampleCart());

        mockMvc.perform(patch("/api/cart/items/10")
                        .with(user(regularUser()))
                        .param("quantity", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void removeItem_authenticated_returns204() throws Exception {
        mockMvc.perform(delete("/api/cart/items/10").with(user(regularUser())))
                .andExpect(status().isNoContent());

        verify(cartService).removeItem(eq(1L), eq(10L));
    }

    @Test
    void clear_authenticated_returns204() throws Exception {
        mockMvc.perform(delete("/api/cart").with(user(regularUser())))
                .andExpect(status().isNoContent());

        verify(cartService).clearCart(eq(1L));
    }
}
