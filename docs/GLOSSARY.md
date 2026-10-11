# Glossary

**Cardholder**: the person with the card. **Merchant / shop**: where they pay.

**Issuer**: the bank that issued the card and holds the customer's money. Here: NeoBank.
**Acquirer**: the shop's bank, which connects the shop's terminals to the card network. Here: the acquirer program.
**Card network (scheme)**: routes messages between acquirers and issuers and sets the rules and fees (Visa, Mastercard in real life). Here: the network program. Four parties in all: cardholder, merchant, acquirer, issuer, with the network in the middle.
**BIN**: the first digits of a card number; they say which issuer it belongs to. Test BIN here: 999900.

**ISO 8583**: the message format card systems use. A message has a type (MTI, for example 0100) and numbered data elements (DE 2 card number, DE 4 amount, DE 39 response code...). This project uses the 1987 version plus common usage.
**MTI**: message type indicator: 0100 authorization request, 0110 its response, 0400 reversal, 0120 advice, 0800 network management (sign-on, echo), 1240 first presentment (clearing), 1644 file header and trailer.
**Response code (DE 39)**: 00 approved, 51 not enough money, 54 expired, 55 wrong PIN, 57 not permitted, 61 over the limit, 91 issuer not available, 94 duplicate, 96 system error.

**Authorization**: the real-time question "may this payment go ahead?". If approved, the issuer reserves the money: a **hold**. **Available balance** = balance minus holds.
**Reversal**: cancels an authorization (a void, or after a timeout). **Duplicate**: the same request sent twice: answered once, held once.
**Stand-in**: the network approving small payments on the issuer's behalf while the issuer is unreachable; it tells the issuer afterwards with an **advice**.

**Capture**: the shop confirming the final amount. **Clearing**: the file the acquirer sends at the end of the day with every payment's final amount (a tip makes it more than was authorized); the issuer matches each to its authorization and posts the real debit. A **refund** is a credit presented the same way.
**Receivable**: money a customer owes the bank (here: the part of a cleared payment their account could not cover).

**Interchange**: a fee the acquirer pays the issuer for each purchase (here 1.20%). **Scheme fee**: what each bank pays the network (0.05%). **Merchant discount rate (MDR)**: what the shop pays its own bank (2.00%).
**Settlement**: the actual movement of money between the banks for a day's cleared payments, as one net amount each. **Value date**: the day the money counts as moved (here: the next business day). **camt.053**: the ISO 20022 bank statement format; the XML here is a simplified subset.
**Reconciliation**: proving that the records agree: what was authorized, what was cleared, what was settled, and the ledger.

**Double-entry ledger**: every money movement is written as two postings that sum to zero, so money is never created or lost, and balances can always be recomputed from the entries.
