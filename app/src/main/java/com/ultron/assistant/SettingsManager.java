package com.ultron.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.security.GeneralSecurityException;
import java.io.IOException;

/**
 * PHASE 2 - Stores the OpenRouter API key + selected model encrypted at rest,
 * via androidx.security's EncryptedSharedPreferences. Falls back to plain
 * SharedPreferences if the keystore isn't available (rare, older/custom ROMs).
 */
public class SettingsManager {

    private static final String TAG = "Ultron.Settings";
    private static final String PREFS_NAME = "ultron_secure_prefs";
    private static final String KEY_API_KEY = "openrouter_api_key";
    private static final String KEY_MODEL = "selected_model";

    // Telegram Bridge - bot token + the one chat_id it's paired to (see TelegramAuthManager for
    // how that pairing happens - PIN generated in-app only, never sent through Telegram itself).
    private static final String KEY_TG_BOT_TOKEN = "telegram_bot_token";
    private static final String KEY_TG_BOUND_CHAT_ID = "telegram_bound_chat_id";
    private static final String KEY_TG_BRIDGE_ENABLED = "telegram_bridge_enabled";
    private static final long NO_CHAT_ID = Long.MIN_VALUE;

    public static final String DEFAULT_MODEL = "meta-llama/llama-3.1-8b-instruct:free";

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        prefs = buildPrefs(context.getApplicationContext());
    }

    private SharedPreferences buildPrefs(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Encrypted prefs unavailable, falling back to plain prefs", e);
            return context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
        }
    }

    public void saveApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply();
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void saveModel(String modelId) {
        prefs.edit().putString(KEY_MODEL, modelId).apply();
    }

    public String getModel() {
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public boolean hasApiKey() {
        return !getApiKey().trim().isEmpty();
    }

    public void saveTelegramBotToken(String token) {
        prefs.edit().putString(KEY_TG_BOT_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public String getTelegramBotToken() {
        return prefs.getString(KEY_TG_BOT_TOKEN, "");
    }

    public boolean hasTelegramBotToken() {
        return !getTelegramBotToken().trim().isEmpty();
    }

    /** The single Telegram chat_id this device is paired to, or NO_CHAT_ID if unpaired. */
    public void saveBoundChatId(long chatId) {
        prefs.edit().putLong(KEY_TG_BOUND_CHAT_ID, chatId).apply();
    }

    public long getBoundChatId() {
        return prefs.getLong(KEY_TG_BOUND_CHAT_ID, NO_CHAT_ID);
    }

    public boolean hasBoundChatId() {
        return getBoundChatId() != NO_CHAT_ID;
    }

    public void clearBoundChatId() {
        prefs.edit().remove(KEY_TG_BOUND_CHAT_ID).apply();
    }

    /** Whether the bridge should come back up on its own (boot, process restart). Only true
     *  while the user hasn't explicitly hit "Stop Bridge". */
    public void setTelegramBridgeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TG_BRIDGE_ENABLED, enabled).apply();
    }

    public boolean isTelegramBridgeEnabled() {
        return prefs.getBoolean(KEY_TG_BRIDGE_ENABLED, false);
    }
}
