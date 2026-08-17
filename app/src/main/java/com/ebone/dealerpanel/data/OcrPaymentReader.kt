package com.ebone.dealerpanel.data

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Step 2 of the Payment Verification Engine (used when the customer uploads
 * a payment screenshot to confirm the manually-entered TID).
 *
 * Three outcomes:
 *  - onSuccess: OCR confidently found an amount/T-ID in the image.
 *  - onAmbiguous: OCR read *some* text but couldn't confidently extract a
 *    T-ID/amount via regex — caller should fall back to AiPaymentInterpreter
 *    using the raw OCR text.
 *  - onFailure: OCR itself failed to process the image (corrupt file, etc).
 */
object OcrPaymentReader {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun readFromBitmap(
        bitmap: Bitmap,
        onSuccess: (SmsPaymentParser.ParsedResult) -> Unit,
        onAmbiguous: (rawText: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extracted = visionText.text
                val parsed = SmsPaymentParser.parse(extracted)
                if (parsed.transactionId == null && parsed.amount == null) {
                    onAmbiguous(extracted)
                } else {
                    onSuccess(parsed)
                }
            }
            .addOnFailureListener { e -> onFailure(e) }
    }
}
