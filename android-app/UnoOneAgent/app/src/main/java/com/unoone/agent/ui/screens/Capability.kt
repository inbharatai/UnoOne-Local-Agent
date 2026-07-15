package com.unoone.agent.ui.screens

/**
 * Eyes-free (WS5) main-page capability surface. The large, TalkBack-labeled actions a blind user
 * reaches in one tap from the top of the Agent screen. [handler] is the routing each capability maps
 * to — pure data, JVM-tested in [CapabilityTest]; the Compose buttons in `AgentScreen` invoke these
 * handlers (start listening, toggle blind aid, read the screen, open the secure browser).
 *
 * The B5/WS3 work refines the READ_SCREEN handler to the MediaProjection + OCR/describe_scene path
 * with a spoken result; until then READ_SCREEN routes through the always-available `read_screen`
 * (Accessibility) tool, which the B1 system-permission deep-link + B2 VOICE narration already make
 * speak its result end-to-end.
 */
enum class Capability(val label: String, val talkBackLabel: String, val handler: CapabilityHandler) {
    LISTEN(
        label = "Listen",
        talkBackLabel = "Listen. Tap, then speak your command.",
        handler = CapabilityHandler.START_LISTENING
    ),
    BLIND_AID(
        label = "Blind Aid",
        talkBackLabel = "Blind Aid. Turns on the camera and guides you around what is in front of you.",
        handler = CapabilityHandler.TOGGLE_BLIND_AID
    ),
    READ_SCREEN(
        label = "Read Screen",
        talkBackLabel = "Read Screen. Speaks what is currently on your screen.",
        handler = CapabilityHandler.READ_SCREEN
    ),
    SECURE_BROWSER(
        label = "Secure Browser",
        talkBackLabel = "Secure Browser. Opens the voice-driven private browser.",
        handler = CapabilityHandler.OPEN_SECURE_BROWSER
    )
}

/** The handler each [Capability] routes to when tapped. */
enum class CapabilityHandler { START_LISTENING, TOGGLE_BLIND_AID, READ_SCREEN, OPEN_SECURE_BROWSER }