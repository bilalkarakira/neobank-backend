# NeoBank Backend

Spring Boot 4 / Java 21 / PostgreSQL / Flyway. Frontend lives in `../neobank-frontend`.

## Run
```bash
docker compose up -d          # Postgres :5433 (5432 is often taken by a local install), pgAdmin :5050
./mvnw spring-boot:run        # API on :8080
```
Health check: http://localhost:8080/actuator/health

**Card issuing needs the ISO 8583 codec from the sibling repo.** Install it once (and again after changing it):
```bash
cd ../neobank-cardnet && ./mvnw install     # puts com.bilal.cardnet:iso8583 in your local Maven repository
```
The API then also listens for ISO 8583 card messages on 127.0.0.1:8583 (`app.cards.listener.*`; localhost only, no TLS).
The key that hashes card numbers and PINs is `CARD_PAN_KEY` (>= 32 characters; a development default is built in).
This is a card-*issuing* simulation: no licence, no real card scheme, test BIN 999900.

Requires JDK 21+ (set `JAVA_HOME`). See [../docs/plan.md](../docs/plan.md) for the plan.

## Demo card
The `demo` profile also gives the demo customer one test card on the checking account (also added to a demo database that was
seeded before cards existed): number `9999 0000 0000 0012`, PIN `4321`, expiry shown on the Cards page (the issuer wants it as YYMM).
It is a published test number, not a secret. Send it a payment with the smoke client in `../neobank-cardnet/tools`.

## Demo data (for the dashboard)
Start the API with the `demo` profile to get a demo customer with about six months of believable activity:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```
Log in as `demo@neobank.local` / `DemoPassw0rd`. It is idempotent and never runs without the profile.
