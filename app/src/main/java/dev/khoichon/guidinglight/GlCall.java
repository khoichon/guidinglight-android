package dev.khoichon.guidinglight;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.List;

/**
 * The one entry point for every external call, whether it arrived as an activity intent
 * (CallActivity) or a broadcast (CallReceiver):
 *
 *   guidinglight://show?mode=guiding|curious&message=a%3Bb%3Bc   -> play
 *   guidinglight://abort                                         -> dismiss a running screen
 *   guidinglight://config (or bare / unparseable)                -> open the config screen
 *
 *   action dev.khoichon.guidinglight.action.SHOW  (extras: mode, message | messages[])
 *   action dev.khoichon.guidinglight.action.ABORT
 *   action dev.khoichon.guidinglight.action.CONFIG
 */
final class GlCall {
    static final String PKG = "dev.khoichon.guidinglight";
    static final String ACTION_SHOW = PKG + ".action.SHOW";
    static final String ACTION_ABORT = PKG + ".action.ABORT";
    static final String ACTION_CONFIG = PKG + ".action.CONFIG";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_MESSAGE = "message";   // "a;b;c"
    static final String EXTRA_MESSAGES = "messages"; // String[]
    static final String SCHEME = "guidinglight";

    private GlCall() {}

    /** Must run on the main thread (activities and manifest receivers both do). */
    static void handle(Context context, Intent intent) {
        Context ctx = context.getApplicationContext();
        if (intent == null) { openConfig(ctx); return; }

        Uri data = intent.getData();
        if (data != null && SCHEME.equals(data.getScheme())) {
            String host = data.isHierarchical() ? data.getHost() : null;
            if ("show".equals(host)) {
                String message = data.getQueryParameter("message"); // null when absent
                show(ctx, data.getQueryParameter("mode"), message == null ? null : GlConfig.parse(message));
            } else if ("abort".equals(host)) {
                abort();
            } else {
                openConfig(ctx);
            }
            return;
        }

        String action = intent.getAction();
        if (ACTION_SHOW.equals(action)) {
            String[] arr = intent.getStringArrayExtra(EXTRA_MESSAGES);
            List<String> override;
            if (arr != null) {
                override = GlConfig.parse(String.join(";", arr));
            } else {
                String message = intent.getStringExtra(EXTRA_MESSAGE);
                override = message == null ? null : GlConfig.parse(message);
            }
            show(ctx, intent.getStringExtra(EXTRA_MODE), override);
        } else if (ACTION_ABORT.equals(action)) {
            abort();
        } else {
            openConfig(ctx);
        }
    }

    static void show(Context ctx, String mode, List<String> override) {
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, R.string.toast_need_overlay, Toast.LENGTH_LONG).show();
            try {
                ctx.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { /* background launch can be blocked; the toast stands */ }
            return;
        }

        String m = GlConfig.isMode(mode) ? mode : GlConfig.getMode(ctx);
        List<String> msgs = GlConfig.messagesFor(ctx, m, override);

        OverlayService running = OverlayService.current;
        if (running != null) { // already up: restart the sequence in place
            running.begin(m, msgs);
            return;
        }
        Intent i = new Intent(ctx, OverlayService.class)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_MODE, m)
                .putExtra(EXTRA_MESSAGES, msgs.toArray(new String[0]));
        try {
            ctx.startForegroundService(i);
        } catch (RuntimeException e) {
            Toast.makeText(ctx, R.string.toast_cant_start, Toast.LENGTH_LONG).show();
        }
    }

    static void abort() {
        OverlayService running = OverlayService.current;
        if (running != null) running.abort();
    }

    static void openConfig(Context ctx) {
        try {
            ctx.startActivity(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { /* background launch blocked */ }
    }
}
