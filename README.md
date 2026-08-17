# Ebone Dealer Panel

Android app for Ebone's dealers/franchises to submit payment proof
(bank transaction ID + screenshot) for wallet balance top-ups against
Wateen, Ebone, and Zong network credit.

## What this app does

1. **Register** — dealer enters their name, mobile, and a 6-digit dealer
   code (issued by the admin) to link their device to their dealer record.
2. **Submit payment** — dealer picks a panel (Wateen/Ebone/Zong), a
   payment method (EasyPaisa, JazzCash, bank, etc.), enters the amount +
   transaction ID, and optionally attaches a screenshot (auto-read via
   on-device OCR to double-check the amount/TID).
3. **History** — dealer can see their past submissions and their
   verification status (Pending / Verified).

## Verification flow (matches EboneAdminPanel)

Submitting a payment here does **not** credit the dealer's balance
immediately — it creates a `PENDING` record in Firestore
(`dealerTransactions` collection). The actual balance credit happens on
the **admin's phone**, running EboneAdminPanel:

- `DealerPaymentSmsReceiver` watches incoming payment SMS in real time.
- `DealerPaymentSmsScanner` covers any SMS that arrived while the app
  wasn't running.
- Both cross-check the SMS against the dealer's typed transaction ID
  **and** whatever ID/amount the on-device OCR read off their screenshot
  — a typo in one doesn't block a match if the other is correct.
- On a match: the transaction flips to `VERIFIED` and the dealer's
  balance (`wateenBalance` / `eboneBalance` / `zongBalance`) is credited
  automatically — no manual approval step needed.

### Isolation from the customer-facing app

This dealer payment flow is **completely separate** from
CustomerIDApp/EboneAdminPanel's customer payment flow — different
Firestore collection (`dealerTransactions`, not `transactions`),
different SMS receiver class, different balance fields. The two systems
never read or write each other's data.

## Requirements

- Android 7.0 (API 24) or higher
- SMS permission is **not** required by this app — only by
  EboneAdminPanel, which is where the actual SMS matching happens.

## Tech stack

- Kotlin, Firebase Firestore
- Google ML Kit (on-device OCR, via `play-services-mlkit-text-recognition`)
- Kotlin Coroutines (`kotlinx-coroutines-play-services` for `.await()`)

See `CHANGELOG.md` for release history.
