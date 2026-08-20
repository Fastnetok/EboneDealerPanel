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

    // NEW: which ISPs are actually enabled for this dealer's zone —
    // read from zoneServiceConfig once we know the dealer's zone.
    // Default all-true until the zone config loads, so nothing looks
    // broken during the brief load moment.
    private var wateenEnabled = true
    private var eboneEnabled = true
    private var zongEnabled = true

    // NEW: last known balance per panel — used to detect when a payment
    // has just been auto-verified and credited, so we can show the
    // dealer a clear "Payment successful" popup instead of them having
    // to notice the number silently changed. Null until the first real
    // reading arrives, so the very first dashboard load never triggers
    // a false "payment successful" popup.
    private var lastWateenBalance: Double? = null
    private var lastEboneBalance: Double? = null
    private var lastZongBalance: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        repo = DealerRepo(this)

        // NEW: app khulte hi GitHub Releases check karta hai naye version ke liye
        VersionChecker.checkForUpdate(this)

        // More menu: opens the Dealer Panel's additional settings screen.
        findViewById<TextView>(R.id.menuButton).setOnClickListener {
            startActivity(Intent(this, MoreActivity::class.java))
        }

        findViewById<android.view.View>(R.id.pay).setOnClickListener {
            openPayment()
        }

        findViewById<TextView>(R.id.w).setOnClickListener {
            if (wateenEnabled) openPayment("Wateen")
            else Toast.makeText(this, "Wateen is not available yet", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.e).setOnClickListener {
            if (eboneEnabled) openPayment("Ebone")
            else Toast.makeText(this, "Ebone is not available yet", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.z).setOnClickListener {
            if (zongEnabled) openPayment("Zong")
            else Toast.makeText(this, "Zong is not available yet", Toast.LENGTH_SHORT).show()
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

        val wateenNow = (data["wateenBalance"] as? Number)?.toDouble() ?: 0.0
        val eboneNow = (data["eboneBalance"] as? Number)?.toDouble() ?: 0.0
        val zongNow = (data["zongBalance"] as? Number)?.toDouble() ?: 0.0

        // NEW: a payment just landed (SMS verified + auto-transferred)
        // if this panel's balance is HIGHER than the last known value.
        // Skipped on the very first load (lastX == null) since that's
        // just the starting balance, not a new payment.
        checkForNewPayment("Wateen", lastWateenBalance, wateenNow)
        checkForNewPayment("Ebone", lastEboneBalance, eboneNow)
        checkForNewPayment("Zong", lastZongBalance, zongNow)
        lastWateenBalance = wateenNow
        lastEboneBalance = eboneNow
        lastZongBalance = zongNow

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

        // NEW: zone-aware service enable/disable — a dealer's zone is
        // set by Admin (defaults to "Okara" if not yet tagged, so
        // nothing changes for existing dealers until Admin assigns
        // zones). Reads zoneServiceConfig for that zone and greys out
        // whichever ISPs aren't running there yet (e.g. Renala only
        // has Zong active right now).
        val zone = data["zone"]?.toString()?.ifBlank { null } ?: "Okara"
        lifecycleScope.launch {
            val config = repo.zoneServiceConfig(zone)
            wateenEnabled = config["wateenEnabled"] ?: true
            eboneEnabled = config["eboneEnabled"] ?: true
            zongEnabled = config["zongEnabled"] ?: true
            applyServiceAvailability()
        }
    }

    /** NEW: visually greys out (alpha 0.4, non-clickable via the flags
     * checked in each button's own listener in onCreate) any ISP button
     * that's disabled for this dealer's zone. */
    private fun applyServiceAvailability() {
        findViewById<TextView>(R.id.w).alpha = if (wateenEnabled) 1.0f else 0.4f
        findViewById<TextView>(R.id.e).alpha = if (eboneEnabled) 1.0f else 0.4f
        findViewById<TextView>(R.id.z).alpha = if (zongEnabled) 1.0f else 0.4f
    }

    /** NEW: if [nowValue] is higher than [lastValue] (and this isn't the
     * very first load), a payment was just auto-verified and credited —
     * show the dealer a clear success popup so they don't have to
     * notice the number silently changed on its own. */
    private fun checkForNewPayment(panelLabel: String, lastValue: Double?, nowValue: Double) {
        if (lastValue == null) return // first load — just the starting balance, not a new payment
        val increase = nowValue - lastValue
        if (increase > 0.5) { // small tolerance for floating point noise
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Payment Successful ✅")
                .setMessage("Rs. ${"%.0f".format(increase)} has been added to your $panelLabel balance.\n\nYour payment is successfully done.")
                .setPositiveButton("OK", null)
                .show()
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