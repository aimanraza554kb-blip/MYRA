package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.myra.assistant.data.ServiceLocator
import com.myra.assistant.util.Logger

/**
 * Monitors phone call state so MYRA can pause the microphone / playback while a
 * real call is in progress and resume afterwards.
 */
class CallMonitorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val session = ServiceLocator.voiceSessionManager
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (!session.micMuted.value) session.toggleMic()
                Logger.d(TAG, "Call active - mic paused")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (session.micMuted.value) session.toggleMic()
                Logger.d(TAG, "Call ended - mic resumed")
            }
        }
    }

    companion object { private const val TAG = "CallMonitor" }
}
