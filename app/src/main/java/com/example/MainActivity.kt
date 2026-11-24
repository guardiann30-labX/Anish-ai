package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.AiAssistantRepository
import com.example.data.ChatRepository
import com.example.data.HapticController
import com.example.data.PreferencesRepository
import com.example.data.configureAppCheck
import com.example.ui.ChatViewModel
import com.example.ui.auth.AuthScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.theme.AnnTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check with launch intent before any UI, ViewModels, or Firebase calls.
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            AnishAiRoot()
        }
    }
}

@Composable
private fun AnishAiRoot() {
    val auth = remember { Firebase.auth }
    var currentUser by remember { mutableStateOf(auth.currentUser) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        AnnTheme {
            AuthScreen(
                onAuthSuccess = { currentUser = auth.currentUser }
            )
        }
    } else {
        val uid = user.uid
        key(uid) {
            val chatViewModel: ChatViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY]) {
                            "APPLICATION_KEY missing from CreationExtras"
                        }
                        ChatViewModel(
                            chatRepository = ChatRepository(app),
                            aiRepository = AiAssistantRepository(),
                            preferencesRepository = PreferencesRepository(app),
                            hapticController = HapticController(app),
                            currentUserId = uid
                        )
                    }
                }
            )

            val userPreferences by chatViewModel.userPreferences.collectAsStateWithLifecycle()
            val conversationsState by chatViewModel.conversationsState.collectAsStateWithLifecycle()
            val activeConversationId by chatViewModel.activeConversationId.collectAsStateWithLifecycle()
            val messagesState by chatViewModel.messagesState.collectAsStateWithLifecycle()
            val streamingState by chatViewModel.streamingState.collectAsStateWithLifecycle()
            val selectedMode by chatViewModel.selectedMode.collectAsStateWithLifecycle()
            val attachedImage by chatViewModel.attachedImage.collectAsStateWithLifecycle()
            val aiErrorMessage by chatViewModel.aiErrorMessage.collectAsStateWithLifecycle()

            AnnTheme(themePreference = userPreferences.themePreference) {
                ChatScreen(
                    currentUser = user,
                    conversationsState = conversationsState,
                    activeConversationId = activeConversationId,
                    messagesState = messagesState,
                    streamingState = streamingState,
                    selectedMode = selectedMode,
                    attachedImage = attachedImage,
                    aiErrorMessage = aiErrorMessage,
                    userPreferences = userPreferences,
                    starterPrompts = chatViewModel.starterPrompts,
                    onSelectConversation = chatViewModel::selectConversation,
                    onNewChat = chatViewModel::startNewChat,
                    onDeleteConversation = chatViewModel::deleteConversation,
                    onSelectMode = chatViewModel::setModelMode,
                    onAttachImage = chatViewModel::attachImage,
                    onClearImage = chatViewModel::clearAttachedImage,
                    onSendMessage = chatViewModel::sendMessage,
                    onStopGeneration = chatViewModel::stopGeneration,
                    onRegenerateLast = chatViewModel::regenerateLastResponse,
                    onDismissError = chatViewModel::dismissAiError,
                    onThemePreferenceChange = chatViewModel::setThemePreference,
                    onHapticsChange = chatViewModel::setHapticsEnabled
                )
            }
        }
    }
}
