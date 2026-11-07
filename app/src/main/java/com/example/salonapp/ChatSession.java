package com.example.salonapp;

import com.google.firebase.Timestamp;

/** Firestore model for users/{userId}/chat_sessions/{sessionId}. */
public class ChatSession {
    private String id;
    private String title;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    /** Required by Firestore's object mapper. */
    public ChatSession() { }

    public ChatSession(String id, String title, Timestamp createdAt, Timestamp updatedAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
