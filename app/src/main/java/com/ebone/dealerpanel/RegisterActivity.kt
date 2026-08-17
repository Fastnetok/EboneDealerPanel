package com.ebone.dealerpanel

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ebone.dealerpanel.data.DealerRepo
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {
    private lateinit var repo: DealerRepo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.register)
        repo = DealerRepo(this)

        val name = findViewById<EditText>(R.id.n)
        val mobile = findViewById<EditText>(R.id.m)
        val code = findViewById<EditText>(R.id.c)
        val status = findViewById<TextView>(R.id.s)

        findViewById<Button>(R.id.go).setOnClickListener {
            val n = name.text.toString().trim()
            val m = mobile.text.toString().trim()
            val c = code.text.toString().trim()
            if (n.isEmpty() || m.isEmpty() || c.length != 6) {
                status.text = "Name, mobile aur 6-digit dealer code zaroori hai."
                return@setOnClickListener
            }

            lifecycleScope.launch {
                status.text = "Verifying..."
                repo.register(n, m, c)
                    .onSuccess {
                        startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                        finish()
                    }
                    .onFailure { error ->
                        status.text = error.message ?: "Registration failed"
                    }
            }
        }
    }
}
