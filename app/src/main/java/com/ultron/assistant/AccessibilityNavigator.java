package com.ultron.assistant;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * PHASE 3 - Low-level node-finding + interaction helpers used by AppAutomationManager.
 * Implements the fallback chain from the spec:
 *   1. findAccessibilityNodeInfosByViewId()
 *   2. findAccessibilityNodeInfosByText()
 *   3. findAccessibilityNodeInfosByContentDescription() (text search covers most content-desc
 *      matches on modern Android, so we search text first and only special-case desc below)
 *   4. class name + index (left as a manual TODO hook - very device/app specific)
 *   5. coordinate-based click (last resort, exposed via clickAt)
 */
public class AccessibilityNavigator {

    private final UltronAccessibilityService service;

    public AccessibilityNavigator(UltronAccessibilityService service) {
        this.service = service;
    }

    private AccessibilityNodeInfo root() {
        return service.getRootInActiveWindow();
    }

    /** Public escape hatch for callers (Phase 5 flows) that need to walk the tree themselves. */
    public AccessibilityNodeInfo getRootNode() {
        return root();
    }

    public AccessibilityNodeInfo findNodeById(String viewId) {
        AccessibilityNodeInfo root = root();
        if (root == null || viewId == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        return (nodes != null && !nodes.isEmpty()) ? nodes.get(0) : null;
    }

    public List<AccessibilityNodeInfo> findNodesByText(String text) {
        AccessibilityNodeInfo root = root();
        if (root == null || text == null) return null;
        return root.findAccessibilityNodeInfosByText(text);
    }

    public AccessibilityNodeInfo findFirstByText(String text) {
        List<AccessibilityNodeInfo> nodes = findNodesByText(text);
        return (nodes != null && !nodes.isEmpty()) ? nodes.get(0) : null;
    }

    /**
     * Content-description search: walks the tree since there's no built-in
     * findByContentDescription API. Used as fallback step 3.
     */
    public AccessibilityNodeInfo findFirstByContentDescription(String desc) {
        return searchByDesc(root(), desc);
    }

    private AccessibilityNodeInfo searchByDesc(AccessibilityNodeInfo node, String desc) {
        if (node == null || desc == null) return null;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().equalsIgnoreCase(desc)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = searchByDesc(child, desc);
            if (result != null) return result;
        }
        return null;
    }

    /** Tries id -> text -> content-description, in that order. Fallback chain steps 1-3. */
    public AccessibilityNodeInfo findWithFallback(String viewId, String text, String contentDesc) {
        AccessibilityNodeInfo node = viewId != null ? findNodeById(viewId) : null;
        if (node != null) return node;

        node = text != null ? findFirstByText(text) : null;
        if (node != null) return node;

        return contentDesc != null ? findFirstByContentDescription(contentDesc) : null;
    }

    public boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        // Walk up to find a clickable ancestor if the matched node itself isn't clickable
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        if (target == null) return false;
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    public boolean typeText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    public boolean scrollDown() {
        AccessibilityNodeInfo root = root();
        if (root == null) return false;
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    public boolean scrollUp() {
        AccessibilityNodeInfo root = root();
        if (root == null) return false;
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    public boolean pressBack() {
        return service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
    }

    public boolean pressHome() {
        return service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
    }

    public boolean pressRecents() {
        return service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS);
    }

    /**
     * BUGFIX helper: after a search (YouTube/YT Music/Spotify), result rows almost never carry
     * a literal "Play" label or text that exactly matches the search query - YouTube result
     * cards have no "Play" text at all (that's only the label of the transport control once a
     * video is already open), and music apps show truncated/decorated titles like
     * "Song Name - Artist" rather than the raw query. Fallback-by-exact-text/content-desc was
     * silently failing on all of these, so the flow searched and then just stopped. This walks
     * the tree for the first scrollable results container and returns its first clickable
     * descendant - i.e. "whatever the first result row is", independent of its label.
     */
    public AccessibilityNodeInfo findFirstResultRow() {
        AccessibilityNodeInfo root = root();
        if (root == null) return null;
        AccessibilityNodeInfo container = findScrollableContainer(root);
        return findFirstClickableDescendant(container != null ? container : root);
    }

    private AccessibilityNodeInfo findScrollableContainer(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findScrollableContainer(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstClickableDescendant(AccessibilityNodeInfo node) {
        if (node == null) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            if (child.isClickable()) return child;
            AccessibilityNodeInfo deeper = findFirstClickableDescendant(child);
            if (deeper != null) return deeper;
        }
        return null;
    }

    /**
     * BUGFIX (root cause of "YouTube voice assistant opens instead of searching"): the old code
     * hunted for a "Search"/"Send"/"Go" labelled icon and clicked it after typing - but on
     * YouTube's search bar there's also a mic/voice-search icon sitting right next to the real
     * submit icon, and depending on the exact OEM/app version its content-desc can collide with
     * the same generic label, so the wrong icon got tapped and voice search launched instead.
     * This instead presses the keyboard's own "search/go" action directly ON the text field via
     * ACTION_IME_ENTER - the same action a physical Enter-on-search-IME does - so it never has
     * to guess which icon on screen is the right one. Needs API 30+; returns false below that
     * (or if the node has no such IME action) so the caller can fall back to icon-hunting.
     */
    public boolean pressImeAction(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (android.os.Build.VERSION.SDK_INT < 30) return false;
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    /**
     * DIAGNOSTIC: dumps every visible node's id/text/content-desc/class/clickable state to a
     * single string. Call this and Log.d/e it whenever a step's node-lookup fails - without
     * seeing the real on-device UI tree, every selector in this file is a guess (WhatsApp/
     * YouTube/etc change their internal view IDs across updates, so hardcoded ones go stale).
     * Grab this from Logcat (tag "Ultron.Automation") after a failed step and share it back -
     * that's what turns a guess into an exact, guaranteed-correct fix.
     */
    public String dumpVisibleTree() {
        StringBuilder sb = new StringBuilder();
        dumpNode(root(), sb, 0);
        return sb.length() > 0 ? sb.toString() : "(root node null - accessibility service kuch dekh nahi pa raha)";
    }

    private void dumpNode(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 25) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String id = node.getViewIdResourceName();
        if ((text != null && text.length() > 0) || (desc != null && desc.length() > 0) || id != null || node.isClickable()) {
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append(node.getClassName()).append(" | id=").append(id)
                    .append(" | text=\"").append(text).append("\"")
                    .append(" | desc=\"").append(desc).append("\"")
                    .append(" | clickable=").append(node.isClickable())
                    .append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            dumpNode(node.getChild(i), sb, depth + 1);
        }
    }

    /**
     * PHASE 9 - AGENTIC FALLBACK: collects every clickable-or-editable node currently on screen
     * that has *something* identifying about it (text, content-desc, or a resource id), in
     * stable tree order, capped at maxNodes. This is the "grounding" step for AgenticExecutor -
     * instead of hunting for one hardcoded selector, we hand the LLM a numbered menu of
     * everything actually on screen right now and let it pick an index. We deliberately return
     * the live AccessibilityNodeInfo objects (not a serialized copy) so the caller can act on
     * whichever index the LLM picks without a second lookup - the LLM only ever sees the
     * text description built from these, never the objects themselves.
     */
    public List<AccessibilityNodeInfo> collectInteractableNodes(int maxNodes) {
        List<AccessibilityNodeInfo> out = new java.util.ArrayList<>();
        collectInteractable(root(), out, 0, maxNodes);
        return out;
    }

    private void collectInteractable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth, int maxNodes) {
        if (node == null || depth > 30 || out.size() >= maxNodes) return;
        boolean actionable = node.isClickable() || node.isEditable();
        boolean identifiable = (node.getText() != null && node.getText().length() > 0)
                || (node.getContentDescription() != null && node.getContentDescription().length() > 0)
                || node.getViewIdResourceName() != null;
        if (actionable && identifiable) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount() && out.size() < maxNodes; i++) {
            collectInteractable(node.getChild(i), out, depth + 1, maxNodes);
        }
    }

    /** Step 5 fallback: coordinate-based tap via a gesture dispatch. */
    public boolean clickAt(float x, float y) {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50);
        android.accessibilityservice.GestureDescription gesture =
                new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build();
        return service.dispatchGesture(gesture, null, null);
    }
}
