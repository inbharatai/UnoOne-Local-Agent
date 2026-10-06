package com.unoone.agent.core.device

/** Denial-only heuristics, never proof that an unlabelled canvas contains no secrets. */
object SensitiveReadRedaction {
    private val labels = Regex("(?i)password|passcode|pass.?word|\\botp\\b|one.?time|verification.?code|security.?code|security.?answer|secret|\\bpin\\b|credit.?card|debit.?card|card.?number|\\bcvv\\b|\\bcvc\\b|recovery.?code|backup.?code|पासवर्ड|पासकोड|ओटीपी|सत्यापन.?कोड|सुरक्षा.?कोड|कार्ड.?नंबर")
    private val numeric = Regex("[0-9]")
    fun hasSecretLabel(value: CharSequence?): Boolean = value != null && labels.containsMatchIn(value)
    fun shouldRedact(metadata: CharSequence?, value: CharSequence?, secretContext: Boolean, editable: Boolean = false): Boolean =
        hasSecretLabel(metadata) || hasSecretLabel(value) || (secretContext && (editable || (value != null && numeric.containsMatchIn(value))))

    fun redactNodes(nodes: List<UiNode>): List<UiNode> {
        val secretContext = nodes.any { it.password || it.semantic.sensitiveObservation() || hasSecretLabel(it.resourceId + " " + it.text + " " + it.description + " " + it.hint) }
        val labelledParents = nodes.filter { hasSecretLabel(it.resourceId + " " + it.text + " " + it.description + " " + it.hint) }
            .map { it.path.substringBeforeLast('.', "") }.toSet()
        return nodes.map { n ->
            val nearbyLabel = n.path.substringBeforeLast('.', "") in labelledParents
            if (n.password || n.semantic.sensitiveObservation() || nearbyLabel ||
                shouldRedact(n.resourceId, n.text + " " + n.description + " " + n.hint, secretContext, n.editable))
                n.copy(text = "", description = "", hint = "", semantic = if (n.password) TargetSemantic.SECRET else if (n.semantic.sensitiveObservation()) n.semantic else TargetSemantic.SECRET)
            else n
        }
    }

    fun readScreen(snapshot: UiSnapshot, packageName: String): String = redactNodes(snapshot.nodes)
        .filter { it.packageName == packageName && !it.password && !it.semantic.sensitiveObservation() }
        .map { it.text.ifBlank { it.description } }.filter { it.isNotBlank() }.distinct().joinToString("\n").take(6000)

    /** Legacy/OCR has no reliable field boundaries: labelled secret context hands over the whole read. */
    fun redactText(text: String): String = if (hasSecretLabel(text)) "Sensitive screen content withheld; read it manually." else text
}
