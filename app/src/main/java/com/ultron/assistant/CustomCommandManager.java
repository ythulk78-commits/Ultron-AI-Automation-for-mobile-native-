package com.ultron.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PHASE 6 - "Night Mode" = Brightness 10% + Volume 20% + DND On, spec example.
 * A custom command is just a name plus an ordered list of raw command strings, each of
 * which gets re-parsed through CommandParser and executed in sequence by ActionExecutor.
 * Stored as a JSON array in SharedPreferences, per the spec's storage note.
 */
public class CustomCommandManager {

    private static final String PREFS_NAME = "ultron_custom_commands";
    private static final String KEY_COMMANDS = "commands_json";

    public static class CustomCommand {
        public String name;          // e.g. "night mode" - matched against heard text
        public List<String> steps;   // e.g. ["brightness 10 karo", "volume 20 karo", "dnd on karo"]

        public CustomCommand(String name, List<String> steps) {
            this.name = name;
            this.steps = steps;
        }
    }

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final Type listType = new TypeToken<ArrayList<CustomCommand>>() {}.getType();

    public CustomCommandManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        seedDefaultsIfEmpty();
    }

    private void seedDefaultsIfEmpty() {
        if (!prefs.contains(KEY_COMMANDS)) {
            List<CustomCommand> defaults = new ArrayList<>();
            defaults.add(new CustomCommand("night mode", java.util.Arrays.asList(
                    "brightness 10 karo", "volume 20 karo", "dnd on karo")));
            defaults.add(new CustomCommand("study mode", java.util.Arrays.asList(
                    "dnd on karo", "brightness 60 karo", "volume 0 karo")));
            defaults.add(new CustomCommand("day mode off", java.util.Arrays.asList(
                    "dnd off karo", "brightness 80 karo", "volume 60 karo")));
            saveAll(defaults);
        }
    }

    public synchronized List<CustomCommand> listAll() {
        String json = prefs.getString(KEY_COMMANDS, "[]");
        List<CustomCommand> list = gson.fromJson(json, listType);
        return list != null ? list : new ArrayList<>();
    }

    public synchronized void saveAll(List<CustomCommand> commands) {
        prefs.edit().putString(KEY_COMMANDS, gson.toJson(commands)).apply();
    }

    public synchronized void addOrUpdate(String name, List<String> steps) {
        List<CustomCommand> commands = listAll();
        String key = normalize(name);
        for (CustomCommand c : commands) {
            if (normalize(c.name).equals(key)) {
                c.steps = steps;
                saveAll(commands);
                return;
            }
        }
        commands.add(new CustomCommand(name, steps));
        saveAll(commands);
    }

    public synchronized boolean remove(String name) {
        List<CustomCommand> commands = listAll();
        String key = normalize(name);
        boolean removed = commands.removeIf(c -> normalize(c.name).equals(key));
        if (removed) saveAll(commands);
        return removed;
    }

    /** Finds a custom command whose name appears inside the heard text ("night mode on karo" -> "night mode"). */
    public synchronized CustomCommand findMatching(String heardText) {
        if (heardText == null) return null;
        String text = normalize(heardText);
        for (CustomCommand c : listAll()) {
            if (text.contains(normalize(c.name))) {
                return c;
            }
        }
        return null;
    }

    public synchronized CustomCommand get(String name) {
        String key = normalize(name);
        for (CustomCommand c : listAll()) {
            if (normalize(c.name).equals(key)) return c;
        }
        return null;
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.getDefault());
    }
}
