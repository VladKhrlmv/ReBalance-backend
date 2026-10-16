# ReBalance backend

The REST + WebSocket API behind ReBalance, a web app for sharing group expenses (a Tricount clone).
Spring Boot 3.5, Java 21, MySQL 8.4, Flyway.

- API contract: [docs/API.md](docs/API.md)
- Deployment (Oracle Cloud, Docker Compose): [deploy/README.md](deploy/README.md)

## How money works

- **Storage:** amounts are `DECIMAL(19,2)`. Each expense stores every participant's share in exact cents.
  Equal and weighted splits use the largest-remainder rule, so shares always add up to the expense amount.
  Leftover cents go to the largest remainders; ties go to the lower member id.
- **Balances:** a balance is never stored. It is computed from the expenses each time:
  balance = Σ amounts the member paid − Σ the member's shares.
  Positive means the others owe this member money.
- **"Who owes whom":** the settlement suggestions match the largest creditor with the largest debtor, in whole cents.
  That gives at most n−1 transfers. "Mark as paid" records a `TRANSFER` expense.
- **Concurrent edits:** edits and deletes must send the `version` they started from. A stale version gets `409 RB_102`.

## Run locally

Requires JDK 21 and Docker (for MySQL and the integration tests).

```sh
docker run -d --name rebalance-mysql -p 3306:3306 \
  -e MYSQL_DATABASE=rebalance -e MYSQL_USER=rebalance -e MYSQL_PASSWORD=rebalance \
  -e MYSQL_ROOT_PASSWORD=root mysql:8.4

SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

- The `local` profile uses a throwaway JWT key, allows the SPA dev server on `http://localhost:3000`, and enables Swagger UI at http://localhost:8080/swagger-ui.html.
- Flyway creates the schema on startup.

## Test

```sh
./mvnw verify
```

`verify` runs two kinds of tests:
- **Unit tests** (`*Test`): split, settlement and image processing.
- **Integration tests** (`*IT`): the whole application against a real MySQL in Testcontainers. These cover authentication, the authorization matrix, expense flows, invites, images, notifications and WebSocket delivery.

## Configuration

Settings come from environment variables:

| Variable | Meaning |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` on the server, `local` for development |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL connection |
| `JWT_SECRET` | base64 HMAC key, at least 32 bytes (`openssl rand -base64 48`); changing it signs everybody out |
| `APP_CORS_ORIGINS` | comma-separated browser origins for cross-origin calls; empty in production (the SPA is served from the same origin) |
| `APP_STORAGE_PATH` | directory for receipt images |
| `JAVA_OPTS` | JVM options in the Docker image |

## Admin: reset a forgotten password

There is no email service. The admin runs this on the server:

```sh
docker compose run --rm --no-deps backend --spring.main.web-application-type=none --reset-password=user@example.com
```

It prints a temporary password and signs that user out everywhere. The user then changes it from the account menu.
