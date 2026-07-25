package com.myra.assistant.phone

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.myra.assistant.data.repository.SettingsRepository
import com.myra.assistant.service.MyraAccessibilityService
import com.myra.assistant.util.Logger
import com.myra.assistant.util.PermissionHelper
import java.util.Locale

/**
 * Executes device actions requested through MYRA. Everything here is triggered
 * either by explicit UI buttons or by [handleAssistantText], which scans the
 * spoken reply for clear action intents.
 */
class PhoneController(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun launch(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "No activity for intent: ${intent.action}")
        }
    }

    // ----- Apps -----
    fun openApp(query: String) {
        val pm = context.packageManager
        val known = KNOWN_APPS[query.lowercase(Locale.ROOT).trim()]
        val pkg = known ?: findPackageByLabel(query)
        if (pkg != null) {
            pm.getLaunchIntentForPackage(pkg)?.let { launch(it); return }
        }
        // Fall back to a Play Store search so the intent still resolves.
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query))))
    }

    private fun findPackageByLabel(label: String): String? {
        val pm = context.packageManager
        val target = label.lowercase(Locale.ROOT).trim()
        return pm.getInstalledApplications(0).firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(target)
        }?.packageName
    }

    fun closeCurrentApp() {
        MyraAccessibilityService.instance?.goHome()
    }

    fun openPlayStore() = launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=apps")))
    fun openChrome(url: String) {
        val target = if (url.startsWith("http")) url else "https://www.google.com/search?q=" + Uri.encode(url)
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
    }
    fun openSettings() = launch(Intent(Settings.ACTION_SETTINGS))
    fun openCalculator() = openApp("calculator")
    fun openInstagram() = openApp("instagram")
    fun openFacebook() = openApp("facebook")

    // ----- Communication -----
    fun callContact(name: String) {
        val number = lookupNumber(name) ?: run { Logger.w(TAG, "Contact not found: $name"); return }
        if (PermissionHelper.hasPermission(context, android.Manifest.permission.CALL_PHONE)) {
            launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        } else {
            launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        }
    }

    fun whatsapp(name: String, message: String) {
        val number = lookupNumber(name)?.filter { it.isDigit() || it == '+' }
        val uri = if (number != null) {
            Uri.parse("https://wa.me/" + number.removePrefix("+") + "?text=" + Uri.encode(message))
        } else {
            Uri.parse("https://wa.me/?text=" + Uri.encode(message))
        }
        launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
    }

    fun sendSms(name: String, message: String) {
        val number = lookupNumber(name) ?: name
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message))
    }

    fun email(to: String, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        launch(intent)
    }

    fun lookupNumber(name: String): String? {
        if (!PermissionHelper.hasPermission(context, android.Manifest.permission.READ_CONTACTS)) return null
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    // ----- Hardware toggles -----
    fun setTorch(on: Boolean) {
        try {
            val id = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(id, on)
        } catch (e: Exception) {
            Logger.e(TAG, "Torch failed", e)
        }
    }

    fun openBluetoothSettings() = launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun openWifiSettings() = launch(Intent(Settings.ACTION_WIFI_SETTINGS))

    fun setVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = (percent.coerceIn(0, 100) * max / 100)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, AudioManager.FLAG_SHOW_UI)
    }

    fun setBrightness(percent: Int) {
        if (!PermissionHelper.canWriteSettings(context)) {
            launch(PermissionHelper.writeSettingsIntent(context))
            return
        }
        val value = (percent.coerceIn(0, 100) * 255 / 100)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    // ----- Clock -----
    fun setAlarm(hour: Int, minute: Int, label: String) {
        launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        )
    }

    fun setTimer(seconds: Int, label: String) {
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        )
    }

    // ----- Media / navigation -----
    fun openCamera() = launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    fun openGallery() = launch(Intent(Intent.ACTION_VIEW).setType("image/*"))
    fun openMaps(query: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))))
    fun navigate(destination: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination))))
    fun openYouTube(query: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
    fun openSpotify() = openApp("spotify")
    fun playMusic() {
        launch(Intent("android.intent.action.MUSIC_PLAYER").addCategory(Intent.CATEGORY_APP_MUSIC))
    }

    // ----- Calendar -----
    fun addCalendarEvent(title: String, startMillis: Long) {
        launch(
            Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        )
    }
    fun openCalendar() {
        val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
        ContentUris.appendId(builder, System.currentTimeMillis())
        launch(Intent(Intent.ACTION_VIEW).setData(builder.build()))
    }

    // ----- Utilities -----
    fun shareText(text: String) {
        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    }
    fun takeScreenshot() = MyraAccessibilityService.instance?.takeScreenshotAction()

    /**
     * Very small natural-language command layer. Scans the user's request for
     * an obvious device action; the spoken reply is what the user hears.
     */
    fun handleAssistantText(userText: String, assistantText: String) {
        val t = userText.lowercase(Locale.ROOT)
        when {
            t.contains("torch") || t.contains("flashlight") -> setTorch(!t.contains("off"))
            t.startsWith("open ") -> openApp(t.removePrefix("open ").trim())
            t.contains("call ") -> callContact(t.substringAfter("call ").trim())
            t.contains("screenshot") -> takeScreenshot()
            t.contains("camera") -> openCamera()
            t.contains("whatsapp") -> whatsapp(t.substringAfter("to ").trim(), assistantText)
        }
    }

    /** Extract a durable fact worth remembering (learning mode). */
    fun maybeLearn(userText: String): String? {
        val t = userText.lowercase(Locale.ROOT)
        return when {
            t.contains("my name is") -> userText.substringAfter("my name is").trim().let { "User's name is $it" }
            t.contains("i like") -> "User likes" + userText.substringAfter("i like")
            t.contains("remember that") -> userText.substringAfter("remember that").trim()
            else -> null
        }
    }

    companion object {
        private const val TAG = "PhoneController"
        private val KNOWN_APPS = mapOf(
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "play store" to "com.android.vending",
            "calculator" to "com.google.android.calculator",
            "camera" to "com.android.camera2",
            "settings" to "com.android.settings"
        )
    }
}
