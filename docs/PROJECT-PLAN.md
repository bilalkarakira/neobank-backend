# NeoBank: project plan

This document is the plan of the project and its honest status: what it is for, what is in and out of scope, how the work was
broken down, how each part was proven to work, what is risky and what is left. How to run it is in the [README](../README.md).

## 1. Summary

NeoBank is a **simulated card-issuing neobank** built to show, in running code, what really happens when someone pays by card:
the shop's terminal asks, a card network routes the request, the issuing bank decides in milliseconds and reserves the money, the
shop's bank sends the final amounts at the end of the day, the bank posts the real debit, the network settles the day between the
banks, and a reconciliation proves that everything agrees. Disputes (chargebacks), cash machines, fuel-pump style additions and
online payments with customer approval are modelled too.

It is a learning and portfolio project. Its aim is **depth and honesty**: real ISO 8583 messages over real sockets, real files,
real failure handling (timeouts, duplicates, a bank that is down), tested and demonstrable on one machine.

| | |
|---|---|
| **Audience** | engineers and recruiters who want to see an understanding of card payments and payment systems, not only a CRUD app |
| **Stack** | Java 21 (developed with JDK 27), Spring Boot 4, PostgreSQL 17, Flyway, Maven, Angular 22, Tailwind CSS 4, Docker Compose, GitHub Actions |
| **Repositories** | `neobank-backend` (issuer, card network, demo stack, docs) and `neobank-frontend` (web app) |
| **Built** | 4 to 11 October 2026 (the commit history is the day-by-day record) |

## 2. Goals and non-goals

**Goals**

1. Follow **one card payment** through every stage of its life with working code: authorization, clearing, settlement, reconciliation, dispute.
2. Behave correctly when things go wrong: the same message twice, no answer in time, the bank unreachable, a customer who owes money after a payment cleared for more than the account held.
3. Keep **money correct by construction**: one place moves money, every movement is two postings that sum to zero, holds are taken under a row lock.
4. Keep **secrets out**: card numbers only as keyed hashes (or encrypted where a message must be rebuilt), PIN data and security codes never stored.
5. Be **demonstrable**: one command starts everything, a scenario runner plays a whole card day and checks the outcome, and the docs say exactly what is simulated.

**Non-goals** (see [LIMITS.md](LIMITS.md) for the full list)

- Not a real bank or a real card scheme: no licence, no KYC or AML, no Visa or Mastercard profile, no certification claim.
- No hardware security modules, EMV chip cryptography, message authentication codes or a real 3-D Secure protocol.
- One currency (USD), one acquirer, one issuer, one network.
- Nothing here is meant to run against real card numbers or real money.

## 3. Scope and architecture at a glance

| Piece | Where | What it does |
|---|---|---|
| **NeoBank** (the issuer and the bank) | repository root, `src/` | accounts, double-entry ledger, cards, the decision engine, holds, the ISO 8583 listener, clearing and settlement ingest, reconciliation, disputes, notifications, admin |
| **Card network** | `cardnet/network` | routes by card number, sign-on and echo, timeouts, stand-in processing, clearing and settlement runs, fees, control API |
| **Acquirer and virtual POS** | `cardnet/acquirer` | the shop's side: payments, durable reversals, voids, tips, refunds, batch close, cash machine, online shop, chargeback answers |
| **ISO 8583 codec** | `cardnet/iso8583` | packager, TCP framing, message inspector with masking, PIN block, clearing and acknowledgement file formats |
| **Shared** | `cardnet/shared` | card-number vault (AES-GCM), the durable reversal queue, sequences, shared-folder helpers, test doubles |
| **Web app** | `neobank-frontend` repository | customer pages (accounts, transfers, history, cards, notifications) and admin pages (clearing, settlement, disputes) |
| **Demo stack** | `demo/` | Docker Compose, Dockerfiles, the scenario runner |

More in [ARCHITECTURE.md](ARCHITECTURE.md) and [HOW-IT-WORKS.md](HOW-IT-WORKS.md).

## 4. Delivery plan and status

Each phase ended with tests, mutation checks of the important behaviours, a live run against the real application, independent SQL
checks where numbers matter, and a written report of what was and was not verified.

| Phase | Goal | Delivered | Status |
|---|---|---|---|
| **The bank** | a believable retail bank under the cards | registration and login with short-lived access tokens and a refresh cookie, sessions, rate limiting, checking and savings accounts, a double-entry ledger with idempotent deposits, transfers with limits, history with filters and CSV export, spending analytics, savings interest, recurring payments, notifications, admin pages | Done |
| **P1 Codec** | speak ISO 8583 | ISO 8583:1987 packager (bitmaps, all field types, validation), TCP framing, inspector that masks card numbers, PIN and security code, PIN block format 0 (unencrypted: simulated) | Done |
| **P2 Issuer** | NeoBank decides | cards (issue, freeze, limits), holds and the available balance, the decision engine with a fixed rule order, duplicate detection, reversals, hold expiry, the TCP listener, an admin view of authorizations | Done |
| **P3 Network and POS** | route and demonstrate | routing by card number, sign-on and echo, timeouts that become reversals, stand-in approval when the bank is down (and advices afterwards), the virtual POS with decoded and hex message views, chaos switches | Done |
| **P4 Clearing** | the end of the day | batch close, encrypted clearing files with control totals, fees (merchant discount, interchange, scheme fee), the issuer posts the debit and releases the hold, tips, refunds, unmatched items, receivables when a customer cannot cover a cleared amount | Done |
| **P5 Settlement** | money between the banks | daily netting per participant, CSV and camt.053-style XML statements, value dates, the issuer recomputes the figures itself, three-way reconciliation with an exceptions report, an admin page | Done |
| **P6a Chargebacks** | disputes | a customer disputes a posted payment, provisional credit, chargeback through the network, one representment by the shop, settled like a refund and a purchase | Done |
| **P6b Cash** | the cash machine | cash withdrawals with the PIN, per-withdrawal and daily cash limits, cleared and settled like a purchase | Done |
| **P6c Additions** | the fuel pump | incremental authorizations that name the first one, merged when the first is cleared for the final amount | Done |
| **P6d Online** | card not present | an online shop on the POS page, a security code checked by the bank (never stored), and above 30.00 the customer's approval of exactly that payment in the app; a proof that pays once | Done |
| **P7 Packaging** | a reader can run it | one-command Docker Compose stack, the scenario runner, samples checked in and decoded by a test, README, walk-through, glossary, limits | Done |
| **Hardening** | trust | CI workflows, a layout review at phone width, audits of the docs against the code, a clean-room build of everything from committed code | Done |

## 5. Quality strategy

| Layer | What it proves |
|---|---|
| Unit and integration tests | rules, boundaries, concurrency (two requests for one approval pay once), file formats; the database tests use a real PostgreSQL through Testcontainers |
| Mutation checks | the important behaviours were broken on purpose to confirm a test fails; survivors were investigated and reported (an equivalent survivor is named as such) |
| Independent verification | numbers were checked with SQL queries written separately from the code under test (ledger sums to zero, holds equal clearing) |
| End-to-end scenario | `demo/scenario/Scenario.java` plays a whole card day against the running stack and checks 57 expectations |
| Clean-room builds | everything is built and tested again from an export of the committed code, and the whole stack is built from the Dockerfiles |
| Continuous integration | GitHub Actions run the builds and tests on every push, and the scenario on the default branch and nightly |

**Test counts at the last full run:** backend 667, card network 123 (codec) + 44 (shared) + 142 (network) + 122 (acquirer), frontend 412.

## 6. Risks and known issues

| Risk or issue | Effect | What is done about it |
|---|---|---|
| Intermittent "connection attempt failed" from the Testcontainers database in a full test run | a test run occasionally needs a rerun; it has not hidden a real failure, and it is never counted as a mutation kill | open: cause not found; reruns pass |
| Development defaults (database password, keys, demo logins) | fine on a laptop, unsafe anywhere else | documented in every place they appear; the keys can be overridden with environment variables |
| The simulation is not a card scheme | readers may mistake a simplification for the real thing | [LIMITS.md](LIMITS.md) lists every simplification, and the README says it first |
| Time-based rules (ten approvals per ten minutes, a 30-second dispute window in the demo) | surprising declines when testing quickly | documented where they matter; the scenario runner needs ten minutes between runs |
| Looked at in a browser pane at phone width only | tablet widths, keyboard-only use and screen readers are unchecked | listed in the roadmap |

## 7. Roadmap (not built)

- Pre-arbitration (a second dispute round).
- PIN encryption with test keys, message authentication codes, tokenization notes.
- Published container images.
- An accessibility pass (keyboard, screen reader) and a tablet-width review.
- Finding the cause of the intermittent test-database failure.
- Choosing a license.

## 8. How to verify it yourself

1. Start the stack (README, "Quick start with Docker") and open the shop page and the bank side by side.
2. Follow [DEMO-SCRIPT.md](DEMO-SCRIPT.md), or run `java scenario/Scenario.java` from `demo/` and read the checks it prints.
3. Run the test suites (README, "Tests").
4. Read [LIMITS.md](LIMITS.md) to see where the simulation stops.
