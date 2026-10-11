package dev.khoichon.guidinglight;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Draws one frame of the death screen straight from the engine's state. Layer stack
 * (bottom -> top), same as the desktop overlay: backdrop, message text, cover (hides the
 * text swaps), static, black vignette, white vignette, You Died artwork.
 *
 * Geometry: the Scratch stage is 480x360 design units. Full-bleed layers (backdrop, cover,
 * static, vignettes) stretch to the whole screen; artwork and text use one uniform scale so
 * they keep their proportions in portrait too.
 */
final class OverlayView extends View {

    interface Listener {
        void onAdvance();
        void onAbort();
    }

    private static final long HOLD_TO_DISMISS_MS = 1000;

    private DeathScreenEngine engine;
    private Listener listener;

    private final Paint fill = new Paint();
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint staticPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint vigPaint = new Paint();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();
    private final Rect fullRect = new Rect();

    private RadialGradient vigBlack, vigWhite;
    private float capPerEm = 0.711f;

    // cached text layout
    private StaticLayout layout;
    private String layoutChunk;
    private int layoutWidth;
    private float layoutSize;

    private final Runnable holdAbort = new Runnable() {
        @Override public void run() {
            if (listener != null) listener.onAbort();
        }
    };

    OverlayView(Context c) {
        super(c);
        textPaint.setTypeface(GlAssets.font);
        measureCap();

        // TV static: 10% opacity, hue-shifted 75 degrees (CSS hue-rotate matrix).
        double a = Math.toRadians(75);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        ColorMatrix cm = new ColorMatrix(new float[]{
                0.213f + cos * 0.787f - sin * 0.213f, 0.715f - cos * 0.715f - sin * 0.715f, 0.072f - cos * 0.072f + sin * 0.928f, 0, 0,
                0.213f - cos * 0.213f + sin * 0.143f, 0.715f + cos * 0.285f + sin * 0.140f, 0.072f - cos * 0.072f - sin * 0.283f, 0, 0,
                0.213f - cos * 0.213f - sin * 0.787f, 0.715f - cos * 0.715f + sin * 0.715f, 0.072f + cos * 0.928f + sin * 0.072f, 0, 0,
                0, 0, 0, 0.1f, 0,
        });
        staticPaint.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    void bind(DeathScreenEngine e) {
        engine = e;
        layout = null;
        layoutChunk = null;
        invalidate();
    }

    void setListener(Listener l) { listener = l; }

    /** Real cap height of the loaded font, so the text can be sized by cap height exactly. */
    private void measureCap() {
        Paint p = new Paint();
        p.setTypeface(GlAssets.font);
        p.setTextSize(100f);
        Rect r = new Rect();
        p.getTextBounds("H", 0, 1, r);
        if (r.height() > 10) capPerEm = r.height() / 100f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fullRect.set(0, 0, w, h);
        // Elliptical vignette: horizontal radius 113.87% of width, vertical 151.83% of
        // height (the Scratch gradient's 546.576-unit radius evaluated on the 480x360 stage).
        // Unit-circle gradient + scale matrix = ellipse.
        Matrix m = new Matrix();
        m.setScale(1.1387f * w, 1.5183f * h);
        m.postTranslate(w / 2f, h / 2f);
        vigBlack = new RadialGradient(0, 0, 1f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        vigBlack.setLocalMatrix(m);
        vigWhite = new RadialGradient(0, 0, 1f, 0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP);
        vigWhite.setLocalMatrix(m);
        layout = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final DeathScreenEngine e = engine;
        if (e == null) return;
        final int w = getWidth(), h = getHeight();
        final float s = Math.min(w / 480f, h / 360f); // design unit -> px (uniform)

        // backdrop
        canvas.drawColor(e.backdropColor());

        // message text (below the cover, like Scratch's pen layer)
        if (e.textVisible()) drawText(canvas, e, w, h, s);

        // cover
        float cover = e.coverOpacity();
        if (cover > 0f) {
            fill.setShader(null);
            fill.setColor(e.modeColor());
            fill.setAlpha(Math.round(255 * Math.min(1f, cover)));
            canvas.drawRect(0, 0, w, h, fill);
        }

        // static: one of ten frames per tick, stretched over the whole screen
        Bitmap st = GlAssets.statics[e.staticFrame()];
        if (st != null) canvas.drawBitmap(st, null, fullRect, staticPaint);

        // vignettes
        drawVignette(canvas, vigBlack, e.vignetteBlack(), w, h);
        drawVignette(canvas, vigWhite, e.vignetteWhite(), w, h);

        // You Died artwork: offset + wobble rotation + scale, all from the engine
        Bitmap art = GlAssets.youDied;
        if (e.ydVisible && art != null) {
            float ghost = Math.max(0f, Math.min(100f, e.ydGhost));
            artPaint.setAlpha(Math.round(255 * (1f - ghost / 100f)));
            float aw = GlAssets.ART_W * s, ah = GlAssets.ART_H * s;
            canvas.save();
            canvas.translate(w / 2f + e.ydX * s, h / 2f + e.ydY * s);
            canvas.rotate(e.ydDir - 90f);
            float sc = e.ydSize / 100f;
            canvas.scale(sc, sc);
            rectF.set(-aw / 2f, -ah / 2f, aw / 2f, ah / 2f);
            canvas.drawBitmap(art, null, rectF, artPaint);
            canvas.restore();
        }
    }

    private void drawVignette(Canvas canvas, RadialGradient g, float opacity, int w, int h) {
        if (opacity <= 0f || g == null) return;
        vigPaint.setShader(g);
        vigPaint.setAlpha(Math.round(255 * Math.min(1f, opacity)));
        canvas.drawRect(0, 0, w, h, vigPaint);
    }

    private void drawText(Canvas canvas, DeathScreenEngine e, int w, int h, float s) {
        float size = (20f * s) / capPerEm; // cap height = 20 design units
        int width = Math.round(w * 0.875f);
        String chunk = e.chunk();
        if (layout == null || !chunk.equals(layoutChunk) || width != layoutWidth || size != layoutSize) {
            textPaint.setTextSize(size);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float natural = fm.descent - fm.ascent;
            float add = 1.75f * size - natural; // CSS line-height 1.75 x font size
            layout = StaticLayout.Builder.obtain(chunk, 0, chunk.length(), textPaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(add, 1f)
                    .setIncludePad(false)
                    .build();
            layoutChunk = chunk;
            layoutWidth = width;
            layoutSize = size;
        }
        textPaint.setColor(e.textColor());
        canvas.save();
        canvas.translate((w - width) / 2f + e.swayX() * s, (h - layout.getHeight()) / 2f + e.swayY() * s);
        layout.draw(canvas);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // a tap advances the message (mousedown on the desktop); holding dismisses
                if (listener != null) listener.onAdvance();
                postDelayed(holdAbort, HOLD_TO_DISMISS_MS);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(holdAbort);
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(holdAbort);
        super.onDetachedFromWindow();
    }
}
