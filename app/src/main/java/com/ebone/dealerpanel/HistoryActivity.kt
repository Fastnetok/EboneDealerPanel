package com.ebone.dealerpanel

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ebone.dealerpanel.data.DealerRepo
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {
    private lateinit var repo: DealerRepo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.history)
        repo = DealerRepo(this)
        val list = findViewById<LinearLayout>(R.id.list)

        lifecycleScope.launch {
            val id = repo.id()
            if (id.isBlank()) return@launch
            repo.history(id) { items ->
                runOnUiThread {
                    list.removeAllViews()
                    if (items.isEmpty()) {
                        list.addView(TextView(this@HistoryActivity).apply {
                            text = "No payments yet"
                            textSize = 18f
                        })
                        return@runOnUiThread
                    }
                    items.forEach { item ->
                        val panel = item["panel"]?.toString().orEmpty()
                        val amount = item["amount"]?.toString().orEmpty()
                        val tid = item["bankTransactionId"]?.toString().orEmpty()
                        val status = item["status"]?.toString().orEmpty()
                        list.addView(TextView(this@HistoryActivity).apply {
                            text = "$panel  |  Rs. $amount\nTID: $tid\n$status"
                            textSize = 16f
                            setPadding(0, 20, 0, 20)
                        })
                    }
                }
            }
        }
    }
}
