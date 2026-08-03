package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.AddressResponse;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.AddressService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AddressControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddressService addressService;

    private static CurrentUser regularUser() {
        return new CurrentUser(1L, "u@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static AddressResponse sampleResponse() {
        return AddressResponse.builder()
                .id(1L)
                .street("123 Main St")
                .city("Springfield")
                .stateOrProvince("IL")
                .postalCode("62704")
                .country("US")
                .build();
    }

    private static String validRequestBody() {
        return """
                {
                  "street": "123 Main St",
                  "city": "Springfield",
                  "postalCode": "62704",
                  "country": "US"
                }
                """;
    }

    @Test
    void list_authenticated_returns200WithAddresses() throws Exception {
        when(addressService.findByUserId(eq(1L))).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/addresses").with(user(regularUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].street").value("123 Main St"));
    }

    @Test
    void list_unauthenticated_returns403() throws Exception {
        mockMvc.perform(get("/api/addresses"))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_authenticated_returns200WithAddress() throws Exception {
        when(addressService.findByIdAndUserId(eq(1L), eq(1L))).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/addresses/1").with(user(regularUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void get_notFound_returns404WithNotFoundCode() throws Exception {
        when(addressService.findByIdAndUserId(eq(99L), eq(1L)))
                .thenThrow(new ResourceNotFoundException("Address", 99L));

        mockMvc.perform(get("/api/addresses/99").with(user(regularUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void create_validPayload_returns201() throws Exception {
        when(addressService.create(eq(1L), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/addresses")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void create_unauthenticated_returns403() throws Exception {
        mockMvc.perform(post("/api/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_blankStreet_returns400() throws Exception {
        String body = """
                {
                  "street": "",
                  "city": "Springfield",
                  "postalCode": "62704",
                  "country": "US"
                }
                """;

        mockMvc.perform(post("/api/addresses")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.street").exists());
    }

    @Test
    void create_blankCity_returns400() throws Exception {
        String body = """
                {
                  "street": "123 Main St",
                  "city": "",
                  "postalCode": "62704",
                  "country": "US"
                }
                """;

        mockMvc.perform(post("/api/addresses")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.city").exists());
    }

    @Test
    void create_blankPostalCode_returns400() throws Exception {
        String body = """
                {
                  "street": "123 Main St",
                  "city": "Springfield",
                  "postalCode": "",
                  "country": "US"
                }
                """;

        mockMvc.perform(post("/api/addresses")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.postalCode").exists());
    }

    @Test
    void create_blankCountry_returns400() throws Exception {
        String body = """
                {
                  "street": "123 Main St",
                  "city": "Springfield",
                  "postalCode": "62704",
                  "country": ""
                }
                """;

        mockMvc.perform(post("/api/addresses")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.country").exists());
    }

    @Test
    void update_authenticated_returns200() throws Exception {
        when(addressService.update(eq(1L), eq(1L), any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/addresses/1")
                        .with(user(regularUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void delete_authenticated_returns204() throws Exception {
        mockMvc.perform(delete("/api/addresses/1").with(user(regularUser())))
                .andExpect(status().isNoContent());

        verify(addressService).deleteById(eq(1L), eq(1L));
    }
}
