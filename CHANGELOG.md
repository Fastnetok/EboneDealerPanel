# Changelog

## v1.0.0 — First release

- Dealer registration via 6-digit dealer code + device binding
- Payment submission (panel, method, amount, transaction ID, screenshot)
- On-device OCR reads amount/TID off the payment screenshot as a
  cross-check against what the dealer typed manually
- Payment history screen (Pending / Verified)
- Fixed: missing `kotlinx-coroutines-play-services` dependency, which
  caused build failures on every `.await()` call in `DealerRepo`
- Fixed: dealer transactions now write to their own `dealerTransactions`
  Firestore collection, kept fully separate from the customer-facing
  app's `transactions` collection (prevents any cross-matching/mixing)
