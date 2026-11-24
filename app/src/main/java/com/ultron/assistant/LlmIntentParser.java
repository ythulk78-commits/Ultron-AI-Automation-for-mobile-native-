package com.ultron.assistant;

import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * PHASE 4 - Builds the system prompt that turns OpenRouter into a strict JSON intent
 * extractor (not a chatty assistant), and parses its reply back into an UltronCommand.
 * This is the "LLM fallback" half of the Phase 4 hybrid parser: CommandParser (regex)
 * runs first and is free/instant; this only fires when that returns UNKNOWN.
 */
public class LlmIntentParser {

    private static final String TAG = "Ultron.LlmParser";

    // Keep this list in sync with ActionExecutor's automation switch (AppAutomationManager).
    private static final String KNOWN_AUTOMATIONS =
            "whatsapp_send, chrome_search, instagram_dm, instagram_search, instagram_open_dms, telegram_send, " +
            "youtube_search, youtube_music_play, spotify_play, notes_create, call_contact, " +
            "call_number, maps_navigate, flipkart_search, amazon_search, food_search, " +
            "app_search (generic: works for any app not in this list - params: app, query)";

    private static final String SYSTEM_PROMPT_TEMPLATE =
            "You are the intent-extraction brain for an Android voice assistant called Ultron, " +
            "used with Hinglish (Hindi+English mixed) voice commands. " +
            "A rule-based parser already tried and failed to match the user's command, so it's your turn. " +
            "Reply with STRICT JSON ONLY - no markdown fences, no explanation, no extra text. Schema for a SINGLE action:\n" +
            "{\"type\":\"open_app|system_control|automation|agentic|chat\",\"action\":\"<action id>\",\"params\":{...},\"reply\":\"<hinglish text, only for type=chat>\"}\n\n" +
            "Schema for MULTIPLE distinct actions in one sentence (see the multi-step rule below):\n" +
            "{\"steps\":[ {\"type\":...,\"action\":...,\"params\":{...}}, {\"type\":...,\"action\":...,\"params\":{...}} ]}\n\n" +
            "Rules:\n" +
            "- type=open_app -> action=\"open\", params={\"app\":\"<spoken app name>\"}\n" +
            "- type=system_control -> action is one of: volume_set,volume_up,volume_down,brightness_set,lock,torch_on,torch_off,wifi_on,wifi_off,bluetooth_on,bluetooth_off,dnd_on,dnd_off. " +
            "volume_set/brightness_set need params={\"value\":\"0-100\"}\n" +
            "- type=automation -> action is one of: " + KNOWN_AUTOMATIONS + ". Fill params with whatever the flow needs (contact, message, query, app, number, location, song, artist, content).\n" +
            "- type=agentic -> use this when the task needs multi-step navigation INSIDE an app that " +
            "none of the fixed automation ids above cover - e.g. \"instagram khol kar DM section me jao, " +
            "aditya ko search karo aur usko hii bhejo\" isn't just instagram_dm (that only handles a " +
            "direct DM-by-username in one shot) if the user describes a specific on-screen path/sequence, " +
            "or names an app+action combo with no matching automation id at all. action=\"run\", " +
            "params={\"goal\":\"<the full task restated clearly and completely in Hinglish, including every " +
            "detail needed - contact names, message text, what screen/section to reach>\", " +
            "\"app\":\"<the app to open first, if obvious>\"}. This runs a slower screen-by-screen AI-driven " +
            "flow, so only use it when automation genuinely doesn't already cover the task - prefer a plain " +
            "automation id whenever one fits.\n" +
            "- IMPORTANT: users often describe ONE task as several casual clauses, e.g. " +
            "\"whatsapp open kro and search kro aditya new and usko msg send kro\". This is still " +
            "ONE automation action (whatsapp_send) with contact=\"aditya\" and message=\"<whatever text they want sent>\" " +
            "- do NOT reply with just open_app and drop the rest. Read the whole sentence, figure out the " +
            "single underlying app-automation task, and fill every param it needs from across all the clauses.\n" +
            "- MULTI-STEP: only use the \"steps\" array form when the sentence genuinely describes more than one " +
            "DIFFERENT action id, usually across different apps - e.g. \"gaana bajao phir maa ko whatsapp pe bata do busy hoon\" " +
            "is youtube_music_play THEN whatsapp_send, two different action ids, so use \"steps\". " +
            "But \"notes kholo aur essay likho aur save karo\" is still just ONE action (notes_create with the essay text " +
            "as params.content) - a single action described across several clauses is NOT a multi-step case. " +
            "When you do use \"steps\", each entry is a normal single-action object (same shape as the single schema above, " +
            "minus \"reply\") and they run strictly in the order you list them.\n" +
            "- When an action needs generated text content (e.g. notes_create's params.content for an essay/note the user " +
            "asked you to write), actually write that full content into params.content yourself in Hinglish/English as " +
            "appropriate - don't leave it as a placeholder or a summary of what should go there.\n" +
            "- type=chat -> the command isn't a device action at all (a question, small talk, or anything else) - just answer briefly in Hinglish via \"reply\". Keep it under 2 sentences.\n" +
            "- If genuinely ambiguous, prefer type=chat and ask a short clarifying question as \"reply\".\n\n" +
            "Recent conversation for context (most recent last):\n%s";

    public interface ParseCallback {
        void onParsed(UltronCommand command);
    }

    private final OpenRouterClient client;
    private final ContextMemory contextMemory;

    public LlmIntentParser(OpenRouterClient client, ContextMemory contextMemory) {
        this.client = client;
        this.contextMemory = contextMemory;
    }

    public void parse(String heardText, ParseCallback callback) {
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, contextMemory.asPromptContext());

        client.chat(systemPrompt, heardText, new OpenRouterClient.ChatCallback() {
            @Override
            public void onSuccess(String content) {
                UltronCommand command = toCommand(content);
                callback.onParsed(command);
            }

            @Override
            public void onFailure(String reason) {
                callback.onParsed(new UltronCommand(UltronCommand.Type.CHAT, "reply").with("reply", reason));
            }
        });
    }

    private UltronCommand toCommand(String content) {
        try {
            String cleaned = stripCodeFences(content);
            JsonObject obj = JsonParser.parseString(cleaned).getAsJsonObject();

            // BUGFIX: multi-step support. If the LLM decided this sentence needs more than one
            // distinct action (e.g. play a song THEN send a whatsapp message), it replies with
            // a top-level "steps" array instead of a single object. Build a MULTI_STEP command
            // whose subCommands are each parsed the same way a single object would be.
            if (obj.has("steps") && obj.get("steps").isJsonArray()) {
                com.google.gson.JsonArray steps = obj.getAsJsonArray("steps");
                UltronCommand multi = new UltronCommand(UltronCommand.Type.MULTI_STEP, "multi_step");
                multi.subCommands = new java.util.ArrayList<>();
                for (com.google.gson.JsonElement el : steps) {
                    if (el.isJsonObject()) {
                        multi.subCommands.add(parseSingleAction(el.getAsJsonObject()));
                    }
                }
                if (multi.subCommands.isEmpty()) {
                    return new UltronCommand(UltronCommand.Type.CHAT, "reply")
                            .with("reply", "Samajh nahi aaya, dubara bolo");
                }
                return multi;
            }

            return parseSingleAction(obj);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't parse LLM JSON: " + content, e);
            return new UltronCommand(UltronCommand.Type.CHAT, "reply")
                    .with("reply", "AI response samajh nahi aaya, dubara bolo");
        }
    }

    /** Parses one {"type":...,"action":...,"params":{...}} object - used for both the single-action
     *  reply shape and each entry inside a multi-step "steps" array. */
    private UltronCommand parseSingleAction(JsonObject obj) {
        try {
            String typeStr = obj.has("type") ? obj.get("type").getAsString() : "chat";
            String action = obj.has("action") ? obj.get("action").getAsString() : "reply";

            UltronCommand.Type type;
            switch (typeStr.toLowerCase()) {
                case "open_app": type = UltronCommand.Type.OPEN_APP; break;
                case "system_control": type = UltronCommand.Type.SYSTEM_CONTROL; break;
                case "automation": type = UltronCommand.Type.AUTOMATION; break;
                case "agentic": type = UltronCommand.Type.AGENTIC_TASK; action = "run"; break;
                default: type = UltronCommand.Type.CHAT; action = "reply";
            }

            UltronCommand command = new UltronCommand(type, action);

            if (obj.has("params") && obj.get("params").isJsonObject()) {
                JsonObject params = obj.getAsJsonObject("params");
                for (Map.Entry<String, com.google.gson.JsonElement> entry : params.entrySet()) {
                    if (!entry.getValue().isJsonNull()) {
                        command.with(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            if (obj.has("reply") && !obj.get("reply").isJsonNull()) {
                command.with("reply", obj.get("reply").getAsString());
            } else if (type == UltronCommand.Type.CHAT) {
                command.with("reply", "Samajh nahi aaya, dubara bolo");
            }
            return command;
        } catch (Exception e) {
            Log.e(TAG, "Couldn't parse LLM action object: " + obj, e);
            return new UltronCommand(UltronCommand.Type.CHAT, "reply")
                    .with("reply", "AI response samajh nahi aaya, dubara bolo");
        }
    }

    private String stripCodeFences(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(json)?", "").trim();
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }
}
