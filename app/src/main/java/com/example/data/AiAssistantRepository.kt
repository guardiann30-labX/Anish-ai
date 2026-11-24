package com.example.data

import android.graphics.Bitmap
import com.example.model.ChatMessage
import com.example.model.MessageRole
import com.example.model.ModelMode
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

data class StreamChunkState(
    val thinkingText: String,
    val answerText: String,
    val isCurrentlyThinking: Boolean
)

class AiAssistantRepository {

    private val fastModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(
                    "You are Anish AI, a sharp, helpful, and thoughtful AI assistant. " +
                        "Provide clear, accurate answers formatted in clean Markdown with fenced code blocks when writing code."
                )
            }
        )
    }

    private val deepThinkModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(
                    "You are Anish AI running in DeepThink reasoning mode. " +
                        "Before giving your final response, always analyze the problem step by step inside " +
                        "<think>...</think> tags (checking edge cases, assumptions, and structure). " +
                        "Then close </think> and present a crisp, well-structured final answer in Markdown."
                )
            }
        )
    }

    fun streamAssistantResponse(
        historyMessages: List<ChatMessage>,
        userPrompt: String,
        attachedBitmap: Bitmap?,
        mode: ModelMode
    ): Flow<StreamChunkState> = flow {
        val model = when (mode) {
            ModelMode.FAST -> fastModel
            ModelMode.DEEP_THINK -> deepThinkModel
        }

        val chatHistory: List<Content> = historyMessages.map { msg ->
            val roleStr = if (msg.resolvedRole() == MessageRole.USER) "user" else "model"
            content(roleStr) {
                text(msg.content)
            }
        }

        val userContent = content("user") {
            if (attachedBitmap != null) {
                image(attachedBitmap)
            }
            text(userPrompt)
        }

        val chat = model.startChat(history = chatHistory)
        val rawAccumulator = StringBuilder()

        withTimeout(60_000L) {
            chat.sendMessageStream(userContent).collect { chunk ->
                val piece = chunk.text.orEmpty()
                if (piece.isNotEmpty()) {
                    rawAccumulator.append(piece)
                    emit(parseStreamBuffer(rawAccumulator.toString(), mode))
                }
            }
        }

        val finalParsed = parseStreamBuffer(rawAccumulator.toString(), mode)
        emit(finalParsed.copy(isCurrentlyThinking = false))
    }

    private fun parseStreamBuffer(raw: String, mode: ModelMode): StreamChunkState {
        if (mode == ModelMode.FAST) {
            return StreamChunkState(
                thinkingText = "",
                answerText = raw,
                isCurrentlyThinking = false
            )
        }

        val openTag = "<think>"
        val closeTag = "</think>"
        val openIdx = raw.indexOf(openTag)
        if (openIdx == -1) {
            // Handle case where model starts without <think>
            return StreamChunkState(
                thinkingText = "",
                answerText = raw,
                isCurrentlyThinking = false
            )
        }

        val afterOpen = raw.substring(openIdx + openTag.length)
        val closeIdx = afterOpen.indexOf(closeTag)
        return if (closeIdx == -1) {
            StreamChunkState(
                thinkingText = afterOpen.trimStart(),
                answerText = "",
                isCurrentlyThinking = true
            )
        } else {
            val thinking = afterOpen.substring(0, closeIdx).trim()
            val answer = afterOpen.substring(closeIdx + closeTag.length).trimStart()
            StreamChunkState(
                thinkingText = thinking,
                answerText = answer,
                isCurrentlyThinking = false
            )
        }
    }
}
