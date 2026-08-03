package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.RefundResponse;
import com.antheagao.ecommerce_api.entity.OrderStatus;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefundService refundService;

    private static CurrentUser regularUser() {
        return new CurrentUser(1L, "u@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static CurrentUser adminUser() {
        return new CurrentUser(2L, "admin@x.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static RefundResponse sampleResponse() {
        return RefundResponse.builder()
                .orderId(1L)
                .orderNumber("ORD-00001")
                .refundId("re_test_abc")
                .refundStatus("succeeded")
                .orderStatus(OrderStatus.REFUNDED)
                .build();
    }

    @Test
    void refund_asAdmin_returns200WithBody() throws Exception {
        when(refundService.refund(eq(1L))).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/admin/orders/1/refund").with(user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.refundId").value("re_test_abc"))
                .andExpect(jsonPath("$.orderStatus").value("REFUNDED"));

        verify(refundService, times(1)).refund(eq(1L));
    }

    @Test
    void refund_asRegularUser_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/orders/1/refund").with(user(regularUser())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void refund_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/orders/1/refund"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
