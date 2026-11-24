package com.ultron.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

/**
 * PHASE 9 - THE AGENTIC FALLBACK.
 *
 * HybridCommandRouter/ActionExecutor only reach this class when a task genuinely can't be
 * handled by (a) the free regex CommandParser, or (b) a hand-written AppAutomationManager flow
 * (WhatsApp/YouTube/etc). Those two are fast and free, so they always run first - this is the
 * last resort for arbitrary multi-step in-app navigation the developer never explicitly coded,
 * e.g. "instagram open karo, DM section me jao, aditya search karo, usko hii bhejo".
 *
 * Instead of a fixed Step list with hardcoded selectors, this runs a perceive -> decide -> act
 * loop:
 *   1) PERCEIVE - AccessibilityNavigator.collectInteractableNodes() grabs every clickable/
 *      editable element currently on screen (id/text/desc), each given a plain index number.
 *   2) DECIDE - that indexed list + the original goal + a short history of what's already been
 *      done gets sent to the LLM (OpenRouterClient), which replies with exactly ONE next action
 *      as strict JSON: click/type/scroll/back/open_app/done/fail.
 *   3) ACT - the index the LLM picked maps straight back to the real AccessibilityNodeInfo this
 *      class already holds (the LLM never sees or invents a selector - it only ever picks a
 *      number from a menu we handed it), so there's no hallucinated-selector risk.
 *   4) Wait for the UI to settle, then go back to (1) - until the LLM says the goal is done,
 *      it gives up, or MAX_STEPS is hit (safety cap so a confused loop can't run forever / spam
 *      the OpenRouter API).
 *
 * This makes Ultron's automation open-ended - any app already reachable through Accessibility
 * can in principle be driven this way, not just the ones with a bespoke AppAutomationManager
 * method - at the cost of one LLM call per on-screen step (slower + uses API quota vs the
 * instant hardcoded flows, which is exactly why those are still tried first).
 */
public class AgenticExecutor {

    private static final String TAG = "Ultron.Agent";
    private static final int MAX_STEPS = 15;          // safety cap - never loop forever
    private static final int MAX_ELEMENTS = 60;        // keep the prompt small, fast, cheap
    private static final int SETTLE_DELAY_MS = 900;    // let the new screen finish animating/loading
    private static final int QUICK_DELAY_MS = 500;     // shorter wait after scroll (no navigation)

    public interface AgentCallback {
        void onDone(String message);
    }

    private final Context context;
    private final AccessibilityNavigator navigator;
    private final OpenRouterClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public AgenticExecutor(Context context, AccessibilityNavigator navigator, SettingsManager settingsManager) {
        this.context = context.getApplicationContext();
        this.navigator = navigator;
        this.client = new OpenRouterClient(settingsManager);
    }

    /** @param goal   the task in the user's own words (Hinglish), e.g. the full spoken sentence
     *  @param startApp optional app name to launch before the loop starts (the LLM can also
     *                  choose "open_app" mid-loop if it decides a different app is needed) */
    public void runTask(String goal, String startApp, AgentCallback callback) {
        StringBuilder history = new StringBuilder();
        if (startApp != null && !startApp.trim().isEmpty()) {
            String pkg = AppPackageMap.resolve(startApp);
            if (pkg != null) {
                launchPackage(pkg);
                history.append("- Opened ").append(startApp).append(".\n");
                handler.postDelayed(() -> step(goal, history, 0, null, callback), SETTLE_DELAY_MS);
                return;
            }
        }
        step(goal, history, 0, null, callback);
    }

    private void step(String goal, StringBuilder history, int stepNum, String lastScreenSignature, AgentCallback callback) {
        if (stepNum >= MAX_STEPS) {
            callback.onDone("Task poora nahi ho paya, bahut steps lag gaye - khud try kar lo ya command chhota kar ke bolo");
            return;
        }

        List<AccessibilityNodeInfo> elements = navigator.collectInteractableNodes(MAX_ELEMENTS);
        if (elements.isEmpty()) {
            callback.onDone("Screen pe kuch interact karne layak nahi mila - accessibility permission check karo");
            return;
        }

        String elementsDescription = describeElements(elements);
        String screenSignature = String.valueOf(elementsDescription.hashCode());
        boolean stuck = screenSignature.equals(lastScreenSignature);

        String systemPrompt = buildSystemPrompt(goal, history.toString(), elementsDescription, stuck);

        client.chat(systemPrompt, "Ab agla ek action kya hoga? JSON mein jawab do.", new OpenRouterClient.ChatCallback() {
            @Override
            public void onSuccess(String content) {
                handleAction(content, goal, history, elements, stepNum, screenSignature, callback);
            }

            @Override
            public void onFailure(String reason) {
                callback.onDone(reason);
            }
        });
    }

    private void handleAction(String content, String goal, StringBuilder history,
                               List<AccessibilityNodeInfo> elements, int stepNum,
                               String screenSignature, AgentCallback callback) {
        JsonObject obj;
        try {
            obj = JsonParser.parseString(stripFences(content)).getAsJsonObject();
        } catch (Exception e) {
            Log.e(TAG, "Bad agent JSON: " + content, e);
            callback.onDone("AI response samajh nahi aaya, dubara try karo");
            return;
        }

        String action = obj.has("action") && !obj.get("action").isJsonNull() ? obj.get("action").getAsString() : "fail";

        switch (action) {
            case "done": {
                callback.onDone(textOr(obj, "reply", "Ho gaya"));
                return;
            }
            case "fail": {
                callback.onDone(textOr(obj, "reply", "Ye task nahi ho paya"));
                return;
            }
            case "open_app": {
                String app = textOr(obj, "app", null);
                String pkg = app != null ? AppPackageMap.resolve(app) : null;
                if (pkg == null) {
                    history.append("- Tried opening \"").append(app).append("\" - app not found, need a different approach.\n");
                    handler.postDelayed(() -> step(goal, history, stepNum + 1, null, callback), QUICK_DELAY_MS);
                } else {
                    launchPackage(pkg);
                    history.append("- Opened ").append(app).append(".\n");
                    handler.postDelayed(() -> step(goal, history, stepNum + 1, null, callback), SETTLE_DELAY_MS);
                }
                return;
            }
            case "back": {
                navigator.pressBack();
                history.append("- Pressed back.\n");
                handler.postDelayed(() -> step(goal, history, stepNum + 1, null, callback), SETTLE_DELAY_MS);
                return;
            }
            case "scroll_down": {
                navigator.scrollDown();
                history.append("- Scrolled down.\n");
                handler.postDelayed(() -> step(goal, history, stepNum + 1, null, callback), QUICK_DELAY_MS);
                return;
            }
            case "scroll_up": {
                navigator.scrollUp();
                history.append("- Scrolled up.\n");
                handler.postDelayed(() -> step(goal, history, stepNum + 1, null, callback), QUICK_DELAY_MS);
                return;
            }
            case "click":
            case "type": {
                int index = obj.has("index") && !obj.get("index").isJsonNull() ? obj.get("index").getAsInt() : -1;
                if (index < 0 || index >= elements.size()) {
                    history.append("- Tried to ").append(action).append(" an invalid index (").append(index).append(") - pick only from the list given.\n");
                    handler.postDelayed(() -> step(goal, history, stepNum + 1, screenSignature, callback), QUICK_DELAY_MS);
                    return;
                }
                AccessibilityNodeInfo node = elements.get(index);
                String label = describeOne(node);

                if ("type".equals(action)) {
                    String text = textOr(obj, "text", "");
                    boolean submit = obj.has("submit") && !obj.get("submit").isJsonNull() && obj.get("submit").getAsBoolean();
                    navigator.typeText(node, text);
                    history.append("- Typed \"").append(text).append("\" into ").append(label);
                    if (submit) {
                        boolean pressed = navigator.pressImeAction(node);
                        history.append(pressed ? " and pressed submit/enter" : " (submit not available on this field)");
                    }
                    history.append(".\n");
                } else {
                    boolean clicked = navigator.clickNode(node);
                    history.append("- Clicked ").append(label).append(clicked ? "" : " (click may not have registered)").append(".\n");
                }
                handler.postDelayed(() -> step(goal, history, stepNum + 1, null, callback), SETTLE_DELAY_MS);
                return;
            }
            default:
                callback.onDone("Agent ne samajh se bahar action bola, dubara try karo");
        }
    }

    private String buildSystemPrompt(String goal, String history, String elementsDescription, boolean stuck) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tum ek Android phone chalane wale AI agent ho, ek accessibility service ke through ")
          .append("screen dekh aur control kar sakte ho. Sirf ek time mein EK chhota action decide karo, poora plan nahi.\n\n");
        sb.append("User ka goal: \"").append(goal).append("\"\n\n");
        sb.append("Ab tak jo actions ho chuke hain:\n")
          .append(history.isEmpty() ? "(kuch nahi - ye pehla step hai)\n" : history);
        if (stuck) {
            sb.append("\nWARNING: screen pichli baar jaisi hi hai - pichla action kaam nahi aaya. ")
              .append("Koi doosra element ya action try karo, wahi mat dohrao.\n");
        }
        sb.append("\nAbhi screen pe ye clickable/typeable elements hain (sirf inhi indexes ko use karo):\n")
          .append(elementsDescription);
        sb.append("\n\nSTRICT JSON ONLY reply karo (no markdown fences, no extra text), is schema mein:\n");
        sb.append("{\"action\":\"click|type|scroll_down|scroll_up|back|open_app|done|fail\",")
          .append("\"index\":<element ka index, sirf click/type ke liye>,")
          .append("\"text\":\"<sirf type ke liye>\",")
          .append("\"submit\":<true/false, type ke baad enter/search dabana hai kya>,")
          .append("\"app\":\"<sirf open_app ke liye, app ka naam>\",")
          .append("\"reply\":\"<hinglish mein chhota jawab, sirf done/fail ke liye>\"}\n\n");
        sb.append("Rules:\n");
        sb.append("- Jo index list mein diya hi nahi hai wo kabhi mat use karo.\n");
        sb.append("- Jab goal poora ho chuka ho (message bhej diya, jo maanga tha wo ho gaya), action=\"done\" bolo aur reply mein confirm karo.\n");
        sb.append("- Agar 4-5 alag tareeke try karne ke baad bhi rasta nahi mil raha, ya bilkul galat app/screen khul gaya hai jaha se recover nahi ho sakta, action=\"fail\" bolo aur reply mein wajah batao.\n");
        sb.append("- Search/text field mein type karne ke baad agar submit/enter dabana zaroori hai (jaise search chalane ke liye) toh submit=true rakho.\n");
        return sb.toString();
    }

    private String describeElements(List<AccessibilityNodeInfo> elements) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < elements.size(); i++) {
            sb.append("  ").append(i).append(": ").append(describeOne(elements.get(i))).append('\n');
        }
        sb.append("]");
        return sb.toString();
    }

    private String describeOne(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        StringBuilder sb = new StringBuilder();
        sb.append(node.isEditable() ? "[input] " : "[button] ");
        if (text != null && text.length() > 0) sb.append("text=\"").append(text).append("\" ");
        if (desc != null && desc.length() > 0) sb.append("desc=\"").append(desc).append("\"");
        String result = sb.toString().trim();
        return result.isEmpty() ? (node.isEditable() ? "[input] (unlabeled)" : "[button] (unlabeled)") : result;
    }

    private void launchPackage(String pkg) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch " + pkg, e);
        }
    }

    private String textOr(JsonObject obj, String key, String fallback) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : fallback;
    }

    private String stripFences(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(json)?", "").trim();
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }
}
