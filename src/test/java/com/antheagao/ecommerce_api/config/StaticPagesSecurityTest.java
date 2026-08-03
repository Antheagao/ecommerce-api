package com.antheagao.ecommerce_api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StaticPagesSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootPage_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    void indexHtml_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void checkoutSuccessPage_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/checkout-success.html"))
                .andExpect(status().isOk());
    }

    @Test
    void checkoutCancelPage_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/checkout-cancel.html"))
                .andExpect(status().isOk());
    }
}
