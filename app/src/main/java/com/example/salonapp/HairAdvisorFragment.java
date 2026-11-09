package com.example.salonapp;

import android.graphics.Color;
import android.content.Intent;
import android.text.InputType;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.*;

/** Shop-specific text consultation for hair styling, color and care. */
public class HairAdvisorFragment extends Fragment {
    private static final String WELCOME = "Chào bạn! Mình là tư vấn viên AI của Chí Phèo BarberShop. Mình có thể giúp bạn chọn kiểu cắt, uốn, nhuộm và cách chăm sóc tóc phù hợp. Bạn muốn thay đổi điều gì ở mái tóc của mình?";
    private EditText question;
    private MaterialButton sendButton;
    private LinearLayout messages;
    private ScrollView messageScroll;
    private boolean sending;
    private ChatViewModel viewModel;
    private ChatSessionAdapter sessionAdapter;
    private DrawerLayout historyDrawer;
    private String userInitials = "?";
    private String userAvatarUrl;
    private final List<UserAvatar> userAvatars = new ArrayList<>();
    private final ActivityResultLauncher<Intent> faceScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null || !isAdded()) return;
                Intent data = result.getData();
                if (!data.hasExtra(FaceScanActivity.EXTRA_FACE_ASPECT)
                        || !data.hasExtra(FaceScanActivity.EXTRA_FOREHEAD_CHEEK)
                        || !data.hasExtra(FaceScanActivity.EXTRA_JAW_CHEEK)
                        || !data.hasExtra(FaceScanActivity.EXTRA_FOREHEAD_JAW)) {
                    Toast.makeText(requireContext(), "Chưa nhận đủ dữ liệu khuôn mặt. Vui lòng quét lại.", Toast.LENGTH_SHORT).show();
                    return;
                }
                viewModel.sendFaceConsultation(
                        data.getDoubleExtra(FaceScanActivity.EXTRA_FACE_ASPECT, 0),
                        data.getDoubleExtra(FaceScanActivity.EXTRA_FOREHEAD_CHEEK, 0),
                        data.getDoubleExtra(FaceScanActivity.EXTRA_JAW_CHEEK, 0),
                        data.getDoubleExtra(FaceScanActivity.EXTRA_FOREHEAD_JAW, 0));
            });

    @Nullable @Override public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        android.content.Context context = requireContext();
        viewModel = new ViewModelProvider(requireActivity(),
                new ChatViewModel.Factory(requireActivity().getApplication())).get(ChatViewModel.class);
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(8));
        page.setBackgroundColor(Color.parseColor("#F5F1E9"));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Tư vấn tóc cùng AI", 22, "#29211D", true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton historyButton = new ImageButton(context);
        historyButton.setImageResource(R.drawable.ic_brand_mark);
        historyButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        historyButton.setPadding(dp(4), dp(4), dp(4), dp(4));
        historyButton.setContentDescription("Mở lịch sử trò chuyện");
        MaterialButton newChatButton = new MaterialButton(context);
        newChatButton.setText("Cuộc tư vấn mới");
        newChatButton.setTextSize(11);
        newChatButton.setAllCaps(false);
        newChatButton.setMinHeight(dp(36));
        newChatButton.setInsetTop(0);
        newChatButton.setInsetBottom(0);
        newChatButton.setOnClickListener(v -> startNewConsultation());
        LinearLayout.LayoutParams newChatParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        newChatParams.rightMargin = dp(6);
        titleRow.addView(newChatButton, newChatParams);
        titleRow.addView(historyButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
        page.addView(titleRow);
        TextView intro = text("Tư vấn tóc theo phong cách Chí Phèo Barber Shop.", 13, "#766B62", false);
        intro.setPadding(0, dp(2), 0, dp(7));
        page.addView(intro);
        MaterialButton faceScanButton = new MaterialButton(context);
        faceScanButton.setText("Phân tích khuôn mặt để nhận tư vấn");
        faceScanButton.setAllCaps(false);
        faceScanButton.setTextSize(13);
        faceScanButton.setMinHeight(dp(40));
        faceScanButton.setInsetTop(0);
        faceScanButton.setInsetBottom(0);
        faceScanButton.setOnClickListener(v -> faceScanLauncher.launch(new Intent(requireContext(), FaceScanActivity.class)));
        LinearLayout.LayoutParams faceScanParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        faceScanParams.bottomMargin = dp(5);
        page.addView(faceScanButton, faceScanParams);

        messageScroll = new ScrollView(context);
        messageScroll.setFillViewport(false);
        messages = new LinearLayout(context);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(1), dp(3), dp(1), dp(8));
        userAvatars.clear();
        loadUserAvatar();
        messageScroll.addView(messages, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(messageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        addMessage("assistant", WELCOME);

        LinearLayout composer = new LinearLayout(context);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        question = new EditText(context);
        question.setSingleLine(false);
        question.setMaxLines(3);
        question.setMinLines(1);
        question.setTextSize(14);
        question.setHint("Hỏi về kiểu tóc, màu tóc, chăm sóc…");
        question.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        question.setBackground(roundRect("#FFFFFF", "#D9D0C5", 14));
        question.setPadding(dp(12), dp(9), dp(12), dp(9));
        composer.addView(question, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sendButton = new MaterialButton(context);
        sendButton.setText("Gửi");
        sendButton.setAllCaps(false);
        sendButton.setOnClickListener(v -> sendQuestion());
        sending = Boolean.TRUE.equals(viewModel.getSending().getValue());
        sendButton.setEnabled(!sending);
        if (sending) sendButton.setText("…");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        sendParams.leftMargin = dp(6);
        composer.addView(sendButton, sendParams);
        LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        composerParams.topMargin = dp(2);
        page.addView(composer, composerParams);
        observeChatState();
        historyDrawer = new DrawerLayout(context);
        historyDrawer.addView(page, new DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View drawer = createHistoryDrawer(context);
        DrawerLayout.LayoutParams drawerParams = new DrawerLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        historyDrawer.addView(drawer, drawerParams);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (historyDrawer != null && historyDrawer.isDrawerOpen(Gravity.START)) {
                    historyDrawer.closeDrawer(Gravity.START);
                    return;
                }
                setEnabled(false);
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
        historyButton.setOnClickListener(v -> historyDrawer.openDrawer(drawer));
        return historyDrawer;
    }

    private View createHistoryDrawer(android.content.Context context) {
        LinearLayout drawer = new LinearLayout(context);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(Color.parseColor("#F5F1E9"));
        drawer.setElevation(dp(12));
        drawer.setPadding(0, dp(18), 0, dp(8));

        TextView heading = text("Lịch sử trò chuyện", 20, "#29211D", true);
        heading.setPadding(dp(18), dp(8), dp(18), dp(10));
        drawer.addView(heading);

        MaterialButton newChat = new MaterialButton(context);
        newChat.setText("+ Cuộc trò chuyện mới");
        newChat.setAllCaps(false);
        LinearLayout.LayoutParams newChatParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        newChatParams.setMargins(dp(12), dp(2), dp(12), dp(10));
        drawer.addView(newChat, newChatParams);
        newChat.setOnClickListener(view -> startNewConsultation());

        RecyclerView sessions = new RecyclerView(context);
        sessions.setLayoutManager(new LinearLayoutManager(context));
        sessionAdapter = new ChatSessionAdapter();
        sessionAdapter.setOnClick(session -> {
            viewModel.loadSession(session.getId());
            if (historyDrawer != null) historyDrawer.closeDrawer(drawer);
        });
        sessionAdapter.setOnLongClick(session -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Xóa cuộc trò chuyện?")
                .setMessage(session.getTitle())
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa", (dialog, which) -> viewModel.deleteSession(session.getId()))
                .show());
        sessions.setAdapter(sessionAdapter);
        drawer.addView(sessions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return drawer;
    }

    private void observeChatState() {
        viewModel.getMessagesList().observe(getViewLifecycleOwner(), this::renderMessages);
        viewModel.getSessionsList().observe(getViewLifecycleOwner(), sessions -> {
            if (sessionAdapter != null) sessionAdapter.submitList(sessions);
        });
        viewModel.getSending().observe(getViewLifecycleOwner(), isSending -> {
            sending = Boolean.TRUE.equals(isSending);
            if (sendButton != null) {
                sendButton.setEnabled(!sending);
                sendButton.setText(sending ? "…" : "Gửi");
            }
        });
        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            if (error == null || error.trim().isEmpty() || !isAdded()) return;
            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
            viewModel.clearError();
        });
    }

    private void renderMessages(List<ChatMessage> transcript) {
        if (messages == null) return;
        messages.removeAllViews();
        if (transcript == null || transcript.isEmpty()) {
            addMessage("assistant", WELCOME);
            return;
        }
        for (ChatMessage message : transcript) {
            if (message == null || message.getText() == null) continue;
            boolean fromUser = "user".equals(message.getSender());
            String body = fromUser ? message.getText() : ChatViewModel.normalizeAiText(message.getText());
            addMessage(fromUser ? "user" : "assistant", body);
        }
    }

    @Override public void onDestroyView() {
        userAvatars.clear();
        sessionAdapter = null;
        historyDrawer = null;
        messages = null;
        messageScroll = null;
        question = null;
        sendButton = null;
        super.onDestroyView();
    }

    private void sendQuestion() {
        if (sending) return;
        String prompt = question.getText().toString().trim();
        if (prompt.isEmpty()) { question.setError("Hãy nhập câu hỏi của bạn"); return; }
        viewModel.sendMessage(prompt);
        question.setText("");
    }

    private void startNewConsultation() {
        if (sending) {
            Toast.makeText(requireContext(), "Vui lòng đợi câu trả lời hiện tại trước.", Toast.LENGTH_SHORT).show();
            return;
        }
        viewModel.startNewChat();
        if (question != null) question.setText("");
        if (historyDrawer != null && historyDrawer.isDrawerOpen(Gravity.START)) historyDrawer.closeDrawer(Gravity.START);
    }

    private void addMessage(String who, String body) {
        if (messages == null || !isAdded()) return;
        boolean fromUser = who.equals("user");
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity((fromUser ? Gravity.END : Gravity.START) | Gravity.BOTTOM);

        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        avatarParams.leftMargin = dp(5);
        avatarParams.rightMargin = dp(5);
        TextView bubble = text(body, 14, who.equals("user") ? "#FFFFFF" : "#29211D", false);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        bubble.setBackground(roundRect(fromUser ? "#8D3C32" : "#FFFEFC", fromUser ? "#8D3C32" : "#E9DFD0", 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        params.bottomMargin = dp(4);
        params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(100), dp(300));
        if (fromUser) {
            row.addView(bubble, params);
            UserAvatar avatar = createUserAvatar();
            row.addView(avatar.root, avatarParams);
        } else {
            TextView avatar = text("AI", 10, "#8D3C32", true);
            avatar.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable avatarBackground = new android.graphics.drawable.GradientDrawable();
            avatarBackground.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            avatarBackground.setColor(Color.parseColor("#F0DED2"));
            avatarBackground.setStroke(dp(1), Color.parseColor("#D9B9A5"));
            avatar.setBackground(avatarBackground);
            row.addView(avatar, avatarParams);
            row.addView(bubble, params);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(2);
        rowParams.bottomMargin = dp(2);
        messages.addView(row, rowParams);
        if (messageScroll != null) messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void loadUserAvatar() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        userInitials = initials(user.getDisplayName());
        FirebaseFirestore.getInstance().collection("users").document(user.getUid()).get()
                .addOnSuccessListener(document -> {
                    if (!isAdded()) return;
                    String name = document.getString("name");
                    if (name == null || name.trim().isEmpty()) name = user.getDisplayName();
                    userInitials = initials(name);
                    userAvatarUrl = document.getString("avatarUrl");
                    if ((userAvatarUrl == null || userAvatarUrl.trim().isEmpty()) && user.getPhotoUrl() != null)
                        userAvatarUrl = user.getPhotoUrl().toString();
                    for (UserAvatar avatar : userAvatars) renderUserAvatar(avatar);
                });
    }

    private UserAvatar createUserAvatar() {
        UserAvatar avatar = new UserAvatar();
        userAvatars.add(avatar);
        renderUserAvatar(avatar);
        return avatar;
    }

    private void renderUserAvatar(UserAvatar avatar) {
        avatar.initials.setText(userInitials);
        if (userAvatarUrl == null || userAvatarUrl.trim().isEmpty()) {
            avatar.image.setVisibility(View.GONE);
            avatar.initials.setVisibility(View.VISIBLE);
        } else {
            avatar.initials.setVisibility(View.GONE);
            avatar.image.setVisibility(View.VISIBLE);
            Glide.with(this).load(userAvatarUrl).circleCrop().into(avatar.image);
        }
    }

    private String initials(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String[] parts = name.trim().replaceAll("\\s+", " ").split(" ");
        if (parts.length == 1) return parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    private final class UserAvatar {
        final FrameLayout root = new FrameLayout(requireContext());
        final TextView initials = text("?", 12, "#FFFFFF", true);
        final ImageView image = new ImageView(requireContext());

        UserAvatar() {
            root.setLayoutParams(new ViewGroup.LayoutParams(dp(30), dp(30)));
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            background.setColor(Color.parseColor("#8D3C32"));
            initials.setGravity(Gravity.CENTER);
            initials.setBackground(background);
            root.addView(initials, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            image.setVisibility(View.GONE);
        }
    }

    private TextView text(String value, int size, String color, boolean bold) {
        TextView view = new TextView(requireContext());
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private android.graphics.drawable.GradientDrawable roundRect(String fill, String stroke, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(Color.parseColor(fill)); drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), Color.parseColor(stroke));
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
