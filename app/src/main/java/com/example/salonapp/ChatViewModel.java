package com.example.salonapp;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.FirebaseFirestoreException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Lifecycle-aware owner of the current chat, Firestore history and AI requests. */
public class ChatViewModel extends ViewModel {
    private final Application application;
    private final ChatHistoryRepository repository = new ChatHistoryRepository();
    private final MutableLiveData<String> currentSessionId = new MutableLiveData<>(null);
    private final MutableLiveData<List<ChatSession>> sessionsList = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<ChatMessage>> messagesList = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> sending = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final String userId;
    private ListenerRegistration sessionsListener;
    private ListenerRegistration messagesListener;

    public ChatViewModel(@NonNull Application application) {
        this.application = application;
        userId = FirebaseAuth.getInstance().getUid();
        if (userId == null) {
            errorMessage.setValue("Vui lòng đăng nhập để lưu lịch sử trò chuyện.");
        } else {
            sessionsListener = repository.getChatSessions(userId, new ChatHistoryRepository.OnSessionsLoadedListener() {
                @Override public void onSessionsLoaded(@NonNull List<ChatSession> sessions) {
                    sessionsList.setValue(sessions);
                }
                @Override public void onError(@NonNull Exception error) {
                    Log.e("ChatHistory", "Could not load chat sessions for user " + userId, error);
                    errorMessage.setValue(describeHistoryError(error));
                }
            });
        }
    }

    public LiveData<String> getCurrentSessionId() { return currentSessionId; }
    public LiveData<List<ChatSession>> getSessionsList() { return sessionsList; }
    public LiveData<List<ChatMessage>> getMessagesList() { return messagesList; }
    public LiveData<Boolean> getSending() { return sending; }
    public LiveData<String> getErrorMessage() { return errorMessage; }
    public void clearError() { errorMessage.setValue(null); }

    /** Turns common Firestore failures into an actionable message for the signed-in user. */
    private String describeHistoryError(@NonNull Exception error) {
        if (error instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) error).getCode();
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                return "Không có quyền tải lịch sử. Hãy triển khai Firestore rules cho chat_sessions rồi thử lại.";
            }
            if (code == FirebaseFirestoreException.Code.UNAUTHENTICATED) {
                return "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.";
            }
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                return "Không kết nối được Firestore. Hãy kiểm tra mạng rồi thử lại.";
            }
        }
        return "Không tải được lịch sử trò chuyện. Hãy kiểm tra log ChatHistory để xem lỗi Firestore.";
    }

    /** Starts a new session on the first send, then persists the user and AI messages. */
    public void sendMessage(String userText) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty() || Boolean.TRUE.equals(sending.getValue())) return;
        if (userId == null) {
            errorMessage.setValue("Vui lòng đăng nhập để trò chuyện.");
            return;
        }
        sending.setValue(true);
        errorMessage.setValue(null);
        String activeId = currentSessionId.getValue();
        if (activeId != null) {
            sendToSession(activeId, text);
            return;
        }
        repository.createNewSession(userId, text, new ChatHistoryRepository.OnSessionCreatedListener() {
            @Override public void onSuccess(@NonNull String sessionId) {
                if (currentSessionId.getValue() == null) {
                    currentSessionId.setValue(sessionId);
                    observeMessages(sessionId);
                }
                sendToSession(sessionId, text);
            }
            @Override public void onFailure(@NonNull Exception error) {
                sending.setValue(false);
                errorMessage.setValue("Không tạo được cuộc trò chuyện. Vui lòng thử lại.");
            }
        });
    }

    /** Persists an automatic face-scan request in the active chat and asks the local AI for advice. */
    public void sendFaceConsultation(double faceAspect, double foreheadToCheek,
                                     double jawToCheek, double foreheadToJaw) {
        if (Boolean.TRUE.equals(sending.getValue())) return;
        if (userId == null) {
            errorMessage.setValue("Vui lòng đăng nhập để nhận tư vấn.");
            return;
        }
        if (!isFiniteInRange(faceAspect, 0.5, 3.0)
                || !isFiniteInRange(foreheadToCheek, 0.3, 1.5)
                || !isFiniteInRange(jawToCheek, 0.3, 1.5)
                || !isFiniteInRange(foreheadToJaw, 0.3, 2.5)) {
            errorMessage.setValue("Chưa đo được khuôn mặt rõ ràng. Vui lòng quét lại với ánh sáng tốt hơn.");
            return;
        }
        sending.setValue(true);
        errorMessage.setValue(null);
        String prompt = "Tư vấn kiểu tóc phù hợp với dáng khuôn mặt của tôi.";
        String activeId = currentSessionId.getValue();
        if (activeId != null) {
            sendFaceToSession(activeId, prompt, faceAspect, foreheadToCheek, jawToCheek, foreheadToJaw);
            return;
        }
        repository.createNewSession(userId, prompt, new ChatHistoryRepository.OnSessionCreatedListener() {
            @Override public void onSuccess(@NonNull String sessionId) {
                if (currentSessionId.getValue() == null) {
                    currentSessionId.setValue(sessionId);
                    observeMessages(sessionId);
                }
                sendFaceToSession(sessionId, prompt, faceAspect, foreheadToCheek, jawToCheek, foreheadToJaw);
            }
            @Override public void onFailure(@NonNull Exception error) {
                sending.setValue(false);
                errorMessage.setValue("Không tạo được cuộc trò chuyện. Vui lòng thử lại.");
            }
        });
    }

    private void sendFaceToSession(String sessionId, String prompt, double faceAspect,
                                  double foreheadToCheek, double jawToCheek, double foreheadToJaw) {
        ChatMessage userMessage = new ChatMessage(null, "user", prompt, Timestamp.now());
        appendIfOpen(sessionId, userMessage);
        repository.saveMessage(userId, sessionId, userMessage, saveError -> {
            if (saveError != null) {
                removeOptimisticIfOpen(sessionId, userMessage.getId());
                sending.setValue(false);
                errorMessage.setValue("Không lưu được tin nhắn. Vui lòng thử lại.");
                return;
            }
            JSONObject payload;
            try {
                payload = new JSONObject()
                        .put("face_aspect_ratio", faceAspect)
                        .put("forehead_to_cheek_ratio", foreheadToCheek)
                        .put("jaw_to_cheek_ratio", jawToCheek)
                        .put("forehead_to_jaw_ratio", foreheadToJaw);
            } catch (JSONException error) {
                sending.setValue(false);
                errorMessage.setValue("Không thể tạo yêu cầu phân tích khuôn mặt.");
                return;
            }
            SupabaseFunctions.postFaceConsultGateway(payload, (result, requestError) -> {
                if (requestError != null || result == null) {
                    sending.setValue(false);
                    errorMessage.setValue(requestError == null
                            ? "Chưa nhận được tư vấn. Vui lòng thử lại."
                            : requestError);
                    return;
                }
                String shape = result.optString("face_shape", "").trim();
                String reply = normalizeAiText(result.optString("reply", ""));
                if (reply.isEmpty()) {
                    sending.setValue(false);
                    errorMessage.setValue("AI chưa tạo được phần tư vấn. Vui lòng thử lại.");
                    return;
                }
                String localizedShape = localizedFaceShape(shape);
                String answer = localizedShape.isEmpty() ? reply
                        : "Dáng khuôn mặt ước tính: " + localizedShape + ".\n\n" + reply;
                ChatMessage aiMessage = new ChatMessage(null, "ai", answer, Timestamp.now());
                appendIfOpen(sessionId, aiMessage);
                repository.saveMessage(userId, sessionId, aiMessage, aiSaveError -> {
                    sending.setValue(false);
                    if (aiSaveError != null) errorMessage.setValue("Câu trả lời đã đến nhưng chưa lưu được vào lịch sử.");
                });
            });
        });
    }

    private static boolean isFiniteInRange(double value, double minExclusive, double maxInclusive) {
        return !Double.isNaN(value) && !Double.isInfinite(value)
                && value > minExclusive && value <= maxInclusive;
    }

    private static String localizedFaceShape(String shape) {
        switch (shape.toLowerCase(java.util.Locale.ROOT)) {
            case "oval": return "oval";
            case "round": return "tròn";
            case "square": return "vuông";
            case "heart": return "trái tim";
            case "oblong": return "dài";
            default: return "";
        }
    }

    /** Selects a saved transcript and replaces the previous message listener. */
    public void loadSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty() || userId == null) return;
        currentSessionId.setValue(sessionId);
        messagesList.setValue(new ArrayList<>());
        observeMessages(sessionId);
    }

    /** Clears the visible transcript; the next send creates a new Firestore session. */
    public void startNewChat() {
        removeMessagesListener();
        currentSessionId.setValue(null);
        messagesList.setValue(new ArrayList<>());
        errorMessage.setValue(null);
    }

    public void deleteSession(String sessionId) {
        if (userId == null || sessionId == null) return;
        repository.deleteSession(userId, sessionId, error -> {
            if (error != null) {
                errorMessage.setValue("Không xóa được cuộc trò chuyện.");
            } else if (sessionId.equals(currentSessionId.getValue())) {
                startNewChat();
            }
        });
    }

    private void sendToSession(String sessionId, String text) {
        JSONObject payload;
        try {
            payload = createBackendPayload(text, messagesList.getValue());
        } catch (JSONException error) {
            sending.setValue(false);
            errorMessage.setValue("Không thể tạo yêu cầu tư vấn.");
            return;
        }

        ChatMessage userMessage = new ChatMessage(null, "user", text, Timestamp.now());
        appendIfOpen(sessionId, userMessage);
        repository.saveMessage(userId, sessionId, userMessage, saveError -> {
            if (saveError != null) {
                removeOptimisticIfOpen(sessionId, userMessage.getId());
                sending.setValue(false);
                errorMessage.setValue("Không lưu được tin nhắn. Vui lòng thử lại.");
                return;
            }
            SupabaseFunctions.Callback callback = (result, requestError) -> {
                if (requestError != null || result == null) {
                    sending.setValue(false);
                    errorMessage.setValue(requestError == null ? "AI chưa trả lời được. Vui lòng thử lại." : requestError);
                    return;
                }
                String answer = normalizeAiText(result.optString("answer"));
                if (answer.isEmpty()) answer = normalizeAiText(result.optString("reply"));
                if (answer.isEmpty()) answer = normalizeAiText(result.optString("response"));
                if (answer.isEmpty()) {
                    sending.setValue(false);
                    errorMessage.setValue("AI chưa trả lời được. Vui lòng thử lại.");
                    return;
                }
                ChatMessage aiMessage = new ChatMessage(null, "ai", answer, Timestamp.now());
                appendIfOpen(sessionId, aiMessage);
                repository.saveMessage(userId, sessionId, aiMessage, aiSaveError -> {
                    sending.setValue(false);
                    if (aiSaveError != null) errorMessage.setValue("Câu trả lời đã đến nhưng chưa lưu được vào lịch sử.");
                });
            };
            if (BuildConfig.DEBUG) SupabaseFunctions.postHairAdvisorGateway(payload, callback);
            else SupabaseFunctions.post(application, "hair-advisor", payload, callback);
        });
    }

    private JSONObject createBackendPayload(String text, List<ChatMessage> priorMessages) throws JSONException {
        JSONArray history = new JSONArray();
        List<ChatMessage> source = priorMessages == null ? new ArrayList<>() : priorMessages;
        int start = Math.max(0, source.size() - 8);
        for (int i = start; i < source.size(); i++) {
            ChatMessage message = source.get(i);
            if (message == null || message.getText() == null) continue;
            boolean fromUser = "user".equals(message.getSender());
            String role = fromUser ? "user" : "model";
            String historyText = fromUser ? message.getText() : normalizeAiText(message.getText());
            history.put(new JSONObject().put("role", role).put("text", historyText));
        }
        history.put(new JSONObject().put("role", "user").put("text", text));
        return new JSONObject()
                .put("message", text)
                .put("preferences", new JSONObject())
                .put("history", history);
    }

    /** Cleans common model formatting/translation artifacts before display and persistence. */
    public static String normalizeAiText(String text) {
        if (text == null) return "";
        String normalized = text.trim()
                .replaceAll("(?i)tóc\\s+dài\\s+tay", "tóc dài")
                .replaceAll("(?i)cắt theo hình bàn chèn hoặc cái ca lô", "có hai bên gọn và phần đỉnh tạo độ phồng nhẹ")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "");
        if (!normalized.matches("(?s).*(?<!\\d)[.!?…。！？]\\s*$")) {
            java.util.regex.Matcher endings = java.util.regex.Pattern
                    .compile("(?<!\\d)[.!?…](?=\\s|$)")
                    .matcher(normalized);
            int lastCompleteEnd = -1;
            while (endings.find()) lastCompleteEnd = endings.end();
            if (lastCompleteEnd >= 0) normalized = normalized.substring(0, lastCompleteEnd).trim();
        }
        return normalized;
    }

    private void observeMessages(String sessionId) {
        removeMessagesListener();
        if (userId == null) return;
        messagesListener = repository.getSessionMessages(userId, sessionId, new ChatHistoryRepository.OnMessagesLoadedListener() {
            @Override public void onMessagesLoaded(@NonNull List<ChatMessage> messages) {
                if (sessionId.equals(currentSessionId.getValue())) messagesList.setValue(messages);
            }
            @Override public void onError(@NonNull Exception error) {
                if (sessionId.equals(currentSessionId.getValue())) errorMessage.setValue("Không tải được tin nhắn của cuộc trò chuyện.");
            }
        });
    }

    private void appendIfOpen(String sessionId, ChatMessage message) {
        if (!sessionId.equals(currentSessionId.getValue())) return;
        List<ChatMessage> current = messagesList.getValue() == null
                ? new ArrayList<>() : new ArrayList<>(messagesList.getValue());
        current.add(message);
        messagesList.setValue(current);
    }

    private void removeOptimisticIfOpen(String sessionId, String messageId) {
        if (!sessionId.equals(currentSessionId.getValue())) return;
        List<ChatMessage> current = messagesList.getValue() == null
                ? new ArrayList<>() : new ArrayList<>(messagesList.getValue());
        current.removeIf(message -> messageId != null && messageId.equals(message.getId()));
        messagesList.setValue(current);
    }

    private void removeMessagesListener() {
        if (messagesListener != null) {
            messagesListener.remove();
            messagesListener = null;
        }
    }

    @Override protected void onCleared() {
        removeMessagesListener();
        if (sessionsListener != null) {
            sessionsListener.remove();
            sessionsListener = null;
        }
        super.onCleared();
    }

    /** Factory required because this ViewModel accepts the process-safe Application context. */
    public static final class Factory implements ViewModelProvider.Factory {
        private final Application application;
        public Factory(@NonNull Application application) { this.application = application; }
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (!modelClass.isAssignableFrom(ChatViewModel.class)) throw new IllegalArgumentException("Unknown ViewModel class");
            return modelClass.cast(new ChatViewModel(application));
        }
    }
}
