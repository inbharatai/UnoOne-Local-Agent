package com.unoone.agent.localbrain

import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.Result

/** MNN proposals use the same PageAgent wire protocol and native controller as Gemma.
 * This adapter does not execute actions or interpret `done` as verified task success.
 */
class QwenPageAgentPlanner(residentOwner: String = "secure-browser") {
    private val planner = QwenMnnPlanner(residentOwner = residentOwner)
    // Protocol methods only: no load(), conversation, or LiteRT engine is ever created here.
    private val protocol = PageAgentGemmaPlanner()

    fun activeBackend(): String = planner.activeBackend()
    fun configReceipt(): String? = planner.configReceipt()
    fun lastLoadError(): String = planner.lastLoadError()
    fun requestCancel(reason: String = "browser stop") = planner.requestCancel(reason)
    suspend fun cancelAndAwaitIdle(): Boolean = planner.cancelAndAwaitIdle()
    suspend fun close(): Boolean = planner.close()
    suspend fun load(path: String, spec: BrainModelSpec, ownerToken: String): Result<Unit> =
        planner.load(path, spec, ownerToken)

    internal fun buildPrompt(system: String, page: String, schema: String, maxOutputTokens: Int): String =
        protocol.buildPrompt(system, page, schema, maxOutputTokens)

    internal fun parseAndValidate(raw: String): Result<PageAgentPlan> = protocol.parseAndValidate(raw)

    suspend fun plan(
        pageAgentSystemPrompt: String,
        pageAgentUserPrompt: String,
        macroToolSchemaJson: String,
        maxOutputTokens: Int
    ): Result<PageAgentPlan> {
        val budget = maxOutputTokens.coerceIn(1, 256)
        val prompt = buildPrompt(pageAgentSystemPrompt, pageAgentUserPrompt, macroToolSchemaJson, budget)
        return when (val output = planner.controllerRequest(
            system = PageAgentGemmaPlanner.PAGE_AGENT_SYSTEM_INSTRUCTION,
            prompt = prompt,
            maxOutputTokens = budget
        )) {
            is Result.Error -> output
            is Result.Success -> parseAndValidate(output.data)
        }
    }
}
