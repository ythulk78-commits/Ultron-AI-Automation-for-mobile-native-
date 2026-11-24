package com.ultron.assistant;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * PHASE 4 - Thin wrapper around OpenRouter's chat-completions endpoint
 * (https://openrouter.ai/api/v1/chat/completions). Free-tier models only need
 * the API key from SettingsManager - no other auth. Runs fully async via OkHttp's
 * own callback thread, so it never blocks the caller (important since this is
 * invoked right after STT returns, on whatever thread that lands on).
 */
public class OpenRouterClient {

    private static final String TAG = "Ultron.OpenRouter";
    private static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface ChatCallback {
        void onSuccess(String content);
        void onFailure(String reason);
    }

    private final SettingsManager settingsManager;
    private final OkHttpClient http;

    public OpenRouterClient(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * @param systemPrompt instructions (schema, persona, rules)
     * @param userMessage  the actual Hinglish command text (+ any context the caller folded in)
     */
    public void chat(String systemPrompt, String userMessage, ChatCallback callback) {
        if (!settingsManager.hasApiKey()) {
            callback.onFailure("API key set nahi hai, Settings mein daal do");
            return;
        }

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);

        JsonArray messages = new JsonArray();
        messages.add(systemMsg);
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", settingsManager.getModel());
        body.add("messages", messages);
        body.addProperty("temperature", 0.2); // low temp - we want consistent JSON, not creativity
        // BUGFIX: was 300, which silently truncates anything longer than a short intent JSON -
        // an essay/note/HTML body response gets cut mid-string, the JSON becomes invalid, and
        // parsing fails with the generic "AI response samajh nahi aaya" error. 300 is fine for
        // a bare {"type":"automation",...} intent object but not for generated content, so bump
        // it high enough to cover a full note/essay/HTML reply too.
        body.addProperty("max_tokens", 2048);

        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        Request request = new Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer " + settingsManager.getApiKey())
                .addHeader("Content-Type", "application/json")
                // OpenRouter asks for these two for free-tier routing/attribution - harmless if ignored
                .addHeader("HTTP-Referer", "https://ultron.assistant.app")
                .addHeader("X-Title", "Ultron Assistant")
                .post(requestBody)
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Network call failed", e);
                callback.onFailure("Internet nahi hai, offline mode mein hun");
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful()) {
                        handleHttpError(r.code(), callback);
                        return;
                    }
                    String raw = r.body() != null ? r.body().string() : null;
                    String content = extractContent(raw);
                    if (content == null) {
                        callback.onFailure("AI se jawab nahi mila");
                        return;
                    }
                    callback.onSuccess(content);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse OpenRouter response", e);
                    callback.onFailure("AI response samajh nahi aaya");
                }
            }
        });
    }

    private void handleHttpError(int code, ChatCallback callback) {
        switch (code) {
            case 401:
                callback.onFailure("API key galat hai, settings mein check karo");
                break;
            case 429:
                callback.onFailure("API limit aa gayi, settings mein model change karo");
                break;
            default:
                callback.onFailure("API mein problem aa gayi (code " + code + ")");
        }
    }

    private String extractContent(String raw) {
        if (raw == null) return null;
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return null;
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) return null;
            return message.get("content").getAsString();
        } catch (Exception e) {
            Log.e(TAG, "Bad OpenRouter payload: " + raw, e);
            return null;
        }
    }
}
