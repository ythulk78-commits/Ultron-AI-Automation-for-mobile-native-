package com.ultron.assistant;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple parsed-command data holder shared by CommandParser and ActionExecutor
 * across all phases. "type" tells ActionExecutor which handler to route to.
 */
public class UltronCommand {

    public enum Type {
        OPEN_APP,
        SYSTEM_CONTROL,
        AUTOMATION,        // handled from Phase 3 onward
        CUSTOM_COMMAND,    // Phase 6 - user-defined shortcut ("Night Mode")
        SCHEDULE,          // Phase 6 - "5 minute baad X karo"
        CHAT,              // Phase 4 - LLM couldn't map an action, so it just answers directly
        UNKNOWN,           // Phase 4 onward: routed to the LLM brain before giving up
        // BUGFIX: added so a single spoken sentence can chain distinct actions across apps
        // (e.g. "gaana bajao phir whatsapp pe maa ko bata do busy hoon" = youtube_music_play
        // THEN whatsapp_send) - previously there was no way to represent more than one action
        // per command at all, so the LLM was structurally limited to a single step.
        MULTI_STEP,
        // PHASE 9: for tasks that need in-app navigation beyond any hardcoded AppAutomationManager
        // flow (e.g. "instagram DM section me jao, aditya search karo, hii bhejo") - handled by
        // AgenticExecutor's screen-dump -> LLM-picks-index -> act -> repeat loop instead of a
        // fixed Step list. params: "goal" (required), "app" (optional starting app name).
        AGENTIC_TASK
    }

    public final Type type;
    public final String action;          // e.g. "open", "volume_set", "send_message"
    public final Map<String, String> params;
    /** Only populated when type == MULTI_STEP: the ordered sub-actions to run one after another. */
    public java.util.List<UltronCommand> subCommands;

    public UltronCommand(Type type, String action) {
        this.type = type;
        this.action = action;
        this.params = new HashMap<>();
    }

    public UltronCommand with(String key, String value) {
        params.put(key, value);
        return this;
    }

    public String get(String key) {
        return params.get(key);
    }
}
