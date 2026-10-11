# NeoBank

**A card payment, end to end.** NeoBank is a simulated card-issuing neobank, built together with the other parties a real card
payment needs: a **card network**, an **acquirer** with a **virtual point-of-sale terminal**, and the files they exchange
afterwards (**clearing**, **settlement**, **reconciliation**, **disputes**). The parties speak real **ISO 8583** messages over
TCP, so you can watch what happens between a shop's terminal and your bank: the millisecond authorization, the end-of-day
debit, and the settlement between the banks.

> **Honest scope.** NeoBank is a **learning and portfolio simulation**, not a bank. No licence, no KYC or AML, no real card
> scheme: the messages are ISO 8583:1987 plus common usage, *not* a Visa or Mastercard profile, and test card numbers start with
> `999900`. All keys are development defaults. Do not point this at anything real. See [docs/LIMITS.md](docs/LIMITS.md) for exactly
> what is simulated and what is simplified.

This repository is the **backend** and everything behind the web app: the issuer (Spring Boot), the card network and the
acquirer (`cardnet/`), and the demo stack (`demo/`). The web app is in the companion repository
[neobank-frontend](https://github.com/OWNER/neobank-frontend).

## What it can do

| Area | What happens | Try it |
|---|---|---|
| **Authorization** | The issuer applies its rules in a fixed order (card status, expiry, PIN, limits, velocity, funds) within milliseconds and **holds** the money | pay 25.00 with the demo card; see the pending hold on the Cards page |
| **Declines** | blocked merchant category, wrong PIN (three strikes freeze the card), not enough money, daily and per-payment limits, too many payments in a short time | the casino button; a wrong PIN |
| **Duplicates and timeouts** | a repeated message is answered the same way and holds once; a missing answer becomes a durable reversal that is retried | "Send last again"; the slow-answer switches in the network panel |
| **Stand-in** | when the bank is down, the network approves small payments itself and tells the bank later | switch NeoBank off in the network panel and pay a small amount |
| **Tips, voids, refunds** | a tip changes the final amount; a void releases the hold; a refund travels in the next clearing file | the payment list on the shop page |
| **Clearing** | at the end of the day the shop's bank sends one encrypted file; NeoBank posts the real debit and releases the hold | "Close the day" |
| **Settlement and reconciliation** | the network nets the day between the banks and writes CSV and camt.053-style statements; NeoBank recomputes the figures itself and checks that authorizations, clearing and settlement agree | admin pages: Clearing, Settlement |
| **Disputes** | a chargeback with an immediate provisional credit, one answer from the shop (representment), win or lose | Cards page: dispute a posted payment |
| **Cash machine** | a withdrawal always needs the PIN and has its own limits | the Cash machine on the shop page |
| **Additions to a payment** | a fuel pump asks for more on the same authorization, and clearing is for the final amount | "Add to payment" |
| **Online payments** | no card present: the security code is checked, and above 30.00 the customer must approve the payment in the app (a simulation of the idea of 3-D Secure) | the Online shop on the shop page |

## How the parties fit together

```mermaid
flowchart LR
    C([Cardholder]) -->|card| POS[Shop terminal<br/>virtual POS]
    POS -->|ISO 8583 0100| ACQ[Acquirer<br/>the shop's bank]
    ACQ -->|0100| NET{{Card network<br/>routes by card number}}
    NET -->|0100| ISS[(Issuer = NeoBank<br/>decides, holds the money)]
    ISS -.->|0110 approved or declined| NET
    NET -.->|0110| ACQ
    ACQ -.->|0110| POS
    ACQ ==>|clearing file| NET
    NET ==>|clearing file per issuer| ISS
    NET ==>|settlement statements| ACQ
    NET ==>|settlement statements| ISS
```

Real-time messages travel over TCP; the end-of-day files travel through a shared folder, the way banks use file transfers.
[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) follows one payment through every step, and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
lists the pieces, ports, files, databases and design decisions.

## This repository

```
src/            NeoBank: the issuer and the bank (Spring Boot 4, JPA, Flyway, PostgreSQL)
cardnet/        the card network, the acquirer with its virtual POS, the ISO 8583 codec (plain Java 21, Maven)
demo/           docker compose files, Dockerfiles and the scenario runner that plays a whole card day
docs/           the project plan, how it works, architecture, demo script, glossary, limits
docker-compose.yml   a development PostgreSQL (port 5433) and pgAdmin
```

## Quick start with Docker (recommended)

You need **Docker**. Clone this repository and the frontend **side by side** (the demo stack builds the web app from it):

```bash
git clone https://github.com/OWNER/neobank-backend.git
git clone https://github.com/OWNER/neobank-frontend.git

cd neobank-backend/demo
docker compose up --build        # the first build downloads and compiles everything: a few minutes
```

When it has settled, open:

| Address | What it is | Login |
|---|---|---|
| http://127.0.0.1:4200 | the bank (web app) | `demo@neobank.local` / `DemoPassw0rd` |
| http://127.0.0.1:4200 | the same app as the bank's admin | `admin@neobank.local` / `AdminPassw0rd` |
| http://127.0.0.1:9000 | the virtual POS, the shop's terminal | none (test cards only) |
| http://127.0.0.1:8080 | NeoBank's API | |
| http://127.0.0.1:9100 | the card network's control API | |

The demo customer's test card is **`9999 0000 0000 0012`**, PIN **`4321`**, security code **`123`** (published test values). The shop page
has "Use the demo card" buttons that fill everything in. Then follow the five-minute walk-through in
[docs/DEMO-SCRIPT.md](docs/DEMO-SCRIPT.md), or let the scenario runner play a whole card day and check the outcome of every step:

```bash
java scenario/Scenario.java      # in a second terminal, from demo/ (JDK 21+)
docker compose down -v           # stop everything and delete its data
```

The bank allows at most ten approvals per card in ten minutes, so run the scenario once per ten minutes. To start faster while
changing code, see the "prebuilt" option in [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md).

## Run it locally for development

Use this when you want to change the code. You need **JDK 21 or newer**, **Node.js 22 or newer** (for the frontend) and **Docker**
(for PostgreSQL, and for the tests). On Windows use `.\mvnw.cmd` instead of `./mvnw`.

**1. A database** (from this repository's root). PostgreSQL listens on port 5433 because 5432 is often taken by a local install:

```bash
docker compose up -d                      # PostgreSQL :5433 and pgAdmin :5050 (database, user and password: neobank)
docker exec neobank-postgres psql -U neobank -d postgres -c "create database cardnet"   # the network and the acquirer use their own database
```

**2. Build the card network and install the codec** (NeoBank depends on the ISO 8583 codec in `cardnet/`):

```bash
(cd cardnet && ./mvnw install -DskipTests)
```

**3. One shared folder** for the clearing and settlement files. NeoBank, the network and the acquirer must all use the **same**
folder, so set the same variable in each terminal before starting it (bash; in PowerShell use `$env:CARDNET_DATA_DIR = "$PWD\data\clearing"`):

```bash
export CARDNET_DATA_DIR="$PWD/data/clearing"
```

**4. Start the three Java processes**, each in its own terminal, from this repository's root, with that variable set:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo        # NeoBank: API :8080, ISO 8583 listener 127.0.0.1:8583, demo customer and demo card
java -jar cardnet/network/target/network-0.1.0-SNAPSHOT.jar   # the card network: control API :9100, routes card numbers 999900... to NeoBank
java -jar cardnet/acquirer/target/acquirer-0.1.0-SNAPSHOT.jar # the acquirer and the virtual POS: http://127.0.0.1:9000
```

**5. The web app** (from the `neobank-frontend` repository; it proxies `/api` to port 8080):

```bash
npm install
npm start                       # http://localhost:4200
```

**6. Try it.** Log in as `demo@neobank.local` / `DemoPassw0rd`, open the shop page (port 9000), press "Use the demo card" and pay.
`java demo/scenario/Scenario.java` plays a whole card day against these default ports.

Settings are environment variables (`CARDNET_*` for the network and the acquirer, `SPRING_*` and `CARD_PAN_KEY` for NeoBank); the defaults
all line up with the commands above. `cardnet/README.md` lists the network and acquirer settings and has tools to poke the issuer by hand
and to inspect and dump messages and files.

## Tests

```bash
(cd cardnet && ./mvnw clean verify)      # the card network: codec, network, acquirer, clearing, settlement, samples
./mvnw clean verify                      # NeoBank (install the codec first, step 2 above)
```

Both start a real PostgreSQL through Testcontainers, so Docker must be running. The frontend has its own tests
(`npx ng test --watch=false` in `neobank-frontend`). On top of the unit and integration tests, the important behaviours were
**mutation-checked** (break the code on purpose and confirm that a test fails), and the scenario runner checks a whole card day end to end.

## Documentation

| | |
|---|---|
| [docs/PROJECT-PLAN.md](docs/PROJECT-PLAN.md) | the project plan: goals, scope, phases and status, quality strategy, risks, roadmap |
| [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) | one card payment, step by step, with sequence diagrams |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | the pieces, ports, files, databases and the main design decisions |
| [docs/DEMO-SCRIPT.md](docs/DEMO-SCRIPT.md) | a guided walk-through to click through |
| [docs/GLOSSARY.md](docs/GLOSSARY.md) | issuer, acquirer, authorization, clearing, settlement, interchange and the rest |
| [docs/LIMITS.md](docs/LIMITS.md) | what is simulated, what is simplified, what is not built |
| [cardnet/docs/ISO8583-spec.md](cardnet/docs/ISO8583-spec.md) | the message layout, fields and response codes used here |

## Status

All planned phases are delivered (see [docs/PROJECT-PLAN.md](docs/PROJECT-PLAN.md)). The test suites pass and the full scenario passes against the
stack built from the Dockerfiles. There is no license file yet.
