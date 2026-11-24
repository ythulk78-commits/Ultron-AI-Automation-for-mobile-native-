package com.ultron.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * PHASE 3 + PHASE 5 - App-specific automation flows.
 * Phase 3 (WhatsApp send, Chrome search) is hand-written against the
 * "Launch -> Wait for Window -> Find Node -> Action -> Confirm" pattern with polling.
 * Phase 5 adds a small declarative Step/runFlow engine (below the Phase 3 methods) so every
 * additional app is a short list of steps instead of a new callback pyramid, plus a
 * genericAppSearch() fallback for the 80+ apps in AppPackageMap that don't get a bespoke flow,
 * and two intent-based actions (calling, navigation) that skip UI automation entirely because
 * Android already exposes first-class intents for them.
 */
public class AppAutomationManager {

    private static final String TAG = "Ultron.Automation";
    private static final int POLL_INTERVAL_MS = 300;
    private static final int MAX_POLL_ATTEMPTS = 20; // ~6 seconds max wait per step

    public interface AutomationCallback {
        void onSuccess(String message);
        void onFailure(String reason);
    }

    private final Context context;
    private final AccessibilityNavigator navigator;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public AppAutomationManager(Context context, AccessibilityNavigator navigator) {
        this.context = context.getApplicationContext();
        this.navigator = navigator;
    }

    // ================= WhatsApp send message =================

    public void sendWhatsAppMessage(String contact, String message, AutomationCallback callback) {
        if (!launchApp("com.whatsapp", callback)) return;

        pollFor(() -> firstNonNull(
                        navigator.findNodeById("com.whatsapp:id/menuitem_search"),
                        navigator.findFirstByContentDescription("Search")),
                searchIcon -> {
                    if (searchIcon == null) {
                        logFailure("Search icon nahi mila");
                        callback.onFailure("App mein problem aa gayi");
                        return;
                    }
                    navigator.clickNode(searchIcon);

                    // BUGFIX: was ID-only with zero fallback - if WhatsApp's internal resource ID
                    // changes across an update (it does, periodically) this step used to just die
                    // silently right here. Now falls back to "whatever EditText is on screen",
                    // which after tapping Search is reliably the search box regardless of ID.
                    pollFor(() -> firstNonNull(navigator.findNodeById("com.whatsapp:id/search_src_text"), firstEditText()),
                            searchBox -> {
                                if (searchBox == null) {
                                    logFailure("Search box nahi mila");
                                    callback.onFailure("Search box nahi mila");
                                    return;
                                }
                                navigator.typeText(searchBox, contact);

                                pollFor(() -> navigator.findFirstByText(contact),
                                        contactResult -> {
                                            if (contactResult == null) {
                                                logFailure(contact + " contact result nahi mila");
                                                callback.onFailure(contact + " contact mein nahi mila");
                                                return;
                                            }
                                            navigator.clickNode(contactResult);

                                            pollFor(() -> firstNonNull(
                                                            navigator.findNodeById("com.whatsapp:id/entry"),
                                                            firstNonNull(navigator.findFirstByContentDescription("Type a message"), firstEditText())),
                                                    msgInput -> {
                                                        if (msgInput == null) {
                                                            logFailure("Message box nahi mila");
                                                            callback.onFailure("Message box nahi mila");
                                                            return;
                                                        }
                                                        navigator.typeText(msgInput, message);

                                                        // give WhatsApp's UI a beat to enable/show the send button after text lands
                                                        handler.postDelayed(() -> pollFor(() -> firstNonNull(
                                                                        navigator.findNodeById("com.whatsapp:id/send"),
                                                                        navigator.findFirstByContentDescription("Send")),
                                                                sendBtn -> {
                                                                    if (sendBtn == null) {
                                                                        logFailure("Send button nahi mila");
                                                                        callback.onFailure("Send button nahi mila");
                                                                        return;
                                                                    }
                                                                    navigator.clickNode(sendBtn);
                                                                    callback.onSuccess("Message bhej diya " + contact + " ko");
                                                                }), 300);
                                                    });
                                        });
                            });
                });
    }

    /** DIAGNOSTIC: log the live screen whenever a hand-written (non-runFlow) step fails. */
    private void logFailure(String reason) {
        Log.e(TAG, "Step failed: " + reason + " | current screen:\n" + navigator.dumpVisibleTree());
    }

    // ================= Chrome search =================
    // Uses a direct web-search Intent instead of accessibility automation - Chrome (and every
    // other browser) already handles "open + search" via a standard ACTION_VIEW URL intent, so
    // there's no need to hunt for the address bar and fake a submit at all. More reliable and
    // has zero dependency on AccessibilityNodeInfo's lack of a public "press Enter" action.
    public void chromeSearch(String query, AutomationCallback callback) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query)));
            intent.setPackage("com.android.chrome");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            callback.onSuccess("Chrome mein search kar diya: " + query);
        } catch (android.content.ActivityNotFoundException e) {
            // Chrome not installed/default - fall back to whatever handles web links.
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query)));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                callback.onSuccess(query + " search kar diya");
            } catch (Exception e2) {
                callback.onFailure("Browser nahi khula");
            }
        }
    }

    // ================= PHASE 5: generic flow engine =================
    // Reduces the Phase 3 nested-callback pattern into a declarative list of steps so each
    // new app automation below is ~5 lines instead of a new pyramid of callbacks. Still uses
    // the exact same pollFor() + fallback-chain philosophy underneath.

    /** One step in an automation flow: find a node (with fallback chain baked into the supplier), then act on it. */
    private static class Step {
        final NodeSupplier supplier;
        final String typeText;      // null => click the found node; non-null => set this text into it
        final boolean submitIme;    // after typing, also try tapping a search/send/go button
        final boolean saveAfterType; // after typing, try tapping a Done/Save button (or back-press)
        final String notFoundMessage;

        private Step(NodeSupplier supplier, String typeText, boolean submitIme, boolean saveAfterType, String notFoundMessage) {
            this.supplier = supplier;
            this.typeText = typeText;
            this.submitIme = submitIme;
            this.saveAfterType = saveAfterType;
            this.notFoundMessage = notFoundMessage;
        }

        static Step click(NodeSupplier supplier, String notFoundMessage) {
            return new Step(supplier, null, false, false, notFoundMessage);
        }

        static Step type(NodeSupplier supplier, String text, String notFoundMessage) {
            return new Step(supplier, text, false, false, notFoundMessage);
        }

        static Step typeAndSubmit(NodeSupplier supplier, String text, String notFoundMessage) {
            return new Step(supplier, text, true, false, notFoundMessage);
        }

        /** BUGFIX helper: type text, then explicitly save it (Done/Save button, or back-press fallback). */
        static Step typeAndSave(NodeSupplier supplier, String text, String notFoundMessage) {
            return new Step(supplier, text, false, true, notFoundMessage);
        }
    }

    /** Convenience: id -> text -> content-desc fallback chain, wrapped as a lazy NodeSupplier. */
    private NodeSupplier fallback(String viewId, String text, String contentDesc) {
        return () -> navigator.findWithFallback(viewId, text, contentDesc);
    }

    private void runFlow(java.util.List<Step> steps, String successMessage, AutomationCallback callback) {
        runFlow(steps, 0, successMessage, callback);
    }

    private void runFlow(java.util.List<Step> steps, int index, String successMessage, AutomationCallback callback) {
        if (index >= steps.size()) {
            callback.onSuccess(successMessage);
            return;
        }
        Step step = steps.get(index);
        pollFor(step.supplier, node -> {
            if (node == null) {
                // DIAGNOSTIC: log what the screen actually looks like right now, so a failed
                // selector can be fixed exactly instead of guessed again. Check Logcat, tag
                // "Ultron.Automation", right after this failure happens.
                Log.e(TAG, "Step failed: " + step.notFoundMessage + " | current screen:\n" + navigator.dumpVisibleTree());
                callback.onFailure(step.notFoundMessage);
                return;
            }
            if (step.typeText != null) {
                navigator.typeText(node, step.typeText);
                if (step.submitIme) {
                    handler.postDelayed(() -> {
                        // BUGFIX: try the keyboard's own search/go action on the field itself
                        // FIRST - this is what fixes "voice assistant opens instead of
                        // searching", since it never touches any icon on screen at all. Only
                        // fall back to hunting for a Search/Send/Go icon if that's unsupported
                        // (API < 30) or the field doesn't expose an IME action.
                        boolean submitted = navigator.pressImeAction(node);
                        if (!submitted) {
                            AccessibilityNodeInfo submitNode = findSubmitButton();
                            if (submitNode != null) {
                                navigator.clickNode(submitNode);
                            }
                        }
                        runFlow(steps, index + 1, successMessage, callback);
                    }, 400);
                    return;
                }
                if (step.saveAfterType) {
                    handler.postDelayed(() -> {
                        // BUGFIX: typing text was never followed by anything that actually
                        // saves it. Look for an explicit Done/Save/checkmark affordance first;
                        // if none of those exist, fall back to a back-press, which auto-saves
                        // the draft in ColorOS Notes and most other note apps.
                        AccessibilityNodeInfo saveNode = navigator.findWithFallback(null, "Done", "Save");
                        if (saveNode == null) {
                            saveNode = navigator.findWithFallback(null, "Save", "Done");
                        }
                        if (saveNode != null) {
                            navigator.clickNode(saveNode);
                        } else {
                            navigator.pressBack();
                        }
                        runFlow(steps, index + 1, successMessage, callback);
                    }, 400);
                    return;
                }
            } else {
                navigator.clickNode(node);
            }
            handler.postDelayed(() -> runFlow(steps, index + 1, successMessage, callback), 500);
        });
    }

    /** Best-effort lookup of a search/send/go affordance to tap after typing into a field. */
    private AccessibilityNodeInfo findSubmitButton() {
        String[] labels = {"Search", "Send", "Go", "Submit", "Enter", "Post"};
        for (String label : labels) {
            AccessibilityNodeInfo node = navigator.findWithFallback(null, label, label);
            if (node != null && node.isClickable()) {
                return node;
            }
        }
        return null;
    }

    // ================= PHASE 5: app automations =================

    public void instagramSearchProfile(String username, AutomationCallback callback) {
        if (!launchApp("com.instagram.android", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback(null, "Search", "Search and Explore"), "Search tab nahi mila"),
                Step.type(fallback(null, "Search", null), username, "Search box nahi mila"),
                Step.click(() -> navigator.findFirstByText(username), username + " nahi mila")
        );
        runFlow(steps, username + " ka profile khol diya", callback);
    }

    public void instagramDm(String username, String message, AutomationCallback callback) {
        if (!launchApp("com.instagram.android", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback(null, "Search", "Search and Explore"), "Search tab nahi mila"),
                Step.type(() -> firstNonNull(firstEditText(), navigator.findFirstByContentDescription("Search")), username, "Search box nahi mila"),
                Step.click(() -> navigator.findFirstByText(username), username + " nahi mila"),
                Step.click(fallback(null, "Message", "Message"), "Message button nahi mila"),
                Step.typeAndSubmit(() -> firstNonNull(firstEditText(), navigator.findFirstByContentDescription("Message...")), message, "Message box nahi mila")
        );
        runFlow(steps, message + " bhej diya " + username + " ko", callback);
    }

    /**
     * NEW: opens Instagram straight to the DM inbox (the paper-plane icon on the home feed),
     * without needing a specific contact - for commands like "instagram pe DM section kholo".
     * Tries the stable resource id first (Instagram's inbox icon id has stayed consistent
     * across recent versions), then falls back to the "Direct"/"Messages" content-desc labels
     * different versions have used.
     */
    public void instagramOpenDms(AutomationCallback callback) {
        if (!launchApp("com.instagram.android", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(() -> firstNonNull(
                                navigator.findNodeById("com.instagram.android:id/action_bar_inbox_button"),
                                navigator.findWithFallback(null, "Direct", "Direct")),
                        "DM icon nahi mila")
        );
        runFlow(steps, "DM section khol diya", callback);
    }

    public void telegramSend(String contact, String message, AutomationCallback callback) {
        if (!launchApp("org.telegram.messenger", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback("org.telegram.messenger:id/search_button", null, "Search"), "Search nahi mila"),
                Step.type(fallback("org.telegram.messenger:id/search_edit_text", null, null), contact, "Search box nahi mila"),
                Step.click(() -> navigator.findFirstByText(contact), contact + " nahi mila"),
                Step.typeAndSubmit(fallback("org.telegram.messenger:id/chat_edit_text", null, "Message"), message, "Message box nahi mila")
        );
        runFlow(steps, message + " bhej diya " + contact + " ko", callback);
    }

    public void youtubeSearch(String query, AutomationCallback callback) {
        if (!launchApp("com.google.android.youtube", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback("com.google.android.youtube:id/search_icon", null, "Search"), "Search icon nahi mila"),
                Step.typeAndSubmit(fallback("com.google.android.youtube:id/search_edit_text", null, null), query, "Search box nahi mila"),
                // BUGFIX: result rows have no "Play" label - that's only the player's transport
                // control once a video is already open. Click whatever the first result row is.
                Step.click(() -> navigator.findFirstResultRow(), "Video nahi mila")
        );
        runFlow(steps, query + " play kar raha hoon", callback);
    }

    public void youtubeMusicPlay(String song, AutomationCallback callback) {
        if (!launchApp("com.google.android.apps.youtube.music", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback(null, "Search", "Search"), "Search tab nahi mila"),
                // BUGFIX ("search box nahi mila"): the old supplier looked for exact
                // contentDesc=="Search" again here, which after tapping the Search tab above
                // often re-matches that SAME tab icon (not the new text field that appeared),
                // so typing into it silently failed. Prioritise "whatever EditText is actually
                // on screen right now" - once search mode is open, that's reliably the real
                // search box regardless of its placeholder text or content-desc wording.
                Step.typeAndSubmit(() -> firstNonNull(firstEditText(), navigator.findFirstByContentDescription("Search")),
                        song, "Search box nahi mila"),
                // BUGFIX: was matching exact text == song name, but result rows show decorated/
                // truncated titles ("Song - Artist"), so this almost never matched. Click the
                // first result row instead of requiring an exact text match.
                Step.click(() -> navigator.findFirstResultRow(), song + " nahi mila")
        );
        runFlow(steps, song + " play kar raha hoon", callback);
    }

    public void spotifyPlay(String song, AutomationCallback callback) {
        if (!launchApp("com.spotify.music", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback(null, "Search", "Search"), "Search tab nahi mila"),
                // Same fix as YT Music above: prefer a real EditText over a re-matched icon.
                Step.typeAndSubmit(() -> firstNonNull(firstEditText(), navigator.findFirstByContentDescription("What do you want to listen to?")),
                        song, "Search box nahi mila"),
                // BUGFIX: same issue as YT Music - exact-text match against decorated result
                // titles almost never hits. Click whatever the first result row is.
                Step.click(() -> navigator.findFirstResultRow(), song + " nahi mila")
        );
        runFlow(steps, song + " play kar raha hoon Spotify pe", callback);
    }

    public void notesCreate(String content, AutomationCallback callback) {
        if (!launchApp("com.coloros.note", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback(null, "+", "New note"), "New note button nahi mila"),
                // BUGFIX: this used to be a plain Step.type() which types the text and stops -
                // it never tapped Save, so the note was typed but never actually saved anywhere.
                // typeAndSave looks for a Done/Save/checkmark button after typing, and falls
                // back to a back-press (which auto-saves in ColorOS Notes and most note apps)
                // if no explicit save button is found.
                Step.typeAndSave(() -> navigator.findFirstByContentDescription("Content") != null
                        ? navigator.findFirstByContentDescription("Content")
                        : firstEditText(), content, "Note body nahi mila")
        );
        runFlow(steps, "Note bana di", callback);
    }

    public void flipkartSearch(String query, AutomationCallback callback) {
        if (!launchApp("com.flipkart.android", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.typeAndSubmit(fallback(null, "Search for Products, Brands and More", "Search"), query, "Search box nahi mila")
        );
        runFlow(steps, "Flipkart pe " + query + " search kar diya", callback);
    }

    public void amazonSearch(String query, AutomationCallback callback) {
        if (!launchApp("in.amazon.mShop.android.shopping", callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback(null, null, "Search Amazon.in"), "Search box nahi mila"),
                Step.typeAndSubmit(fallback(null, null, "Search"), query, "Search box nahi mila")
        );
        runFlow(steps, "Amazon pe " + query + " search kar diya", callback);
    }

    /** Swiggy/Blinkit/etc: same generic "tap search, type, submit" shape as Flipkart/Amazon. */
    public void foodSearch(String appPackage, String query, AutomationCallback callback) {
        if (!launchApp(appPackage, callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback(null, null, "Search"), "Search nahi mila"),
                Step.typeAndSubmit(fallback(null, null, "Search"), query, "Search box nahi mila")
        );
        runFlow(steps, query + " search kar diya", callback);
    }

    /**
     * Universal fallback for any app NOT given a dedicated flow above (there are 100+ in the
     * package list - not all get bespoke automation). Launches the app and tries the most
     * common search-entry patterns apps use (search icon by content-desc, generic "Search"
     * text/EditText), per the spec's fallback-chain philosophy applied at the app level too.
     */
    public void genericAppSearch(String packageName, String query, AutomationCallback callback) {
        if (!launchApp(packageName, callback)) return;
        java.util.List<Step> steps = java.util.Arrays.asList(
                Step.click(fallback(null, "Search", "Search"), "Is app mein search nahi mila"),
                Step.typeAndSubmit(() -> firstNonNull(navigator.findFirstByContentDescription("Search"), firstEditText()),
                        query, "Search box nahi mila")
        );
        runFlow(steps, query + " search karne ki koshish kar raha hoon", callback);
    }

    private AccessibilityNodeInfo firstEditText() {
        return findFirstEditText(navigator.getRootNode());
    }

    private AccessibilityNodeInfo findFirstEditText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.EditText".contentEquals(node.getClassName() != null ? node.getClassName() : "")) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findFirstEditText(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    // ================= PHASE 5: intent-based actions (more reliable than UI automation) =================
    // Calling and navigation have first-class Android intents that don't need accessibility
    // node-hunting at all - using them is both more reliable and needs less permission surface
    // than automating the Phone/Maps UI, so Phase 5 uses those instead of the UI-automation
    // pattern above for these two.

    public void callNumber(String number, AutomationCallback callback) {
        if (number == null || number.trim().isEmpty()) {
            callback.onFailure("Number samajh nahi aaya");
            return;
        }
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // No CALL_PHONE grant - fall back to dialer (pre-fills the number, user taps call).
            Intent dial = new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + number));
            dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(dial);
            callback.onSuccess("Dialer khol diya " + number + " ke saath, call permission nahi hai");
            return;
        }
        Intent call = new Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:" + number));
        call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(call);
            callback.onSuccess(number + " ko call kar raha hoon");
        } catch (Exception e) {
            callback.onFailure("Call nahi ho payi");
        }
    }

    public void callContact(String contactName, AutomationCallback callback) {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            callback.onFailure("Contacts permission nahi hai");
            return;
        }
        String number = ContactsHelper.lookupNumber(context, contactName);
        if (number == null) {
            callback.onFailure(contactName + " contact mein nahi mila");
            return;
        }
        callNumber(number, callback);
    }

    /** Turn-by-turn navigation via the standard geo navigation intent - no UI automation needed. */
    public void mapsNavigate(String location, AutomationCallback callback) {
        try {
            Intent navIntent = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(location)));
            navIntent.setPackage("com.google.android.apps.maps");
            navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(navIntent);
            callback.onSuccess(location + " ka rasta dikha raha hoon");
        } catch (Exception e) {
            callback.onFailure("Maps nahi khula");
        }
    }

    // ================= Shared helpers =================

    private boolean launchApp(String packageName, AutomationCallback callback) {
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            callback.onFailure("App install nahi hai");
            return false;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launchIntent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch " + packageName, e);
            callback.onFailure("App open nahi hui");
            return false;
        }
    }

    private interface NodeSupplier {
        AccessibilityNodeInfo get();
    }

    private interface NodeConsumer {
        void accept(AccessibilityNodeInfo node);
    }

    /** Polls for a node every POLL_INTERVAL_MS, up to MAX_POLL_ATTEMPTS times, then calls back with what it found (or null). */
    private void pollFor(NodeSupplier supplier, NodeConsumer onResolved) {
        pollFor(supplier, onResolved, 0);
    }

    private void pollFor(NodeSupplier supplier, NodeConsumer onResolved, int attempt) {
        AccessibilityNodeInfo node = supplier.get();
        if (node != null || attempt >= MAX_POLL_ATTEMPTS) {
            onResolved.accept(node);
            return;
        }
        handler.postDelayed(() -> pollFor(supplier, onResolved, attempt + 1), POLL_INTERVAL_MS);
    }

    private AccessibilityNodeInfo firstNonNull(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
        return a != null ? a : b;
    }
}
