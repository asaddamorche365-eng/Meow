package com.zenpx.zagent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Result of one generation: text is null when skipped, reason says why. */
class Gen(val text: String?, val reason: String)

/** The offline model. Loads the imported .task file once and writes short replies. */
class Agent(private val ctx: Context) {
    private var llm: LlmInference? = null

    val modelFile: File get() = File(ctx.filesDir, "models/model.task")
    val ready: Boolean get() = llm != null

    suspend fun load(gpu: Boolean): String = withContext(Dispatchers.Default) {
        if (llm != null) return@withContext "Model already loaded."
        val f = modelFile
        if (!f.exists()) return@withContext "No model file yet. Tap Import model."
        try {
            llm = create(f, gpu)
            "Model loaded (${if (gpu) "GPU" else "CPU"})."
        } catch (e: Throwable) {
            if (gpu) {
                try {
                    llm = create(f, false)
                    "GPU failed, loaded on CPU."
                } catch (e2: Throwable) {
                    "Load failed: ${e2.message}"
                }
            } else {
                "Load failed: ${e.message}"
            }
        }
    }

    fun unload() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
    }

    private fun create(f: File, gpu: Boolean): LlmInference {
        val b = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(f.absolutePath)
            .setMaxTokens(1536)
            .setPreferredBackend(if (gpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
        return LlmInference.createFromOptions(ctx, b.build())
    }

    suspend fun reply(rules: String, post: String, maxWords: Int): Gen = withContext(Dispatchers.Default) {
        val m = llm ?: return@withContext Gen(null, "model not loaded")
        val prompt = "<start_of_turn>user\n" +
            "You write short replies to posts on X for the account owner.\n" +
            "RULES:\n$rules\n" +
            "Write ONE reply of at most $maxWords words, in the same language as the post. " +
            "Plain single line of text only: no hashtags, no quotes, no explanation, no preamble, " +
            "and never write the characters backslash-n.\n" +
            "Always reply. Answer exactly SKIP only if the post is an ad, spam, hateful, or has no readable text.\n\n" +
            "POST:\n$post<end_of_turn>\n<start_of_turn>model\n"
        val raw = try { m.generateResponse(prompt) } catch (e: Throwable) {
            return@withContext Gen(null, "model error: ${e.message}")
        }
        clean(raw, maxWords)
    }

    private fun clean(raw: String, maxWords: Int): Gen {
        // the model sometimes writes a literal backslash-n; turn it into a real line break first
        val first = raw.replace("\\n", "\n").replace("\\r", " ").lines()
            .map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return Gen(null, "model gave an empty answer")
        if (first.uppercase().startsWith("SKIP")) return Gen(null, "model said SKIP")
        var t = first.replace(Regex("^(reply|comment)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        t = t.trim('"', '\'', '`', '\u201C', '\u201D')
        t = t.split(" ").filter { !it.startsWith("#") }.joinToString(" ").trim()
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return Gen(null, "answer too short: ${first.take(40)}")
        if (words.size > maxWords) t = words.take(maxWords).joinToString(" ").trimEnd(',', ';', ':', '-')
        return Gen(t, "")
    }
}
