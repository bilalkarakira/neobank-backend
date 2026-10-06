# Limits: what is simulated, simplified or not built

This is a learning project. It is built to be **correct about the ideas** and **honest about the rest**.

## Not what the real world has
- **Not a bank.** A card-issuing neobank simulation: no banking licence, no customer due diligence (KYC), no anti-money-laundering, no regulatory reporting, no deposit protection.
- **No real card scheme.** ISO 8583:1987 plus common usage, not a Visa or Mastercard profile (those are proprietary). Test card numbers only (BIN 999900).
- **Cryptography is a sketch.** PINs travel as a *clear* format-0 PIN block (a real one is encrypted under a key between hardware security modules); no MACs; no EMV chip cryptograms; no 3-D Secure; no tokenization. Card numbers are stored only as a keyed hash and the last four digits; the network and the acquirer keep encrypted copies where a message must be rebuilt later, never PIN data.
- **Keys are development defaults** everywhere (`CARD_PAN_KEY`, `CARDNET_VAULT_KEY`, `CARDNET_FILE_KEY`, `JWT_SECRET`). Clearing files use one symmetric key shared by three parties (real ones use a key pair per party).
- **No TLS and no authentication between the programs**; they are meant for one machine (loopback, or a private container network).
- **Settlement is simulated.** No settlement bank, no cut-off times, no calendar (the value date is only a label), no intraday cycles. Statements are readable, unsigned files; NeoBank checks the figures itself instead of trusting them.

## Simplified
- One acquirer, one issuer, one currency (USD). Fees are flat percentages.
- A cleared payment that no authorization matches is posted anyway and flagged; real schemes have more nuanced exception handling.
- A statement NeoBank disputes stays disputed (no resolution workflow); a clearing file item that fails is retried forever.
- Clearing files from before business dates existed never reach a settlement.

## Not built
Chargebacks and representments, ATM withdrawals, incremental or partial authorizations, card-not-present payments, currency conversion, disputes UI, CI, published images, a cloud deployment, screenshots.

## What *is* done properly
Real-time authorization with holds and available balance; idempotency and duplicate handling; reversals with durable retries; stand-in with advices; file-based clearing and settlement with file-level acknowledgement; a double-entry ledger whose invariant is checked; race-safe posting (row locks, unique keys as the final backstop); and tests, including checks that break the code on purpose to confirm a test notices.
