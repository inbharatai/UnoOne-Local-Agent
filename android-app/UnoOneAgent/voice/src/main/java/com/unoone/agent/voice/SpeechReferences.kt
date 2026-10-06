package com.unoone.agent.voice

/** Process-wide speech capabilities. A cleared/stale completion cannot release a successor. */
class SpeechReferences {
    class Owner internal constructor()
    private val owners = mutableSetOf<Owner>()
    private var legacyOwners = 0

    @Synchronized fun acquire(): Owner = Owner().also { owners.add(it) }
    @Synchronized fun release(owner: Owner) { owners.remove(owner) }
    @Synchronized fun beginLegacy() { legacyOwners++ }
    @Synchronized fun endLegacy() { if (legacyOwners > 0) legacyOwners-- }
    @Synchronized fun isBusy(): Boolean = owners.isNotEmpty() || legacyOwners > 0
    // Legacy callers have no identity: retain their balanced references across reset rather than
    // guessing which epoch a no-argument completion belongs to. They cannot release owned speech.
    @Synchronized fun clear() { owners.clear() }
}
