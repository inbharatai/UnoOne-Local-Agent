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

/**
 * A launch request accepted by Android is not proof that the requested application reached the
 * foreground. The app module uses this exact package set with AccessibilityService observations
 * before it announces success.
 */
data class LaunchAttempt(
    val requestedPackage: String,
    val expectedForegroundPackages: Set<String> = setOf(requestedPackage)
)

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

    fun openApp(packageName: String): Result<LaunchAttempt> {
        return try {
            val candidatePackages = if (packageName == "com.whatsapp") {
                listOf("com.whatsapp", "com.whatsapp.w4b")
            } else {
                listOf(packageName)
            }
            val intent = candidatePackages.firstNotNullOfOrNull {
                context.packageManager.getLaunchIntentForPackage(it)
            }
                ?: return Result.Error("App not installed: $packageName")
            val resolvedPackage = intent.component?.packageName
                ?: return Result.Error("App has no launchable activity: $packageName")
            intent.addFlags(RELIABLE_LAUNCH_FLAGS)
            context.startActivity(intent)
            Result.Success(
                LaunchAttempt(
                    requestedPackage = resolvedPackage,
                    expectedForegroundPackages = candidatePackages.toSet()
                )
            )
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
    ): Result<LaunchAttempt> {
        return try {
            val intent = calendarInsertIntent(title, startTime, endTime, description, location)
            val preferredPackages = installedPackages(CALENDAR_PACKAGES)
            preferredPackages.firstOrNull()?.let(intent::setPackage)
            val resolvedPackage = intent.resolveActivity(context.packageManager)?.packageName
                ?: return Result.Error("No installed calendar can create an event")
            context.startActivity(intent)
            Result.Success(
                LaunchAttempt(
                    requestedPackage = resolvedPackage,
                    expectedForegroundPackages = (preferredPackages + resolvedPackage).toSet()
                )
            )
        } catch (e: Exception) {
            Logger.e("Failed to open calendar insert", e)
            Result.Error("Cannot open calendar", e)
        }
    }

    /**
     * Current Google Calendar builds advertise ACTION_INSERT by MIME type. Setting only the event
     * content URI no longer resolves on those builds. Keep the URI and standard event-directory
     * MIME type together so the review screen opens without calendar-write permission.
     */
    internal fun calendarInsertIntent(
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        location: String? = null
    ): Intent = Intent(Intent.ACTION_INSERT).apply {
        setDataAndType(CalendarContract.Events.CONTENT_URI, CALENDAR_EVENT_MIME_TYPE)
        putExtra(CalendarContract.Events.TITLE, InputSanitizer.sanitizeForAccessibility(title))
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
        description?.let {
            putExtra(CalendarContract.Events.DESCRIPTION, InputSanitizer.sanitizeForAccessibility(it))
        }
        location?.let {
            putExtra(CalendarContract.Events.EVENT_LOCATION, InputSanitizer.sanitizeForAccessibility(it))
        }
        addFlags(RELIABLE_LAUNCH_FLAGS)
    }

    /** Opens an installed calendar directly, falling back to the platform calendar category. */
    fun openCalendar(): Result<LaunchAttempt> {
        return try {
            val candidates = installedPackages(CALENDAR_PACKAGES)
            val intent = candidates
                .firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
                ?: Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CALENDAR) }
            val resolvedPackage = intent.component?.packageName
                ?: intent.resolveActivity(context.packageManager)?.packageName
                ?: return Result.Error("Calendar is not installed")
            intent.addFlags(RELIABLE_LAUNCH_FLAGS)
            context.startActivity(intent)
            Result.Success(
                LaunchAttempt(
                    requestedPackage = resolvedPackage,
                    expectedForegroundPackages = (candidates + resolvedPackage).toSet()
                )
            )
        } catch (e: Exception) {
            Logger.e("Failed to open calendar", e)
            Result.Error("Calendar is not installed", e)
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

    fun draftEmail(to: String, subject: String, body: String): Result<LaunchAttempt> {
        val safeSubject = InputSanitizer.sanitizeForAccessibility(subject)
        val safeBody = InputSanitizer.sanitize(body)
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                // Gmail and several OEM mail clients ignore ACTION_SENDTO extras but honour the
                // RFC-6068 query. Supply both so the visible, reviewable compose draft is populated
                // consistently without ever requesting SEND_EMAIL or sending automatically.
                data = EmailDraftUri.build(to, safeSubject, safeBody)
                if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to.trim()))
                putExtra(Intent.EXTRA_SUBJECT, safeSubject)
                putExtra(Intent.EXTRA_TEXT, safeBody)
                if (context.packageManager.getLaunchIntentForPackage("com.google.android.gm") != null) {
                    setPackage("com.google.android.gm")
                }
                addFlags(RELIABLE_LAUNCH_FLAGS)
            }
            val resolvedPackage = intent.resolveActivity(context.packageManager)?.packageName
                ?: return Result.Error("No email application is installed")
            context.startActivity(intent)
            Result.Success(LaunchAttempt(resolvedPackage))
        } catch (e: Exception) {
            Logger.e("Failed to draft email", e)
            Result.Error("Cannot draft email", e)
        }
    }

    fun sendWhatsAppMessage(number: String, message: String): Result<LaunchAttempt> {
        val safeMessage = InputSanitizer.sanitize(message)
        if (safeMessage.isBlank()) return Result.Error("A WhatsApp draft needs a message.")
        val safeNumber = number.takeIf { it.isNotBlank() }?.let(::validatePhoneNumber)
        if (number.isNotBlank() && safeNumber == null) {
            return Result.Error("Invalid phone number: $number")
        }

        return try {
            val whatsappPackage = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
                context.packageManager.getLaunchIntentForPackage(it) != null
            } ?: return Result.Error("WhatsApp is not installed")
            val intent = if (safeNumber != null) {
                val uri = Uri.parse(
                    "https://wa.me/${safeNumber.removePrefix("+")}?text=${Uri.encode(safeMessage)}"
                )
                Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(whatsappPackage)
                    addFlags(RELIABLE_LAUNCH_FLAGS)
                }
            } else {
                // Contact names are private and ambiguous. Let WhatsApp present its own recipient
                // picker with the message prefilled; never guess or persist a recipient mapping.
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, safeMessage)
                    setPackage(whatsappPackage)
                    addFlags(RELIABLE_LAUNCH_FLAGS)
                }
            }
            context.startActivity(intent)
            Result.Success(LaunchAttempt(whatsappPackage))
        } catch (e: Exception) {
            Logger.e("Failed to send WhatsApp message", e)
            Result.Error("WhatsApp is not installed or cannot open this draft", e)
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

    private fun installedPackages(packages: List<String>): List<String> =
        packages.filter { context.packageManager.getLaunchIntentForPackage(it) != null }

    private companion object {
        const val CALENDAR_EVENT_MIME_TYPE = "vnd.android.cursor.dir/event"
        val CALENDAR_PACKAGES = listOf("com.google.android.calendar", "com.xiaomi.calendar")
        const val RELIABLE_LAUNCH_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    }
}
