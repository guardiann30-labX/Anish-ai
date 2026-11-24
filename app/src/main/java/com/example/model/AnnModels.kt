package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class ThemePreference(val storageKey: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemePreference =
            entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

enum class ModelMode(val wireValue: String) {
    FAST("FAST"),
    DEEP_THINK("DEEP_THINK");

    companion object {
        fun fromWire(value: String?): ModelMode =
            entries.firstOrNull { it.wireValue == value } ?: FAST
    }
}

enum class MessageRole(val wireValue: String) {
    USER("USER"),
    ASSISTANT("ASSISTANT");

    companion object {
        fun fromWire(value: String?): MessageRole =
            entries.firstOrNull { it.wireValue == value } ?: ASSISTANT
    }
}

@Keep
@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val lastMessagePreview: String = "",
    val modelMode: String = ModelMode.FAST.wireValue,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolvedMode(): ModelMode = ModelMode.fromWire(modelMode)
}

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val conversationId: String = "",
    val role: String = MessageRole.USER.wireValue,
    val content: String = "",
    val thinkingContent: String? = null,
    val modelMode: String = ModelMode.FAST.wireValue,
    val hasImage: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun resolvedRole(): MessageRole = MessageRole.fromWire(role)
    fun resolvedMode(): ModelMode = ModelMode.fromWire(modelMode)
}

data class UserPreferences(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val hapticsEnabled: Boolean = true
)

data class StarterPrompt(
    val title: String,
    val prompt: String,
    val recommendedMode: ModelMode
)
