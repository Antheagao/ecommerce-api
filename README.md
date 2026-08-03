# Ecommerce API

[![CI](https://github.com/Antheagao/ecommerce-api/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Antheagao/ecommerce-api/actions/workflows/ci.yml)

A layered REST API for an ecommerce platform — JWT auth, role-based access, catalog, cart, and orders — built with Spring Boot 4 and PostgreSQL. Payments (Stripe Checkout + webhooks) are the next milestone; see [Roadmap](#roadmap).

---

## Quick Start

Requires Docker and Docker Compose.

```bash
cp .env.example .env
docker compose up --build
```

That's it. The app comes up on `http://localhost:8080` once Postgres passes its healthcheck.

- API base: `http://localhost:8080/api`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

**Without Docker:** `.\mvnw.cmd spring-boot:run` works too, but you'll need a local Postgres instance and matching `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, plus `JWT_SECRET` in the environment (there is deliberately no default — the app refuses to boot without a real secret). See the comments in `.env.example` for pointing a local run at the compose Postgres instead.

### Environment variables (`.env.example`)

| Variable | Default | Notes |
|---|---|---|
| `POSTGRES_DB` | `ecommerce` | Database name |
| `POSTGRES_USER` | `postgres` | Database user |
| `POSTGRES_PASSWORD` | `postgres` | Database password |
| `POSTGRES_HOST_PORT` | `5435` | Host-side port mapping; container always listens on 5432 internally |
| `APP_HOST_PORT` | `8080` | Host-side port for the app container |
| `DB_URL` | `jdbc:postgresql://db:5432/${POSTGRES_DB}` | Composed automatically by `docker-compose.yml` for the `app` service |
| `DB_USERNAME` | — | Set from `POSTGRES_USER` by compose |
| `DB_PASSWORD` | — | Set from `POSTGRES_PASSWORD` by compose |
| `JWT_SECRET` | — | **Required**, minimum 256 bits. No default — compose fails fast rather than booting insecurely |
| `JWT_EXPIRATION_MS` | `86400000` | Token lifetime in ms (24h) |

`docker-compose.yml` uses `${VAR:?message}` guards on every required variable — if `.env` is missing or incomplete, `docker compose up` fails immediately with a clear error instead of starting with blank credentials.

---

## Current State

- **Auth** — JWT-based, stateless; `USER` and `ADMIN` roles.
- **7 resource areas** — auth, users, addresses, cart, categories, products, orders.
- **Layered architecture** — controllers → services → repositories, with DTOs at the API boundary.
- **Order numbers** — generated via a DB-backed sequence table with pessimistic locking, safe across multiple app instances.
- **Pagination** — supported on catalog and order list endpoints.
- **Tests** — 151 tests across service and controller layers. JaCoCo line coverage: service 91.9%, controller 92.5%, overall 82.2%.
- **CI** — GitHub Actions runs the build and test suite on every push and PR (badge above).
- **Docker** — multi-stage build, non-root runtime user, container healthcheck on `/actuator/health`.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 4.0.2 |
| Security | Spring Security 7, JWT (jjwt 0.12.6), BCrypt |
| Data | Spring Data JPA / Hibernate, PostgreSQL 16 (H2 for tests) |
| API Docs | springdoc-openapi 2.8.6 (Swagger UI) |
| Coverage | JaCoCo 0.8.13 |
| Build | Maven (wrapper — `.\mvnw.cmd`) |
| Test | JUnit 5, Spring Boot Test, MockMvc |

---

## API Overview

| Resource | Endpoints | Auth |
|---|---|---|
| Auth | `POST /api/auth/register`, `POST /api/auth/login` | Public |
| Users | `GET /api/users/me` | Authenticated |
| Addresses | Full CRUD (`GET`/`POST`/`PUT`/`DELETE /api/addresses[/{id}]`) | Authenticated, user-scoped |
| Cart | `GET /api/cart`, `POST /api/cart/items`, `PATCH /api/cart/items/{id}`, `DELETE /api/cart/items/{id}`, `DELETE /api/cart` (clear) | Authenticated, user-scoped |
| Categories | `GET /api/categories[/{id}]` public; `POST`/`PUT`/`DELETE` | ADMIN for writes |
| Products | `GET /api/products[/{id}]` public; `POST`/`PUT`/`DELETE` | ADMIN for writes |
| Orders | `GET /api/orders`, `GET /api/orders/{id}`, `POST /api/orders`, `PATCH /api/orders/{id}/status` | Authenticated, user-scoped |
| Docs | `/swagger-ui.html`, `/v3/api-docs` | Public |
| Ops | `/actuator/health` | Public |
| Ops | Remaining actuator endpoints | ADMIN |

Send `Authorization: Bearer <token>` for any non-public endpoint.

---

## Project Structure

```
src/main/java/com/antheagao/ecommerce_api/
├── config/          # Security, OpenAPI, PasswordEncoder
├── controller/      # REST controllers
├── dto/             # Request/response DTOs
├── entity/          # JPA entities (User, Product, Category, Order, Cart, etc.)
├── exception/       # Custom exceptions, GlobalExceptionHandler, ErrorResponse
├── repository/      # Spring Data JPA repositories
├── security/        # JwtUtil, JwtAuthFilter, CustomUserDetailsService, CurrentUser
└── service/         # Business logic
```

---

## Running Tests

```bash
.\mvnw.cmd test
```

Tests run against H2 in-memory with `src/test/resources/application.properties`; JaCoCo produces a coverage report under `target/site/jacoco` on `mvn verify`.

---

## Roadmap

Stripe Checkout + webhook-driven payments (idempotent event handling, order state machine, admin refunds) are the next milestone. Deployment, structured payment-path logging, and architecture/sequence diagrams follow once that lands.

---

## License

This project is for portfolio and educational use.
