package com.ebone.dealerpanel.data

import android.content.Context

/**
 * Extracts amount + transaction id (T-ID) from raw SMS body using regex.
 * If parsing fails (no amount found), the caller should trigger the
 * OCR fallback flow (see OcrPaymentReader), and if OCR also fails,
 * fall back to AI/ChatGPT text interpretation (see AiPaymentInterpreter).
 */
object SmsPaymentParser {

    private val amountRegex = Regex("""(?:Rs\.?|PKR)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val labeledTidRegex = Regex("""(?:T-?ID|Txn\s?ID|Trx\s?No|Reference)[:\s#]*([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    // Fallback: JazzCash/bank TIDs are typically a standalone 12–14 digit number
    // with no label at all in some screenshots. Deliberately excludes 11-digit
    // sequences, since Pakistani mobile numbers (03XXXXXXXXX) are exactly 11
    // digits and were being mistaken for TIDs.
    private val standaloneDigitTidRegex = Regex("""\b(\d{12,14})\b""")

    data class ParsedResult(
        val amount: Double?,
        val transactionId: String?,
        val rawText: String
    )

    fun parse(smsBody: String): ParsedResult {
        val amountMatch = amountRegex.find(smsBody)
        val amount = amountMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        val labeledMatch = labeledTidRegex.find(smsBody)
        val tid = labeledMatch?.groupValues?.get(1)
            ?: standaloneDigitTidRegex.find(smsBody)?.groupValues?.get(1)

        return ParsedResult(amount, tid, smsBody)
    }

    /**
     * Returns EVERY TID-looking candidate found in the text, not just the
     * first one. Needed because a single screenshot can contain multiple
     * transactions (e.g. an SMS thread showing 2 payments) — the customer's
     * manually entered TID should be matched against any of them, not only
     * whichever one happens to appear first.
     */
    fun parseAllTidCandidates(smsBody: String): List<String> {
        val labeled = labeledTidRegex.findAll(smsBody).map { it.groupValues[1] }.toList()
        val standalone = standaloneDigitTidRegex.findAll(smsBody).map { it.groupValues[1] }.toList()
        return (labeled + standalone).distinct()
    }

    /**
     * Parses the SMS and forwards result to the pending payment screen / repository.
     * If amount could not be determined, this signals that OCR/AI fallback is required —
     * that hand-off happens in PaymentActivity, which owns the UI state.
     */
    fun parseAndForward(context: Context, source: PaymentSource, smsBody: String) {
        val result = parse(smsBody)
        PendingPaymentBus.postSmsResult(source, result)
    }

    /**
     * A screenshot can contain MULTIPLE messages (e.g. a full SMS thread with
     * cashback notifications, other transfers, etc.) — blindly taking the
     * first "Rs ..." found anywhere in the text can grab the wrong amount.
     * This instead searches only the text immediately around where the
     * MATCHED TID appears, so the amount picked up belongs to that specific
     * transaction.
     */
    fun amountNearTid(smsBody: String, tid: String): Double? {
        val tidIndex = smsBody.indexOf(tid, ignoreCase = true)
        if (tidIndex == -1) return parse(smsBody).amount // fallback: old behaviour

        val windowStart = (tidIndex - 200).coerceAtLeast(0)
        val windowEnd = (tidIndex + tid.length + 50).coerceAtMost(smsBody.length)
        val nearbyText = smsBody.substring(windowStart, windowEnd)

        return amountRegex.find(nearbyText)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    }
}

/**
 * Simple in-memory bus so the SMS receiver (which has no UI) can hand off
 * a detected payment to whichever screen is currently listening
 * (typically PaymentActivity). Replace with LiveData/Flow as the app grows.
 */
object PendingPaymentBus {
    private var listener: ((PaymentSource, SmsPaymentParser.ParsedResult) -> Unit)? = null

    fun subscribe(callback: (PaymentSource, SmsPaymentParser.ParsedResult) -> Unit) {
        listener = callback
    }

    fun unsubscribe() {
        listener = null
    }

    fun postSmsResult(source: PaymentSource, result: SmsPaymentParser.ParsedResult) {
        listener?.invoke(source, result)
    }
}