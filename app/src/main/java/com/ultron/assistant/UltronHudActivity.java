package com.ultron.assistant;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * Opens on the wake word (or can be launched manually) with a circular-reveal animation from
 * the center of the screen outward - Android's built-in ViewAnimationUtils.createCircularReveal
 * IS exactly "ek circle center se khulke fullscreen ho jaye", no custom animation math needed.
 *
 * Shows the animated HUD rings, a live conversation log, and a text input row - typed text goes
 * through the exact same UltronCore.handle() pipeline as spoken commands, so anything Ultron can
 * do by voice it can do by typing too, and both show up in the same log (ConversationBus keeps
 * them in sync no matter which input channel is used, including turns that started in
 * UltronService while this Activity wasn't even open).
 */
public class UltronHudActivity extends AppCompatActivity
        implements ConversationBus.Listener, ListeningStateBus.Listener {

    public static final String EXTRA_AUTO_LISTEN = "com.ultron.assistant.extra.AUTO_LISTEN";

    private FrameLayout hudRoot;
    private HudRingsView ringsView;
    private TextView statusText;
    private LinearLayout chatLog;
    private ScrollView chatScroll;
    private EditText textInput;
    private TextView micButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.UltronHudTheme);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_hud);

        hudRoot = findViewById(R.id.hud_root);
        ringsView = findViewById(R.id.hud_rings);
        statusText = findViewById(R.id.hud_status);
        chatLog = findViewById(R.id.hud_chat_log);
        chatScroll = findViewById(R.id.hud_chat_scroll);
        textInput = findViewById(R.id.hud_text_input);
        micButton = findViewById(R.id.hud_mic_button);

        // PHASE 8: the mic is now owned by UltronService's continuous-listening loop, not by this
        // Activity - that's what lets listening survive the HUD being closed. This tap just toggles
        // that shared on/off switch; onListeningStateChanged() below keeps the UI in sync with it
        // no matter which trigger (this button, a spoken "stop listening", wake word) changed it.
        findViewById(R.id.hud_close).setOnClickListener(v -> finish());
        findViewById(R.id.hud_send_button).setOnClickListener(v -> sendTyped());
        micButton.setOnClickListener(v -> {
            if (ListeningStateBus.isListening()) {
                UltronService.stopContinuousListening();
            } else {
                UltronService.startContinuousListening();
            }
        });

        textInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendTyped();
                return true;
            }
            return false;
        });

        // Replay recent history so opening the HUD mid-conversation isn't a blank slate.
        for (ConversationBus.Message m : ConversationBus.history()) {
            appendBubble(m.speaker, m.text);
        }

        hudRoot.post(this::playCircularReveal);
        applyListeningUi(ListeningStateBus.isListening());

        if (getIntent().getBooleanExtra(EXTRA_AUTO_LISTEN, false) && !ListeningStateBus.isListening()) {
            hudRoot.postDelayed(UltronService::startContinuousListening, 450); // let the reveal start first
        }
    }

    private void playCircularReveal() {
        int cx = hudRoot.getWidth() / 2;
        int cy = hudRoot.getHeight() / 2;
        float finalRadius = (float) Math.hypot(hudRoot.getWidth(), hudRoot.getHeight());

        hudRoot.setVisibility(View.VISIBLE);
        Animator reveal = ViewAnimationUtils.createCircularReveal(hudRoot, cx, cy, 0f, finalRadius);
        reveal.setDuration(500);
        reveal.start();
    }

    private void playCircularHideThenFinish() {
        int cx = hudRoot.getWidth() / 2;
        int cy = hudRoot.getHeight() / 2;
        float startRadius = (float) Math.hypot(hudRoot.getWidth(), hudRoot.getHeight());

        Animator hide = ViewAnimationUtils.createCircularReveal(hudRoot, cx, cy, startRadius, 0f);
        hide.setDuration(350);
        hide.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finish();
                overridePendingTransition(0, 0);
            }
        });
        hide.start();
    }

    private void sendTyped() {
        String text = textInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        textInput.setText("");
        setStatus("Samajh raha hoon...");
        ringsView.setActive(true);
        // Typed turns don't need TTS to talk over the user, but they run through the identical
        // routing + execution pipeline as voice - same commands, same automations, same brain.
        UltronCore.get().handle(text, false, response -> runOnUiThread(() -> {
            ringsView.setActive(ListeningStateBus.isListening());
            setStatus(ListeningStateBus.isListening() ? "Sun raha hoon..." : "Systems online");
        }));
    }

    /** PHASE 8 - keeps the rings/status/mic icon in sync with UltronService's listening loop. */
    private void applyListeningUi(boolean isListening) {
        ringsView.setActive(isListening);
        setStatus(isListening ? "Sun raha hoon..." : "Systems online");
        micButton.setText(isListening ? "\u23F9" : "\uD83C\uDF99"); // ⏹ while on, 🎙 while off
        micButton.setTextColor(isListening ? Color.parseColor("#FF1744") : Color.parseColor("#00E5FF"));
    }

    @Override
    public void onListeningStateChanged(boolean isListening) {
        applyListeningUi(isListening);
    }

    @SuppressLint("SetTextI18n")
    private void setStatus(String text) {
        statusText.setText(text);
    }

    /** ConversationBus.Listener - fires for EVERY turn app-wide, voice or typed, HUD open or not. */
    @Override
    public void onMessage(ConversationBus.Message message) {
        appendBubble(message.speaker, message.text);
    }

    private void appendBubble(ConversationBus.Speaker speaker, String text) {
        boolean isUser = speaker == ConversationBus.Speaker.USER;

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(14f);
        bubble.setPadding(28, 18, 28, 18);
        bubble.setBackgroundColor(isUser ? Color.parseColor("#332979FF") : Color.parseColor("#33FF1744"));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 10;
        params.gravity = isUser ? android.view.Gravity.END : android.view.Gravity.START;
        params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        bubble.setLayoutParams(params);

        chatLog.addView(bubble);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onStart() {
        super.onStart();
        ConversationBus.addListener(this);
        ListeningStateBus.addListener(this);
        applyListeningUi(ListeningStateBus.isListening());
    }

    @Override
    protected void onStop() {
        super.onStop();
        ConversationBus.removeListener(this);
        ListeningStateBus.removeListener(this);
    }

    @Override
    public void onBackPressed() {
        playCircularHideThenFinish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // PHASE 8: deliberately does NOT stop listening here. Continuous mode is owned by
        // UltronService, not this Activity - closing the HUD (back/X) should let Ultron keep
        // listening in the background exactly like the user asked, until they explicitly say
        // "stop listening" or tap the mic again next time they open the HUD.
    }
}
