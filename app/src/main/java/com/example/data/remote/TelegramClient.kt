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
    val fromUserId: Long = 0L,
    val text: String = "",
    val fromUsername: String = "",
    val chatType: String = "",
    val callbackQueryId: String? = null,
    val callbackData: String? = null
)

object TelegramClient {

    private val channelChatCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun buildChannelCommandInlineKeyboard(): JSONArray {
        return JSONArray().apply {
            put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "💡 Перевірити наявність")
                    put("callback_data", "cmd_status")
                })
            })
            put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "📊 Статистика")
                    put("callback_data", "cmd_stats")
                })
                put(JSONObject().apply {
                    put("text", "ℹ️ Інфо")
                    put("callback_data", "cmd_info")
                })
            })
        }
    }

    private suspend fun isChannelChat(botToken: String, chatId: String): Boolean = withContext(Dispatchers.IO) {
        val cleanId = chatId.trim()
        channelChatCache[cleanId]?.let { return@withContext it }
        var connection: HttpURLConnection? = null
        try {
            val cleanToken = botToken.trim()
            val encodedChatId = java.net.URLEncoder.encode(cleanId, "UTF-8")
            val url = URL("https://api.telegram.org/bot$cleanToken/getChat?chat_id=$encodedChatId")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (connection.responseCode in 200..299) {
                val resp = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                val type = json.optJSONObject("result")?.optString("type", "") ?: ""
                val isChannel = type == "channel"
                channelChatCache[cleanId] = isChannel
                return@withContext isChannel
            }
        } catch (e: Exception) {
            // Do not cache transient failures
        } finally {
            connection?.disconnect()
        }
        false
    }

    suspend fun deleteWebhook(botToken: String): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext false
        val cleanToken = botToken.trim()
        val urlString = "https://api.telegram.org/bot$cleanToken/deleteWebhook"
        val payload = JSONObject().apply {
            put("drop_pending_updates", false)
        }
        postJson(urlString, payload, 8000).isSuccess
    }

    private suspend fun postJson(urlString: String, payload: JSONObject, timeoutMs: Int = 12000): Result<String> =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                }
                OutputStreamWriter(connection.outputStream, "UTF-8").use {
                    it.write(payload.toString())
                    it.flush()
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    val resp = connection.inputStream.bufferedReader().use { it.readText() }
                    Result.success(resp)
                } else {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Код $code"
                    Result.failure(Exception("Telegram API ($code): $err"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                connection?.disconnect()
            }
        }

    suspend fun sendMessage(
        botToken: String,
        chatId: String,
        text: String,
        withKeyboard: Boolean = false,
        forceInlineForChannel: Boolean? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Bot Token або Chat ID порожні"))
        }

        val cleanToken = botToken.trim()
        val cleanChatId = chatId.trim()
        val urlString = "https://api.telegram.org/bot$cleanToken/sendMessage"

        val useInline = withKeyboard && (forceInlineForChannel ?: isChannelChat(cleanToken, cleanChatId))

        fun buildPayload(useMarkdown: Boolean): JSONObject = JSONObject().apply {
            put("chat_id", cleanChatId)
            put("text", text)
            if (useMarkdown) {
                put("parse_mode", "Markdown")
            }
            put("disable_web_page_preview", true)

            if (withKeyboard) {
                if (useInline) {
                    put("reply_markup", JSONObject().apply {
                        put("inline_keyboard", buildChannelCommandInlineKeyboard())
                    })
                } else {
                    val keyboard = JSONArray().apply {
                        put(JSONArray().apply {
                            put(JSONObject().apply { put("text", "💡 Перевірити наявність") })
                        })
                        put(JSONArray().apply {
                            put(JSONObject().apply { put("text", "📊 Статистика") })
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
        }

        var res = postJson(urlString, buildPayload(useMarkdown = true))
        if (res.isFailure) {
            val errMsg = res.exceptionOrNull()?.message ?: ""
            if (errMsg.contains("400") && (errMsg.contains("parse", ignoreCase = true) || errMsg.contains("entity", ignoreCase = true))) {
                res = postJson(urlString, buildPayload(useMarkdown = false))
            }
        }
        if (res.isSuccess) Result.success(true)
        else Result.failure(res.exceptionOrNull() ?: Exception("Помилка відправки"))
    }

    suspend fun sendInlineMessage(
        botToken: String,
        chatId: String,
        text: String,
        inlineKeyboard: JSONArray
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Bot Token або Chat ID порожні"))
        }

        val cleanToken = botToken.trim()
        val cleanChatId = chatId.trim()
        val urlString = "https://api.telegram.org/bot$cleanToken/sendMessage"

        fun buildPayload(useMarkdown: Boolean) = JSONObject().apply {
            put("chat_id", cleanChatId)
            put("text", text)
            if (useMarkdown) {
                put("parse_mode", "Markdown")
            }
            put("disable_web_page_preview", true)
            put("reply_markup", JSONObject().apply {
                put("inline_keyboard", inlineKeyboard)
            })
        }

        var res = postJson(urlString, buildPayload(useMarkdown = true))
        if (res.isFailure) {
            val errMsg = res.exceptionOrNull()?.message ?: ""
            if (errMsg.contains("400") && errMsg.contains("parse", ignoreCase = true)) {
                res = postJson(urlString, buildPayload(useMarkdown = false))
            }
        }
        if (res.isSuccess) Result.success(true)
        else Result.failure(res.exceptionOrNull() ?: Exception("Помилка відправки"))
    }

    suspend fun editMessageText(
        botToken: String,
        chatId: Long,
        messageId: Long,
        text: String,
        inlineKeyboard: JSONArray? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext Result.failure(IllegalArgumentException("Bot Token порожній"))

        val cleanToken = botToken.trim()
        val urlString = "https://api.telegram.org/bot$cleanToken/editMessageText"

        fun buildPayload(useMarkdown: Boolean) = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
            if (useMarkdown) {
                put("parse_mode", "Markdown")
            }
            put("disable_web_page_preview", true)
            if (inlineKeyboard != null) {
                put("reply_markup", JSONObject().apply {
                    put("inline_keyboard", inlineKeyboard)
                })
            }
        }

        var res = postJson(urlString, buildPayload(useMarkdown = true))
        if (res.isFailure) {
            val errMsg = res.exceptionOrNull()?.message ?: ""
            if (errMsg.contains("400") && errMsg.contains("parse", ignoreCase = true)) {
                res = postJson(urlString, buildPayload(useMarkdown = false))
            }
        }
        if (res.isSuccess) Result.success(true)
        else Result.failure(res.exceptionOrNull() ?: Exception("Помилка редагування"))
    }

    suspend fun answerCallbackQuery(
        botToken: String,
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || callbackQueryId.isBlank()) return@withContext false
        val cleanToken = botToken.trim()
        val urlString = "https://api.telegram.org/bot$cleanToken/answerCallbackQuery"
        val payload = JSONObject().apply {
            put("callback_query_id", callbackQueryId)
            if (!text.isNullOrBlank()) {
                put("text", text)
                put("show_alert", showAlert)
            }
        }
        postJson(urlString, payload, 5000).isSuccess
    }

    suspend fun deleteMessage(botToken: String, chatId: Long, messageId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (botToken.isBlank() || chatId == 0L || messageId == 0L) return@withContext false
            val cleanToken = botToken.trim()
            val urlString = "https://api.telegram.org/bot$cleanToken/deleteMessage"
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("message_id", messageId)
            }
            postJson(urlString, payload, 5000).isSuccess
        }

    suspend fun setBotCommands(
        botToken: String,
        adminIds: List<Long>
    ): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext false
        val cleanToken = botToken.trim()
        val baseCmds = JSONArray().apply {
            put(JSONObject().apply {
                put("command", "status")
                put("description", "💡 Перевірити наявність")
            })
            put(JSONObject().apply {
                put("command", "stats")
                put("description", "📊 Статистика")
            })
            put(JSONObject().apply {
                put("command", "info")
                put("description", "ℹ️ Інфо")
            })
        }

        // Default scope (channels, users)
        postJson(
            "https://api.telegram.org/bot$cleanToken/setMyCommands",
            JSONObject().apply {
                put("commands", baseCmds)
                put("scope", JSONObject().apply { put("type", "default") })
            }
        )

        // All group chats
        postJson(
            "https://api.telegram.org/bot$cleanToken/setMyCommands",
            JSONObject().apply {
                put("commands", baseCmds)
                put("scope", JSONObject().apply { put("type", "all_group_chats") })
            }
        )

        // Admin scope
        val adminCmds = JSONArray().apply {
            put(JSONObject().apply {
                put("command", "status")
                put("description", "💡 Перевірити наявність")
            })
            put(JSONObject().apply {
                put("command", "stats")
                put("description", "📊 Статистика")
            })
            put(JSONObject().apply {
                put("command", "set")
                put("description", "⚙️ Налаштування")
            })
            put(JSONObject().apply {
                put("command", "info")
                put("description", "ℹ️ Інфо")
            })
        }

        for (adminId in adminIds) {
            if (adminId != 0L) {
                postJson(
                    "https://api.telegram.org/bot$cleanToken/setMyCommands",
                    JSONObject().apply {
                        put("commands", adminCmds)
                        put("scope", JSONObject().apply {
                            put("type", "chat")
                            put("chat_id", adminId)
                        })
                    }
                )
            }
        }
        true
    }

    suspend fun getMe(botToken: String): JSONObject? = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            val cleanToken = botToken.trim()
            val url = URL("https://api.telegram.org/bot$cleanToken/getMe")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (connection.responseCode in 200..299) {
                val resp = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                if (json.optBoolean("ok", false)) json.optJSONObject("result") else null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun getUpdates(botToken: String, offset: Long, timeoutSeconds: Int = 15): List<TgUpdate> =
        withContext(Dispatchers.IO) {
            if (botToken.isBlank()) return@withContext emptyList()
            var connection: HttpURLConnection? = null
            val updates = mutableListOf<TgUpdate>()
            try {
                val cleanToken = botToken.trim()
                // Explicitly request channel posts, messages, and callback queries
                val allowedUpdates = "%5B%22message%22%2C%22edited_message%22%2C%22channel_post%22%2C%22edited_channel_post%22%2C%22callback_query%22%5D"
                val urlString = "https://api.telegram.org/bot$cleanToken/getUpdates?offset=$offset&timeout=$timeoutSeconds&allowed_updates=$allowedUpdates"
                val url = URL(urlString)

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = (timeoutSeconds + 10) * 1000
                    readTimeout = (timeoutSeconds + 10) * 1000
                }

                val code = connection.responseCode
                if (code in 200..299) {
                    val resp = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(resp)
                    if (json.optBoolean("ok", false)) {
                        val result = json.optJSONArray("result") ?: JSONArray()
                        for (i in 0 until result.length()) {
                            val item = result.optJSONObject(i) ?: continue
                            val updateId = item.optLong("update_id", 0)

                            // 1. Callback Query (inline buttons)
                            val cbObj = item.optJSONObject("callback_query")
                            if (cbObj != null) {
                                val queryId = cbObj.optString("id", "")
                                val from = cbObj.optJSONObject("from")
                                val fromUserId = from?.optLong("id", 0L) ?: 0L
                                val fromUser = from?.optString("username", "") ?: ""
                                val msgObj = cbObj.optJSONObject("message")
                                val messageId = msgObj?.optLong("message_id", 0L) ?: 0L
                                val chat = msgObj?.optJSONObject("chat")
                                val chatId = chat?.optLong("id", 0L) ?: fromUserId
                                val chatType = chat?.optString("type", "private") ?: "private"
                                val data = cbObj.optString("data", "")

                                if (chatId != 0L && chatType.isNotBlank()) {
                                    channelChatCache[chatId.toString()] = (chatType == "channel")
                                }

                                if (chatId != 0L && data.isNotBlank()) {
                                    updates.add(
                                        TgUpdate(
                                            updateId = updateId,
                                            messageId = messageId,
                                            chatId = chatId,
                                            fromUserId = fromUserId,
                                            text = "",
                                            fromUsername = fromUser,
                                            chatType = chatType,
                                            callbackQueryId = queryId,
                                            callbackData = data
                                        )
                                    )
                                }
                                continue
                            }

                            // 2. Message, Channel Post, Edited Message, Edited Channel Post
                            val msgObj = item.optJSONObject("message")
                                ?: item.optJSONObject("channel_post")
                                ?: item.optJSONObject("edited_message")
                                ?: item.optJSONObject("edited_channel_post")

                            if (msgObj != null) {
                                val messageId = msgObj.optLong("message_id", 0L)
                                val chat = msgObj.optJSONObject("chat")
                                val chatId = chat?.optLong("id", 0L) ?: 0L
                                val chatType = chat?.optString("type", "") ?: ""
                                val text = msgObj.optString("text", "")
                                    .ifBlank { msgObj.optString("caption", "") }
                                val from = msgObj.optJSONObject("from")
                                val fromUserId = from?.optLong("id", 0L) ?: 0L
                                val fromUser = from?.optString("username", "") ?: ""

                                if (chatId != 0L && chatType.isNotBlank()) {
                                    channelChatCache[chatId.toString()] = (chatType == "channel")
                                }

                                if (chatId != 0L && text.isNotBlank()) {
                                    updates.add(
                                        TgUpdate(
                                            updateId = updateId,
                                            messageId = messageId,
                                            chatId = chatId,
                                            fromUserId = fromUserId,
                                            text = text,
                                            fromUsername = fromUser,
                                            chatType = chatType
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    if (code == 409) {
                        // Active webhook conflicts with getUpdates - remove webhook and recover
                        deleteWebhook(cleanToken)
                    }
                    kotlinx.coroutines.delay(2000)
                }
            } catch (e: Exception) {
                kotlinx.coroutines.delay(2000)
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

            val me = getMe(botToken)
            if (me != null) {
                val botName = me.optString("first_name", "Бот")
                val username = me.optString("username", "")

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
                Result.failure(Exception("Невірний Bot Token або немає зв'язку з серверами Telegram"))
            }
        }
}
