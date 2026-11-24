package com.ultron.assistant;

import android.content.Context;

/**
 * PHASE 4 - The AI Brain entry point. Tries the free, instant, offline-capable
 * CommandParser (Phases 1-3) first. Only when that can't confidently match anything
 * (Type.UNKNOWN) does it spend an API call on the OpenRouter LLM. Also checks
 * Phase 6 custom commands and scheduled-action phrasing before falling all the way
 * through to the LLM, since those are cheap regex checks too.
 */
public class HybridCommandRouter {

    public interface RouteCallback {
        void onRouted(UltronCommand command);
    }

    private final CommandParser ruleParser;
    private final LlmIntentParser llmParser;
    private final CustomCommandManager customCommandManager;
    private final ContextMemory contextMemory;

    public HybridCommandRouter(Context context, SettingsManager settingsManager, ContextMemory contextMemory) {
        this.ruleParser = new CommandParser();
        this.llmParser = new LlmIntentParser(new OpenRouterClient(settingsManager), contextMemory);
        this.customCommandManager = new CustomCommandManager(context);
        this.contextMemory = contextMemory;
    }

    public void route(String heardText, RouteCallback callback) {
        // 1) Phase 6 custom command by exact/contained name match, e.g. "night mode on karo"
        CustomCommandManager.CustomCommand custom = customCommandManager.findMatching(heardText);
        if (custom != null) {
            callback.onRouted(new UltronCommand(UltronCommand.Type.CUSTOM_COMMAND, "run")
                    .with("name", custom.name));
            return;
        }

        // 2) Phase 6 scheduled action, e.g. "5 minute baad volume 0 karo"
        UltronCommand scheduled = Scheduler.tryParse(heardText);
        if (scheduled != null) {
            callback.onRouted(scheduled);
            return;
        }

        // 3) Phase 1-3 rule-based parser (instant, no network, no API cost)
        UltronCommand ruleResult = ruleParser.parse(heardText);
        if (ruleResult.type != UltronCommand.Type.UNKNOWN) {
            callback.onRouted(ruleResult);
            return;
        }

        // 4) Phase 4 LLM fallback - only reached when nothing above matched
        llmParser.parse(heardText, callback::onRouted);
    }

    public CustomCommandManager getCustomCommandManager() {
        return customCommandManager;
    }

    public ContextMemory getContextMemory() {
        return contextMemory;
    }
}
