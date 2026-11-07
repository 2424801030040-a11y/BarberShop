package com.example.salonapp;

import com.google.firebase.Timestamp;

/** Firestore model for a chat session message. */
public class ChatMessage {
    private String id;
    private String sender;
    private String text;
    private Timestamp timestamp;

    /** Required by Firestore's object mapper. */
    public ChatMessage() { }

    public ChatMessage(String id, String sender, String text, Timestamp timestamp) {
        this.id = id;
        this.sender = sender;
        this.text = text;
        this.timestamp = timestamp;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }
}
