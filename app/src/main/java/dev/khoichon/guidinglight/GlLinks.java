package dev.khoichon.guidinglight;

import android.net.Uri;

import java.util.List;

/** Builds the ready-to-paste ways of calling the app: deep link, adb command, Intent code. */
final class GlLinks {
    private GlLinks() {}

    static String link(String mode, String messages) {
        return GlCall.SCHEME + "://show?mode=" + GlConfig.normalMode(mode)
                + "&message=" + Uri.encode(GlConfig.normalize(messages));
    }

    /** `adb shell "am start ..."` with the message single-quoted for the device shell. */
    static String adb(String mode, String messages) {
        String msg = GlConfig.normalize(messages);
        String inner = "am start -a " + GlCall.ACTION_SHOW
                + " --es mode " + GlConfig.normalMode(mode)
                + " --es message '" + msg.replace("'", "'\\''") + "'";
        String outer = inner.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("$", "\\$").replace("`", "\\`");
        return "adb shell \"" + outer + "\"";
    }

    /** Java snippet for calling from another Android app. */
    static String javaIntent(String mode, String messages) {
        List<String> list = GlConfig.parse(messages);
        StringBuilder arr = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) arr.append(", ");
            arr.append('"').append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return "Intent i = new Intent(\"" + GlCall.ACTION_SHOW + "\")\n"
                + "        .setPackage(\"" + GlCall.PKG + "\")\n"
                + "        .putExtra(\"mode\", \"" + GlConfig.normalMode(mode) + "\")\n"
                + "        .putExtra(\"messages\", new String[]{" + arr + "});\n"
                + "startActivity(i); // or sendBroadcast(i)";
    }
}
