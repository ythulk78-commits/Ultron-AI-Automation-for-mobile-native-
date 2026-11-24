package com.ultron.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based Hinglish command parser for Phases 1-3.
 * Phase 4 will add an LLM fallback (OpenRouter) for anything this can't confidently parse -
 * for now, anything unmatched returns UltronCommand.Type.UNKNOWN.
 */
public class CommandParser {

    // Phase 1: open app -> "whatsapp khol do", "open chrome", "instagram kholo"
    private static final Pattern OPEN_APP = Pattern.compile(
            "(.+?)\\s*(khol do|kholo|खोल दो|खोलो|open)|open\\s+(.+)",
            Pattern.CASE_INSENSITIVE);

    // Phase 2: volume -> "volume 50 karo", "volume badhao", "volume kam karo", "volume set 30"
    private static final Pattern VOLUME_SET = Pattern.compile(
            "volume\\s*(?:set)?\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLUME_UP = Pattern.compile(
            "volume\\s*(badhao|up|increase)|आवाज़?\\s*(बढ़ाओ|तेज)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLUME_DOWN = Pattern.compile(
            "volume\\s*(kam karo|down|decrease)|आवाज़?\\s*(कम करो)", Pattern.CASE_INSENSITIVE);

    // Phase 2: brightness -> "brightness 80 karo", "brightness set 20"
    private static final Pattern BRIGHTNESS_SET = Pattern.compile(
            "brightness\\s*(?:set)?\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);

    // Phase 2: lock -> "phone lock karo", "screen lock karo"
    private static final Pattern LOCK = Pattern.compile(
            "(phone|screen)?\\s*lock\\s*(kar do|karo)|लॉक\\s*करो", Pattern.CASE_INSENSITIVE);

    // Phase 2: torch -> "torch on karo", "flashlight off karo", "light jala do" / "band karo"
    private static final Pattern TORCH_ON = Pattern.compile(
            "(torch|flashlight|light)\\s*(on|jala do|jalao)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TORCH_OFF = Pattern.compile(
            "(torch|flashlight|light)\\s*(off|band karo|bujha do)", Pattern.CASE_INSENSITIVE);

    // Phase 2: wifi / bluetooth / dnd
    private static final Pattern WIFI_ON = Pattern.compile("wifi\\s*on", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIFI_OFF = Pattern.compile("wifi\\s*off", Pattern.CASE_INSENSITIVE);
    private static final Pattern BT_ON = Pattern.compile("bluetooth\\s*on", Pattern.CASE_INSENSITIVE);
    private static final Pattern BT_OFF = Pattern.compile("bluetooth\\s*off", Pattern.CASE_INSENSITIVE);
    private static final Pattern DND_ON = Pattern.compile("dnd\\s*on|do not disturb\\s*on", Pattern.CASE_INSENSITIVE);
    private static final Pattern DND_OFF = Pattern.compile("dnd\\s*off|do not disturb\\s*off", Pattern.CASE_INSENSITIVE);

    // Phase 3: "whatsapp pe <contact> ko <message> bhejo"
    private static final Pattern WHATSAPP_SEND = Pattern.compile(
            "whatsapp\\s*pe\\s+(.+?)\\s+ko\\s+(.+?)\\s+bhejo", Pattern.CASE_INSENSITIVE);

    // Phase 3: "chrome mein <query> search karo"
    private static final Pattern CHROME_SEARCH = Pattern.compile(
            "chrome\\s*mein\\s+(.+?)\\s+search\\s*karo", Pattern.CASE_INSENSITIVE);

    // Phase 5: "instagram pe <username> ko dm karo <message>"
    private static final Pattern INSTAGRAM_DM = Pattern.compile(
            "instagram\\s*pe\\s+(.+?)\\s+ko\\s+dm\\s*karo\\s+(.+)", Pattern.CASE_INSENSITIVE);
    // Phase 5: "instagram pe <username> ko <message> bhejo"
    private static final Pattern INSTAGRAM_DM_BHEJO = Pattern.compile(
            "instagram\\s*pe\\s+(.+?)\\s+ko\\s+(.+?)\\s+bhejo", Pattern.CASE_INSENSITIVE);
    // Phase 5: "instagram pe <username> search karo" / "instagram pe <username> ka profile khol do"
    private static final Pattern INSTAGRAM_SEARCH = Pattern.compile(
            "instagram\\s*pe\\s+(.+?)\\s+(?:search\\s*karo|ka\\s*profile\\s*khol)", Pattern.CASE_INSENSITIVE);
    // Phase 5: "telegram pe <contact> ko message bhejo <message>"
    private static final Pattern TELEGRAM_SEND = Pattern.compile(
            "telegram\\s*pe\\s+(.+?)\\s+ko\\s+message\\s*bhejo\\s+(.+)", Pattern.CASE_INSENSITIVE);
    // Phase 5: "telegram pe <contact> ko <message> bhejo"
    private static final Pattern TELEGRAM_SEND_BHEJO = Pattern.compile(
            "telegram\\s*pe\\s+(.+?)\\s+ko\\s+(.+?)\\s+bhejo", Pattern.CASE_INSENSITIVE);
    // Phase 5: "youtube pe <video> search karo" / "youtube pe <video> dekho"
    private static final Pattern YOUTUBE_SEARCH = Pattern.compile(
            "youtube\\s*pe\\s+(.+?)\\s+(?:search\\s*karo|dekho|chalao)", Pattern.CASE_INSENSITIVE);
    // Phase 5: "youtube music pe <song> play karo"
    private static final Pattern YT_MUSIC_PLAY = Pattern.compile(
            "youtube\\s*music\\s*pe\\s+(.+?)\\s+play\\s*karo", Pattern.CASE_INSENSITIVE);
    // Phase 5: "spotify pe <song> play karo"
    private static final Pattern SPOTIFY_PLAY = Pattern.compile(
            "spotify\\s*pe\\s+(.+?)\\s+play\\s*karo", Pattern.CASE_INSENSITIVE);
    // Phase 5: "nayi note banao <content>" / "note banao <content>"
    private static final Pattern NOTE_CREATE = Pattern.compile(
            "(?:nayi\\s*)?note\\s*banao\\s+(.+)", Pattern.CASE_INSENSITIVE);
    // Phase 5: "<10-13 digit number> pe call karo"
    private static final Pattern CALL_NUMBER = Pattern.compile(
            "(\\+?\\d{7,13})\\s*pe\\s*call\\s*karo", Pattern.CASE_INSENSITIVE);
    // Phase 5: "<contact> ko call karo"
    private static final Pattern CALL_CONTACT = Pattern.compile(
            "(.+?)\\s+ko\\s*call\\s*karo", Pattern.CASE_INSENSITIVE);
    // Phase 5: "<location> ka rasta dikhado" / "<location> tak rasta dikhado" / "<location> navigate karo"
    private static final Pattern MAPS_NAVIGATE = Pattern.compile(
            "(.+?)\\s*(?:ka|tak)?\\s*rasta\\s*dikha\\s*do|(.+?)\\s*navigate\\s*karo", Pattern.CASE_INSENSITIVE);
    // Phase 5: "flipkart pe <product> search karo" / "flipkart pe <product> kharido"
    private static final Pattern FLIPKART_SEARCH = Pattern.compile(
            "flipkart\\s*pe\\s+(.+?)\\s+(?:search\\s*karo|kharido)", Pattern.CASE_INSENSITIVE);
    // Phase 5: "amazon pe <product> search karo" / "amazon pe <product> kharido"
    private static final Pattern AMAZON_SEARCH = Pattern.compile(
            "amazon\\s*pe\\s+(.+?)\\s+(?:search\\s*karo|kharido)", Pattern.CASE_INSENSITIVE);
    // Phase 5: "swiggy pe <food> search karo" / "swiggy pe <food> order karo"
    private static final Pattern SWIGGY_SEARCH = Pattern.compile(
            "swiggy\\s*pe\\s+(.+?)\\s+(?:search\\s*karo|order\\s*karo)", Pattern.CASE_INSENSITIVE);
    // Phase 5 fallback: "<app> mein <query> search karo" for any app without a bespoke pattern above
    private static final Pattern GENERIC_APP_SEARCH = Pattern.compile(
            "(.+?)\\s*mein\\s+(.+?)\\s+search\\s*karo", Pattern.CASE_INSENSITIVE);

    // BUGFIX: guard for OPEN_APP so it doesn't swallow casual multi-step commands.
    // OPEN_APP is checked last and is intentionally broad ("X kholo"/"X open karo"), but a
    // casual compound command like "whatsapp open kro and search kro aditya ko msg bhejo"
    // also contains "open" as its FIRST clause - without this guard OPEN_APP would match
    // just that first clause, grab "whatsapp" as the app name, and return immediately. The
    // rest of the sentence (search + send) is then silently dropped, and since the router
    // treats any non-UNKNOWN result as final, the LLM fallback (which CAN parse the full
    // casual sentence into one whatsapp_send action) never gets invoked. So: if the sentence
    // has more going on than "just open the app" - a conjunction, or a second action verb
    // like search/send/bhejo/call/dm/play - skip OPEN_APP here and fall through to UNKNOWN,
    // which sends the whole sentence to Phase 4 (LLM) instead.
    private static final Pattern COMPOUND_INDICATOR = Pattern.compile(
            "\\b(and|aur|phir|fir|uske\\s*baad)\\b|search|dhund|bhejo|\\bsend\\b|message|\\bmsg\\b|" +
            "call\\s*karo|dm\\s*karo|play\\s*karo|type\\s*karo",
            Pattern.CASE_INSENSITIVE);

    public UltronCommand parse(String rawText) {
        if (rawText == null) return new UltronCommand(UltronCommand.Type.UNKNOWN, "unrecognized");
        String text = rawText.trim().toLowerCase(Locale.getDefault());

        // --- Phase 3 + Phase 5 automation checks first (most specific patterns) ---
        Matcher m = WHATSAPP_SEND.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "whatsapp_send")
                    .with("contact", m.group(1).trim())
                    .with("message", m.group(2).trim());
        }

        m = CHROME_SEARCH.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "chrome_search")
                    .with("query", m.group(1).trim());
        }

        m = INSTAGRAM_DM.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "instagram_dm")
                    .with("username", m.group(1).trim()).with("message", m.group(2).trim());
        }
        m = INSTAGRAM_DM_BHEJO.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "instagram_dm")
                    .with("username", m.group(1).trim()).with("message", m.group(2).trim());
        }
        m = INSTAGRAM_SEARCH.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "instagram_search")
                    .with("username", m.group(1).trim());
        }
        m = TELEGRAM_SEND.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "telegram_send")
                    .with("contact", m.group(1).trim()).with("message", m.group(2).trim());
        }
        m = TELEGRAM_SEND_BHEJO.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "telegram_send")
                    .with("contact", m.group(1).trim()).with("message", m.group(2).trim());
        }
        m = YT_MUSIC_PLAY.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "youtube_music_play")
                    .with("song", m.group(1).trim());
        }
        m = SPOTIFY_PLAY.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "spotify_play")
                    .with("song", m.group(1).trim());
        }
        m = YOUTUBE_SEARCH.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "youtube_search")
                    .with("query", m.group(1).trim());
        }
        m = NOTE_CREATE.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "notes_create")
                    .with("content", m.group(1).trim());
        }
        m = CALL_NUMBER.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "call_number")
                    .with("number", m.group(1).trim());
        }
        m = MAPS_NAVIGATE.matcher(text);
        if (m.find()) {
            String location = m.group(1) != null ? m.group(1) : m.group(2);
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "maps_navigate")
                    .with("location", location.trim());
        }
        m = FLIPKART_SEARCH.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "flipkart_search")
                    .with("query", m.group(1).trim());
        }
        m = AMAZON_SEARCH.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "amazon_search")
                    .with("query", m.group(1).trim());
        }
        m = SWIGGY_SEARCH.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "food_search")
                    .with("app", "swiggy").with("query", m.group(1).trim());
        }
        // CALL_CONTACT is intentionally broad ("<name> ko call karo") so it's checked after
        // the more specific automation patterns above, but still before the generic fallback.
        m = CALL_CONTACT.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "call_contact")
                    .with("contact", m.group(1).trim());
        }

        // --- Phase 2 system control ---
        m = VOLUME_SET.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "volume_set").with("value", m.group(1));
        }
        if (VOLUME_UP.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "volume_up");
        }
        if (VOLUME_DOWN.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "volume_down");
        }

        m = BRIGHTNESS_SET.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "brightness_set").with("value", m.group(1));
        }

        if (LOCK.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "lock");
        }
        if (TORCH_ON.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "torch_on");
        }
        if (TORCH_OFF.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "torch_off");
        }
        if (WIFI_ON.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "wifi_on");
        }
        if (WIFI_OFF.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "wifi_off");
        }
        if (BT_ON.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "bluetooth_on");
        }
        if (BT_OFF.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "bluetooth_off");
        }
        if (DND_ON.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "dnd_on");
        }
        if (DND_OFF.matcher(text).find()) {
            return new UltronCommand(UltronCommand.Type.SYSTEM_CONTROL, "dnd_off");
        }

        // --- Phase 5 generic "<app> mein <query> search karo" fallback for apps without a
        // bespoke pattern above (routed through AppPackageMap + genericAppSearch) ---
        m = GENERIC_APP_SEARCH.matcher(text);
        if (m.find()) {
            return new UltronCommand(UltronCommand.Type.AUTOMATION, "app_search")
                    .with("app", m.group(1).trim()).with("query", m.group(2).trim());
        }

        // --- Phase 1 open app (checked last since it's the most generic pattern) ---
        // Skipped for compound/casual sentences (see COMPOUND_INDICATOR above) so those
        // fall through to UNKNOWN and get a shot at the LLM fallback instead of getting
        // cut short at "open the app".
        if (!COMPOUND_INDICATOR.matcher(text).find()) {
            m = OPEN_APP.matcher(text);
            if (m.find()) {
                String appName = m.group(1) != null ? m.group(1) : m.group(3);
                if (appName != null) {
                    return new UltronCommand(UltronCommand.Type.OPEN_APP, "open").with("app", appName.trim());
                }
            }
        }

        return new UltronCommand(UltronCommand.Type.UNKNOWN, "unrecognized").with("raw", text);
    }
}
