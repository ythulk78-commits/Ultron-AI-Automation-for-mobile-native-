package com.ultron.assistant;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin wrapper around Telegram's Bot API - long-poll getUpdates() (blocking, run off the main
 * thread) + fire-and-forget sendMessage(). No chat_id/PIN logic here at all - that's
 * TelegramAuthManager's job; this class only talks to Telegram's HTTP API.
 */
public class TelegramApiClient {

    private static final String TAG = "Ultron.Telegram";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int POLL_TIMEOUT_SECONDS = 25;

    public static class Update {
        public final long updateId;
        public final long chatId;
        public final String text;

        Update(long updateId, long chatId, String text) {
            this.updateId = updateId;
            this.chatId = chatId;
            this.text = text;
        }
    }

    private final String botToken;
    private final OkHttpClient http;

    public TelegramApiClient(String botToken) {
        this.botToken = botToken;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // must comfortably exceed the getUpdates long-poll "timeout" query param below,
                // or OkHttp cuts the connection before Telegram's own long-poll returns.
                .readTimeout(POLL_TIMEOUT_SECONDS + 15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    private String api(String method) {
        return "https://api.telegram.org/bot" + botToken + "/" + method;
    }

    /**
     * Blocking long-poll call - MUST be called from a background thread. Telegram holds the
     * connection open for up to POLL_TIMEOUT_SECONDS waiting for a new message before returning
     * an empty result, so this doubles as the bridge's idle wait - no separate sleep loop needed.
     */
    public List<Update> getUpdates(long offset) throws IOException {
        String url = api("getUpdates")
                + "?timeout=" + POLL_TIMEOUT_SECONDS
                + "&offset=" + offset
                + "&allowed_updates=%5B%22message%22%5D"; // ["message"] url-encoded
        Request request = new Request.Builder().url(url).get().build();
        List<Update> result = new ArrayList<>();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.w(TAG, "getUpdates non-2xx: " + response.code());
                return result;
            }
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (!root.has("ok") || !root.get("ok").getAsBoolean()) return result;

            JsonArray results = root.getAsJsonArray("result");
            if (results == null) return result;

            for (int i = 0; i < results.size(); i++) {
                JsonObject u = results.get(i).getAsJsonObject();
                long updateId = u.get("update_id").getAsLong();
                JsonObject message = u.getAsJsonObject("message");
                if (message == null) continue; // edits, channel posts, etc. - ignore
                JsonObject chat = message.getAsJsonObject("chat");
                if (chat == null || !chat.has("id")) continue;
                long chatId = chat.get("id").getAsLong();
                String text = message.has("text") ? message.get("text").getAsString() : "";
                result.add(new Update(updateId, chatId, text));
            }
        } catch (Exception e) {
            Log.e(TAG, "getUpdates parse failed", e);
        }
        return result;
    }

    /** Fire-and-forget - failures are logged, not surfaced (nothing useful to retry into here). */
    public void sendMessage(long chatId, String text) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("chat_id", chatId);
            body.addProperty("text", text);
            RequestBody requestBody = RequestBody.create(body.toString(), JSON);
            Request request = new Request.Builder().url(api("sendMessage")).post(requestBody).build();

            http.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "sendMessage failed", e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    response.close();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "sendMessage build failed", e);
        }
    }
}
