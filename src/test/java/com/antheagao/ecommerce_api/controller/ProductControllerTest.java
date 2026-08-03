package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.ProductResponse;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    private static CurrentUser regularUser() {
        return new CurrentUser(1L, "u@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static CurrentUser adminUser() {
        return new CurrentUser(2L, "admin@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static ProductResponse sampleResponse() {
        return ProductResponse.builder()
                .id(1L)
                .name("Widget")
                .price(new BigDecimal("9.99"))
                .sku("WID-1")
                .stockQuantity(10)
                .build();
    }

    private static String validRequestBody() {
        return """
                {
                  "name": "Widget",
                  "price": 9.99
                }
                """;
    }

    @Test
    void findAll_public_returns200() throws Exception {
        when(productService.findAll()).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void findAll_paged_delegatesToFindAllFiltered() throws Exception {
        Page<ProductResponse> page = new PageImpl<>(List.of(sampleResponse()));
        when(productService.findAllFiltered(any(), isNull(), isNull(), isNull(), isNull())).thenReturn(page);

        mockMvc.perform(get("/api/products").param("paged", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));
    }

    @Test
    void findById_public_returns200() throws Exception {
        when(productService.findById(eq(1L))).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("WID-1"));
    }

    @Test
    void findById_notFound_returns404() throws Exception {
        when(productService.findById(eq(99L))).thenThrow(new ResourceNotFoundException("Product", 99L));

        mockMvc.perform(get("/api/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void create_asAdmin_returns201() throws Exception {
        when(productService.create(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/products")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void create_asRegularUser_returns403() throws Exception {
        mockMvc.perform(post("/api/products")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_missingPrice_returns400() throws Exception {
        String body = """
                {
                  "name": "Widget"
                }
                """;

        mockMvc.perform(post("/api/products")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.price").exists());
    }

    @Test
    void create_negativePrice_returns400() throws Exception {
        String body = """
                {
                  "name": "Widget",
                  "price": -5
                }
                """;

        mockMvc.perform(post("/api/products")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.price").exists());
    }

    @Test
    void create_skuConflict_returns409() throws Exception {
        when(productService.create(any())).thenThrow(new ConflictException("Product with SKU already exists: WID-1"));

        mockMvc.perform(post("/api/products")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void update_asAdmin_returns200() throws Exception {
        when(productService.update(eq(1L), any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/products/1")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void update_asRegularUser_returns403() throws Exception {
        mockMvc.perform(put("/api/products/1")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_asAdmin_returns204() throws Exception {
        mockMvc.perform(delete("/api/products/1").with(user(adminUser())))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_asRegularUser_returns403() throws Exception {
        mockMvc.perform(delete("/api/products/1").with(user(regularUser())))
                .andExpect(status().isForbidden());
    }
}
