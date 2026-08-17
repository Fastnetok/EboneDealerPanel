package com.ebone.dealerpanel

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ebone.dealerpanel.data.DealerRepo
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var repo: DealerRepo
    private var dealerListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        repo = DealerRepo(this)

        // More menu: opens the Dealer Panel's additional settings screen.
        findViewById<TextView>(R.id.menuButton).setOnClickListener {
            startActivity(Intent(this, MoreActivity::class.java))
        }

        findViewById<android.view.View>(R.id.pay).setOnClickListener {
            openPayment()
        }

        findViewById<TextView>(R.id.w).setOnClickListener {
            openPayment("Wateen")
        }

        findViewById<TextView>(R.id.e).setOnClickListener {
            openPayment("Ebone")
        }

        findViewById<TextView>(R.id.z).setOnClickListener {
            openPayment("Zong")
        }

        findViewById<android.view.View>(R.id.hist).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun openPayment(panel: String? = null) {
        val intent = Intent(this, PaymentActivity::class.java)
        if (!panel.isNullOrBlank()) {
            intent.putExtra(PaymentActivity.EXTRA_PANEL, panel)
        }
        startActivity(intent)
    }

    override fun onStart() {
        super.onStart()
        syncDealerNow()
    }

    override fun onStop() {
        dealerListener?.remove()
        dealerListener = null
        super.onStop()
    }

    private fun syncDealerNow() {
        lifecycleScope.launch {
            val id = repo.id()
            if (id.isBlank()) {
                openRegistration()
                return@launch
            }

            try {
                val doc = repo.dealer(id)
                if (!doc.exists()) {
                    repo.clearSession()
                    openRegistration()
                    return@launch
                }

                dealerListener?.remove()
                dealerListener = repo.observeDealer(id) { data ->
                    runOnUiThread {
                        if (data == null) return@runOnUiThread
                        if (data["status"]?.toString() != "ACTIVE") {
                            Toast.makeText(this@MainActivity, "Dealer account disabled", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        updateDashboard(data)
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Unable to sync dealer account. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateDashboard(data: Map<String, Any>) {
        findViewById<TextView>(R.id.name).text = data["name"]?.toString() ?: "Dealer"
        findViewById<TextView>(R.id.w).text = "WATEEN\nRs. ${money(data["wateenBalance"])}"
        findViewById<TextView>(R.id.e).text = "EBONE\nRs. ${money(data["eboneBalance"])}"
        findViewById<TextView>(R.id.z).text = "ZONG\nRs. ${money(data["zongBalance"])}"

        val raw = data["paymentAccounts"] as? Map<*, *>
        val active = raw?.filterValues { it != false }?.keys?.map { methodLabel(it.toString()) } ?: emptyList()
        findViewById<TextView>(R.id.methods).text = if (active.isEmpty()) {
            "No receiving account is enabled."
        } else {
            active.joinToString("  •  ")
        }
    }

    private fun methodLabel(value: String): String {
        val original = value.trim()
        val key = original
            .uppercase()
            .replace("-", "_")
            .replace(" ", "_")
            .replace(Regex("_+"), "_")

        return when {
            key.contains("FAISAL") || key.contains("FAYSAL") ->
                "Faysal Bank"

            key.contains("TILL") ->
                "Jazz Till ID"

            key == "EASYPAISA" ->
                "EasyPaisa"

            key == "JAZZCASH" ->
                "JazzCash"

            key == "SADAPAY" ->
                "SadaPay"

            key == "RAAST_ID" || key == "RAASTID" ->
                "Raast ID"

            key.contains("ALFALAH") ->
                "Alfalah Bank"

            key == "OTHER_BANK" || key == "OTHERBANK" ->
                "Other Bank"

            else ->
                original.replace('_', ' ')
        }
    }

    private fun openRegistration() {
        startActivity(Intent(this@MainActivity, RegisterActivity::class.java))
        finish()
    }

    private fun money(value: Any?): String = when (value) {
        is Number -> "%.0f".format(value.toDouble())
        else -> value?.toString() ?: "0"
    }
}