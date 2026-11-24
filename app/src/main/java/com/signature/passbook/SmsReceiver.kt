package com.signature.passbook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val fullBody = messages.joinToString(" ") { it.messageBody ?: "" }
            val txn = SyncEngine.parseMessage(fullBody, "PHONE_SMS") ?: return
            CoroutineScope(Dispatchers.IO).launch {
                SyncEngine.pushTransaction(context, txn)
            }
        }
    }
}
