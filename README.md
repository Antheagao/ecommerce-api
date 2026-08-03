# Ecommerce API

[![CI](https://github.com/Antheagao/ecommerce-api/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Antheagao/ecommerce-api/actions/workflows/ci.yml)

A production-oriented REST API for an ecommerce platform, built with **Spring Boot 4**, **Java 21**, and **PostgreSQL**. The API supports catalog management, shopping cart, orders, JWT authentication, role-based access (USER/ADMIN), pagination, filtering, and OpenAPI documentation.

---

## Features

### Authentication & Authorization
- **JWT-based authentication** — Stateless; login and register return a Bearer token.
- **Role-based access control** — `ROLE_USER` and `ROLE_ADMIN`; category and product create/update/delete restricted to ADMIN; GET on catalog is public.
- **Custom user principal** — `CurrentUser` exposes `id` and `email` for controller logic.
- **BCrypt password hashing** — Passwords never stored in plain text.

### Catalog
- **Categories** — CRUD, optional parent category (hierarchy), unique slug, auto-slug from name.
- **Products** — CRUD, SKU, price, stock, category link; list with **pagination** and **filtering** by category, price range, and search (name/description).

### Cart & Orders
- **Shopping cart** — Per-user cart; add/update/remove items; quantity and unit-price snapshot; clear cart.
- **Orders** — Create from cart or from explicit line items; shipping address snapshot; **order status workflow** (PENDING → PAID → SHIPPED → DELIVERED, or CANCELLED) with `PATCH /api/orders/{id}/status`; optional **payment reference** stored on order.
- **Order numbers** — DB-backed sequence (`order_number_seq`) with pessimistic lock for **multi-instance safety**.

### User & Addresses
- **User profile** — `GET /api/users/me` returns current user (no password).
- **Addresses** — CRUD for shipping/billing addresses scoped to the authenticated user; used when creating orders.

### API Design & Quality
- **Consistent error handling** — `ErrorResponse` (code, message, details); `ResourceNotFoundException` → 404, `ConflictException` → 409, validation errors → 400 with field details.
- **Validation** — Jakarta Bean Validation on request DTOs (`@Valid`, `@NotBlank`, `@Email`, `@Size`, `@DecimalMin`, etc.).
- **OpenAPI / Swagger** — Interactive docs at `/swagger-ui.html`, JSON at `/v3/api-docs`; JWT bearer scheme configured.

### Scalability & Operations
- **Stateless** — No server-side session; safe to run multiple instances behind a load balancer.
- **Pagination** — Categories, products, and orders support `?paged=true&page=0&size=20`.
- **Product filtering** — `categoryId`, `minPrice`, `maxPrice`, `search` (name/description).
- **Profiles** — `application-dev.properties` and `application-prod.properties`; prod uses env for JWT secret and DB.
- **Connection pooling** — HikariCP (default); tunable for production.

---

## Tech Stack

| Layer        | Technology |
|-------------|------------|
| Runtime     | Java 21    |
| Framework   | Spring Boot 4.0.2 |
| Web         | Spring Web MVC, Spring Validation |
| Data        | Spring Data JPA, Hibernate 7.x, PostgreSQL |
| Security    | Spring Security 6, JWT (jjwt 0.12.x), BCrypt |
| API Docs    | Springdoc OpenAPI 2.8 (Swagger UI) |
| Build       | Maven |
| Test        | JUnit 5, Spring Boot Test, H2 (test scope) |

---

## API Overview

| Method | Endpoint | Auth | Description |
|--------|----------|------|--------------|
| POST   | `/api/auth/register` | No  | Register; returns JWT |
| POST   | `/api/auth/login`    | No  | Login; returns JWT |
| GET    | `/api/users/me`      | Yes | Current user profile |
| GET    | `/api/categories`     | No  | List categories (optional `?paged=true`) |
| GET    | `/api/categories/{id}`| No  | Get category |
| POST   | `/api/categories`     | Admin | Create category |
| PUT    | `/api/categories/{id}`| Admin | Update category |
| DELETE | `/api/categories/{id}`| Admin | Delete category |
| GET    | `/api/products`       | No  | List products (optional `paged`, `categoryId`, `minPrice`, `maxPrice`, `search`) |
| GET    | `/api/products/{id}`  | No  | Get product |
| POST   | `/api/products`       | Admin | Create product |
| PUT    | `/api/products/{id}`  | Admin | Update product |
| DELETE | `/api/products/{id}`  | Admin | Delete product |
| GET    | `/api/addresses`       | Yes | List my addresses |
| GET    | `/api/addresses/{id}` | Yes | Get address |
| POST   | `/api/addresses`      | Yes | Create address |
| PUT    | `/api/addresses/{id}` | Yes | Update address |
| DELETE | `/api/addresses/{id}` | Yes | Delete address |
| GET    | `/api/cart`           | Yes | Get my cart |
| POST   | `/api/cart/items`     | Yes | Add item (body: `productId`, `quantity`) |
| PATCH  | `/api/cart/items/{id}`| Yes | Update quantity (`?quantity=`) |
| DELETE | `/api/cart/items/{id}`| Yes | Remove item |
| DELETE | `/api/cart`           | Yes | Clear cart |
| GET    | `/api/orders`         | Yes | List my orders (optional `?paged=true`) |
| GET    | `/api/orders/{id}`    | Yes | Get order |
| POST   | `/api/orders`         | Yes | Create order (from cart or body with `shippingAddressId` + optional `items`) |
| PATCH  | `/api/orders/{id}/status` | Yes | Update status (body: `status`, optional `paymentReference`) |

**Authentication:** Send `Authorization: Bearer <token>` for protected endpoints.

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
├── security/       # JwtUtil, JwtAuthFilter, CustomUserDetailsService, CurrentUser
└── service/        # Business logic
```

---

## Getting Started

### Prerequisites
- **Java 21**
- **PostgreSQL** (e.g. local instance with database `ecommerce`)

### Configuration
Copy or edit `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/ecommerce
spring.datasource.username=postgres
spring.datasource.password=postgres
app.jwt.secret=your-256-bit-secret
```

For production, use environment variables (see `application-prod.properties`):
- `JWT_SECRET` (required in prod)
- `JWT_EXPIRATION_MS` (optional, default 24h)

### Run
```bash
mvn spring-boot:run
```

API base: `http://localhost:8080`  
Swagger UI: `http://localhost:8080/swagger-ui.html`

### Run Tests
```bash
mvn test
```
Tests use H2 in-memory and `src/test/resources/application.properties`.

---

## Design Highlights

- **Layered architecture** — Controllers → Services → Repositories; DTOs for API boundary; entities for persistence.
- **Security** — Stateless JWT filter; method security (`@PreAuthorize`) where needed; public read for catalog, authenticated write for user resources, ADMIN for catalog mutations.
- **Idempotent order numbers** — Sequence table with lock to avoid duplicates across instances.
- **Order status transitions** — Validated state machine (e.g. PENDING → PAID → SHIPPED → DELIVERED) to keep data consistent.

---

## License

This project is for portfolio and educational use.
