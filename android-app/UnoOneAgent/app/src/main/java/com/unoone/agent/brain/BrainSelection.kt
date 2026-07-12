package com.unoone.agent.brain

import android.content.Context
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.BrainModelSpec

/**
 * Compatibility facade for callers that previously supported multiple brain profiles.
 *
 * UnoOne V2 has one brain only, so there is no user selection to persist. Older preference values
 * are ignored and every call resolves to Gemma 4 E2B. Keeping this facade avoids coupling startup,
 * self-test and settings code directly to registry internals while the migration is completed.
 */
object BrainSelection {

    fun selected(context: Context): BrainModelSpec = BrainModelRegistry.GEMMA_4_E2B

    fun manifestId(context: Context): String = BrainModelRegistry.GEMMA_4_E2B.manifestId

    /** No-op compatibility method: model choice is no longer configurable. */
    fun set(context: Context, manifestId: String) = Unit
}
