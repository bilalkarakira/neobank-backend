# NeoBank Backend

Spring Boot 4 / Java 21 / PostgreSQL / Flyway. Frontend lives in `../neobank-frontend`.

## Run
```bash
docker compose up -d          # Postgres :5433 (5432 is often taken by a local install), pgAdmin :5050
./mvnw spring-boot:run        # API on :8080
```
Health check: http://localhost:8080/actuator/health

Requires JDK 21+ (set `JAVA_HOME`). See [../docs/plan.md](../docs/plan.md) for the plan.

## Demo data (for the dashboard)
Start the API with the `demo` profile to get a demo customer with about six months of believable activity:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```
Log in as `demo@neobank.local` / `DemoPassw0rd`. It is idempotent and never runs without the profile.
