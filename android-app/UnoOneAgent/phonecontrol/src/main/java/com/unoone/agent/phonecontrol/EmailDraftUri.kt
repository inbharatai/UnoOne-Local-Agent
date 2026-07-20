package com.unoone.agent.phonecontrol

import android.net.Uri

/** Builds the reviewable RFC-6068 draft URI used by Gmail and compatible mail clients. */
internal object EmailDraftUri {
    fun build(to: String, subject: String, body: String): Uri =
        Uri.parse(
            "mailto:${Uri.encode(to.trim())}" +
                "?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
        )
}
