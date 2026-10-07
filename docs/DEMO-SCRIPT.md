# The five-minute walk-through

Start the stack (`docker compose up --build`, wait until it settles) and open three browser tabs: the **bank**
(http://127.0.0.1:4200, sign in as `demo@neobank.local` / `DemoPassw0rd`), the **shop** (http://127.0.0.1:9000) and, in a private
window or another browser, the **bank's admin** view (same address, `admin@neobank.local` / `AdminPassw0rd`).
Prefer to watch it happen in a terminal? `java scenario/Scenario.java` plays steps 1 to 9 for you with the expected outcome of each.

## 1. A payment (30 seconds)

*Shop:* press **Use the demo card**, enter **25.00**, **Pay**.  
The page shows the ISO 8583 request and response as decoded fields and as hex (the card number and PIN hidden).
*Bank, Cards:* open the card, **Show activity**: the 25.00 is **pending**, and the available balance dropped by 25.00 while the
balance did not. *Lesson: an approval reserves money, it does not take it.*

## 2. Things going wrong (60 seconds)

*Shop:* pay at **Casino Royale**: declined 57. Pay with a wrong PIN: declined 55. Press **Send last again**: the same answer and
the bank still holds the money once. *Lesson: a decline has a reason code; a repeated request must not hold twice.*

## 3. The bank is down (60 seconds)

*Shop, Network panel:* tick **NeoBank is down**, **Apply**, then pay **20.00** without a PIN: approved, with a code that starts
with `SI`: the *network* approved it by itself (stand-in). Pay **80.00**: refused (91), too large for stand-in. Untick the box.  
*Bank, Cards:* within seconds the 20.00 appears as pending with the note "Approved by the card network while NeoBank was
unavailable": the network told the bank afterwards (a 0120 advice). *Lesson: availability rules and who bears the risk.*

## 4. Closing the day: clearing (60 seconds)

*Shop:* on one approved payment type a final amount (**30.00** for a 25.00 payment: a tip) and **Set amount**. Then **Close the
day** (End of day section): a clearing file goes to the network, which checks it, works out fees and forwards it; the batch shows
*accepted* and the payments *cleared*.  
*Bank, Cards:* the payments are now under **Posted**: the tipped one shows "Authorized $25.00, posted $30.00". The balance dropped.
*Bank, History:* "Card payment" entries. *Lesson: the real debit happens at clearing, for the final amount.*  
*Admin, Clearing:* the file, every item and how it was decided, and the books check.

## 5. Refund and settlement (60 seconds)

*Shop:* **Refund** part of a cleared payment, **Close the day** again, then run the end of day:
`java ../neobank-cardnet/tools/Eod.java` (or `curl -X POST http://127.0.0.1:9100/settlement/run`). It prints the net movement for the
issuer, the acquirer and the network (they add up to zero).  
*Admin, Settlement:* the statement is **Matched and posted** (NeoBank recomputed the figures itself); the three totals
(authorized, cleared, settled) and the differences list: the tip shows up as "Amount changed". *Lesson: reconciliation is
three records that must agree, and every difference has a plain explanation.*

## 5b. Cash (1 minute)

*Shop page, Cash machine:* **Use the demo card** and withdraw 100: the PIN is required (try without it: the machine refuses), the machine
pays notes of 10, and the bank applies its own cash limits (300 per withdrawal, 500 per day: try 400 and read the refusal, code 61).
*Bank, Cards:* the withdrawal is marked **Cash withdrawal**, pending until the day is closed, then posted like a payment.
*Lesson: a cash withdrawal is the same authorization with another processing code (01), a mandatory PIN and its own limits.*

## 5c. A fuel pump that tops up (1 minute)

*Shop page:* pay 50 at the **Gas Station** with the PIN, then in the payment list type 25 under **add amount** and press **Add to payment**:
the bank approves it and the payment now reads "50.00 + 25.00". Try adding 2500: the bank refuses (code 12, the total would pass its limit).
Type the real amount the pump used (for example 61.40) under **final amount** and press **Set amount**, then **Close the day**.
*Bank, Cards:* the 25.00 addition shows as a pending hold "Added to an earlier payment"; after clearing the payment is posted for 61.40
and the addition reads "Added to the main payment". *Lesson: an incremental authorization is an authorization of its own that points at the
first one; clearing is for the final amount, and every hold of the payment ends with it.*

## 6. A dispute (2 minutes)

*Bank, Cards:* under **Posted**, press **Dispute this payment**, choose a reason and send. The amount is **credited at once**
(provisional credit) and the dispute reads "Sent to the card network", then "Under review: the shop can still answer".
*Shop:* the **Chargebacks** section shows the chargeback with its reason. Type some evidence and press **Represent**: the
shop's bank answers once.
*Bank:* the dispute reads **Lost** and the money is taken from the account again. Dispute another payment and do nothing at the
shop: after the answer window (30 seconds in this stack, two weeks by default) it reads **Won: the credit is final**.
*Admin, Disputes:* every dispute and its state, a button that closes the due ones now; *Settlement:* the chargeback and the
representment are in the next statement (a refund and a purchase), and NeoBank matched it by its own computation.
*Lesson: a chargeback is a refund-like item through the whole chain; the shop gets one answer; silence means the customer wins.*

## Look at the files (optional)

```bash
docker compose exec network sh -c "ls /data/clearing/settlement-out"     # the CSV and camt.053-style XML statements
```
The checked-in samples (`neobank-cardnet/samples/`) show every file and message type without running anything.
