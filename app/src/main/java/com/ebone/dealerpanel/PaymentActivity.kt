package com.ebone.dealerpanel

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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

    private data class BankDetails(
        val bankName: String,
        val accountTitle: String,
        val accountNumber: String,
        val qrDrawableRes: Int
    )

    private val bankDetailsMap: Map<String, BankDetails> = mapOf(
        "BANK_ALFALAH" to BankDetails(
            bankName = "Bank Alfalah",
            accountTitle = "Muhammad Abbas",
            accountNumber = "5721 5002 8070 58",
            qrDrawableRes = R.drawable.qr_bank_alfalah
        )
    )

    private lateinit var repo: DealerRepo
    private lateinit var panelSpinner: Spinner
    private lateinit var methodSpinner: Spinner
    private lateinit var amountInput: EditText
    private lateinit var tidInput: EditText
    private lateinit var senderNameInput: EditText
    private lateinit var result: TextView

    private lateinit var bankDetailsCard: View
    private lateinit var bankQrImage: ImageView
    private lateinit var bankNameValue: TextView
    private lateinit var bankTitleValue: TextView
    private lateinit var bankAccountValue: TextView
    private lateinit var copyAccountBtn: TextView

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
        senderNameInput = findViewById(R.id.senderName)
        result = findViewById(R.id.result)

        bankDetailsCard = findViewById(R.id.bankDetailsCard)
        bankQrImage = findViewById(R.id.bankQrImage)
        bankNameValue = findViewById(R.id.bankNameValue)
        bankTitleValue = findViewById(R.id.bankTitleValue)
        bankAccountValue = findViewById(R.id.bankAccountValue)
        copyAccountBtn = findViewById(R.id.copyAccountBtn)

        panelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Wateen", "Ebone", "Zong")
        )

        val presetPanel = intent.getStringExtra(EXTRA_PANEL)

        lifecycleScope.launch {
            val id = repo.id()
            val zone = if (id.isNotBlank()) {
                repo.dealer(id).get("zone")?.toString()?.ifBlank { null } ?: "Okara"
            } else "Okara"
            val config = repo.zoneServiceConfig(zone)
            val allPanels = listOf("Wateen" to (config["wateenEnabled"] ?: true), "Ebone" to (config["eboneEnabled"] ?: true), "Zong" to (config["zongEnabled"] ?: true))
            val enabledPanels = allPanels.filter { it.second }.map { it.first }
            val panelsToShow = enabledPanels.ifEmpty { listOf("Wateen", "Ebone", "Zong") }

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

        methodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateBankCard()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                bankDetailsCard.visibility = View.GONE
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
            updateBankCard()
        }

        copyAccountBtn.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val accountNumber = bankAccountValue.text.toString().replace(" ", "")
            clipboard.setPrimaryClip(ClipData.newPlainText("Account Number", accountNumber))
            Toast.makeText(this, "Account number copied", Toast.LENGTH_SHORT).show()
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
        val key = normalizeMethodKey(value)
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

    private fun normalizeMethodKey(value: String): String = value.trim()
        .uppercase()
        .replace(" ", "_")
        .replace("-", "_")

    private fun updateBankCard() {
        val selected = methodSpinner.selectedItem?.toString()
        if (selected.isNullOrBlank()) {
            bankDetailsCard.visibility = View.GONE
            return
        }

        val normalizedKey = when (val key = normalizeMethodKey(selected)) {
            "ALFALAH_BANK", "BANKALFALAH" -> "BANK_ALFALAH"
            else -> key
        }

        val details = bankDetailsMap[normalizedKey]
        if (details == null) {
            bankDetailsCard.visibility = View.GONE
            return
        }

        bankNameValue.text = details.bankName
        bankTitleValue.text = details.accountTitle
        bankAccountValue.text = details.accountNumber
        bankQrImage.setImageResource(details.qrDrawableRes)
        bankDetailsCard.visibility = View.VISIBLE
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
        val senderName = senderNameInput.text.toString().trim()

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
                Toast.makeText(this@PaymentActivity, "Unable to check payment limit. Please try again.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val minimumAmount = limitsSnapshot.child("minimumAmount").getValue(Long::class.java) ?: 3000L
            val maximumAmount = limitsSnapshot.child("maximumAmount").getValue(Long::class.java) ?: 100000L

            if (amount < minimumAmount.toDouble() || amount > maximumAmount.toDouble()) {
                Toast.makeText(this@PaymentActivity, "Amount must be between $minimumAmount and $maximumAmount", Toast.LENGTH_LONG).show()
                return@launch
            }

            val now = System.currentTimeMillis()
            val data: Map<String, Any> = mapOf(
                "dealerId" to dealerId,
                "panel" to panelSpinner.selectedItem.toString(),
                "paymentSource" to methodSpinner.selectedItem.toString(),
                "amount" to amount,
                "bankTransactionId" to tid,
                "senderName" to senderName,
                "ocrAmount" to (ocrResult?.amount ?: amount),
                "ocrTransactionId" to (ocrResult?.transactionId ?: tid),
                "status" to "PENDING",
                "submittedAt" to now,
                "availableAt" to (now + 30L * 60L * 1000L)
            )

            try {
                repo.save(data)
                Toast.makeText(this@PaymentActivity, "Payment submitted successfully", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@PaymentActivity, "Failed to submit: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}