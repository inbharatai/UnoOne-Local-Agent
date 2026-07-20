package com.unoone.agent.localbrain

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.unoone.agent.core.agent.ResponseTextJoiner
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A validated PageAgent reflection/action response produced by local Gemma. */
@Serializable
data class PageAgentPlan(
    val evaluationPreviousGoal: String,
    val memory: String,
    val nextGoal: String,
    val actionName: String,
    val actionArgumentsJson: String
)

/**
 * Dedicated browser-planning conversation for UnoOne Page Agent.
 *
 * This planner owns a LiteRT-LM engine only while the Secure Browser holds an exclusive model lease.
 * The main phone-planning brain is unloaded before [load], so model weights are never resident twice.
 * Browser actions are returned as strict JSON and checked against [ALLOWED_ACTIONS]. PageAgent still
 * sends every action through the native [BrowserSafetyPolicy] before touching the DOM.
 */
class PageAgentGemmaPlanner {

    // Small on-device models occasionally emit JSON-style objects with unquoted property names
    // even when explicitly asked for strict JSON. Lenient parsing accepts that harmless syntax;
    // the action allowlist and typed native bridge validation still reject unknown or malformed
    // browser actions before anything can touch the page.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val mutex = Mutex()

    private var engine: Engine? = null
    @Volatile private var loaded = false
    @Volatile private var backend = ""
    @Volatile private var lastError = ""

    fun isLoaded(): Boolean = loaded
    fun activeBackend(): String = backend
    fun lastLoadError(): String = lastError

    suspend fun load(modelPath: String, spec: BrainModelSpec): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeInternal()
            lastError = ""
            try {
                val loadedPair = loadEngine(modelPath)
                    ?: return@withLock Result.Error("${spec.displayName} failed to load for Secure Browser")
                val newEngine = loadedPair.first
                // Validate conversation creation while loading, but do not retain it. PageAgent
                // already sends its complete task/history/DOM on every step; retaining LiteRT-LM's
                // native conversation would duplicate all previous prompts until the 4096-token
                // context overflows after only a few browser actions.
                val validationConversation = try {
                    newEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(PAGE_AGENT_SYSTEM_INSTRUCTION),
                            tools = emptyList(),
                            automaticToolCalling = false
                        )
                    )
                } catch (e: Exception) {
                    runCatching { newEngine.close() }
                    throw e
                }
                runCatching { validationConversation.close() }
                engine = newEngine
                backend = loadedPair.second
                loaded = true
                Logger.i("PageAgentGemmaPlanner: loaded ${spec.displayName} on $backend")
                Result.Success(Unit)
            } catch (e: Exception) {
                closeInternal()
                lastError = e.message ?: "Unknown browser-brain load error"
                Logger.e("PageAgentGemmaPlanner: load failed", e)
                Result.Error("Secure Browser model load failed: $lastError", e)
            }
        }
    }

    suspend fun plan(
        pageAgentSystemPrompt: String,
        pageAgentUserPrompt: String,
        macroToolSchemaJson: String,
        maxOutputTokens: Int
    ): Result<PageAgentPlan> {
        val activeEngine = engine ?: return Result.Error("Secure Browser Gemma model is not loaded")
        val prompt = buildPrompt(
            pageAgentSystemPrompt = pageAgentSystemPrompt,
            pageAgentUserPrompt = pageAgentUserPrompt,
            macroToolSchemaJson = macroToolSchemaJson,
            maxOutputTokens = maxOutputTokens
        )
        Logger.i(
            "PageAgentGemmaPlanner: compact prompt chars=${prompt.length}; " +
                "source system=${pageAgentSystemPrompt.length}, page=${pageAgentUserPrompt.length}, schema=${macroToolSchemaJson.length}"
        )

        return try {
            val message = mutex.withLock {
                val stepConversation = activeEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(PAGE_AGENT_SYSTEM_INSTRUCTION),
                        tools = emptyList(),
                        automaticToolCalling = false
                    )
                )
                try {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { stepConversation.sendMessage(prompt) }
                    }
                } finally {
                    runCatching { stepConversation.close() }
                }
            }
            val text = extractText(message).orEmpty()
            val parsed = parseAndValidate(text).let { result ->
                if (result is Result.Error) {
                    recoverIncompleteInputAction(text, pageAgentUserPrompt) ?: result
                } else {
                    result
                }
            }
            val corrected = if (parsed is Result.Success) {
                val adjusted = correctActionForDom(parsed.data, pageAgentUserPrompt)
                val indexPresent = runCatching {
                    json.parseToJsonElement(adjusted.actionArgumentsJson)
                        .jsonObject["index"] != null
                }.getOrDefault(false)
                Logger.i(
                    "PageAgentGemmaPlanner: action=${parsed.data.actionName}, " +
                        "corrected=${adjusted.actionName}, indexed=$indexPresent"
                )
                Result.Success(adjusted)
            } else {
                parsed
            }
            corrected.also { result ->
                if (result is Result.Error) {
                    Logger.w(
                        "PageAgentGemmaPlanner: rejected model output (${result.message}); " +
                            "chars=${text.length}; structuralShape=${structuralShape(text)}"
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w("PageAgentGemmaPlanner: inference timed out; closing browser brain")
            close()
            Result.Error("Secure Browser inference timed out")
        } catch (e: Exception) {
            Logger.e("PageAgentGemmaPlanner: inference failed", e)
            Result.Error("Secure Browser inference failed: ${e.message}", e)
        }
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { engine?.close() }
            .onFailure { Logger.w("PageAgentGemmaPlanner: engine close failed: ${it.message}") }
        engine = null
        loaded = false
        backend = ""
    }

    private fun loadEngine(modelPath: String): Pair<Engine, String>? {
        for ((candidateBackend, name) in listOf(Backend.GPU() to "GPU", Backend.CPU() to "CPU")) {
            var candidate: Engine? = null
            try {
                candidate = Engine(EngineConfig(modelPath = modelPath, backend = candidateBackend))
                candidate.initialize()
                return candidate to name
            } catch (e: Exception) {
                Logger.w("PageAgentGemmaPlanner: $name backend failed (${e.message})")
                runCatching { candidate?.close() }
            }
        }
        return null
    }

    internal fun buildPrompt(
        pageAgentSystemPrompt: String,
        pageAgentUserPrompt: String,
        macroToolSchemaJson: String,
        maxOutputTokens: Int
    ): String = buildString {
        // The upstream PageAgent system prompt and OpenAI tool schema are intentionally replaced
        // with this compact equivalent. Passing their full versions consumed 4342 input tokens on
        // a 4096-token Gemma context before the first form action ran.
        appendLine("Plan one browser action for the current task, history, and simplified DOM:")
        appendLine(compactPageContext(PromptBuilder.sanitizeContext(pageAgentUserPrompt)))
        appendLine()
        appendLine("Return exactly one JSON object with these keys and no markdown:")
        appendLine("{\"evaluation_previous_goal\":\"...\",\"memory\":\"...\",\"next_goal\":\"...\",\"action\":{\"ACTION_NAME\":{...}}}")
        appendLine("Inside action, use the action name as the key. Do not emit action_name/arguments.")
        appendLine(COMPACT_ACTION_SCHEMA)
        appendLine("Choose one action only. Never output execute_javascript.")
        appendLine("For input_text, copy the requested value exactly into text; text must never be blank or omitted.")
        appendLine("After a write, inspect the updated DOM on the next step. Correct a wrong value or validation error before moving on.")
        appendLine("Before done, compare every requested field with the current DOM. Never claim success for an unverified field.")
        appendLine("If the user says stop before submission, do not click or submit the form.")
        appendLine("Every proposed DOM action is independently authorized by native UnoOne policy. Obey that decision; never retry or bypass a denial.")
        appendLine("Use ask_user when information is missing and done when the task is complete or native policy prevents progress.")
        appendLine("Suggested output budget: ${maxOutputTokens.coerceIn(128, 1_024)} tokens.")
    }

    internal fun compactPageContext(context: String): String {
        if (context.length <= MAX_PAGE_CONTEXT_CHARS) return context
        val head = context.take(PAGE_CONTEXT_HEAD_CHARS)
        val tail = context.takeLast(MAX_PAGE_CONTEXT_CHARS - PAGE_CONTEXT_HEAD_CHARS)
        return "$head\n[older middle context omitted to fit the on-device model]\n$tail"
    }

    internal fun parseAndValidate(raw: String): Result<PageAgentPlan> {
        return try {
            val jsonText = extractJsonObject(raw)
                ?: return Result.Error("PageAgent model returned no JSON object")
            val rootResult = runCatching {
                json.parseToJsonElement(jsonText).jsonObject
            }.getOrElse {
                // Gemma 4 E2B has been observed on-device emitting `input_text"` (missing only the
                // opening quote) in an otherwise valid plan. Repair only known structural keys;
                // never rewrite arbitrary string values from the user or page.
                runCatching {
                    json.parseToJsonElement(repairStructuralJson(jsonText)).jsonObject
                }.getOrElse { envelopeError ->
                    // The small model can also corrupt a non-action field in the surrounding
                    // reflection envelope while leaving the one requested action completely
                    // valid. Recover only an allow-listed action with a separately parseable JSON
                    // argument object. Native PageAgent policy and the typed bridge still validate
                    // its index and arguments before DOM execution.
                    return parseAllowlistedActionOnly(jsonText)
                        ?: parseTypedActionFromRaw(jsonText)
                        ?: Result.Error(
                            "Invalid PageAgent model response",
                            envelopeError
                        )
                }
            }
            val action = rootResult["action"]?.jsonObject
                ?: return Result.Error("PageAgent response missing action object")
            val normalizedAction = normalizeActionObject(action)
                ?: return parseAllowlistedActionOnly(jsonText)
                    ?: parseTypedActionFromRaw(jsonText)
                    ?: Result.Error("PageAgent response must contain exactly one valid action")
            val (actionName, args) = normalizedAction
            Result.Success(
                PageAgentPlan(
                    evaluationPreviousGoal = rootResult["evaluation_previous_goal"]?.jsonPrimitive?.content.orEmpty(),
                    memory = rootResult["memory"]?.jsonPrimitive?.content.orEmpty(),
                    nextGoal = rootResult["next_goal"]?.jsonPrimitive?.content.orEmpty(),
                    actionName = actionName,
                    actionArgumentsJson = args.toString()
                )
            )
        } catch (e: Exception) {
            runCatching { parseTypedActionFromRaw(raw) }.getOrNull()
                ?: Result.Error("Invalid PageAgent model response", e)
        }
    }

    /**
     * Corrects only mechanically impossible element/tool combinations using PageAgent's current
     * simplified DOM. This does not invent values or indices: it preserves Gemma's target and value,
     * while ensuring selects, dates, checkboxes and radios use the typed macro tool that can
     * actually operate that element. Native browser safety still authorizes the corrected action.
     */
    internal fun correctActionForDom(plan: PageAgentPlan, pageContext: String): PageAgentPlan {
        val args = runCatching { json.parseToJsonElement(plan.actionArgumentsJson).jsonObject }
            .getOrNull() ?: return plan
        val index = args["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return plan
        val elementLine = indexedElementLine(pageContext, index)
        val correctedTargetIndex = when (plan.actionName) {
            "select_dropdown_option" ->
                index.takeIf { elementLine?.contains("<select") == true }
                    ?: uniqueElementIndex(pageContext, Regex("""<select\b"""))
            "toggle_checkbox" ->
                index.takeIf {
                    elementLine?.let {
                        it.contains("<input") &&
                            Regex("""\btype\s*=\s*["']?checkbox""").containsMatchIn(it)
                    } == true
                } ?: uniqueElementIndex(
                    pageContext,
                    Regex("""<input\b[^\r\n]*\btype\s*=\s*["']?checkbox""")
                )
            "choose_radio" ->
                index.takeIf {
                    elementLine?.let {
                        it.contains("<input") &&
                            Regex("""\btype\s*=\s*["']?radio""").containsMatchIn(it)
                    } == true
                } ?: uniqueElementIndex(
                    pageContext,
                    Regex("""<input\b[^\r\n]*\btype\s*=\s*["']?radio""")
                )
            "pick_date" ->
                index.takeIf {
                    elementLine?.let {
                        it.contains("<input") &&
                            Regex("""\btype\s*=\s*["']?date""").containsMatchIn(it)
                    } == true
                } ?: uniqueElementIndex(
                    pageContext,
                    Regex("""<input\b[^\r\n]*\btype\s*=\s*["']?date""")
                )
            else -> index
        }
        if (correctedTargetIndex != null && correctedTargetIndex != index) {
            return plan.copy(
                actionArgumentsJson = buildJsonObject {
                    args.forEach { (key, value) ->
                        if (key == "index") put(key, correctedTargetIndex) else put(key, value)
                    }
                }.toString()
            )
        }
        val targetLine = elementLine ?: return plan

        // A small local model can repeat an already-completed select even when the updated DOM
        // proves success. For an explicit basic-form request, advance to one unambiguous requested
        // checkbox, or finish before submission once both controls are verified. This remains
        // bounded: no value or target is invented, ambiguous controls are never chosen, and every
        // resulting DOM action still passes native browser authorization.
        correctVerifiedBasicFormProgress(plan, args, targetLine, pageContext)?.let { return it }

        val corrected = when {
            targetLine.contains("<select") && plan.actionName == "input_text" -> {
                val text = args["text"] ?: return plan
                "select_dropdown_option" to buildJsonObject {
                    put("index", index)
                    put("text", text)
                }
            }
            targetLine.contains("<input") &&
                Regex("""\btype\s*=\s*["']?date""").containsMatchIn(targetLine) &&
                plan.actionName == "input_text" -> {
                val date = args["text"] ?: return plan
                "pick_date" to buildJsonObject {
                    put("index", index)
                    put("date", date)
                }
            }
            targetLine.contains("<input") &&
                Regex("""\btype\s*=\s*["']?checkbox""").containsMatchIn(targetLine) &&
                plan.actionName == "click_element_by_index" ->
                "toggle_checkbox" to buildJsonObject { put("index", index) }
            targetLine.contains("<input") &&
                Regex("""\btype\s*=\s*["']?radio""").containsMatchIn(targetLine) &&
                plan.actionName == "click_element_by_index" ->
                "choose_radio" to buildJsonObject { put("index", index) }
            else -> return plan
        }
        return plan.copy(
            actionName = corrected.first,
            actionArgumentsJson = corrected.second.toString()
        )
    }

    private fun indexedElementLine(pageContext: String, index: Int): String? =
        Regex("""(?m)^\s*\*?\[$index\](?:<|\s)[^\r\n]*""")
            .findAll(pageContext)
            .map { it.value.lowercase() }
            .lastOrNull { it.contains('<') }

    /**
     * Gemma 4 E2B can choose the correct indexed text field while emitting `"text":` with no
     * value. Recover only when the current DOM identifies a common field and the user request
     * contains one unambiguous, explicit value for that field. Nothing is guessed or learned:
     * unknown fields, duplicate candidates and prose without a delimiter remain rejected.
     */
    internal fun recoverIncompleteInputAction(
        raw: String,
        pageContext: String
    ): Result<PageAgentPlan>? {
        val actionCandidates = ALLOWED_ACTIONS.mapNotNull { actionName ->
            actionName.takeIf {
                Regex("""["']?${Regex.escape(actionName)}["']?\s*:\s*\{""")
                    .containsMatchIn(raw)
            }
        }.toSet()
        if (actionCandidates != setOf("input_text")) return null

        val actionTail = Regex("""["']?input_text["']?\s*:\s*\{([\s\S]*)""")
            .find(raw)?.groupValues?.getOrNull(1) ?: return null
        val index = Regex("""["']?index["']?\s*:\s*(\d+)""")
            .find(actionTail)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""^\s*(\d+)\s*,""")
                .find(actionTail)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val request = Regex("""(?is)<user_request>\s*(.*?)\s*</user_request>""")
            .find(pageContext)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val explicitModelValue = Regex(
            """(?s)["']?text["']?\s*:\s*["']([^"']{1,$MAX_RECOVERED_FIELD_VALUE_CHARS})["']"""
        ).find(actionTail)?.groupValues?.getOrNull(1)?.trim()

        if (index == null) {
            val option = explicitModelValue?.takeIf { it.isNotBlank() } ?: return null
            val explicitlyRequested = Regex(
                """(?i)\b(?:select|choose)\b[^,;\r\n]{0,80}\b${Regex.escape(option)}\b"""
            ).containsMatchIn(request)
            val selectIndex = uniqueElementIndex(
                pageContext,
                Regex("""<select\b""")
            )
            if (!explicitlyRequested || selectIndex == null) return null
            return Result.Success(
                PageAgentPlan(
                    evaluationPreviousGoal = "",
                    memory = "",
                    nextGoal = "Select the explicit requested option",
                    actionName = "select_dropdown_option",
                    actionArgumentsJson = buildJsonObject {
                        put("index", selectIndex)
                        put("text", option)
                    }.toString()
                )
            )
        }

        val element = indexedElementLine(pageContext, index) ?: return null
        val value = explicitModelValue?.takeIf { it.isNotBlank() }
            ?: explicitValueForField(element, request)
            ?: return null

        return Result.Success(
            PageAgentPlan(
                evaluationPreviousGoal = "",
                memory = "",
                nextGoal = "Enter the explicit requested value",
                actionName = "input_text",
                actionArgumentsJson = buildJsonObject {
                    put("index", index)
                    put("text", value)
                }.toString()
            )
        )
    }

    private fun explicitValueForField(element: String, request: String): String? {
        val patterns = when {
            Regex("""\btype\s*=\s*["']?email\b""").containsMatchIn(element) ||
                Regex("""\b(email|e-mail)\b""").containsMatchIn(element) ->
                listOf(
                    Regex(
                        """(?i)(?<![\w.+-])([A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,})(?![\w.-])"""
                    )
                )

            Regex("""\btype\s*=\s*["']?tel\b""").containsMatchIn(element) ||
                Regex("""\b(phone|telephone|mobile|contact.?number)\b""").containsMatchIn(element) ->
                listOf(
                    Regex(
                        """(?i)\b(?:telephone|phone|mobile|contact\s+number)\b\s*(?:is|with|to|:)?\s*(\+?[0-9][0-9 ()-]{6,}[0-9])"""
                    )
                )

            Regex("""\b(custname|customer.?name|full.?name|your.?name)\b""")
                .containsMatchIn(element) ->
                listOf(
                    Regex(
                        """(?i)\b(?:customer|full|your)\s+name\b\s*(?:is|with|to|:)?\s*([^,;]+?)(?=\s+and\s+(?:telephone|phone|mobile|email|select|choose|check|stop|submit)\b|[,;]|$)"""
                    ),
                    Regex(
                        """(?i)\bname\b\s*(?:is|with|to|:)?\s*([^,;]+?)(?=\s+and\s+(?:telephone|phone|mobile|email|select|choose|check|stop|submit)\b|[,;]|$)"""
                    )
                )

            element.contains("<textarea") ->
                listOf(
                    Regex(
                        """(?i)\btextarea\b\s*(?:is|with|to|:)?\s*([^,;]+?)(?=\s+and\s+(?:select|choose|check|stop|submit|text\s+input)\b|[,;]|$)"""
                    )
                )

            element.contains("<input") &&
                (
                    Regex("""\btype\s*=\s*["']?text\b""").containsMatchIn(element) ||
                        Regex("""\b(text.?input|my.?text)\b""").containsMatchIn(element)
                    ) ->
                listOf(
                    Regex(
                        """(?i)\btext\s+input\b\s*(?:is|with|to|:)?\s*([^,;]+?)(?=\s+and\s+(?:textarea|select|choose|check|stop|submit)\b|[,;]|$)"""
                    )
                )

            else -> emptyList()
        }

        val candidates = patterns.flatMap { pattern ->
            pattern.findAll(request).mapNotNull { match ->
                match.groupValues.getOrNull(1)?.trim()
                    ?.trim('"', '\'', '.', ',')
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_RECOVERED_FIELD_VALUE_CHARS }
            }.toList()
        }.distinct()
        return candidates.singleOrNull()
    }

    /**
     * Return a replacement only when the current Page Agent DOM has one unambiguous control of the
     * required type. This cannot guess between two selects or checkboxes; ambiguity stays with the
     * planner instead of silently acting on the wrong field.
     */
    private fun uniqueElementIndex(pageContext: String, elementPattern: Regex): Int? {
        val candidates = Regex("""(?m)^\s*\*?\[(\d+)](<[^\r\n]*)""")
            .findAll(pageContext)
            .mapNotNull { match ->
                val line = match.groupValues[2].lowercase()
                match.groupValues[1].toIntOrNull()?.takeIf { elementPattern.containsMatchIn(line) }
            }
            .toSet()
        return candidates.singleOrNull()
    }

    private fun correctVerifiedBasicFormProgress(
        plan: PageAgentPlan,
        args: JsonObject,
        targetLine: String,
        pageContext: String
    ): PageAgentPlan? {
        val userRequest = Regex(
            """(?is)<user_request>\s*(.*?)\s*</user_request>"""
        ).find(pageContext)?.groupValues?.getOrNull(1)?.lowercase() ?: return null
        val asksForCheckbox = Regex(
            """\b(?:check|tick|enable|accept|agree|opt[\s-]?in|subscribe)\b"""
        ).containsMatchIn(userRequest)
        if (!asksForCheckbox) return null

        val checkboxIndex = uniqueElementIndex(
            pageContext,
            Regex("""<input\b[^\r\n]*\btype\s*=\s*["']?checkbox""")
        ) ?: return null
        val checkboxLine = indexedElementLine(pageContext, checkboxIndex) ?: return null
        val checkboxChecked = Regex(
            """(?:\bchecked\s*=\s*["']?true["']?|\baria-checked\s*=\s*["']?true["']?)"""
        )
            .containsMatchIn(checkboxLine)
        val stopBeforeSubmit = Regex(
            """\b(?:stop|do not|don't|without)\b.{0,40}\b(?:submit(?:ting|ted)?|submission)\b"""
        ).containsMatchIn(userRequest)

        val repeatedCompletedSelect = plan.actionName == "select_dropdown_option" &&
            targetLine.contains("<select") &&
            args["text"]?.jsonPrimitive?.content?.trim()?.lowercase()?.let { requested ->
                requested.isNotBlank() && targetLine.contains(requested)
            } == true
        val repeatedCheckedCheckbox = plan.actionName == "toggle_checkbox" &&
            targetLine.contains("checkbox") &&
            checkboxChecked

        return when {
            (repeatedCompletedSelect || repeatedCheckedCheckbox) && !checkboxChecked ->
                plan.copy(
                    evaluationPreviousGoal = "The previous field is already verified in the current DOM.",
                    nextGoal = "Enable the one requested checkbox.",
                    actionName = "toggle_checkbox",
                    actionArgumentsJson = buildJsonObject { put("index", checkboxIndex) }.toString()
                )
            (repeatedCompletedSelect || repeatedCheckedCheckbox) && checkboxChecked && stopBeforeSubmit ->
                plan.copy(
                    evaluationPreviousGoal = "All explicitly requested fields are verified.",
                    nextGoal = "Finish without submitting, as requested.",
                    actionName = "done",
                    actionArgumentsJson = buildJsonObject {
                        put("text", "Requested form fields are filled. The form was not submitted.")
                        put("success", true)
                    }.toString()
                )
            else -> null
        }
    }

    /**
     * Accept the canonical `{action_name:{args}}` shape and one bounded small-model variant seen on
     * device: `{input_text:[3], text:"value"}`. The latter is normalized only when exactly one
     * allow-listed action key is present; all sibling keys become typed arguments and the numeric
     * array supplies the element index.
     */
    private fun normalizeActionObject(action: JsonObject): Pair<String, JsonObject>? {
        // Gemma 4 E2B also emits a common typed-tool envelope on device:
        // {"action_name":"scroll","arguments":{"down":true,...}}. Accept this only when those are
        // the sole keys, the name is allow-listed, the arguments are an object and its required
        // typed fields are present. Native browser policy still authorizes the resulting action.
        if (action.keys == setOf("action_name", "arguments")) {
            val actionName = runCatching {
                action.getValue("action_name").jsonPrimitive.content
            }.getOrNull()
            val arguments = action["arguments"] as? JsonObject
            if (
                actionName != null &&
                actionName in ALLOWED_ACTIONS &&
                arguments != null &&
                hasRequiredArguments(actionName, arguments)
            ) {
                return actionName to arguments
            }
        }
        val actionNames = action.keys.filter { it in ALLOWED_ACTIONS }
        if (actionNames.size != 1) return null
        val actionName = actionNames.single()
        val value = action[actionName] ?: return null
        if (action.size == 1 && value is JsonObject) {
            return if (hasRequiredArguments(actionName, value)) actionName to value else null
        }
        if (action.size == 1 && value is JsonArray) {
            val positional = positionalArguments(actionName, value) ?: return null
            return if (hasRequiredArguments(actionName, positional)) actionName to positional else null
        }
        if (value is JsonObject) return null

        val primaryIndex = when (value) {
            is JsonArray -> value.firstOrNull()?.jsonPrimitive?.content?.toIntOrNull()
            else -> value.jsonPrimitive.content.toIntOrNull()
        }
        val siblingArgs = action.filterKeys { it != actionName }
        val normalizedArgs = buildJsonObject {
            siblingArgs.forEach { (key, siblingValue) -> put(key, siblingValue) }
            if (
                primaryIndex != null &&
                actionName in INDEXED_ACTIONS &&
                "index" !in siblingArgs
            ) {
                put("index", primaryIndex)
            }
        }
        return if (hasRequiredArguments(actionName, normalizedArgs)) {
            actionName to normalizedArgs
        } else null
    }

    private fun positionalArguments(actionName: String, values: JsonArray): JsonObject? =
        buildJsonObject {
            fun copy(position: Int, name: String, required: Boolean = true): Boolean {
                val value = values.getOrNull(position)
                if (value == null) return !required
                put(name, value)
                return true
            }
            when (actionName) {
                "input_text", "select_dropdown_option" -> {
                    if (!copy(0, "index") || !copy(1, "text")) return null
                }
                "pick_date" -> {
                    if (!copy(0, "index") || !copy(1, "date")) return null
                }
                "click_element_by_index", "toggle_checkbox", "choose_radio" -> {
                    if (!copy(0, "index")) return null
                }
                "submit_form", "upload_file" -> {
                    if (!copy(0, "index") || !copy(1, "purpose")) return null
                }
                "done" -> {
                    if (!copy(0, "text")) return null
                    copy(1, "success", required = false)
                }
                "wait" -> copy(0, "seconds", required = false)
                "ask_user" -> if (!copy(0, "question")) return null
                "scroll" -> {
                    copy(0, "down", required = false)
                    copy(1, "num_pages", required = false)
                    copy(2, "pixels", required = false)
                    copy(3, "index", required = false)
                }
                "scroll_horizontally" -> {
                    copy(0, "right", required = false)
                    if (!copy(1, "pixels")) return null
                    copy(2, "index", required = false)
                }
                else -> return null
            }
        }

    private fun hasRequiredArguments(actionName: String, args: JsonObject): Boolean = when (actionName) {
        "input_text", "select_dropdown_option" -> "index" in args && "text" in args
        "pick_date" -> "index" in args && "date" in args
        "click_element_by_index", "toggle_checkbox", "choose_radio" -> "index" in args
        "submit_form", "upload_file" -> "index" in args && "purpose" in args
        "done" -> "text" in args
        "ask_user" -> "question" in args
        "scroll_horizontally" -> "pixels" in args
        "wait", "scroll" -> true
        else -> false
    }

    private fun parseAllowlistedActionOnly(raw: String): Result<PageAgentPlan>? {
        val repaired = repairStructuralJson(raw)
        val candidates = ALLOWED_ACTIONS.mapNotNull { actionName ->
            val key = Regex.escape(actionName)
            val match = Regex("""(?:^|[,{]\s*)"?$key"?\s*:\s*\{""")
                .find(repaired) ?: return@mapNotNull null
            val objectStart = repaired.indexOf('{', match.range.first)
            val arguments = extractBalancedObject(repaired, objectStart)?.let { argumentObject ->
                runCatching { json.parseToJsonElement(argumentObject).jsonObject }.getOrNull()
            } ?: parseKnownArguments(actionName, repaired.substring(objectStart))
            if (arguments == null) return@mapNotNull null
            if (!hasRequiredArguments(actionName, arguments)) return@mapNotNull null
            actionName to arguments
        }
        if (candidates.size != 1) return null
        val (actionName, arguments) = candidates.single()
        return Result.Success(
            PageAgentPlan(
                evaluationPreviousGoal = "Recovered a valid action from a malformed model envelope",
                memory = "",
                nextGoal = "",
                actionName = actionName,
                actionArgumentsJson = arguments.toString()
            )
        )
    }

    /**
     * Schema-driven recovery that does not depend on the reflection envelope or balanced braces.
     * This handles extra closing braces and corrupted prose while still requiring exactly one
     * allow-listed action name and every required typed argument.
     */
    private fun parseTypedActionFromRaw(raw: String): Result<PageAgentPlan>? {
        val repaired = repairStructuralJson(raw)
        val candidates = ALLOWED_ACTIONS.mapNotNull { actionName ->
            val key = Regex.escape(actionName)
            val match = Regex(""""?$key"?\s*:\s*(?:\{|\[)""")
                .find(repaired) ?: return@mapNotNull null
            val actionText = repaired.substring(match.range.first)
            val arguments = parseKnownArguments(actionName, actionText)
                ?: parsePositionalArgumentsFromRaw(actionName, actionText)
                ?: return@mapNotNull null
            actionName to arguments
        }
        if (candidates.size != 1) return null
        val (actionName, arguments) = candidates.single()
        return Result.Success(
            PageAgentPlan(
                evaluationPreviousGoal = "Recovered a typed action from a malformed model response",
                memory = "",
                nextGoal = "",
                actionName = actionName,
                actionArgumentsJson = arguments.toString()
            )
        )
    }

    /**
     * Last-resort typed decoding for the exact failure mode observed from Gemma 4 E2B: the action
     * name and primitive arguments are correct, but the final string value is missing its closing
     * quote. Each supported action has an explicit schema here; unknown keys are ignored and a
     * missing required argument rejects the candidate.
     */
    private fun parseKnownArguments(actionName: String, rawArguments: String): JsonObject? {
        fun stringArg(name: String): String? {
            val key = Regex.escape(name)
            val closed = Regex("""(?s)"?$key"?\s*:\s*"([^"]*)"""")
                .find(rawArguments)?.groupValues?.getOrNull(1)
            if (closed != null) return closed
            // Unterminated final JSON string: stop only at the structural closing brace/newline.
            return Regex("""(?s)"?$key"?\s*:\s*"([^}\r\n]*)""")
                .find(rawArguments)?.groupValues?.getOrNull(1)?.trimEnd()
        }
        fun intArg(name: String): Int? {
            val key = Regex.escape(name)
            return Regex(""""?$key"?\s*:\s*(-?\d+)""")
                .find(rawArguments)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        fun numberArg(name: String): Double? {
            val key = Regex.escape(name)
            return Regex(""""?$key"?\s*:\s*(-?\d+(?:\.\d+)?)""")
                .find(rawArguments)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }
        fun boolArg(name: String): Boolean? {
            val key = Regex.escape(name)
            return Regex(""""?$key"?\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
                .find(rawArguments)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
        }

        return when (actionName) {
            "input_text", "select_dropdown_option" -> {
                val index = intArg("index") ?: return null
                val text = stringArg("text") ?: return null
                buildJsonObject { put("index", index); put("text", text) }
            }
            "pick_date" -> {
                val index = intArg("index") ?: return null
                val date = stringArg("date") ?: return null
                buildJsonObject { put("index", index); put("date", date) }
            }
            "click_element_by_index", "toggle_checkbox", "choose_radio" -> {
                val index = intArg("index") ?: return null
                buildJsonObject { put("index", index) }
            }
            "submit_form", "upload_file" -> {
                val index = intArg("index") ?: return null
                val purpose = stringArg("purpose") ?: return null
                buildJsonObject { put("index", index); put("purpose", purpose) }
            }
            "done" -> {
                val text = stringArg("text") ?: return null
                buildJsonObject {
                    put("text", text)
                    put("success", boolArg("success") ?: true)
                }
            }
            "wait" -> buildJsonObject { put("seconds", numberArg("seconds") ?: 1.0) }
            "ask_user" -> {
                val question = stringArg("question") ?: return null
                buildJsonObject { put("question", question) }
            }
            "scroll" -> buildJsonObject {
                put("down", boolArg("down") ?: true)
                numberArg("num_pages")?.let { put("num_pages", it) }
                intArg("pixels")?.let { put("pixels", it) }
                intArg("index")?.let { put("index", it) }
            }
            "scroll_horizontally" -> {
                val pixels = intArg("pixels") ?: return null
                buildJsonObject {
                    put("right", boolArg("right") ?: true)
                    put("pixels", pixels)
                    intArg("index")?.let { put("index", it) }
                }
            }
            else -> null
        }
    }

    /**
     * Recovers a positional action array even when Gemma omits its final `]`, for example
     * `"select_dropdown_option":[3,"India"}}`. Only the allow-listed action's explicit positional
     * schema is accepted; native authorization and the PageAgent tool schema still validate it.
     */
    private fun parsePositionalArgumentsFromRaw(actionName: String, rawArguments: String): JsonObject? {
        val positionalStart = listOf(
            rawArguments.indexOf('['),
            rawArguments.indexOf('{')
        ).filter { it >= 0 }.minOrNull() ?: return null
        val body = rawArguments.substring(positionalStart + 1)

        fun intAtStart(): Int? =
            Regex("""^\s*(-?\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()

        fun quotedAfterComma(): String? =
            Regex("""^\s*-?\d+\s*,\s*"([^"]*)""")
                .find(body)?.groupValues?.getOrNull(1)

        fun namedStringAfterComma(name: String): String? =
            Regex(
                """^\s*-?\d+\s*,\s*["']?${Regex.escape(name)}["']?\s*:\s*["']([^"']*)"""
            ).find(body)?.groupValues?.getOrNull(1)

        fun numericTextAfterComma(): String? =
            Regex("""^\s*-?\d+\s*,\s*(-?\d{1,32})(?=\s*[\]},])""")
                .find(body)?.groupValues?.getOrNull(1)

        return when (actionName) {
            "input_text" -> {
                val index = intAtStart() ?: return null
                val text = namedStringAfterComma("text")
                    ?: quotedAfterComma()
                    ?: numericTextAfterComma()
                    ?: return null
                buildJsonObject { put("index", index); put("text", text) }
            }
            "select_dropdown_option" -> {
                val index = intAtStart() ?: return null
                val text = namedStringAfterComma("text") ?: quotedAfterComma() ?: return null
                buildJsonObject { put("index", index); put("text", text) }
            }
            "pick_date" -> {
                val index = intAtStart() ?: return null
                val date = namedStringAfterComma("date") ?: quotedAfterComma() ?: return null
                buildJsonObject { put("index", index); put("date", date) }
            }
            "click_element_by_index", "toggle_checkbox", "choose_radio" -> {
                val index = intAtStart() ?: return null
                buildJsonObject { put("index", index) }
            }
            else -> null
        }
    }

    private fun extractBalancedObject(text: String, start: Int): String? {
        if (start !in text.indices || text[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val c = text[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') inString = !inString
            if (inString) continue
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
        return null
    }

    private fun extractJsonObject(raw: String): String? {
        val unfenced = raw
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = unfenced.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until unfenced.length) {
            val c = unfenced[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') inString = !inString
            if (inString) continue
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return unfenced.substring(start, index + 1)
            }
        }
        // A missing opening quote on a property name leaves the scanner "inside" a string and
        // therefore prevents the balanced-object path above from finding the final brace. Keep
        // this fallback deliberately narrow: take only the outermost object-looking range, then
        // let repairStructuralJson and the typed parser validate it.
        val lastBrace = unfenced.lastIndexOf('}')
        return if (lastBrace > start) unfenced.substring(start, lastBrace + 1) else null
    }

    internal fun repairStructuralJson(raw: String): String {
        // LiteRT-LM occasionally surfaces its internal quote token literally in generated text.
        // Normalize only this exact structural token; never apply broad substitutions to values.
        var repaired = raw.replace("""<|"|>""", "\"")
        // Another observed tokenizer artifact is an escaped delimiter outside a JSON string:
        // `"text":\"value\"`. Normalize only the opening quote immediately after a colon and the
        // closing quote immediately before a comma/brace.
        repaired = Regex("""(:\s*)\\"""").replace(repaired) { match ->
            "${match.groupValues[1]}\""
        }
        repaired = Regex("""\\"(\s*[,}])""").replace(repaired) { match ->
            "\"${match.groupValues[1]}"
        }
        for (key in STRUCTURAL_KEYS) {
            val token = Regex.escape(key)
            // Missing opening quote: { input_text": {...} }
            repaired = Regex("""([,{]\s*)$token"\s*:""").replace(repaired) { match ->
                "${match.groupValues[1]}\"$key\":"
            }
            // Fully unquoted key. Lenient JSON accepts this, but quoting it makes the repaired
            // fallback deterministic across kotlinx.serialization versions.
            repaired = Regex("""([,{]\s*)$token\s*:""").replace(repaired) { match ->
                "${match.groupValues[1]}\"$key\":"
            }
            // Missing colon after a correctly quoted structural key: "action" {...}
            repaired = Regex("""([,{]\s*)"$token"\s+(?=[{\[])""").replace(repaired) { match ->
                "${match.groupValues[1]}\"$key\":"
            }
        }
        return repaired
    }

    private fun extractText(message: Message): String? =
        // Concatenate ALL text fragments, not just the first (see GemmaPlanner.extractText).
        ResponseTextJoiner.join(
            message.contents?.contents?.map { (it as? Content.Text)?.text } ?: emptyList()
        )

    /**
     * Retain only JSON punctuation for diagnostics. All letters, digits and non-ASCII content are
     * replaced, so browser text, prompts, form values and model prose can never enter logs.
     */
    internal fun structuralShape(raw: String): String =
        raw.take(MAX_STRUCTURAL_LOG_CHARS).map { char ->
            when {
                char.isLetterOrDigit() || char.code > 127 -> 'x'
                else -> char
            }
        }.joinToString("")

    companion object {
        const val INFERENCE_TIMEOUT_MS = 45_000L
        // Conservative character budget for Gemma 4 E2B's 4096-token input context. PageAgent's
        // user prompt is ASCII-heavy markup, so a 6000-character cap plus the compact instructions
        // leaves substantial tokenizer and output headroom.
        const val MAX_PAGE_CONTEXT_CHARS = 6_000
        private const val PAGE_CONTEXT_HEAD_CHARS = 2_200
        private const val MAX_STRUCTURAL_LOG_CHARS = 1_200
        private const val MAX_RECOVERED_FIELD_VALUE_CHARS = 120

        private const val COMPACT_ACTION_SCHEMA =
            "ACTION_NAME and arguments: done{text,success}; wait{seconds}; ask_user{question}; " +
                "click_element_by_index{index}; input_text{index,text}; " +
                "select_dropdown_option{index,text}; scroll{down,num_pages,pixels,index}; " +
                "scroll_horizontally{right,pixels,index}; toggle_checkbox{index}; " +
                "choose_radio{index}; pick_date{index,date}; submit_form{index,purpose}; " +
                "upload_file{index,purpose}. " +
                "Use only an indexed element visible in the DOM."

        val ALLOWED_ACTIONS: Set<String> = linkedSetOf(
            "done",
            "wait",
            "ask_user",
            "click_element_by_index",
            "input_text",
            "select_dropdown_option",
            "scroll",
            "scroll_horizontally",
            "toggle_checkbox",
            "choose_radio",
            "pick_date",
            "submit_form",
            "upload_file"
        )

        private val INDEXED_ACTIONS: Set<String> = setOf(
            "click_element_by_index",
            "input_text",
            "select_dropdown_option",
            "toggle_checkbox",
            "choose_radio",
            "pick_date",
            "submit_form",
            "upload_file"
        )

        private val STRUCTURAL_KEYS: Set<String> =
            ALLOWED_ACTIONS + setOf(
                "evaluation_previous_goal",
                "memory",
                "next_goal",
                "action",
                "index",
                "text",
                "success",
                "question",
                "seconds",
                "down",
                "num_pages",
                "pixels",
                "right",
                "date",
                "purpose"
            )

        private const val PAGE_AGENT_SYSTEM_INSTRUCTION =
            "You are UnoOne's local browser planning model operating Page Agent. " +
                "Return one strict JSON reflection/action object per turn. You propose DOM actions only; " +
                "native UnoOne policy authorizes every action before execution. Obey native allow, deny, " +
                "or takeover decisions exactly and never retry a denial. Never use JavaScript execution " +
                "or hidden actions. Stop or ask the user when authorized progress is impossible."
    }
}
