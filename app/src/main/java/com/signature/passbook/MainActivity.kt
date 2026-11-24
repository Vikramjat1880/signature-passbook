package com.signature.passbook

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var statusText: TextView
    private lateinit var logListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllPermissions()

        val prefs = getSharedPreferences("sig_prefs", Context.MODE_PRIVATE)
        val savedBranch = prefs.getString("device_name", null)
        val savedCode = prefs.getString("pairing_code", "") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6F9"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#008D4C"))
            setPadding(40, 48, 40, 40)
        }
        val title = TextView(this).apply {
            text = "SIGNATURE PASSBOOK PRO"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        }
        statusText = TextView(this).apply {
            text = if (savedBranch != null) "● Connected: $savedBranch (Code: $savedCode)" else "○ Not Paired — Enter 4-Digit Code"
            setTextColor(Color.parseColor("#D1FAE5"))
            textSize = 13f
        }
        header.addView(title)
        header.addView(statusText)
        root.addView(header)

        val pairCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 28, 32, 28)
            gravity = Gravity.CENTER_VERTICAL
        }
        val codeInput = EditText(this).apply {
            hint = "4-Digit Code (e.g. 4829)"
            setText(savedCode)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val connectBtn = Button(this).apply {
            text = "CONNECT"
            setBackgroundColor(Color.parseColor("#00A65A"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val c = codeInput.text.toString().trim()
                if (c.length != 4) {
                    Toast.makeText(this@MainActivity, "4-Digit Code daalein", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                statusText.text = "Connecting..."
                scope.launch {
                    val (ok, msg) = withContext(Dispatchers.IO) { SyncEngine.pairDevice(this@MainActivity, c) }
                    statusText.text = if (ok) "● Connected: $msg (Code: $c)" else "✕ $msg"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        pairCard.addView(codeInput)
        pairCard.addView(connectBtn)
        root.addView(pairCard)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 20, 24, 12)
        }
        val importBtn = Button(this).apply {
            text = "📥 IMPORT SMS (LAST 7 DAYS)"
            setBackgroundColor(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 12 }
            setOnClickListener { importRecentSms() }
        }
        val notifBtn = Button(this).apply {
            text = "🔔 UPI NOTIF ACCESS"
            setBackgroundColor(Color.parseColor("#D97706"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        btnRow.addView(importBtn)
        btnRow.addView(notifBtn)
        root.addView(btnRow)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        logListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 24)
        }
        scroll.addView(logListContainer)
        root.addView(scroll)

        setContentView(root)
    }

    private fun importRecentSms() {
        Toast.makeText(this, "Scanning Bank SMS...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val syncedList = withContext(Dispatchers.IO) {
                val results = mutableListOf<SyncEngine.ParsedTxn>()
                val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
                val cursor = contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("body", "date"),
                    "date > ?",
                    arrayOf(sevenDaysAgo.toString()),
                    "date DESC"
                )
                cursor?.use {
                    val bodyIdx = it.getColumnIndex("body")
                    val dateIdx = it.getColumnIndex("date")
                    while (it.moveToNext()) {
                        val body = it.getString(bodyIdx) ?: continue
                        val date = it.getLong(dateIdx)
                        val txn = SyncEngine.parseMessage(body, "SMS_IMPORT", date) ?: continue
                        if (SyncEngine.pushTransaction(this@MainActivity, txn)) {
                            results.add(txn)
                        }
                    }
                }
                results
            }

            logListContainer.removeAllViews()
            syncedList.forEach { t ->
                val item = TextView(this@MainActivity).apply {
                    val sign = if (t.direction == "CREDIT") "+" else "-"
                    text = "$sign₹${t.amount}  |  UTR: ${t.utr}\n${t.bankName} · A/c ${t.accountLast4}"
                    setBackgroundColor(Color.WHITE)
                    setPadding(28, 24, 28, 24)
                    setTextColor(if (t.direction == "CREDIT") Color.parseColor("#047857") else Color.parseColor("#B91C1C"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 14 }
                }
                logListContainer.addView(item)
            }
            Toast.makeText(this@MainActivity, "${syncedList.size} Entries Synced!", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestAllPermissions() {
        val perms = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }
}
