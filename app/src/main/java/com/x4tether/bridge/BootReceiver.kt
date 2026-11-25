package com.x4tether.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && TetherBleService.isDesiredEnabled(context)) {
            val svc = Intent(context, TetherBleService::class.java)
            context.startForegroundService(svc)
        }
    }
}
