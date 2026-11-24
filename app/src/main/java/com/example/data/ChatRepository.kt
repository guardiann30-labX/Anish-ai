package com.example.data

import android.content.Context
import com.example.R
import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.MessageRole
import com.example.model.ModelMode
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(expectedUserId: String): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        check(uid == expectedUserId) { "Signed-in user changed; refusing cross-user access." }
        return uid
    }

    fun observeConversations(userId: String): Flow<List<Conversation>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank." }
        val path = "users/$userId/conversations"
        emitAll(
            db.collection("users")
                .document(userId)
                .collection("conversations")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Conversation::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeMessages(userId: String, conversationId: String): Flow<List<ChatMessage>> = flow {
        require(userId.isNotBlank() && conversationId.isNotBlank()) {
            "User ID and conversation ID must not be blank."
        }
        val path = "users/$userId/conversations/$conversationId/messages"
        emitAll(
            db.collection("users")
                .document(userId)
                .collection("conversations")
                .document(conversationId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createConversation(
        userId: String,
        title: String,
        preview: String,
        mode: ModelMode
    ): Result<String> {
        var docPath = "users/$userId/conversations"
        return try {
            val uid = requireUserId(userId)
            val safeTitle = title.trim().ifBlank { "New Chat" }.take(120)
            val safePreview = preview.trim().take(240)
            val docRef = db.collection("users")
                .document(uid)
                .collection("conversations")
                .document()
            docPath = docRef.path

            val payload = mapOf(
                "userId" to uid,
                "title" to safeTitle,
                "lastMessagePreview" to safePreview,
                "modelMode" to mode.wireValue,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateConversationMeta(
        userId: String,
        conversationId: String,
        preview: String,
        mode: ModelMode,
        newTitle: String? = null
    ): Result<Unit> {
        val docPath = "users/$userId/conversations/$conversationId"
        return try {
            val uid = requireUserId(userId)
            require(conversationId.isNotBlank()) { "Conversation ID must not be blank." }
            val updates = mutableMapOf<String, Any>(
                "lastMessagePreview" to preview.trim().take(240),
                "modelMode" to mode.wireValue,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!newTitle.isNullOrBlank()) {
                updates["title"] = newTitle.trim().take(120)
            }
            db.collection("users")
                .document(uid)
                .collection("conversations")
                .document(conversationId)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun addMessage(
        userId: String,
        conversationId: String,
        role: MessageRole,
        content: String,
        thinkingContent: String?,
        mode: ModelMode,
        hasImage: Boolean
    ): Result<String> {
        var docPath = "users/$userId/conversations/$conversationId/messages"
        return try {
            val uid = requireUserId(userId)
            require(conversationId.isNotBlank()) { "Conversation ID must not be blank." }
            val safeContent = content.trim().ifBlank { " " }.take(32_000)
            val docRef = db.collection("users")
                .document(uid)
                .collection("conversations")
                .document(conversationId)
                .collection("messages")
                .document()
            docPath = docRef.path

            val safeThinking = thinkingContent?.trim()?.takeIf { it.isNotEmpty() }?.take(32_000)
            val payload = mapOf(
                "userId" to uid,
                "conversationId" to conversationId,
                "role" to role.wireValue,
                "content" to safeContent,
                "thinkingContent" to safeThinking,
                "modelMode" to mode.wireValue,
                "hasImage" to hasImage,
                "createdAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteMessage(
        userId: String,
        conversationId: String,
        messageId: String
    ): Result<Unit> {
        val docPath = "users/$userId/conversations/$conversationId/messages/$messageId"
        return try {
            val uid = requireUserId(userId)
            db.collection("users")
                .document(uid)
                .collection("conversations")
                .document(conversationId)
                .collection("messages")
                .document(messageId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteConversation(userId: String, conversationId: String): Result<Unit> {
        val docPath = "users/$userId/conversations/$conversationId"
        return try {
            val uid = requireUserId(userId)
            val convRef = db.collection("users")
                .document(uid)
                .collection("conversations")
                .document(conversationId)
            val messagesSnap = convRef.collection("messages").get().await()
            val batch = db.batch()
            for (msgDoc in messagesSnap.documents) {
                batch.delete(msgDoc.reference)
            }
            batch.delete(convRef)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
