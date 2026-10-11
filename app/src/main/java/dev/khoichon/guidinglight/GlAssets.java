package dev.khoichon.guidinglight;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/** Process-wide cache of the heavy visual assets (decoded once, reused by every call). */
final class GlAssets {
    private static final String TAG = "GuidingLight";

    // Design-space size of the You Died artwork on the 480x360 Scratch stage.
    static final float ART_W = 421.95096f;
    static final float ART_H = 236.08376f;

    static Bitmap youDied;
    static final Bitmap[] statics = new Bitmap[10];
    static Typeface font = Typeface.SANS_SERIF;
    private static boolean loaded;

    private GlAssets() {}

    static synchronized void load(Context c) {
        if (loaded) return;
        youDied = decode(c, "Sprites/you_died.png");
        for (int i = 0; i < statics.length; i++) {
            statics[i] = decode(c, String.format("Sprites/static%02d.png", i + 1));
        }
        try {
            font = Typeface.createFromAsset(c.getAssets(), "Fonts/Nunito-Regular.ttf");
        } catch (RuntimeException e) {
            Log.w(TAG, "font load failed, using sans-serif", e);
        }
        loaded = true;
    }

    static synchronized void release() {
        loaded = false;
        youDied = null;
        for (int i = 0; i < statics.length; i++) statics[i] = null;
    }

    private static Bitmap decode(Context c, String path) {
        try (InputStream in = c.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            Log.w(TAG, "asset missing: " + path, e);
            return null;
        }
    }
}
