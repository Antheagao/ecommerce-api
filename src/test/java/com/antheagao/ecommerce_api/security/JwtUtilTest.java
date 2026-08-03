package com.antheagao.ecommerce_api.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private JwtUtil newJwtUtil(String secret) {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 3600000L);
        return jwtUtil;
    }

    @Test
    void generateToken_shortSecret_throwsIllegalStateException() {
        JwtUtil jwtUtil = newJwtUtil("short");

        assertThatThrownBy(() -> jwtUtil.generateToken("jane@example.com", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void generateAndValidateToken_secretAtLeast32Bytes_roundTrips() {
        JwtUtil jwtUtil = newJwtUtil("testSecretKeyAtLeast256BitsLongForHS256Algorithm");

        String token = jwtUtil.generateToken("jane@example.com", 1L);

        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.getEmailFromToken(token)).isEqualTo("jane@example.com");
        assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo(1L);
    }
}
