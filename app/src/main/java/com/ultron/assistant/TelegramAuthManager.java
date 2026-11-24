package com.ultron.assistant;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gatekeeper for the Telegram bridge. Nobody gets to run commands through Telegram just by
 * finding the bot or messaging it - they need a 4-digit PIN that ONLY EVER appears on this
 * phone's own screen (SettingsActivity's "Generate Remote PIN" button - see there), typed into
 * Telegram within 5 minutes of being generated. The PIN itself is never sent over Telegram by
 * Ultron, so reading it requires physical access to this unlocked device - the same trust model
 * as a TV/monitor pairing code, not a shared password.
 *
 * The first chat to send a correct PIN gets permanently bound (persisted via SettingsManager) -
 * every other chat is silently ignored from that point on (see TelegramBridgeService), so even a
 * lucky guess after pairing goes nowhere. Wrong guesses before pairing are rate-limited per chat
 * so the small 4-digit space can't just be brute-forced against an unattended bot.
 *
 * Singleton (mirrors UltronCore) so SettingsActivity (which generates the PIN) and
 * TelegramBridgeService (which checks it) - both running in the same app process - see the same
 * in-memory PIN/rate-limit state without needing to persist the short-lived PIN itself.
 */
public class TelegramAuthManager {

    public static final long PIN_VALIDITY_MS = 5 * 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;
    private static final long ATTEMPT_WINDOW_MS = 10 * 60 * 1000L;
    private static final long COOLDOWN_MS = 15 * 60 * 1000L;

    private static volatile TelegramAuthManager instance;

    private final SettingsManager settingsManager;
    private final SecureRandom random = new SecureRandom();

    private String activePin;
    private long pinExpiry;
    private boolean pinUsed = true;

    private final Map<Long, List<Long>> failedAttempts = new HashMap<>();
    private final Map<Long, Long> cooldownUntil = new HashMap<>();

    private TelegramAuthManager(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }

    public static TelegramAuthManager get() {
        if (instance == null) {
            synchronized (TelegramAuthManager.class) {
                if (instance == null) {
                    instance = new TelegramAuthManager(
                            new SettingsManager(UltronApplication.get().getApplicationContext()));
                }
            }
        }
        return instance;
    }

    /** Called only from inside the app (Settings screen) - never over any network channel. */
    public synchronized String generatePin() {
        activePin = String.format("%04d", random.nextInt(10000));
        pinExpiry = System.currentTimeMillis() + PIN_VALIDITY_MS;
        pinUsed = false;
        return activePin;
    }

    public synchronized long pinMillisRemaining() {
        if (activePin == null || pinUsed) return 0;
        return Math.max(0, pinExpiry - System.currentTimeMillis());
    }

    public boolean isBound() {
        return settingsManager.hasBoundChatId();
    }

    public boolean isBoundTo(long chatId) {
        return settingsManager.hasBoundChatId() && settingsManager.getBoundChatId() == chatId;
    }

    /** Unpair - only ever called for the currently-bound chat (via /logout) or from Settings. */
    public synchronized void unbind() {
        settingsManager.clearBoundChatId();
        activePin = null;
        pinUsed = true;
    }

    public synchronized boolean isRateLimited(long chatId) {
        Long until = cooldownUntil.get(chatId);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Attempts to pair chatId using candidate as the PIN. Only succeeds if nobody is bound yet,
     * this chat isn't cooling down from prior failures, and candidate matches the current
     * unexpired, unused, in-memory PIN exactly. Every failure (wrong/expired/reused/no PIN yet)
     * counts against that chat's rate limit regardless of which reason it failed for - never
     * distinguish the reason back to the caller, so a wrong guess can't be used to tell "close"
     * from "no PIN active right now".
     */
    public synchronized boolean tryBind(long chatId, String candidate) {
        if (isBound()) return false;
        if (isRateLimited(chatId)) return false;

        boolean matches = activePin != null && !pinUsed
                && System.currentTimeMillis() <= pinExpiry
                && candidate != null
                && activePin.equals(candidate.trim());

        if (!matches) {
            recordFailure(chatId);
            return false;
        }

        pinUsed = true;
        activePin = null;
        settingsManager.saveBoundChatId(chatId);
        failedAttempts.remove(chatId);
        cooldownUntil.remove(chatId);
        return true;
    }

    private void recordFailure(long chatId) {
        long now = System.currentTimeMillis();
        List<Long> attempts = failedAttempts.get(chatId);
        if (attempts == null) {
            attempts = new ArrayList<>();
            failedAttempts.put(chatId, attempts);
        }
        attempts.add(now);
        List<Long> pruned = new ArrayList<>();
        for (Long t : attempts) {
            if (now - t <= ATTEMPT_WINDOW_MS) pruned.add(t);
        }
        failedAttempts.put(chatId, pruned);
        if (pruned.size() >= MAX_ATTEMPTS) {
            cooldownUntil.put(chatId, now + COOLDOWN_MS);
            failedAttempts.put(chatId, new ArrayList<>());
        }
    }
}
