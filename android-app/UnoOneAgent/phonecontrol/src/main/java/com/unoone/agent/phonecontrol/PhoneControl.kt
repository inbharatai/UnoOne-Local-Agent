package com.unoone.agent.phonecontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.InputSanitizer
import com.unoone.agent.core.util.Logger

class PhoneControl(private val context: Context) {

    fun openChrome(): Result<Unit> {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage("com.android.chrome")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to open Chrome", e)
            Result.Error("Chrome is not installed", e)
        }
    }

    fun openUrl(url: String): Result<Unit> {
        val sanitizedUrl = sanitizeUrl(url)
            ?: return Result.Error("Invalid or unsafe URL: $url")

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sanitizedUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to open URL: $sanitizedUrl", e)
            Result.Error("Cannot open URL", e)
        }
    }

    fun openApp(packageName: String): Result<Unit> {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return Result.Error("App not installed: $packageName")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to open app: $packageName", e)
            Result.Error("Cannot open app", e)
        }
    }

    fun openCalendarInsert(
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        location: String? = null
    ): Result<Unit> {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, InputSanitizer.sanitizeForAccessibility(title))
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
                description?.let { putExtra(CalendarContract.Events.DESCRIPTION, InputSanitizer.sanitizeForAccessibility(it)) }
                location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, InputSanitizer.sanitizeForAccessibility(it)) }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to open calendar insert", e)
            Result.Error("Cannot open calendar", e)
        }
    }

    fun openCamera(): Result<Unit> {
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to open camera", e)
            Result.Error("Cannot open camera", e)
        }
    }

    fun openSettings(): Result<Unit> {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to open settings", e)
            Result.Error("Cannot open settings", e)
        }
    }

    fun draftEmail(to: String, subject: String, body: String): Result<Unit> {
        val safeSubject = InputSanitizer.sanitizeForAccessibility(subject)
        val safeBody = InputSanitizer.sanitize(body)
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, safeSubject)
                putExtra(Intent.EXTRA_TEXT, safeBody)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to draft email", e)
            Result.Error("Cannot draft email", e)
        }
    }

    fun sendWhatsAppMessage(number: String, message: String): Result<Unit> {
        val safeNumber = validatePhoneNumber(number)
            ?: return Result.Error("Invalid phone number: $number")
        val safeMessage = InputSanitizer.sanitize(message)

        return try {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$safeNumber&text=${Uri.encode(safeMessage)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to send WhatsApp message", e)
            Result.Error("Cannot send WhatsApp message", e)
        }
    }

    fun openDialer(number: String? = null): Result<Unit> {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                number?.let { data = Uri.parse("tel:${validatePhoneNumber(it) ?: it}") }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to open dialer", e)
            Result.Error("Cannot open dialer", e)
        }
    }

    fun shareText(text: String): Result<Unit> {
        val safeText = InputSanitizer.sanitize(text)
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, safeText)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val chooser = Intent.createChooser(intent, "Share via").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(chooser)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to share text", e)
            Result.Error("Cannot share text", e)
        }
    }

    /**
     * Validates and sanitizes a URL. Rejects javascript:, file:, and content: schemes.
     * Auto-prepends https:// if no scheme is present. Limits length to 2048 chars.
     */
    private fun sanitizeUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.length > 2048) return null

        // Reject dangerous schemes
        val lowered = trimmed.lowercase()
        if (lowered.startsWith("javascript:")) return null
        if (lowered.startsWith("data:")) return null
        if (lowered.startsWith("file:")) return null
        if (lowered.startsWith("content:")) return null

        // Reject URLs with credentials (anti-phishing)
        if (trimmed.contains("@") && !trimmed.startsWith("http")) return null

        // Auto-prepend https:// if no scheme
        return if (lowered.startsWith("http://") || lowered.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    /**
     * Validates a phone number: strips non-digits except leading +,
     * then checks it matches the pattern for international phone numbers.
     */
    private fun validatePhoneNumber(number: String): String? {
        val stripped = number.replace(Regex("[^\\d+]"), "")
        return if (stripped.matches(Regex("^\\+?\\d{8,15}$"))) {
            stripped
        } else {
            null
        }
    }
}