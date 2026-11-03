package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class TgUpdate(
    val updateId: Long,
    val messageId: Long,
    val chatId: Long,
    val text: String,
    val fromUsername: String
)

object TelegramClient {

    suspend fun sendMessage(
        botToken: String,
        chatId: String,
        text: String,
        withKeyboard: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Bot Token або Chat ID порожні"))
        }

        var connection: HttpURLConnection? = null
        try {
            val cleanToken = botToken.trim()
            val cleanChatId = chatId.trim()
            val urlString = "https://api.telegram.org/bot$cleanToken/sendMessage"
            val url = URL(urlString)

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject().apply {
                put("chat_id", cleanChatId)
                put("text", text)
                put("parse_mode", "Markdown")
                put("disable_web_page_preview", true)

                if (withKeyboard) {
                    val keyboard = JSONArray().apply {
                        put(JSONArray().apply {
                            put(JSONObject().apply { put("text", "💡 Перевірити наявність") })
                        })
                        put(JSONArray().apply {
                            put(JSONObject().apply { put("text", "📊 Графік") })
                            put(JSONObject().apply { put("text", "ℹ️ Інфо") })
                        })
                    }

                    put("reply_markup", JSONObject().apply {
                        put("keyboard", keyboard)
                        put("resize_keyboard", true)
                        put("is_persistent", true)
                    })
                }
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = connection.responseCode
            if (code in 200..299) {
                Result.success(true)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Код $code"
                Result.failure(Exception("Помилка Telegram API ($code): $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun deleteMessage(botToken: String, chatId: Long, messageId: Long): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext false
        var connection: HttpURLConnection? = null
        try {
            val cleanToken = botToken.trim()
            val url = URL("https://api.telegram.org/bot$cleanToken/deleteMessage")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("message_id", messageId)
            }
            OutputStreamWriter(connection.outputStream, "UTF-8").use {
                it.write(payload.toString())
                it.flush()
            }
            connection.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun getUpdates(botToken: String, offset: Long, timeoutSeconds: Int = 20): List<TgUpdate> =
        withContext(Dispatchers.IO) {
            if (botToken.isBlank()) return@withContext emptyList()
            var connection: HttpURLConnection? = null
            val updates = mutableListOf<TgUpdate>()
            try {
                val cleanToken = botToken.trim()
                val urlString = "https://api.telegram.org/bot$cleanToken/getUpdates?offset=$offset&timeout=$timeoutSeconds"
                val url = URL(urlString)

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = (timeoutSeconds + 10) * 1000
                    readTimeout = (timeoutSeconds + 10) * 1000
                }

                if (connection.responseCode in 200..299) {
                    val resp = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(resp)
                    if (json.optBoolean("ok", false)) {
                        val result = json.optJSONArray("result") ?: JSONArray()
                        for (i in 0 until result.length()) {
                            val item = result.optJSONObject(i) ?: continue
                            val updateId = item.optLong("update_id", 0)
                            val message = item.optJSONObject("message")
                            if (message != null) {
                                val messageId = message.optLong("message_id", 0)
                                val chat = message.optJSONObject("chat")
                                val chatId = chat?.optLong("id", 0) ?: 0
                                val text = message.optString("text", "")
                                val from = message.optJSONObject("from")
                                val fromUser = from?.optString("username", "") ?: ""

                                if (chatId != 0L && text.isNotBlank()) {
                                    updates.add(
                                        TgUpdate(
                                            updateId = updateId,
                                            messageId = messageId,
                                            chatId = chatId,
                                            text = text,
                                            fromUsername = fromUser
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore transient network errors during long-polling
            } finally {
                connection?.disconnect()
            }
            updates
        }

    suspend fun testConnection(botToken: String, chatId: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (botToken.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Введіть Bot Token"))
            }

            var connection: HttpURLConnection? = null
            try {
                val cleanToken = botToken.trim()
                val url = URL("https://api.telegram.org/bot$cleanToken/getMe")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                val code = connection.responseCode
                if (code in 200..299) {
                    val resp = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(resp)
                    val result = json.optJSONObject("result")
                    val botName = result?.optString("first_name", "Бот") ?: "Бот"
                    val username = result?.optString("username", "") ?: ""

                    // If Chat ID is also provided, send a test ping message
                    if (chatId.isNotBlank()) {
                        val testMsg = "🤖 *PowerBot Android*\n\n✅ Зв'язок з додатком встановлено успішно!\n📱 Тестове сповіщення працює справно."
                        val sendResult = sendMessage(botToken, chatId, testMsg, withKeyboard = true)
                        if (sendResult.isSuccess) {
                            Result.success("Бот @$username знайдено! Меню та тестове повідомлення надіслано в чат.")
                        } else {
                            Result.success("Бот @$username авторизовано, але не вдалося надіслати в чат: ${sendResult.exceptionOrNull()?.message}")
                        }
                    } else {
                        Result.success("Бот @$username ($botName) успішно підключено!")
                    }
                } else {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Код $code"
                    Result.failure(Exception("Невірний Bot Token ($err)"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                connection?.disconnect()
            }
        }
}
