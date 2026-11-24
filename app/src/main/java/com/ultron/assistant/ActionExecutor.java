package com.ultron.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Routes a parsed UltronCommand to the right handler (open app / system control / automation)
 * across Phases 1-3, and returns a Hinglish TTS-ready response string via the callback.
 */
public class ActionExecutor {

    public interface ResultCallback {
        void onResult(String spokenResponse);
    }

    private final Context context;
    private final SystemController systemController;
    private final CustomCommandManager customCommandManager;
    private final Scheduler scheduler;
    private final CommandParser commandParser;

    public ActionExecutor(Context context) {
        this.context = context.getApplicationContext();
        this.systemController = new SystemController(context);
        this.customCommandManager = new CustomCommandManager(context);
        this.scheduler = new Scheduler(context);
        this.commandParser = new CommandParser();
    }

    public void execute(UltronCommand command, ResultCallback callback) {
        switch (command.type) {
            case OPEN_APP:
                callback.onResult(handleOpenApp(command.get("app")));
                break;
            case SYSTEM_CONTROL:
                callback.onResult(handleSystemControl(command));
                break;
            case AUTOMATION:
                handleAutomation(command, callback);
                break;
            case AGENTIC_TASK:
                handleAgenticTask(command.get("goal"), command.get("app"), callback);
                break;
            case CUSTOM_COMMAND:
                handleCustomCommand(command.get("name"), callback);
                break;
            case SCHEDULE:
                callback.onResult(handleSchedule(command));
                break;
            case MULTI_STEP:
                // BUGFIX: previously there was no way to represent more than one action per
                // command at all, so a sentence spanning two apps (e.g. "gaana bajao phir
                // whatsapp pe bata do") could never fully execute - only the first half ever
                // ran. This chains the LLM's ordered sub-commands through this same execute()
                // method, one at a time, waiting for each to actually finish before starting
                // the next (reusing the same sequential pattern as custom commands below).
                runSubCommands(command.subCommands, 0, new StringBuilder(), callback);
                break;
            case CHAT:
                callback.onResult(command.get("reply") != null ? command.get("reply") : "Ji bataiye");
                break;
            case UNKNOWN:
            default:
                callback.onResult("Samajh nahi aaya, dubara bolo");
        }
    }

    // ---------------- Phase 1: open app ----------------

    private String handleOpenApp(String spokenAppName) {
        if (spokenAppName == null) return "App ka naam samajh nahi aaya";

        String packageName = AppPackageMap.resolve(spokenAppName);
        if (packageName == null) {
            return spokenAppName + " App install nahi hai ya list mein nahi hai";
        }

        PackageManager pm = context.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            return "App install nahi hai";
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launchIntent);
            return spokenAppName + " khol raha hoon";
        } catch (Exception e) {
            return "App open nahi hui";
        }
    }

    // ---------------- Phase 2: system control ----------------

    private String handleSystemControl(UltronCommand command) {
        switch (command.action) {
            case "volume_set": {
                int value = safeInt(command.get("value"), 50);
                systemController.setVolumePercent(value);
                return "Volume " + value + " percent kar diya";
            }
            case "volume_up":
                systemController.volumeUp();
                return "Volume badha diya";
            case "volume_down":
                systemController.volumeDown();
                return "Volume kam kar diya";
            case "brightness_set": {
                int value = safeInt(command.get("value"), 50);
                boolean ok = systemController.setBrightnessPercent(value);
                return ok ? "Brightness " + value + " percent kar di"
                        : "Settings permission nahi hai brightness ke liye";
            }
            case "lock": {
                boolean ok = systemController.lockScreen();
                return ok ? "Phone lock kar raha hoon" : "Device admin enable nahi hai";
            }
            case "torch_on": {
                boolean ok = systemController.setTorch(true);
                return ok ? "Torch on kar diya" : "Torch nahi mila is device pe";
            }
            case "torch_off": {
                boolean ok = systemController.setTorch(false);
                return ok ? "Torch off kar diya" : "Torch nahi mila is device pe";
            }
            case "wifi_on":
                systemController.setWifiEnabled(true);
                return "WiFi on karne ki koshish kar raha hoon";
            case "wifi_off":
                systemController.setWifiEnabled(false);
                return "WiFi off karne ki koshish kar raha hoon";
            case "bluetooth_on":
                systemController.setBluetoothEnabled(true);
                return "Bluetooth on kar diya";
            case "bluetooth_off":
                systemController.setBluetoothEnabled(false);
                return "Bluetooth off kar diya";
            case "dnd_on": {
                boolean ok = systemController.setDnd(true);
                return ok ? "DND on kar diya" : "Notification access permission nahi hai";
            }
            case "dnd_off": {
                boolean ok = systemController.setDnd(false);
                return ok ? "DND off kar diya" : "Notification access permission nahi hai";
            }
            default:
                return "Ye system command samajh nahi aaya";
        }
    }

    // ---------------- Phase 3: accessibility automation ----------------

    private void handleAutomation(UltronCommand command, ResultCallback callback) {
        UltronAccessibilityService service = UltronAccessibilityService.getInstance();
        if (service == null) {
            callback.onResult("Settings mein Ultron accessibility enable karo pehle");
            return;
        }
        AppAutomationManager automation = service.getAutomationManager();

        AppAutomationManager.AutomationCallback cb = new AppAutomationManager.AutomationCallback() {
            @Override public void onSuccess(String message) { callback.onResult(message); }
            @Override public void onFailure(String reason) { callback.onResult(reason); }
        };

        switch (command.action) {
            case "whatsapp_send":
                automation.sendWhatsAppMessage(command.get("contact"), command.get("message"), cb);
                break;
            case "chrome_search":
                automation.chromeSearch(command.get("query"), cb);
                break;
            // ---- Phase 5 ----
            case "instagram_dm":
                automation.instagramDm(command.get("username"), command.get("message"), cb);
                break;
            case "instagram_open_dms":
                automation.instagramOpenDms(cb);
                break;
            case "instagram_search":
                automation.instagramSearchProfile(command.get("username"), cb);
                break;
            case "telegram_send":
                automation.telegramSend(command.get("contact"), command.get("message"), cb);
                break;
            case "youtube_search":
                automation.youtubeSearch(command.get("query"), cb);
                break;
            case "youtube_music_play":
                automation.youtubeMusicPlay(command.get("song"), cb);
                break;
            case "spotify_play":
                automation.spotifyPlay(command.get("song"), cb);
                break;
            case "notes_create":
                automation.notesCreate(command.get("content"), cb);
                break;
            case "flipkart_search":
                automation.flipkartSearch(command.get("query"), cb);
                break;
            case "amazon_search":
                automation.amazonSearch(command.get("query"), cb);
                break;
            case "food_search": {
                String appAlias = command.get("app") != null ? command.get("app") : "swiggy";
                String pkg = AppPackageMap.resolve(appAlias);
                automation.foodSearch(pkg != null ? pkg : "in.swiggy.android", command.get("query"), cb);
                break;
            }
            case "call_number":
                automation.callNumber(command.get("number"), cb);
                break;
            case "call_contact":
                automation.callContact(command.get("contact"), cb);
                break;
            case "maps_navigate":
                automation.mapsNavigate(command.get("location"), cb);
                break;
            case "app_search": {
                String appAlias = command.get("app");
                String pkg = appAlias != null ? AppPackageMap.resolve(appAlias) : null;
                if (pkg == null) {
                    callback.onResult(appAlias + " app install nahi hai ya list mein nahi hai");
                } else {
                    automation.genericAppSearch(pkg, command.get("query"), cb);
                }
                break;
            }
            default:
                // PHASE 9: instead of just giving up, hand this off to the agentic fallback with
                // whatever the LLM already extracted (action id + params) folded into a goal
                // sentence - covers the case where the LLM picked an automation id that has no
                // hand-written AppAutomationManager method (e.g. it invents a plausible-sounding
                // action name for a task nobody wrote a Step flow for yet).
                handleAgenticTask(describeAsGoal(command), null, callback);
        }
    }

    /** Turns an automation command the switch above didn't recognise into a plain-language goal
     *  the agent can attempt from scratch (screen-by-screen) instead of failing outright. */
    private String describeAsGoal(UltronCommand command) {
        StringBuilder sb = new StringBuilder(command.action.replace('_', ' '));
        for (java.util.Map.Entry<String, String> entry : command.params.entrySet()) {
            sb.append(" ").append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    // ---------------- Phase 9: agentic fallback (no hardcoded flow exists for this task) ----------------

    private void handleAgenticTask(String goal, String startApp, ResultCallback callback) {
        if (goal == null || goal.trim().isEmpty()) {
            callback.onResult("Samajh nahi aaya kya karna hai, dubara bolo");
            return;
        }
        UltronAccessibilityService service = UltronAccessibilityService.getInstance();
        if (service == null || service.getAgenticExecutor() == null) {
            callback.onResult("Settings mein Ultron accessibility enable karo pehle");
            return;
        }
        service.getAgenticExecutor().runTask(goal, startApp, callback::onResult);
    }

    // ---------------- Phase 6: custom commands ----------------

    private void handleCustomCommand(String name, ResultCallback callback) {
        CustomCommandManager.CustomCommand custom = customCommandManager.get(name);
        if (custom == null) {
            callback.onResult(name + " naam ka custom command nahi mila");
            return;
        }
        runSteps(custom.steps, 0, new StringBuilder(), callback);
    }

    /** Runs each stored step text through the normal parser + this same executor, in order. */
    private void runSteps(java.util.List<String> steps, int index, StringBuilder log, ResultCallback callback) {
        if (steps == null || index >= steps.size()) {
            callback.onResult(log.length() > 0 ? log.toString() : "Custom command chala diya");
            return;
        }
        UltronCommand stepCommand = commandParser.parse(steps.get(index));
        execute(stepCommand, response -> {
            if (log.length() > 0) log.append(", ");
            log.append(response);
            runSteps(steps, index + 1, log, callback);
        });
    }

    /** BUGFIX: runs each LLM-provided sub-command through execute() in order, chaining results. */
    private void runSubCommands(java.util.List<UltronCommand> steps, int index, StringBuilder log, ResultCallback callback) {
        if (steps == null || index >= steps.size()) {
            callback.onResult(log.length() > 0 ? log.toString() : "Ho gaya");
            return;
        }
        execute(steps.get(index), response -> {
            if (log.length() > 0) log.append(", ");
            log.append(response);
            runSubCommands(steps, index + 1, log, callback);
        });
    }

    // ---------------- Phase 6: scheduled actions ----------------

    private String handleSchedule(UltronCommand command) {
        String commandText = command.get("command_text");
        long delayMs = safeLong(command.get("delay_ms"), 0);
        if (commandText == null || delayMs <= 0) {
            return "Schedule samajh nahi aaya";
        }
        boolean armed = scheduler.schedule(commandText, delayMs);
        String when = command.get("display_delay");
        return armed
                ? (when != null ? when + " baad kar dunga: " + commandText : "Schedule kar diya")
                : "Schedule set nahi ho paya";
    }

    private long safeLong(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int safeInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
