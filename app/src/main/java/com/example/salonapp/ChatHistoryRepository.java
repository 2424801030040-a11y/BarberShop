package com.example.salonapp;

import androidx.annotation.NonNull;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.List;

/** Owns Firestore access for the signed-in user's AI chat history. */
public final class ChatHistoryRepository {
    private final FirebaseFirestore firestore = FirebaseFirestore.getInstance();

    public interface OnSessionCreatedListener {
        void onSuccess(@NonNull String sessionId);
        void onFailure(@NonNull Exception error);
    }

    public interface OnSessionsLoadedListener {
        void onSessionsLoaded(@NonNull List<ChatSession> sessions);
        void onError(@NonNull Exception error);
    }

    public interface OnMessagesLoadedListener {
        void onMessagesLoaded(@NonNull List<ChatMessage> messages);
        void onError(@NonNull Exception error);
    }

    public interface OnWriteCompleteListener {
        void onComplete(Exception error);
    }

    private DocumentReference userRef(String userId) {
        return firestore.collection("users").document(userId);
    }

    private com.google.firebase.firestore.CollectionReference sessionsRef(String userId) {
        return userRef(userId).collection("chat_sessions");
    }

    public void createNewSession(String userId, String initialMessage, OnSessionCreatedListener listener) {
        if (userId == null || userId.trim().isEmpty()) {
            listener.onFailure(new IllegalArgumentException("A signed-in user is required."));
            return;
        }
        String prompt = initialMessage == null ? "" : initialMessage.trim();
        if (prompt.isEmpty()) {
            listener.onFailure(new IllegalArgumentException("The first message cannot be empty."));
            return;
        }
        String title = prompt.substring(0, Math.min(30, prompt.length()));
        DocumentReference sessionRef = sessionsRef(userId).document();
        Timestamp now = Timestamp.now();
        ChatSession session = new ChatSession(sessionRef.getId(), title, now, now);
        sessionRef.set(session)
                .addOnSuccessListener(unused -> listener.onSuccess(sessionRef.getId()))
                .addOnFailureListener(listener::onFailure);
    }

    public void saveMessage(String userId, String sessionId, ChatMessage message) {
        saveMessage(userId, sessionId, message, null);
    }

    public void saveMessage(String userId, String sessionId, ChatMessage message, OnWriteCompleteListener listener) {
        if (userId == null || sessionId == null || message == null) {
            notifyWrite(listener, new IllegalArgumentException("User, session, and message are required."));
            return;
        }
        String text = message.getText() == null ? "" : message.getText().trim();
        if (text.isEmpty() || text.length() > 10000
                || !("user".equals(message.getSender()) || "ai".equals(message.getSender()))) {
            notifyWrite(listener, new IllegalArgumentException("Message content or sender is invalid."));
            return;
        }
        DocumentReference sessionRef = sessionsRef(userId).document(sessionId);
        DocumentReference messageRef = sessionRef.collection("messages").document();
        message.setId(messageRef.getId());
        message.setText(text);
        if (message.getTimestamp() == null) message.setTimestamp(Timestamp.now());

        WriteBatch batch = firestore.batch();
        batch.set(messageRef, message);
        batch.update(sessionRef, "updatedAt", FieldValue.serverTimestamp());
        batch.commit()
                .addOnSuccessListener(unused -> notifyWrite(listener, null))
                .addOnFailureListener(error -> notifyWrite(listener, error));
    }

    /** Returns a removable real-time listener; callers must remove it when their owner is cleared. */
    public ListenerRegistration getChatSessions(String userId, OnSessionsLoadedListener listener) {
        return sessionsRef(userId).orderBy("updatedAt", Query.Direction.DESCENDING)
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null) { listener.onError(error); return; }
                    if (snapshot == null) return;
                    List<ChatSession> sessions = new ArrayList<>();
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        ChatSession session = document.toObject(ChatSession.class);
                        if (session == null) continue;
                        if (session.getId() == null) session.setId(document.getId());
                        sessions.add(session);
                    }
                    listener.onSessionsLoaded(sessions);
                });
    }

    /** Returns a removable real-time listener so an open transcript stays current. */
    public ListenerRegistration getSessionMessages(String userId, String sessionId, OnMessagesLoadedListener listener) {
        return sessionsRef(userId).document(sessionId).collection("messages")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null) { listener.onError(error); return; }
                    if (snapshot == null) return;
                    listener.onMessagesLoaded(toMessages(snapshot));
                });
    }

    /** Deletes message documents in batches before deleting their parent session. */
    public void deleteSession(String userId, String sessionId) {
        deleteSession(userId, sessionId, null);
    }

    public void deleteSession(String userId, String sessionId, OnWriteCompleteListener listener) {
        if (userId == null || sessionId == null) {
            notifyWrite(listener, new IllegalArgumentException("User and session are required."));
            return;
        }
        DocumentReference sessionRef = sessionsRef(userId).document(sessionId);
        sessionRef.collection("messages").get()
                .addOnSuccessListener(snapshot -> deleteMessageBatch(sessionRef, snapshot, 0, listener))
                .addOnFailureListener(error -> notifyWrite(listener, error));
    }

    private void deleteMessageBatch(DocumentReference sessionRef, QuerySnapshot snapshot, int start,
                                    OnWriteCompleteListener listener) {
        if (start >= snapshot.size()) {
            sessionRef.delete().addOnSuccessListener(unused -> notifyWrite(listener, null))
                    .addOnFailureListener(error -> notifyWrite(listener, error));
            return;
        }
        WriteBatch batch = firestore.batch();
        int end = Math.min(start + 450, snapshot.size());
        for (int i = start; i < end; i++) batch.delete(snapshot.getDocuments().get(i).getReference());
        batch.commit()
                .addOnSuccessListener(unused -> deleteMessageBatch(sessionRef, snapshot, end, listener))
                .addOnFailureListener(error -> notifyWrite(listener, error));
    }

    private List<ChatMessage> toMessages(QuerySnapshot snapshot) {
        List<ChatMessage> messages = new ArrayList<>();
        for (DocumentSnapshot document : snapshot.getDocuments()) {
            ChatMessage message = document.toObject(ChatMessage.class);
            if (message == null) continue;
            if (message.getId() == null) message.setId(document.getId());
            messages.add(message);
        }
        return messages;
    }

    private void notifyWrite(OnWriteCompleteListener listener, Exception error) {
        if (listener != null) listener.onComplete(error);
    }
}
