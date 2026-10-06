# cardnet

A simulated card network around NeoBank, built to show how a card payment really moves: ISO 8583 messages in real time,
then clearing and settlement files later. NeoBank (the sibling `neobank-backend` project) is the issuer.

Status: phases P1 (the ISO 8583 codec) and P3 (network switch, acquirer, virtual POS) are built. See `../docs/PLAN-card-network.md` for the whole plan and `../docs/SPEC-P3-network.md` for how the three processes behave.

## Modules

| Module | What it is |
|---|---|
| `iso8583` | ISO 8583:1987 codec (ASCII variant), TCP framing, message inspector, PIN block. No dependencies. |
| `shared` | PAN vault (AES-GCM), database and migrations (Flyway), message builders, the durable reversal queue, test doubles (a fake issuer, a throwaway database). |
| `network` | The card network switch: BIN routing, issuer link, timeouts, stand-in processing, advices, control API. Runs as its own process. |
| `acquirer` | The acquirer and the virtual POS web console. Runs as its own process. |

The wire format is specified in [docs/ISO8583-spec.md](docs/ISO8583-spec.md). It follows ISO 8583:1987 and common field usage;
Visa and Mastercard profiles are proprietary, so this is **not** a scheme specification and makes no certification claim.

## Run the whole chain

Needs Docker Postgres (the `neobank-postgres` container of the backend) and the NeoBank backend running with the `demo` profile
(its ISO 8583 interface listens on 127.0.0.1:8583). One-off: `docker exec neobank-postgres psql -U neobank -d postgres -c "create database cardnet"`.

```bash
./mvnw.cmd install                                              # builds and tests everything
java -jar network/target/network-0.1.0-SNAPSHOT.jar      # network: acquirers on :8601, control API on :9100, route 999900 -> :8583
java -jar acquirer/target/acquirer-0.1.0-SNAPSHOT.jar           # acquirer + virtual POS on http://127.0.0.1:9000
```

Settings are CARDNET_* environment variables (ports, routes, timeouts, database, `CARDNET_VAULT_KEY` which both processes must share).
Everything listens on 127.0.0.1 only, without TLS or authentication: this is a simulation with test numbers.

Things to try in the POS console: pay with the demo card; a wrong PIN; the casino (blocked category); "Send last again" (a duplicate);
"Void" an approved payment; switch NeoBank off in the network panel and pay a small amount (the network approves by itself, then tells NeoBank
when it is back); hold NeoBank's answers back for 4000 ms (a slow bank: the network answers on its own, then undoes the late approval);
hold the network's answers back for 7000 ms (the acquirer gives up, cancels, and retries its reversal until it is confirmed).

## Build and test

```bash
./mvnw.cmd test        # JDK 21+; this machine uses JDK 27 (set JAVA_HOME)
```

## Poke the issuer by hand
`tools/SmokeClient.java` signs on and sends one authorization (and optionally a duplicate and a reversal) to the issuer, then prints
both messages decoded. It is a stopgap until the virtual POS of a later phase exists.

```bash
java -cp iso8583/target/iso8583-0.1.0-SNAPSHOT.jar tools/SmokeClient.java \
  --pan 9999000000000012 --expiry 2910 --amount 12.50 --pin 4321 --merchant "Corner Cafe"   # add --reverse, --resend, --port
```

## Inspect a message

```bash
java -cp iso8583/target/iso8583-0.1.0-SNAPSHOT.jar com.bilal.cardnet.iso8583.Inspect \
  "0200702000000080000016999900123456789100000000000025000001230000TERM0001"
```

Prints a hex dump and the decoded fields. The card number is masked (first six, last four) and PIN data is hidden unless
`--show-sensitive` is given. Test card numbers use BIN `999900`, which belongs to no real card brand.

## Clearing files

`com.bilal.cardnet.iso8583.clearing` reads and writes clearing files (P4): a `1644` header, one `1240` first presentment per line, a `1644` trailer with the count, the control total and a SHA-256, all encrypted with AES-256-GCM (the file name is part of the check). To look inside one, with card numbers masked:

```
java -cp iso8583/target/iso8583-0.1.0-SNAPSHOT.jar com.bilal.cardnet.iso8583.clearing.ClearingDump path\to\file.clr
```

The key is the text in `CARDNET_FILE_KEY` (a development default is built in). Do not rename a file before dumping it. One symmetric key for every party is a simulation shortcut.

## End of day: capture and clearing

Set `CARDNET_DATA_DIR` (the same folder for the network and the acquirer; default `./data/clearing`) and `CARDNET_FILE_KEY` (the same text everywhere) and start the network and the acquirer as above. On the POS page (port 9000) approved payments can get a final amount (a tip, up to 20% more) and cleared ones can be refunded; "Close the day" puts everything into one clearing file, the network checks it, splits it per issuer and passes it on, and the acknowledgement comes back. Until NeoBank ingests the files (P4c) you can try the chain with the stand-in issuer:

```
java -cp "shared/target/shared-0.1.0-SNAPSHOT.jar;iso8583/target/iso8583-0.1.0-SNAPSHOT.jar" tools/ClearingStub.java DATA_DIR
```

It answers every file as posted and posts nothing. Fees (merchant discount 2.00%, interchange 1.20%, scheme fee 0.05% each side) are only worked out and stored in P4; they are posted in P5.

## Settlement and the end-of-day tool

After clearing, the network turns the cleared items of a business day into **one net movement per participant** (SPEC-P5-settlement.md): for each cleared purchase the issuer pays amount - interchange + its scheme fee, the acquirer receives amount - interchange - its scheme fee, the network receives both scheme fees; the three add up to zero, and a refund reverses every sign. The run has a value date (the next business day; weekends and `CARDNET_HOLIDAYS=2026-12-25,...` skipped) and writes, per participant, a CSV and a camt.053-STYLE XML statement into `settlement-out/` of the clearing folder (totals only, readable on purpose, not encrypted, not schema-validated: a simulation).

```
POST http://127.0.0.1:9100/settlement/run[?date=2026-10-06]    one run (the oldest unsettled day when no date)
GET  http://127.0.0.1:9100/settlements                          the latest runs
java tools/Eod.java [--pos http://127.0.0.1:9000] [--net http://127.0.0.1:9100]   close the day, wait, settle, print the figures
```
