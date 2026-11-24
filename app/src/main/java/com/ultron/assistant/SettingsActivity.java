package com.ultron.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * PHASE 2 (+ Ultron Heart update, + Telegram Bridge) - "Ultron Heart": where the AI brain's power
 * source lives - the OpenRouter API key and model - plus the Telegram Bridge pairing controls.
 * Model is now a free-text field instead of a fixed dropdown, so any OpenRouter model id (free or
 * paid) can be typed in directly; it's written straight to SettingsManager's encrypted storage the
 * moment Save is tapped, same as the API key and the Telegram bot token.
 */
public class SettingsActivity extends Activity {

    private SettingsManager settingsManager;
    private TelegramAuthManager telegramAuthManager;
    private EditText apiKeyInput;
    private EditText modelInput;
    private EditText telegramTokenInput;
    private TextView telegramStatusText;
    private TextView telegramPinDisplay;
    private CountDownTimer pinCountdown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsManager = new SettingsManager(this);
        telegramAuthManager = TelegramAuthManager.get();

        TextView title = findViewById(R.id.settings_title);
        if (title != null) title.setText("Ultron Heart");

        apiKeyInput = findViewById(R.id.api_key_input);
        modelInput = findViewById(R.id.model_input);
        Button saveButton = findViewById(R.id.save_button);

        // Pre-fill existing values
        apiKeyInput.setText(settingsManager.getApiKey());
        String currentModel = settingsManager.getModel();
        if (currentModel != null && !currentModel.isEmpty()) {
            modelInput.setText(currentModel);
        } else {
            modelInput.setHint("e.g. meta-llama/llama-3.1-8b-instruct:free");
        }

        saveButton.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            String model = modelInput.getText().toString().trim();

            if (model.isEmpty()) {
                Toast.makeText(this, "Model name khali nahi ho sakta", Toast.LENGTH_SHORT).show();
                return;
            }

            settingsManager.saveApiKey(key);
            settingsManager.saveModel(model);

            Toast.makeText(this, "Ultron Heart charged - settings saved", Toast.LENGTH_SHORT).show();
            finish();
        });

        setupTelegramBridge();
    }

    private void setupTelegramBridge() {
        telegramTokenInput = findViewById(R.id.telegram_token_input);
        telegramStatusText = findViewById(R.id.telegram_status_text);
        telegramPinDisplay = findViewById(R.id.telegram_pin_display);
        Button saveStartButton = findViewById(R.id.telegram_save_start_button);
        Button stopButton = findViewById(R.id.telegram_stop_button);
        Button generatePinButton = findViewById(R.id.telegram_generate_pin_button);
        Button resetPairingButton = findViewById(R.id.telegram_reset_pairing_button);

        telegramTokenInput.setText(settingsManager.getTelegramBotToken());

        saveStartButton.setOnClickListener(v -> {
            String token = telegramTokenInput.getText().toString().trim();
            if (token.isEmpty()) {
                Toast.makeText(this, "Bot token khali nahi ho sakta", Toast.LENGTH_SHORT).show();
                return;
            }
            settingsManager.saveTelegramBotToken(token);
            Intent serviceIntent = new Intent(this, TelegramBridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "Telegram bridge start ho raha hai", Toast.LENGTH_SHORT).show();
            refreshTelegramStatus();
        });

        stopButton.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, TelegramBridgeService.class);
            stopIntent.setAction(TelegramBridgeService.ACTION_STOP);
            startService(stopIntent);
            Toast.makeText(this, "Telegram bridge stop ho raha hai", Toast.LENGTH_SHORT).show();
            refreshTelegramStatus();
        });

        generatePinButton.setOnClickListener(v -> {
            if (!settingsManager.hasTelegramBotToken()) {
                Toast.makeText(this, "Pehle bot token save karo aur bridge start karo", Toast.LENGTH_SHORT).show();
                return;
            }
            String pin = telegramAuthManager.generatePin();
            showPinCountdown(pin);
        });

        resetPairingButton.setOnClickListener(v -> {
            telegramAuthManager.unbind();
            Toast.makeText(this, "Pairing reset ho gayi - naya PIN generate karo", Toast.LENGTH_SHORT).show();
            refreshTelegramStatus();
        });

        refreshTelegramStatus();
    }

    /** Shows the freshly generated PIN plus a live "expires in Xs" countdown - this text view is
     *  the ONLY place this PIN is ever displayed; it is never transmitted anywhere by the app. */
    private void showPinCountdown(String pin) {
        if (pinCountdown != null) pinCountdown.cancel();
        pinCountdown = new CountDownTimer(TelegramAuthManager.PIN_VALIDITY_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                telegramPinDisplay.setText(pin + "\n(" + (millisUntilFinished / 1000) + "s)");
            }

            @Override
            public void onFinish() {
                telegramPinDisplay.setText("PIN expired - generate a new one");
            }
        };
        pinCountdown.start();
    }

    private void refreshTelegramStatus() {
        String bridgeState = settingsManager.isTelegramBridgeEnabled() ? "running" : "stopped";
        String pairState = telegramAuthManager.isBound() ? "yes (locked to one chat)" : "no";
        telegramStatusText.setText("Bridge: " + bridgeState + "  |  Paired: " + pairState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (telegramStatusText != null) refreshTelegramStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pinCountdown != null) pinCountdown.cancel();
    }
}
