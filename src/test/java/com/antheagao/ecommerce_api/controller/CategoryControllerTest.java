package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.CategoryResponse;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    private static CurrentUser regularUser() {
        return new CurrentUser(1L, "u@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static CurrentUser adminUser() {
        return new CurrentUser(2L, "admin@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static CategoryResponse sampleResponse() {
        return CategoryResponse.builder()
                .id(1L)
                .name("Electronics")
                .slug("electronics")
                .build();
    }

    private static String validRequestBody() {
        return """
                {
                  "name": "Electronics"
                }
                """;
    }

    @Test
    void findAll_public_returns200() throws Exception {
        when(categoryService.findAll()).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void findById_public_returns200() throws Exception {
        when(categoryService.findById(eq(1L))).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/categories/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("electronics"));
    }

    @Test
    void findById_notFound_returns404() throws Exception {
        when(categoryService.findById(eq(99L))).thenThrow(new ResourceNotFoundException("Category", 99L));

        mockMvc.perform(get("/api/categories/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void create_asAdmin_returns201() throws Exception {
        when(categoryService.create(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/categories")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void create_asRegularUser_returns403() throws Exception {
        mockMvc.perform(post("/api/categories")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_unauthenticated_returns403() throws Exception {
        // Anonymous access to an ADMIN-only route is denied by the authorization rule;
        // with no AuthenticationEntryPoint configured, this falls back to 403 (not 401).
        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_blankName_returns400() throws Exception {
        String body = """
                {
                  "name": ""
                }
                """;

        mockMvc.perform(post("/api/categories")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.name").exists());
    }

    @Test
    void create_slugConflict_returns409() throws Exception {
        when(categoryService.create(any())).thenThrow(new ConflictException("Category with slug already exists: electronics"));

        mockMvc.perform(post("/api/categories")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void update_asAdmin_returns200() throws Exception {
        when(categoryService.update(eq(1L), any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/categories/1")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void delete_asAdmin_returns204() throws Exception {
        mockMvc.perform(delete("/api/categories/1").with(user(adminUser())))
                .andExpect(status().isNoContent());
    }
}
