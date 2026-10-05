# cardnet

A simulated card network around NeoBank, built to show how a card payment really moves: ISO 8583 messages in real time,
then clearing and settlement files later. NeoBank (the sibling `neobank-backend` project) is the issuer.

Status: phase P1 (the ISO 8583 codec). See `../docs/PLAN-card-network.md` for the whole plan.

## Modules

| Module | What it is |
|---|---|
| `iso8583` | ISO 8583:1987 codec (ASCII variant), TCP framing, message inspector. No dependencies. |

The wire format is specified in [docs/ISO8583-spec.md](docs/ISO8583-spec.md). It follows ISO 8583:1987 and common field usage;
Visa and Mastercard profiles are proprietary, so this is **not** a scheme specification and makes no certification claim.

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
