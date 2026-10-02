package com.enigma.messenger.data.repository

import com.enigma.messenger.data.model.Conversation
import com.enigma.messenger.data.model.Invite
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

class InviteRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val random = SecureRandom()
    private val charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generateInviteCode(): String {
        val part1 = (1..4).map { charset[random.nextInt(charset.length)] }.joinToString("")
        val part2 = (1..4).map { charset[random.nextInt(charset.length)] }.joinToString("")
        return "$part1-$part2"
    }

    suspend fun createInvite(creatorId: String, creatorName: String): Invite {
        val code = generateInviteCode()
        val invite = Invite(
            code = code,
            createdBy = creatorId,
            creatorName = creatorName,
            status = "ACTIVE",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + (48 * 60 * 60 * 1000L)
        )
        firestore.collection("invites").document(code).set(invite).await()
        return invite
    }

    suspend fun redeemInvite(code: String, recipientId: String, recipientName: String): String {
        val normalizedCode = code.trim().uppercase()
        val inviteRef = firestore.collection("invites").document(normalizedCode)
        val inviteDoc = inviteRef.get().await()

        if (!inviteDoc.exists()) {
            throw IllegalArgumentException("Invalid invite code.")
        }

        val invite = inviteDoc.toObject(Invite::class.java)
            ?: throw IllegalStateException("Failed to parse invite data.")

        if (invite.status != "ACTIVE") {
            throw IllegalStateException("Invite code has already been claimed or expired.")
        }

        if (invite.createdBy == recipientId) {
            throw IllegalArgumentException("You cannot accept your own invite code.")
        }

        // Establish strictly isolated 1-on-1 conversation
        val newConvRef = firestore.collection("conversations").document()
        val newConv = Conversation(
            id = newConvRef.id,
            participants = listOf(invite.createdBy, recipientId),
            participantNames = mapOf(
                invite.createdBy to invite.creatorName,
                recipientId to recipientName
            ),
            ownerId = invite.createdBy,
            partnerId = recipientId,
            lastMessageText = "Secure 1-on-1 channel established via invite.",
            lastMessageTimestamp = System.currentTimeMillis()
        )
        newConvRef.set(newConv).await()

        inviteRef.update(
            mapOf(
                "status" to "CLAIMED",
                "claimedBy" to recipientId,
                "claimedByName" to recipientName,
                "claimedAt" to System.currentTimeMillis(),
                "conversationId" to newConvRef.id
            )
        ).await()

        return newConvRef.id
    }
}