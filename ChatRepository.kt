package com.enigma.messenger.data.repository

import com.enigma.messenger.data.model.Conversation
import com.enigma.messenger.data.model.Message
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class ChatRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun observeConversations(userId: String): Flow<List<Conversation>> = callbackFlow {
        val listener = firestore.collection("conversations")
            .whereArrayContains("participants", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(Conversation::class.java)
                }?.sortedByDescending { it.lastMessageTimestamp } ?: emptyList()

                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    fun observeMessages(conversationId: String, currentUserId: String): Flow<List<Message>> = callbackFlow {
        val listener = firestore.collection("conversations")
            .document(conversationId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val list = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(Message::class.java)
                }?.filter { msg ->
                    !msg.deletedFor.contains(currentUserId) &&
                    (msg.expiresAt == null || msg.expiresAt > now)
                } ?: emptyList()

                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun sendMessage(
        conversationId: String,
        senderId: String,
        senderName: String,
        text: String,
        disappearingTtlSeconds: Long = 0L,
        replyTo: Message? = null
    ) {
        val messagesRef = firestore.collection("conversations")
            .document(conversationId)
            .collection("messages")

        val newMsgDoc = messagesRef.document()
        val now = System.currentTimeMillis()
        val expiresAt = if (disappearingTtlSeconds > 0) now + (disappearingTtlSeconds * 1000L) else null

        val message = Message(
            id = newMsgDoc.id,
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            text = text,
            timestamp = now,
            read = false,
            delivered = true,
            replyToMessageId = replyTo?.id,
            replyToText = replyTo?.text,
            replyToSenderName = replyTo?.senderName,
            expiresAt = expiresAt
        )
        newMsgDoc.set(message).await()

        firestore.collection("conversations").document(conversationId).update(
            mapOf(
                "lastMessageText" to text,
                "lastMessageTimestamp" to now,
                "lastMessageSenderId" to senderId
            )
        ).await()
    }

    suspend fun pinMessage(conversationId: String, messageId: String, userId: String, pin: Boolean) {
        firestore.collection("conversations")
            .document(conversationId)
            .collection("messages")
            .document(messageId)
            .update(
                mapOf(
                    "isPinned" to pin,
                    "pinnedAt" to if (pin) System.currentTimeMillis() else null,
                    "pinnedBy" to if (pin) userId else null
                )
            ).await()
    }

    suspend fun toggleReaction(conversationId: String, messageId: String, userId: String, emoji: String) {
        val docRef = firestore.collection("conversations").document(conversationId).collection("messages").document(messageId)
        val doc = docRef.get().await()
        val msg = doc.toObject(Message::class.java) ?: return
        val currentReactions = msg.reactions.toMutableMap()
        if (currentReactions[userId] == emoji) currentReactions.remove(userId) else currentReactions[userId] = emoji
        docRef.update("reactions", currentReactions).await()
    }

    suspend fun deleteForMe(conversationId: String, messageId: String, userId: String) {
        firestore.collection("conversations").document(conversationId).collection("messages").document(messageId)
            .update("deletedFor", FieldValue.arrayUnion(userId)).await()
    }

    suspend fun deleteForEveryone(conversationId: String, messageId: String) {
        firestore.collection("conversations").document(conversationId).collection("messages").document(messageId)
            .update(
                mapOf(
                    "text" to "[This message was deleted by sender]",
                    "isDeletedForEveryone" to true,
                    "replyToMessageId" to null,
                    "replyToText" to null
                )
            ).await()
    }

    suspend fun updateDisappearingTimer(conversationId: String, ttlSeconds: Long) {
        firestore.collection("conversations").document(conversationId).update("disappearingTtlSeconds", ttlSeconds).await()
    }

    suspend fun unlinkPartner(conversationId: String, userId: String) {
        firestore.collection("conversations").document(conversationId).update(
            mapOf("unlinked" to true, "unlinkedBy" to userId, "unlinkedAt" to System.currentTimeMillis())
        ).await()
    }
}