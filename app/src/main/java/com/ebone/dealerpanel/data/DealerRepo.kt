package com.ebone.dealerpanel.data

import android.content.Context
import android.provider.Settings
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class DealerRepo(private val context: Context) {
    private val db = FirebaseFirestore.getInstance()
    private val dealers = db.collection("dealers")
    // NEW: separate collection from the customer app's "transactions" —
    // both apps share the same Firebase project (ebone-admin-panel), so
    // using the same collection name would let dealer and customer
    // payment records collide/confuse each other's SMS-matching logic.
    private val transactions = db.collection("dealerTransactions")

    fun device(): String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: "unknown"

    suspend fun id(): String = context.getSharedPreferences("dealer", 0)
        .getString("id", "")
        .orEmpty()

    fun clearSession() {
        context.getSharedPreferences("dealer", 0)
            .edit()
            .clear()
            .apply()
    }

    suspend fun dealer(id: String) = dealers.document(id).get().await()

    /**
     * NEW: reads which ISPs are actually enabled for a given zone
     * (Okara/Renala/etc — Admin sets this per-franchise, since e.g.
     * Renala only runs Zong right now while Okara runs all three).
     * Missing doc / missing fields default to TRUE (enabled) — so
     * existing zones/dealers keep working exactly as before until an
     * Admin explicitly disables something for their zone.
     */
    suspend fun zoneServiceConfig(zone: String): Map<String, Boolean> {
        val defaults = mapOf(
            "eboneEnabled" to true,
            "wateenEnabled" to true,
            "zongEnabled" to true
        )
        return try {
            val doc = db.collection("zoneServiceConfig").document(zone).get().await()
            if (!doc.exists()) {
                defaults
            } else {
                mapOf(
                    "eboneEnabled" to (doc.getBoolean("eboneEnabled") ?: true),
                    "wateenEnabled" to (doc.getBoolean("wateenEnabled") ?: true),
                    "zongEnabled" to (doc.getBoolean("zongEnabled") ?: true)
                )
            }
        } catch (_: Exception) {
            defaults
        }
    }

    fun observeDealer(id: String, callback: (Map<String, Any>?) -> Unit): com.google.firebase.firestore.ListenerRegistration {
        return dealers.document(id).addSnapshotListener { snapshot, _ ->
            callback(snapshot?.data?.toNonNullMap())
        }
    }

    suspend fun register(name: String, mobile: String, code: String): Result<String> = runCatching {
        val snapshot = dealers.whereEqualTo("dealerCode", code.trim()).get().await()
        val doc = snapshot.documents.firstOrNull() ?: error("Dealer code not found")
        if (doc.getString("status") != "ACTIVE") error("Dealer disabled")

        val oldDevice = doc.getString("deviceId").orEmpty()
        if (oldDevice.isNotBlank() && oldDevice != device()) {
            error("Code already used on another phone")
        }
        if (doc.getString("name").orEmpty() != name.trim()) error("Dealer name does not match")
        if (doc.getString("mobile").orEmpty() != mobile.trim()) error("Mobile does not match")

        doc.reference.update("deviceId", device()).await()
        context.getSharedPreferences("dealer", 0).edit().putString("id", doc.id).apply()
        doc.id
    }

    suspend fun methods(id: String): List<String> {
        val raw = dealers.document(id).get().await().get("paymentAccounts")
        val accounts = raw as? Map<*, *> ?: return emptyList()
        return accounts.filterValues { it != false }.keys.map { it.toString() }
    }

    suspend fun save(data: Map<String, Any>) {
        transactions.document().set(data).await()
    }

    suspend fun used(tid: String): Boolean = transactions
        .whereEqualTo("bankTransactionId", tid)
        .whereIn("status", listOf("PENDING", "VERIFIED", "APPROVED"))
        .get()
        .await()
        .documents
        .isNotEmpty()

    fun addDealer(data: Map<String, Any>, callback: (Boolean, String) -> Unit) {
        val ref = dealers.document()
        val payload: MutableMap<String, Any> = data.toMutableMap()
        payload["dealerCode"] = generateCode()
        payload["dealerId"] = ref.id
        payload["deviceId"] = ""
        if (!payload.containsKey("status")) payload["status"] = "ACTIVE"
        payload["createdAt"] = FieldValue.serverTimestamp()

        ref.set(payload)
            .addOnSuccessListener {
                callback(true, "Dealer added successfully. Code: ${payload["dealerCode"]}")
            }
            .addOnFailureListener { error ->
                callback(false, error.message ?: "Failed to add dealer")
            }
    }

    fun getDealers(callback: (List<Map<String, Any>>) -> Unit) {
        dealers.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                callback(emptyList())
                return@addSnapshotListener
            }
            callback(snapshot.documents.mapNotNull { it.data?.toNonNullMap() })
        }
    }

    fun history(id: String, callback: (List<Map<String, Any>>) -> Unit) {
        transactions.whereEqualTo("dealerId", id).addSnapshotListener { snapshot, _ ->
            callback(snapshot?.documents?.mapNotNull { it.data?.toNonNullMap() } ?: emptyList())
        }
    }

    private fun generateCode(): String = (100000..999999).random().toString()

    private fun Map<String, Any?>.toNonNullMap(): Map<String, Any> = buildMap {
        for ((key, value) in this@toNonNullMap) {
            if (value != null) put(key, value)
        }
    }
}