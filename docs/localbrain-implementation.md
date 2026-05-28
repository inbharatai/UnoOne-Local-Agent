# LocalBrain Implementation Plan

## Overview

The `localbrain` module loads a Gemma-compatible local LLM and runs inference on-device to convert user commands into structured `ToolCall` JSON.

## Architecture

```
┌─────────────────────────────────────────┐
│            LocalBrain.kt                  │
│  (load, infer, parse)                   │
└─────────────────────────────────────────┘
                   │
    ┌──────────────┼──────────────┐
    ▼              ▼              ▼
┌────────┐   ┌──────────┐   ┌──────────┐
│ LiteRT │   │ Prompt   │   │ JSON     │
│ Model  │   │ Builder  │   │ Parser   │
│ Loader │   │          │   │          │
└────────┘   └──────────┘   └──────────┘
```

## Step 1: Model Loading

**File:** `localbrain/src/main/java/com/unoone/agent/localbrain/ModelLoader.kt`

```kotlin
class ModelLoader(private val modelPath: String) {
    private var interpreter: Interpreter? = null

    fun load(): Result<Unit> {
        return try {
            val file = File(modelPath)
            if (!file.exists()) {
                return Result.Error("Model file not found at $modelPath")
            }
            val options = Interpreter.Options().apply {
                numThreads = 4
                useXNNPACK = true
            }
            interpreter = Interpreter(file, options)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("Failed to load model", e)
        }
    }

    fun unload() {
        interpreter?.close()
        interpreter = null
    }
}
```

**Model format:**
- Gemma 2B IT quantized to TFLite (`.tflite`) or LiteRT format.
- Alternatively: `gguf` via `llama.cpp` Android JNI bindings.

**Recommended path:**
- Download Gemma 2B IT from Kaggle / Hugging Face.
- Convert to TFLite using Google AI Edge tools or use pre-converted `.tflite`.
- Place in `models/gemma-local/gemma-2b-it-cpu-int4.bin` (or `.tflite`).

## Step 2: Prompt Template

**File:** `localbrain/src/main/java/com/unoone/agent/localbrain/PromptBuilder.kt`

```kotlin
object PromptBuilder {
    fun build(command: String, availableTools: List<String>): String {
        return """
            You are UnoOne, a local AI agent that controls an Android phone safely.
            Available tools: ${availableTools.joinToString(", ")}
            Respond ONLY with a JSON object in this exact format:
            {"tool": "tool_name", "args": {}}
            User command: $command
            JSON:
        """.trimIndent()
    }
}
```

## Step 3: Inference

```kotlin
fun runInference(prompt: String): Result<String> {
    val tokenizer = // load tokenizer
    val inputIds = tokenizer.encode(prompt)
    // Run interpreter
    val outputIds = interpreter.run(inputIds)
    val rawOutput = tokenizer.decode(outputIds)
    return Result.Success(rawOutput)
}
```

## Step 4: JSON Parsing

Already implemented in `LocalBrain.kt` — `parseToolCall(output: String)`.

**Safety checks:**
- Must contain `"tool"` and `"args"`.
- Tool name must exist in registry.
- Args must be a JSON object, not array or primitive.

## Step 5: Prompt Caching / Context Window

- Keep last 3 turns of conversation in memory.
- Inject relevant `MemoryModule` context if available.
- Truncate if prompt exceeds model's context limit (2048 tokens for Gemma 2B).

## Testing

**Test 1: Text command**
- Input: `"Create a note: buy milk tomorrow."`
- Expected output:
  ```json
  {"tool":"create_note","args":{"title":"Buy milk","content":"Buy milk tomorrow","tags":["shopping"]}}
  ```

**Test 2: Open app**
- Input: `"Open WhatsApp."`
- Expected output:
  ```json
  {"tool":"open_app","args":{"app_name":"WhatsApp","package_name":"com.whatsapp"}}
  ```

## Acceptance Criteria

- [ ] Model loads from local file within 5 seconds.
- [ ] Inference completes within 2 seconds for short prompts.
- [ ] JSON output is valid and contains `tool` + `args`.
- [ ] Parser gracefully handles malformed output.
- [ ] Works offline after model is installed.
