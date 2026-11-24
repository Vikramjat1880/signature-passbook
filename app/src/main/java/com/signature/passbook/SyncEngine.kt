package com.signature.passbook

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

object SyncEngine {
    private const val SUPABASE_URL = "https://vyjewxrdcchhurtvbjms.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_XY6vq6-XPYV1qPXbKvqh_A_nmONXCvY"
    private val client = OkHttpClient()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    data class ParsedTxn(
        val utr: String,
        val amount: Double,
        val direction: String,
        val bankName: String,
        val accountLast4: String,
        val rawText: String,
        val sourceType: String,
        val timestampIso: String
    )

    fun getBatteryPct(context: Context): Int {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, ifilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else 100
    }

    fun pairDevice(context: Context, code: String): Pair<Boolean, String> {
        return try {
            val req = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/branch_devices?pairing_code=eq.$code&select=*")
                .addHeader("apikey", SUPABASE_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                .build()

            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: "[]"
                val arr = JSONArray(body)
                if (arr.length() == 0) return Pair(false, "Invalid 4-Digit Code!")

                val dev = arr.getJSONObject(0)
                val branchCode = dev.getString("branch_code")
                val devName = dev.getString("device_name")

                val patchJson = JSONObject().apply {
                    put("is_paired", true)
                    put("battery_pct", getBatteryPct(context))
                    put("last_seen", nowIso())
                }
                val patchReq = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/branch_devices?pairing_code=eq.$code")
                    .patch(patchJson.toString().toRequestBody(JSON_TYPE))
                    .addHeader("apikey", SUPABASE_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                    .build()
                client.newCall(patchReq).execute().close()

                val prefs = context.getSharedPreferences("sig_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("pairing_code", code)
                    .putString("branch_code", branchCode)
                    .putString("device_name", devName)
                    .apply()

                Pair(true, "$devName ($branchCode)")
            }
        } catch (e: Exception) {
            Pair(false, "Network Error: ${e.message}")
        }
    }

    fun parseMessage(raw: String, source: String, timeMillis: Long = System.currentTimeMillis()): ParsedTxn? {
        val utrMatcher = Pattern.compile("\\b(\\d{12})\\b").matcher(raw)
        if (!utrMatcher.find()) return null
        val utr = utrMatcher.group(1) ?: return null

        val amtMatcher = Pattern.compile("(?:Rs\\.?|INR|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE).matcher(raw)
        val amount = if (amtMatcher.find()) {
            amtMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
        } else 0.0
        if (amount <= 0.0) return null

        val isDebit = Pattern.compile("(debited|paid to|sent to|withdrawn|\\bdr\\b)", Pattern.CASE_INSENSITIVE).matcher(raw).find()
        val direction = if (isDebit) "DEBIT" else "CREDIT"

        val accMatcher = Pattern.compile("(?:A/c|Acct|Account|ending)\\s*(?:no\\.?\\s*)?[X*]*(\\d{4})", Pattern.CASE_INSENSITIVE).matcher(raw)
        val accLast4 = if (accMatcher.find()) accMatcher.group(1) ?: "XXXX" else "XXXX"

        val bank = when {
            raw.contains("SBI", true) || raw.contains("State Bank", true) -> "SBI"
            raw.contains("HDFC", true) -> "HDFC Bank"
            raw.contains("ICICI", true) -> "ICICI Bank"
            raw.contains("Kotak", true) -> "Kotak Bank"
            raw.contains("Baroda", true) || raw.contains("BOB", true) -> "Bank of Baroda"
            raw.contains("Maharashtra", true) -> "Bank of Maharashtra"
            raw.contains("UCO", true) -> "UCO Bank"
            raw.contains("RBL", true) -> "RBL Bank"
            raw.contains("PhonePe", true) -> "PhonePe"
            raw.contains("Google Pay", true) || raw.contains("GPay", true) -> "Google Pay"
            raw.contains("Paytm", true) -> "Paytm"
            else -> "Bank / UPI"
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return ParsedTxn(utr, amount, direction, bank, accLast4, raw, source, sdf.format(Date(timeMillis)))
    }

    fun pushTransaction(context: Context, txn: ParsedTxn): Boolean {
        val prefs = context.getSharedPreferences("sig_prefs", Context.MODE_PRIVATE)
        val branchCode = prefs.getString("branch_code", null) ?: return false
        val pairingCode = prefs.getString("pairing_code", null)

        return try {
            val payload = JSONObject().apply {
                put("branch_code", branchCode)
                put("utr_number", txn.utr)
                put("amount", txn.amount)
                put("direction", txn.direction)
                put("bank_name", txn.bankName)
                put("account_last4", txn.accountLast4)
                put("source_type", txn.sourceType)
                put("match_status", "PENDING")
                put("ledger_type", "MAIN")
                put("raw_message", txn.rawText)
                put("txn_timestamp", txn.timestampIso)
            }

            val req = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/utr_logs?on_conflict=branch_code,utr_number")
                .post(payload.toString().toRequestBody(JSON_TYPE))
                .addHeader("apikey", SUPABASE_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                .addHeader("Prefer", "resolution=ignore-duplicates")
                .build()

            val ok = client.newCall(req).execute().use { it.isSuccessful }

            if (pairingCode != null) {
                val hb = JSONObject().apply {
                    put("battery_pct", getBatteryPct(context))
                    put("last_seen", nowIso())
                }
                val hbReq = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/branch_devices?pairing_code=eq.$pairingCode")
                    .patch(hb.toString().toRequestBody(JSON_TYPE))
                    .addHeader("apikey", SUPABASE_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                    .build()
                client.newCall(hbReq).execute().close()
            }
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun nowIso(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date())
    }
}
