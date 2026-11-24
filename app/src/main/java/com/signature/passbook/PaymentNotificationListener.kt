package com.signature.passbook

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PaymentNotificationListener : NotificationListenerService() {
    private val allowedApps = setOf(
        "com.phonepe.app",
        "com.phonepe.app.business",
        "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm",
        "com.paytm.business",
        "com.bharatpe.app"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!allowedApps.contains(sbn.packageName)) return
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val combined = "$title $text"

        val txn = SyncEngine.parseMessage(combined, "UPI_NOTIF", sbn.postTime) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            SyncEngine.pushTransaction(applicationContext, txn)
        }
    }
}
