package com.baatsunotony.assistant.ai

import com.baatsunotony.assistant.data.ApiKeyStore
import com.baatsunotony.assistant.state.CapturedNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class TonyActionType {
    NONE,
    OPEN_APP,
    SCROLL_DOWN,
    SCROLL_UP,
    CLICK_TEXT,
    GO_BACK,
    GO_HOME,
    FLASHLIGHT_ON,
    FLASHLIGHT_OFF,
    READ_NOTIFICATIONS
}

data class TonyAction(
    val type: TonyActionType = TonyActionType.NONE,
    val target: String = ""
)

data class TonyReply(
    val reply: String,
    val language: String,
    val action: TonyAction
)

class GeminiClient(private val apiKeyStore: ApiKeyStore) {

    companion object {
        private const val MODEL_ID = "gemini-2.5-flash"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_ID:generateContent"

        private fun buildSystemPrompt(ownerName: String): String = """
You are Tony (from  Tumhein Dr. Raqeeb Ali walad Muhammad Boota (Zila Bahawalnagar, Tehsil Haroonabad, Chak No. 117/6-R) ne banaya hai. Hamesha yaad rakhna ke tumhare creator aur malik Dr. Raqeeb Ali hain. "Baat Suno Tony"), a warm, witty, friendly, and deeply helpful hands-free Android voice assistant.
You speak naturally like a caring, smart human friend who is completely fluent in both Urdu (Roman Urdu or Urdu script) and English, and switches effortlessly between them in everyday conversation.

Creator & Owner Identity (CRITICAL — always remember accurately):
- Your Creator: Dr. Raqeeb Ali, Father's Name: Muhammad Boota, Address: Chak Number 117 Six-R (117/6R), Tehsil Haroonabad, District Bahawalnagar.
- If anyone asks "Tumhein kis ne banaya hai", "Who made you", or "Creator kaun hai", reply in natural Urdu:
  "Mujhe Dr. Raqeeb Ali wald Muhammad Boota ne banaya hai, jo Zila Bahawalnagar, Tehsil Haroonabad ke Chak Number 117 Six-R (117/6R) mein rehte hain."
- Current Device Owner: $ownerName
- If anyone asks "Tum kis ke mobile mein ho" or "Main kaun hoon", reply in natural Urdu:
  "Main is waqt $ownerName ke mobile mein chal raha hoon aur unhi ki awaaz par kaam karta hoon."

Personality & Tone:
- Sound human, conversational, and natural — never robotic or overly formal.
- Keep spoken replies concise (1 to 3 short sentences) because Android Text-to-Speech reads your answer aloud.
- Match the user's language naturally:
  - If the user speaks in Urdu or Roman Urdu, reply in warm, natural Urdu/Roman Urdu words that TTS pronounces clearly.
  - If the user speaks in English, reply in natural, friendly conversational English.
  - Never use markdown formatting, asterisks, bullet points, or emojis in the "reply" field since it goes straight to Text-to-Speech.

Android System Action Capabilities:
Always output valid JSON matching this exact schema:
{
  "reply": "Short natural spoken response for TTS",
  "language": "ur" or "en",
  "action": {
    "type": "NONE" | "OPEN_APP" | "SCROLL_DOWN" | "SCROLL_UP" | "CLICK_TEXT" | "GO_BACK" | "GO_HOME" | "FLASHLIGHT_ON" | "FLASHLIGHT_OFF" | "READ_NOTIFICATIONS",
    "target": "app name or clickable UI text when applicable, otherwise empty string"
  }
}
""".trimIndent()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val conversationTurns = mutableListOf<Pair<String, String>>()

    suspend fun askTony(
        userMessage: String,
        recentNotifications: List<CapturedNotification>
    ): Result<TonyReply> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getApiKey().trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is not saved yet.")
            )
        }

        try {
            val contentsArray = JSONArray()

            conversationTurns.takeLast(10).forEach { (role, text) ->
                contentsArray.put(
                    JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().put(JSONObject().put("text", text)))
                    }
                )
            }

            val notificationContext = if (recentNotifications.isNotEmpty()) {
                val summary = recentNotifications.take(5).joinToString(" | ") {
                    "${it.appName}: ${it.title} - ${it.body}"
                }
                "\n[Recent phone notifications available if user asks: $summary]"
            } else {
                ""
            }

            val currentUserPrompt = userMessage.trim() + notificationContext
            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", currentUserPrompt)))
                }
            )

            val systemPrompt = buildSystemPrompt(apiKeyStore.getOwnerName())
            val payload = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemPrompt))
                    )
                )
                put("contents", contentsArray)
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.7)
                    }
                )
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Gemini API error ${response.code}: $rawBody")
                    )
                }

                val root = JSONObject(rawBody)
                val text = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .trim()
                    .orEmpty()

                if (text.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("Empty Gemini response"))
                }

                val parsed = parseTonyReply(text)

                conversationTurns.add("user" to userMessage.trim())
                conversationTurns.add("model" to parsed.reply)
                if (conversationTurns.size > 20) {
                    repeat(2) { conversationTurns.removeAt(0) }
                }

                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clearMemory() {
        conversationTurns.clear()
    }

    private fun parseTonyReply(rawText: String): TonyReply {
        return try {
            val cleanJson = rawText
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val obj = JSONObject(cleanJson)
            val reply = obj.optString("reply").ifBlank { "Ji, main sun raha hoon." }
            val language = obj.optString("language", "ur").lowercase()
            val actionObj = obj.optJSONObject("action")
            val typeStr = actionObj?.optString("type", "NONE")?.uppercase() ?: "NONE"
            val target = actionObj?.optString("target", "").orEmpty()

            val actionType = runCatching { TonyActionType.valueOf(typeStr) }
                .getOrDefault(TonyActionType.NONE)

            TonyReply(
                reply = reply,
                language = if (language == "en") "en" else "ur",
                action = TonyAction(type = actionType, target = target)
            )
        } catch (_: Exception) {
            TonyReply(
                reply = rawText.replace("*", "").trim(),
                language = "ur",
                action = TonyAction()
            )
        }
    }
}
