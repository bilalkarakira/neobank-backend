# ISO 8583:1987 as used in cardnet (ASCII variant)

This is the contract the codec implements and the tests check. It follows ISO 8583:1987 and common field usage.
Visa and Mastercard define their own proprietary profiles; this is **not** a scheme specification.

## Message layout

```
[ 2-byte big-endian length ]  <- TCP framing only (see Frame), not part of the message
[ MTI        4 ASCII digits ]
[ primary bitmap  16 ASCII hex chars = 64 bits ]
[ secondary bitmap 16 ASCII hex chars ]  <- only when bit 1 of the primary bitmap is set
[ data elements in ascending field number ]
```

- Bit 1 means "a secondary bitmap follows". Bits 2-64 select fields 2-64 (primary), bits 65-128 select fields 65-128.
- Bit 65 (tertiary bitmap) is not supported; a message that sets it is rejected. Fields 1 and 65 can never be set by a caller.
- Bit numbering: bit n is in byte (n-1)/8 of the bitmap, at mask `0x80 >> ((n-1) % 8)`. Bitmaps are written in upper-case hex.
- Hex ASCII was chosen for the bitmap (and for binary fields) so that every message is human-readable in a hex dump.

## Field types

| Type | Meaning | Allowed characters | Fixed-length padding |
|---|---|---|---|
| `n` | numeric | `0-9` | right-justified, zero filled on the left |
| `an` | alphanumeric | `A-Z a-z 0-9` | left-justified, space filled on the right |
| `ans` | alphanumeric + special | printable ASCII `0x20-0x7E` | left-justified, space filled on the right |
| `b` | binary | hex text `0-9 A-F` (2 chars per byte) | exact length, no padding |

Length prefixes: `LLVAR` = 2 ASCII digits, `LLLVAR` = 3 ASCII digits. The prefix counts **data characters**
(for `b` fields it counts hex characters, i.e. twice the number of bytes, and the count must be even).

Strictness: when packing, a fixed field must be given its **exact** length (the codec never pads or truncates for you);
when unpacking, a value that breaks its type, a bad prefix, truncated or surplus bytes are errors.

## Data elements supported

| DE | Name | Format | Notes |
|---|---|---|---|
| 2 | Primary account number (PAN) | `n ..19` LLVAR | masked in the inspector |
| 3 | Processing code | `n 6` | `00` purchase, `01` cash, `20` refund; then from/to account types |
| 4 | Amount, transaction | `n 12` | minor units (cents), zero filled |
| 7 | Transmission date and time | `n 10` | `MMDDhhmmss`, UTC |
| 11 | System trace audit number (STAN) | `n 6` | |
| 12 | Local transaction time | `n 6` | `hhmmss` |
| 13 | Local transaction date | `n 4` | `MMDD` |
| 14 | Expiration date | `n 4` | `YYMM` |
| 18 | Merchant category code (MCC) | `n 4` | |
| 22 | POS entry mode | `n 3` | `051` chip, `071` contactless, `901` magstripe, `812` e-commerce |
| 25 | POS condition code | `n 2` | |
| 32 | Acquiring institution id | `n ..11` LLVAR | |
| 37 | Retrieval reference number (RRN) | `an 12` | |
| 38 | Authorization id response | `an 6` | |
| 39 | Response code | `an 2` | `00` approved, see below |
| 41 | Card acceptor terminal id | `ans 8` | |
| 42 | Card acceptor id (merchant) | `ans 15` | |
| 43 | Card acceptor name/location | `ans 40` | |
| 48 | Additional data, private | `ans ...999` LLLVAR | |
| 49 | Currency code, transaction | `n 3` | ISO 4217 numeric, `840` USD |
| 52 | PIN data | `b 16 hex` (8 bytes) | masked in the inspector |
| 55 | ICC (chip) data | `b ...510 hex` LLLVAR | TLV, see `Tlv` |
| 70 | Network management information code | `n 3` | `001` sign-on, `002` sign-off, `301` echo |
| 90 | Original data elements | `n 42` | MTI(4) STAN(6) datetime(10) acquirer(11, zero filled) forwarding(11, zero filled) |

Any other bit set in a received bitmap is rejected: without a definition the codec cannot know where the field ends.

## Message type indicator (MTI)

Four digits: version, class, function, origin.

| Digit | Values |
|---|---|
| 1 version | `0` ISO 8583:1987, `1` 1993, `2` 2003 |
| 2 class | `1` authorization, `2` financial, `3` file action, `4` reversal, `5` reconciliation, `6` administrative, `7` fee collection, `8` network management |
| 3 function | `0` request, `1` request response, `2` advice, `3` advice response, `4` notification, `5` notification acknowledgement |
| 4 origin | `0` acquirer, `1` acquirer repeat, `2` card issuer, `3` card issuer repeat |

Messages used in cardnet: `0100/0110` authorization, `0200/0210` financial, `0400/0410` reversal, `0420/0430` reversal advice,
`0800/0810` network management, `1240` first presentment (clearing), `1644` administrative (clearing file header/trailer).

A first presentment is a **notification** (function digit 4): the acquirer tells the network "this sale is final", and
presentments are not answered one by one. The clearing *file* is acknowledged as a whole (file level), and the codec only
supplies `1250` as the generic acknowledgement MTI.

## Response codes (DE 39)

`00` approved, `05` do not honour, `14` invalid card number, `30` format error, `51` insufficient funds, `54` expired card,
`55` incorrect PIN, `57` transaction not permitted, `61` exceeds withdrawal limit, `91` issuer unavailable, `94` duplicate
transmission, `96` system malfunction.

## Framing

On TCP each message is preceded by a 2-byte big-endian length (the number of message bytes that follow). A zero length, a
length above the configured maximum (default 4096) or a stream that ends inside a frame is an error.

## Sensitive data

PAN (DE 2) and PIN data (DE 52) are masked by every printer in this library unless the caller explicitly asks otherwise.
PAN display follows the usual rule: at most the first six and last four digits.
