package org.sterling.assistant

/*
 * OpenRouter hosts many models, some free to use (rate-limited). Free models
 * change over time - check the current list before shipping:
 *     https://openrouter.ai/models?max_price=0
 * Any model id ending in ":free" is a free-tier model, e.g.
 *     "meta-llama/llama-3.1-8b-instruct:free"
 * You need an API key from: https://openrouter.ai/keys
 *
 * Tool-calling reliability depends on the model - not every free model
 * honors the "tools" parameter consistently. If tools never seem to fire,
 * try a different :free model from the link above.
 */

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenRouterException(message: String) : Exception(message)

class OpenRouterClient(
    private val apiKey: String,
    private val model: String,
    private val toolsSchema: JSONArray? = null,
    private val toolExecutor: ((String, JSONObject) -> String)? = null,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<JSONObject>()
    private val maxHistoryTurns = 6

    private val systemPrompt = """
        You are Sterling, a helpful voice assistant running on someone's phone.
        Your replies are converted to speech and read aloud, so keep them short,
        natural, and conversational. Avoid lists, markdown, or long paragraphs.
        You have tools available for web search, looking up phone contacts, and
        browsing/reading files on the phone's shared storage. Use a tool whenever
        the answer needs current information, a phone number, or the contents of
        a file you don't already know - otherwise just answer directly from what
        you know.
    """.trimIndent()

    fun chat(userText: String, remember: Boolean = true): String {
        if (apiKey.isBlank()) throw OpenRouterException("No OpenRouter API key configured.")

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { messages.put(it) }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        var reply = "Sorry, I got stuck using a tool too many times. Try asking differently."

        for (hop in 0 until 4) {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                if (toolsSchema != null) {
                    put("tools", toolsSchema)
                    put("tool_choice", "auto")
                }
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://sterling.local")
                .header("X-Title", "Sterling Voice Assistant")
                .post(body)
                .build()

            val message: JSONObject
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.code == 401) throw OpenRouterException("OpenRouter rejected the API key (401).")
                if (resp.code == 429) throw OpenRouterException("Rate limited by OpenRouter's free tier (429). Try again shortly.")
                if (!resp.isSuccessful) throw OpenRouterException("OpenRouter error ${resp.code}: ${text.take(200)}")

                val data = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw OpenRouterException("Unexpected OpenRouter response: ${text.take(200)}")
                }
                message = try {
                    data.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                } catch (e: Exception) {
                    throw OpenRouterException("Unexpected OpenRouter response shape: ${text.take(200)}")
                }
            }

            val toolCalls = message.optJSONArray("tool_calls")

            if (toolCalls != null && toolCalls.length() > 0 && toolExecutor != null) {
                messages.put(message)
                for (i in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(i)
                    val fn = call.getJSONObject("function")
                    val name = fn.getString("name")
                    val args = try {
                        JSONObject(fn.optString("arguments", "{}"))
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    val result = try {
                        toolExecutor.invoke(name, args)
                    } catch (e: Exception) {
                        JSONObject().put("error", "tool '$name' failed: ${e.message}").toString()
                    }
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.optString("id", ""))
                            .put("content", result)
                    )
                }
                continue // send tool results back for a final answer
            } else {
                reply = message.optString("content", "").trim()
                break
            }
        }

        if (remember) {
            history.add(JSONObject().put("role", "user").put("content", userText))
            history.add(JSONObject().put("role", "assistant").put("content", reply))
            while (history.size > maxHistoryTurns * 2) history.removeAt(0)
        }

        return reply
    }
}
