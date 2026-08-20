package com.ebone.dealerpanel

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ebone.dealerpanel.data.DealerRepo
import com.ebone.dealerpanel.data.OcrPaymentReader
import com.ebone.dealerpanel.data.PaymentSource
import com.ebone.dealerpanel.data.SmsPaymentParser
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class PaymentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PANEL = "extra_panel"
    }
    private lateinit var repo: DealerRepo
    private lateinit var panelSpinner: Spinner
    private lateinit var methodSpinner: Spinner
    private lateinit var amountInput: EditText
    private lateinit var tidInput: EditText
    private lateinit var result: TextView
    private var ocrResult: SmsPaymentParser.ParsedResult? = null
    private var screenshotSelected = false

    private val pickScreenshot = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) runOcr(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.payment)
        repo = DealerRepo(this)

        panelSpinner = findViewById(R.id.panel)
        methodSpinner = findViewById(R.id.method)
        amountInput = findViewById(R.id.amount)
        tidInput = findViewById(R.id.tid)
        result = findViewById(R.id.result)

        panelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Wateen", "Ebone", "Zong")
        )

        val presetPanel = intent.getStringExtra(EXTRA_PANEL)

        // NEW: zone-aware — filter the panel dropdown down to only the
        // ISPs actually enabled for this dealer's zone (defaults to
        // "Okara" / all-enabled if not yet zone-tagged, so nothing
        // changes until Admin assigns zones). This is the safety net
        // for dealers who reach this screen via the generic "Pay"
        // button rather than a specific WATEEN/EBONE/ZONG tap on the
        // dashboard (which already blocks disabled ones on MainActivity).
        lifecycleScope.launch {
            val id = repo.id()
            val zone = if (id.isNotBlank()) {
                repo.dealer(id).get("zone")?.toString()?.ifBlank { null } ?: "Okara"
            } else "Okara"
            val config = repo.zoneServiceConfig(zone)
            val allPanels = listOf("Wateen" to (config["wateenEnabled"] ?: true), "Ebone" to (config["eboneEnabled"] ?: true), "Zong" to (config["zongEnabled"] ?: true))
            val enabledPanels = allPanels.filter { it.second }.map { it.first }
            val panelsToShow = enabledPanels.ifEmpty { listOf("Wateen", "Ebone", "Zong") } // never leave the dropdown empty

            panelSpinner.adapter = ArrayAdapter(
                this@PaymentActivity,
                android.R.layout.simple_spinner_dropdown_item,
                panelsToShow
            )

            if (!presetPanel.isNullOrBlank()) {
                val index = panelsToShow.indexOfFirst { it.equals(presetPanel, ignoreCase = true) }
                if (index >= 0) panelSpinner.setSelection(index)
            }
        }

        lifecycleScope.launch {
            val id = repo.id()
            val methods = if (id.isBlank()) emptyList() else repo.methods(id)
            val visible = if (methods.isEmpty()) defaultMethods() else methods
            methodSpinner.adapter = ArrayAdapter(
                this@PaymentActivity,
                android.R.layout.simple_spinner_dropdown_item,
                visible.map { methodLabel(it) }
            )
        }

        findViewById<Button>(R.id.shot).setOnClickListener {
            pickScreenshot.launch("image/*")
        }

        findViewById<Button>(R.id.submit).setOnClickListener {
            submitPayment()
        }
    }

    private fun defaultMethods(): List<String> = listOf(
        PaymentSource.EASYPAISA.name,
        PaymentSource.JAZZCASH.name,
        PaymentSource.SADAPAY.name,
        PaymentSource.RAAST_ID.name,
        PaymentSource.TILL_ID.name,
        PaymentSource.FAYSAL_BANK.name,
        PaymentSource.BANK_ALFALAH.name,
        PaymentSource.OTHER_BANK.name
    )

    private fun methodLabel(value: String): String {
        val key = value.trim()
            .uppercase()
            .replace(" ", "_")
            .replace("-", "_")

        return when (key) {
            "EASYPAISA" -> "EasyPaisa"
            "JAZZCASH" -> "JazzCash"
            "SADAPAY" -> "SadaPay"
            "RAAST_ID", "RAASTID" -> "Raast ID"
            "TILL_ID", "TILLID", "JAZZ_TILL_ID", "JAZZTILLID" -> "Jazz Till ID"
            "FAYSAL_BANK", "FAISAL_BANK", "FAYSALBANK", "FAISALBANK" -> "Faysal Bank"
            "BANK_ALFALAH", "ALFALAH_BANK", "BANKALFALAH" -> "Alfalah Bank"
            "OTHER_BANK", "OTHERBANK" -> "Other Bank"
            else -> value.trim().replace('_', ' ')
        }
    }

    private fun runOcr(uri: Uri) {
        result.text = "Reading screenshot..."
        val bitmap: Bitmap = try {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        } catch (e: Exception) {
            result.text = "Screenshot read failed: ${e.message ?: "unknown error"}"
            return
        }

        OcrPaymentReader.readFromBitmap(
            bitmap,
            onSuccess = { parsed ->
                ocrResult = parsed
                screenshotSelected = true
                runOnUiThread {
                    if (amountInput.text.isNullOrBlank() && parsed.amount != null) {
                        amountInput.setText("%.2f".format(parsed.amount))
                    }
                    if (tidInput.text.isNullOrBlank() && parsed.transactionId != null) {
                        tidInput.setText(parsed.transactionId)
                    }
                    // NEW: word "OCR" removed (dealer shouldn't know the
                    // verification method), but the actual TID/Amount
                    // readout stays visible in "{TID} / Rs. {Amount}"
                    // format — this is Confirm karo/adjust before
                    // submitting info the dealer genuinely needs to see.
                    result.text = "${parsed.transactionId ?: "TID not found"} / Rs. ${parsed.amount ?: "?"}"
                }
            },
            onAmbiguous = { raw ->
                val parsed = SmsPaymentParser.parse(raw)
                ocrResult = parsed
                screenshotSelected = true
                runOnUiThread {
                    if (amountInput.text.isNullOrBlank() && parsed.amount != null) {
                        amountInput.setText("%.2f".format(parsed.amount))
                    }
                    if (tidInput.text.isNullOrBlank() && parsed.transactionId != null) {
                        tidInput.setText(parsed.transactionId)
                    }
                    result.text = "${parsed.transactionId ?: "TID not found"} / Rs. ${parsed.amount ?: "?"}"
                }
            },
            onFailure = { e ->
                runOnUiThread { result.text = "Screenshot could not be read: ${e.message ?: "unknown error"}" }
            }
        )
    }

    private fun submitPayment() {
        val amount = amountInput.text.toString().trim().toDoubleOrNull()
        val tid = tidInput.text.toString().trim()
        if (amount == null || amount <= 0.0) {
            Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }
        if (tid.isBlank()) {
            Toast.makeText(this, "Transaction ID is required", Toast.LENGTH_SHORT).show()
            return
        }
        if (!screenshotSelected) {
            Toast.makeText(this, "Please share the payment screenshot first", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val dealerId = repo.id()
            if (dealerId.isBlank()) {
                Toast.makeText(this@PaymentActivity, "Dealer registration required", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (repo.used(tid)) {
                Toast.makeText(this@PaymentActivity, "This Transaction ID is already submitted", Toast.LENGTH_LONG).show()
                return@launch
            }

            val limitsSnapshot = try {
                FirebaseDatabase.getInstance()
                    .getReference("paymentSettings")
                    .get()
                    .await()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PaymentActivity,
                    "Unable to check payment limit. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val minimumAmount = limitsSnapshot.child("minimumAmount")
                .getValue(Long::class.java) ?: 3000L

            val maximumAmount = limitsSnapshot.child("maximumAmount")
                .getValue(Long::class.java) ?: 100000L

            if (minimumAmount < 1L || maximumAmount < minimumAmount) {
                Toast.makeText(
                    this@PaymentActivity,
                    "Payment limits are not configured correctly. Please contact Admin.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            if (amount < minimumAmount.toDouble()) {
                Toast.makeText(
                    this@PaymentActivity,
                    "Minimum payment is Rs. $minimumAmount. Please enter Rs. $minimumAmount or more.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            if (amount > maximumAmount.toDouble()) {
                Toast.makeText(
                    this@PaymentActivity,
                    "Maximum payment is Rs. $maximumAmount. Please enter a lower amount.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val now = System.currentTimeMillis()
            val data: Map<String, Any> = mapOf(
                "dealerId" to dealerId,
                "panel" to panelSpinner.selectedItem.toString(),
                "paymentSource" to methodSpinner.selectedItem.toString(),
                "amount" to amount,
                "bankTransactionId" to tid,
                "ocrAmount" to (ocrResult?.amount ?: amount),
                "ocrTransactionId" to (ocrResult?.transactionId ?: tid),
                "status" to "PENDING",
                "submittedAt" to now,
                "availableAt" to (now + 30L * 60L * 1000L)
            )

            try {
                repo.save(data)
                Toast.makeText(
                    this@PaymentActivity,
                    "Payment submitted. Verification is pending for up to 30 minutes.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PaymentActivity,
                    "Payment could not be added: ${e.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}