package com.example.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AiAssistantRepository
import com.example.data.ChatRepository
import com.example.data.HapticController
import com.example.data.PreferencesRepository
import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.MessageRole
import com.example.model.ModelMode
import com.example.model.StarterPrompt
import com.example.model.ThemePreference
import com.example.model.UserPreferences
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class StreamingBubbleState(
    val conversationId: String,
    val thinkingText: String,
    val answerText: String,
    val isCurrentlyThinking: Boolean,
    val modelMode: ModelMode
)

data class AttachedImage(
    val bitmap: Bitmap,
    val label: String
)

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val aiRepository: AiAssistantRepository,
    private val preferencesRepository: PreferencesRepository,
    private val hapticController: HapticController,
    private val currentUserId: String
) : ViewModel() {

    val userPreferences: StateFlow<UserPreferences> = preferencesRepository.userPreferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UserPreferences()
        )

    private val _selectedMode = MutableStateFlow(ModelMode.DEEP_THINK)
    val selectedMode: StateFlow<ModelMode> = _selectedMode.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _streamingState = MutableStateFlow<StreamingBubbleState?>(null)
    val streamingState: StateFlow<StreamingBubbleState?> = _streamingState.asStateFlow()

    private val _aiErrorMessage = MutableStateFlow<String?>(null)
    val aiErrorMessage: StateFlow<String?> = _aiErrorMessage.asStateFlow()

    private val _attachedImage = MutableStateFlow<AttachedImage?>(null)
    val attachedImage: StateFlow<AttachedImage?> = _attachedImage.asStateFlow()

    val conversationsState: StateFlow<UiState<List<Conversation>>> =
        chatRepository.observeConversations(currentUserId)
            .map<List<Conversation>, UiState<List<Conversation>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing conversations", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "Failed to load conversations"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val messagesState: StateFlow<UiState<List<ChatMessage>>> =
        _activeConversationId.flatMapLatest { convId ->
            if (convId.isNullOrBlank()) {
                flowOf<UiState<List<ChatMessage>>>(UiState.Success(emptyList()))
            } else {
                chatRepository.observeMessages(currentUserId, convId)
                    .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing messages", rootError)
                        emit(UiState.Error(rootError.localizedMessage ?: "Failed to load messages"))
                    }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Success(emptyList())
        )

    val starterPrompts: List<StarterPrompt> = listOf(
        StarterPrompt(
            title = "Debug & Architect",
            prompt = "Compare Kotlin Flow StateFlow vs SharedFlow with practical Android Compose examples.",
            recommendedMode = ModelMode.DEEP_THINK
        ),
        StarterPrompt(
            title = "Reason Step by Step",
            prompt = "A bat and a ball cost $1.10 in total. The bat costs $1.00 more than the ball. Explain carefully why the ball is $0.05.",
            recommendedMode = ModelMode.DEEP_THINK
        ),
        StarterPrompt(
            title = "Draft & Polish",
            prompt = "Write a concise, warm release announcement for a new AI assistant feature.",
            recommendedMode = ModelMode.FAST
        ),
        StarterPrompt(
            title = "Explain Simply",
            prompt = "Explain how transformers and attention work using an intuitive library analogy.",
            recommendedMode = ModelMode.FAST
        )
    )

    private var activeGenerationJob: Job? = null

    fun setModelMode(mode: ModelMode) {
        _selectedMode.value = mode
    }

    fun selectConversation(conversationId: String) {
        if (_activeConversationId.value != conversationId) {
            stopGeneration()
            _aiErrorMessage.value = null
            _activeConversationId.value = conversationId
        }
    }

    fun startNewChat() {
        stopGeneration()
        _aiErrorMessage.value = null
        _attachedImage.value = null
        _activeConversationId.value = null
    }

    fun attachImage(bitmap: Bitmap, label: String) {
        _attachedImage.value = AttachedImage(bitmap = bitmap, label = label)
    }

    fun clearAttachedImage() {
        _attachedImage.value = null
    }

    fun dismissAiError() {
        _aiErrorMessage.value = null
    }

    fun sendMessage(rawPrompt: String) {
        val promptText = rawPrompt.trim()
        val image = _attachedImage.value
        if (promptText.isEmpty() && image == null) return
        if (_streamingState.value != null) return

        val effectivePrompt = promptText.ifEmpty { "Please analyze this image in detail." }
        val mode = _selectedMode.value
        val existingMessages = (messagesState.value as? UiState.Success)?.data ?: emptyList()

        _aiErrorMessage.value = null
        _attachedImage.value = null

        activeGenerationJob = viewModelScope.launch {
            var conversationId = _activeConversationId.value
            if (conversationId.isNullOrBlank()) {
                val created = chatRepository.createConversation(
                    userId = currentUserId,
                    title = buildConversationTitle(effectivePrompt),
                    preview = effectivePrompt,
                    mode = mode
                )
                conversationId = created.getOrElse { err ->
                    _aiErrorMessage.value = err.localizedMessage ?: "Could not create conversation."
                    return@launch
                }
                _activeConversationId.value = conversationId
            }

            val userSaveResult = chatRepository.addMessage(
                userId = currentUserId,
                conversationId = conversationId,
                role = MessageRole.USER,
                content = effectivePrompt,
                thinkingContent = null,
                mode = mode,
                hasImage = image != null
            )
            if (userSaveResult.isFailure) {
                _aiErrorMessage.value = userSaveResult.exceptionOrNull()?.localizedMessage
                    ?: "Failed to save message."
                return@launch
            }

            streamAndPersistResponse(
                conversationId = conversationId,
                history = existingMessages,
                userPrompt = effectivePrompt,
                bitmap = image?.bitmap,
                mode = mode
            )
        }
    }

    fun regenerateLastResponse() {
        if (_streamingState.value != null) return
        val convId = _activeConversationId.value ?: return
        val currentMessages = (messagesState.value as? UiState.Success)?.data ?: return
        if (currentMessages.isEmpty()) return

        val lastMessage = currentMessages.last()
        val lastUserIndex = currentMessages.indexOfLast { it.resolvedRole() == MessageRole.USER }
        if (lastUserIndex == -1) return

        val lastUserMsg = currentMessages[lastUserIndex]
        val historyBeforeTurn = currentMessages.take(lastUserIndex)
        val mode = _selectedMode.value

        _aiErrorMessage.value = null
        activeGenerationJob = viewModelScope.launch {
            if (lastMessage.resolvedRole() == MessageRole.ASSISTANT && lastMessage.id.isNotBlank()) {
                chatRepository.deleteMessage(currentUserId, convId, lastMessage.id)
            }
            streamAndPersistResponse(
                conversationId = convId,
                history = historyBeforeTurn,
                userPrompt = lastUserMsg.content,
                bitmap = null,
                mode = mode
            )
        }
    }

    fun stopGeneration() {
        val currentStream = _streamingState.value
        activeGenerationJob?.cancel()
        activeGenerationJob = null
        _streamingState.value = null

        if (currentStream != null && currentStream.answerText.isNotBlank()) {
            viewModelScope.launch {
                chatRepository.addMessage(
                    userId = currentUserId,
                    conversationId = currentStream.conversationId,
                    role = MessageRole.ASSISTANT,
                    content = currentStream.answerText,
                    thinkingContent = currentStream.thinkingText.ifBlank { null },
                    mode = currentStream.modelMode,
                    hasImage = false
                )
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            if (_activeConversationId.value == conversationId) {
                stopGeneration()
                _activeConversationId.value = null
            }
            val res = chatRepository.deleteConversation(currentUserId, conversationId)
            if (res.isFailure) {
                _aiErrorMessage.value = res.exceptionOrNull()?.localizedMessage
                    ?: "Could not delete conversation."
            }
        }
    }

    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch { preferencesRepository.setThemePreference(preference) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setHapticsEnabled(enabled) }
    }

    private suspend fun streamAndPersistResponse(
        conversationId: String,
        history: List<ChatMessage>,
        userPrompt: String,
        bitmap: Bitmap?,
        mode: ModelMode
    ) {
        _streamingState.value = StreamingBubbleState(
            conversationId = conversationId,
            thinkingText = "",
            answerText = "",
            isCurrentlyThinking = mode == ModelMode.DEEP_THINK,
            modelMode = mode
        )

        var latestThinking = ""
        var latestAnswer = ""

        try {
            aiRepository.streamAssistantResponse(
                historyMessages = history,
                userPrompt = userPrompt,
                attachedBitmap = bitmap,
                mode = mode
            ).collect { delta ->
                latestThinking = delta.thinkingText
                latestAnswer = delta.answerText
                _streamingState.value = StreamingBubbleState(
                    conversationId = conversationId,
                    thinkingText = delta.thinkingText,
                    answerText = delta.answerText,
                    isCurrentlyThinking = delta.isCurrentlyThinking,
                    modelMode = mode
                )
            }

            val finalAnswer = latestAnswer.ifBlank { latestThinking }.trim()
            if (finalAnswer.isBlank()) {
                _aiErrorMessage.value = "Anish AI returned an empty response."
            } else {
                chatRepository.addMessage(
                    userId = currentUserId,
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = finalAnswer,
                    thinkingContent = if (latestAnswer.isNotBlank()) latestThinking.ifBlank { null } else null,
                    mode = mode,
                    hasImage = false
                )
                chatRepository.updateConversationMeta(
                    userId = currentUserId,
                    conversationId = conversationId,
                    preview = finalAnswer.replace("\n", " "),
                    mode = mode
                )
                if (userPreferences.value.hapticsEnabled) {
                    hapticController.pulseSoft()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI generation timed out", e)
            _aiErrorMessage.value = "Response timed out after 60 seconds. Please try again."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI generation failed", e)
            _aiErrorMessage.value = e.localizedMessage ?: "Failed to generate response."
        } finally {
            _streamingState.value = null
            activeGenerationJob = null
        }
    }

    private fun buildConversationTitle(prompt: String): String {
        val cleaned = prompt.replace("\n", " ").trim()
        return if (cleaned.length <= 42) cleaned else cleaned.take(39).trimEnd() + "…"
    }

    private companion object {
        const val TAG = "ChatViewModel"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
