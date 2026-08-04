package com.antheagao.ecommerce_api;

import com.antheagao.ecommerce_api.dto.CartResponse;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.repository.CartRepository;
import com.antheagao.ecommerce_api.repository.UserRepository;
import com.antheagao.ecommerce_api.service.CartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the demo-storefront find (2026-08-03): a brand-new user's FIRST request
 * being {@code GET /api/cart} failed on real Postgres with "cannot execute INSERT in a read-only
 * transaction". CartService.getCart was {@code @Transactional(readOnly = true)} while
 * getOrCreateCart lazily INSERTs the user's cart row on first touch; Postgres's JDBC driver
 * enforces the read-only hint at the session level, so the lazy create blew up. Every existing
 * cart test either mocked the repositories (CartServiceTest pins the get-or-create behavior but
 * never opens a real transaction) or added an item first (write tx creates the cart), which is
 * why only the seeded-demo flow -- login, then loadCart before any add -- ever hit it. H2 treats
 * Connection.setReadOnly as a hint and does not enforce it, so this must run against Postgres.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.defer-datasource-initialization=false"
})
class CartFirstGetPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CartService cartService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CartRepository cartRepository;

    @Test
    void firstEverGetCart_forUserWithNoCartRow_createsEmptyCartInsteadOfFailingReadOnly() {
        User user = userRepository.save(User.builder()
                .email("first-get@test.com").passwordHash("x").role("USER").build());
        assertThat(cartRepository.findByUserId(user.getId())).isEmpty();

        CartResponse response = cartService.getCart(user.getId());

        assertThat(response.getItems()).isEmpty();
        assertThat(cartRepository.findByUserId(user.getId())).isPresent();
    }
}
