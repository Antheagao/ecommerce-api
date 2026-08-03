package com.antheagao.ecommerce_api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots against a real Postgres container (matching the postgres:16-alpine
 * image used by docker-compose) with Flyway and Hibernate validate turned
 * back on, overriding the H2 test defaults in application.properties. A
 * green context load means Flyway migrated V1 cleanly on a fresh DB and
 * Hibernate's ddl-auto=validate found the resulting schema matches the
 * entities -- i.e. the baseline is accurate.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        // Test properties set this =true for H2's data.sql seeding, but it also
        // flips Flyway into "delayed" mode (runs after JPA instead of before),
        // which breaks ddl-auto=validate on a fresh Postgres DB. Not needed here:
        // data.sql only auto-runs against embedded databases anyway.
        "spring.jpa.defer-datasource-initialization=false"
})
class MigrationSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoadsWithFlywayMigratedSchema() {
    }

}
