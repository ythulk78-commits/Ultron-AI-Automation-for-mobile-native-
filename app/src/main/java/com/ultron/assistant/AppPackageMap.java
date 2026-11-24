package com.ultron.assistant;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PHASE 1 - Maps spoken app names (Hindi/English/Hinglish aliases) to Android package names.
 * Extend this map freely - add more entries from your full package list as needed.
 * Multiple aliases can point to the same package (e.g. "whatsapp", "wtsap", "व्हाट्सएप्प").
 */
public class AppPackageMap {

    private static final Map<String, String> ALIAS_TO_PACKAGE = new HashMap<>();

    static {
        put("whatsapp", "com.whatsapp");
        put("whatsapp business", "com.whatsapp.w4b");
        put("business whatsapp", "com.whatsapp.w4b");
        put("instagram", "com.instagram.android");
        put("insta", "com.instagram.android");
        put("telegram", "org.telegram.messenger");
        put("youtube", "com.google.android.youtube");
        put("youtube music", "com.google.android.apps.youtube.music");
        put("spotify", "com.spotify.music");
        put("files", "com.google.android.documentsui");
        put("zarchiver", "ru.zdevs.zarchiver");
        put("chrome", "com.android.chrome");
        put("claude", "com.anthropic.claude");
        put("gemini", "com.google.android.apps.bard");
        put("chatgpt", "com.openai.chatgpt");
        put("notes", "com.coloros.note");
        put("phone", "com.android.incallui");
        put("dialer", "com.android.incallui");
        put("photos", "com.coloros.gallery3d");
        put("gallery", "com.coloros.gallery3d");
        put("flipkart", "com.flipkart.android");
        put("kimi", "com.moonshot.kimichat");
        put("maps", "com.google.android.apps.maps");
        put("google maps", "com.google.android.apps.maps");
        put("blinkit", "com.grofers.customerapp");
        put("ola", "com.olacabs.customer");
        put("phonepe", "com.phonepe.app");
        put("amazon", "in.amazon.mShop.android.shopping");
        put("termux", "com.termux");
        put("settings", "com.android.settings");
        put("calendar", "com.oplus.calendar");
        put("contacts", "com.google.android.contacts");
        put("paytm", "net.one97.paytm");
        put("uber", "com.ubercab");
        put("camera", "com.android.camera2");
        put("calculator", "com.google.android.calculator");
        put("clock", "com.google.android.deskclock");
        put("facebook", "com.facebook.katana");
        put("netflix", "com.netflix.mediaclient");
        put("gpay", "com.google.android.apps.nbu.paisa.user");
        put("google pay", "com.google.android.apps.nbu.paisa.user");
        put("messages", "com.google.android.apps.messaging");
        put("google", "com.google.android.googlequicksearchbox");
        put("swiggy", "in.swiggy.android");
        put("bigbasket", "com.bigbasket.mobileapp");
        put("reddit", "com.reddit.frontpage");
        put("snapchat", "com.snapchat.android");
        put("twitter", "com.twitter.android");
        put("x", "com.twitter.android");
        put("pinterest", "com.pinterest");
        put("truecaller", "com.truecaller");
        put("canva", "com.canva.editor");
        put("tradingview", "com.tradingview.tradingviewapp");

        // ---- Phase 5: rest of the master package list from the spec ----
        put("acpay", "com.india.acpay");
        put("a-ide", "com.m4coding.ide");
        put("aide", "com.m4coding.ide");
        put("androidide", "com.m4coding.ide");
        put("oken scanner", "com.cambyte.okenscan");
        put("mcpro24fps", "lv.mcprotector.mcpro24fps");
        put("trebedit", "com.teejay.trebedit");
        put("nethunter kex", "com.offsec.nethunter.kex");
        put("realme community", "com.realmecomm.app");
        put("citymall", "live.citymall.customer.prod");
        put("blackmagic", "com.blackmagicdesign.android.blackmagiccam");
        put("zen space", "com.oneplus.brickmode");
        put("real cricket go", "com.nautilus.RealCricket3DLite");
        put("shizuku", "moe.shizuku.privileged.api");
        put("block blast", "com.block.juggle");
        put("lichess", "org.lichess.mobileV2");
        put("shareme", "com.xiaomi.midrop");
        put("powervpn", "com.vpn.powervpn2");
        put("muka", "com.seep.muka");
        put("realme store", "com.realmestore.app");
        put("clone phone", "com.coloros.backuprestore");
        put("jiohotstar", "in.startv.hotstar");
        put("hotstar", "in.startv.hotstar");
        put("translate", "com.coloros.translate");
        put("turbo vpn", "free.vpn.unblock.proxy.turbovpn");
        put("akki study x", "com.akki.studyx");
        put("drive", "com.google.android.apps.docs");
        put("google drive", "com.google.android.apps.docs");
        put("chromecast", "com.google.android.apps.chromecast.app");
        put("mi remote", "com.duokan.phone.remotecontroller");
        put("droidcam", "com.dev47apps.obsdroidcam");
        put("package names app", "com.csdroid.pkg");
        put("softonic", "com.softonic.hub");
        put("appgallery", "com.huawei.appmarket");
        put("bat-bms", "com.bms.grenergy");
        put("metatrader", "net.metaquotes.metatrader5");
        put("metatrader 5", "net.metaquotes.metatrader5");
        put("find hub", "com.google.android.apps.adm");
        put("proton vpn", "ch.protonvpn.android");
        put("mobikwik", "com.mobikwik_new");
        put("moviebox", "com.community.oneroom");
        put("ypt", "com.pallo.passiontimerscoped");
        put("rapido", "com.rapido.passenger");
        put("lenskart", "com.lenskart.app");
        put("code studio", "com.alif.ide");
        put("droidfish", "org.petero.droidfish");
        put("bsnl selfcare", "com.digital.bsnl.selfcare");
        put("netmirror", "app.netmirror.netmirrornew");
        put("yaarwin", "com.YaarWin.app");
        put("dominos", "com.Dominos");
        put("domino's", "com.Dominos");
        put("terabox", "com.dubox.drive");
        put("documents", "andes.oplus.documentsreader");
        put("acremote", "com.universal.ac.remote.control.air.conditioner");
        put("tor browser", "org.torproject.torbrowser");
        put("as multiverse", "com.asmultiverse.infinity.ABhi");
        put("study ratna", "com.study.ratna");
        put("authenticator", "com.google.android.apps.authenticator2");
        put("happymod", "com.happymod.apk");
        put("qwen", "ai.qwenlm.chat.android");
        put("qwen studio", "ai.qwenlm.chat.android");
        put("weather", "com.coloros.weather2");
        put("theme store", "com.oplus.themestore");
        put("internet", "com.heytap.browser");
        put("browser", "com.heytap.browser");
        put("music", "com.heytap.music");
        put("oroaming", "com.redteamobile.roaming");
        put("app market", "com.heytap.market");
        put("digital wellbeing", "com.google.android.apps.wellbeing");
        put("heytap cloud", "com.heytap.cloud");
        put("edge gallery", "com.google.ai.edge.gallery");
        put("sudathi", "com.sudathi");
        put("elitestudy", "org.chromium.webapk.a4641c424ea671fef_v2");
        put("elite study", "org.chromium.webapk.a4641c424ea671fef_v2");
        put("studystark", "org.chromium.webapk.a13d1f68a0e5a20a9_v2");
        put("softcode", "org.chromium.webapk.a33b10d15ce566b8c_v2");
        put("depth live wallpaper", "com.jndapp.depth.live.wallpaper");
        put("depthx", "com.appslab.live.wallpapers");
        // Add more aliases below as you need them:
    }

    private static void put(String alias, String packageName) {
        ALIAS_TO_PACKAGE.put(normalize(alias), packageName);
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.getDefault());
    }

    /**
     * Looks up a package name from a spoken app name. Does a normalized exact match first,
     * then falls back to a "contains" match so slightly noisy STT output still resolves.
     */
    public static String resolve(String spokenName) {
        if (spokenName == null || spokenName.trim().isEmpty()) return null;
        String key = normalize(spokenName);

        String exact = ALIAS_TO_PACKAGE.get(key);
        if (exact != null) return exact;

        for (Map.Entry<String, String> entry : ALIAS_TO_PACKAGE.entrySet()) {
            if (key.contains(entry.getKey()) || entry.getKey().contains(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static void addAlias(String alias, String packageName) {
        put(alias, packageName);
    }
}
