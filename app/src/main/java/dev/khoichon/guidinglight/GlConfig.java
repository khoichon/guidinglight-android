package dev.khoichon.guidinglight;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Saved configuration (mode + one message list per mode), same shape and limits as the
 * desktop app's glconfig.json: at most 16 chunks of 500 chars, 4000 chars in total.
 */
final class GlConfig {
    static final String MODE_GUIDING = "guiding";
    static final String MODE_CURIOUS = "curious";

    // The Scratch projects' default message lists, verbatim.
    private static final String[] DEFAULT_GUIDING = {
            "Hello, fellow scratcher!",
            "This is an accurate recreation of Roblox Doors' death message screen aka guiding light screen.",
            "Only exception is the text doesn't rotate because word wrapping does not support rotation :(",
            "Now you may be asking \"How do I make a custom death message out of this?\"",
            "Just click see inside and in the \"Tutorial\" sprite there is a list that you can edit that makes the death messages change!",
            "Go ahead, remix this project. Have fun!!!",
    };
    private static final String[] DEFAULT_CURIOUS = {
            "Oh... Hello.",
            "I'm surprised you found this place...",
            "It's pretty tedious to just get here, last time I checked.",
            "Anyways, what are you here for?",
            "Oh, that...",
            "I see you want to remix this project to create some custom Rooms death messages!",
            "Go to my Doors Death Message project to learn how to use this one.",
            "Have fun, I guess.",
    };

    private static final String PREFS = "glconfig";
    private static final String K_MODE = "Mode";
    private static final String K_GUIDING = "MessagesGuiding";
    private static final String K_CURIOUS = "MessagesCurious";

    private GlConfig() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    /** "a;b;c" (newlines count as separators too) -> trimmed, non-empty chunks. Never null. */
    static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String s : text.replace("\r\n", ";").replace('\n', ';').split(";")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (t.length() > 500) t = t.substring(0, 500);
            out.add(t);
            if (out.size() >= 16) break;
        }
        return out;
    }

    static String normalize(String text) {
        String joined = join(parse(text));
        return joined.length() > 4000 ? joined.substring(0, 4000) : joined;
    }

    static String normalMode(String mode) {
        return MODE_CURIOUS.equals(mode) ? MODE_CURIOUS : MODE_GUIDING;
    }

    static boolean isMode(String mode) {
        return MODE_CURIOUS.equals(mode) || MODE_GUIDING.equals(mode);
    }

    static String getMode(Context c) {
        return normalMode(prefs(c).getString(K_MODE, MODE_GUIDING));
    }

    static void setMode(Context c, String mode) {
        prefs(c).edit().putString(K_MODE, normalMode(mode)).apply();
    }

    static String getMessagesText(Context c, String mode) {
        boolean curious = MODE_CURIOUS.equals(mode);
        String def = join(Arrays.asList(curious ? DEFAULT_CURIOUS : DEFAULT_GUIDING));
        return prefs(c).getString(curious ? K_CURIOUS : K_GUIDING, def);
    }

    static void saveMessagesText(Context c, String mode, String text) {
        String key = MODE_CURIOUS.equals(mode) ? K_CURIOUS : K_GUIDING;
        prefs(c).edit().putString(key, normalize(text)).apply();
    }

    /**
     * Effective chunk list for a mode: the override if it has anything in it, else the
     * saved list, else that mode's defaults.
     */
    static List<String> messagesFor(Context c, String mode, List<String> override) {
        if (override != null && !override.isEmpty()) return override;
        List<String> saved = parse(getMessagesText(c, mode));
        if (!saved.isEmpty()) return saved;
        return new ArrayList<>(Arrays.asList(MODE_CURIOUS.equals(mode) ? DEFAULT_CURIOUS : DEFAULT_GUIDING));
    }
}
