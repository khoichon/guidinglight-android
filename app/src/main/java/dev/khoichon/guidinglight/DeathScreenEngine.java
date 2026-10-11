package dev.khoichon.guidinglight;

import android.graphics.Color;

import java.util.List;
import java.util.Random;

/**
 * The death-screen sequence engine, ported tick-for-tick from the desktop app's brain.js
 * (itself ported from the Scratch projects). One 30 Hz master tick drives every subsystem;
 * the ticky stepping is the look, so nothing is smoothed or interpolated.
 *
 * Timeline (ms from sequence start):
 *    0        red backdrop, "You Died" sting + artwork at 1250%, static on
 *    0-500    artwork phase A: violent shake, size 1250 -> 875
 *    500-2167 phase B: damped shake, size -> 200
 *    600      white vignette snaps on
 *    750/1500 ambient starts (guiding/curious), retriggers every 60300
 *    1000-2667 cover fades in (mode colour)
 *    2167-3167 phase C: artwork starts fading, size -> 500
 *    2667     backdrop swaps to the mode colour under the opaque cover; cover fades out
 *    3000     first message chunk appears (instant swap, no typewriter)
 *    3100     white vignette off; black vignette fades in
 *    3167-5167 phases D, E: artwork gone by 5167
 *    then     per message: auto-advance after 5950ms or on tap; cover crossfade hides the swap
 *    last     cover goes opaque, text clears, whole overlay fades 1->0 over 1200ms
 *
 * The engine owns no clock: update(now) catches the tick counter up to wall time, so a
 * dropped frame costs a repaint, never a slower sequence.
 */
final class DeathScreenEngine {

    interface Listener {
        void sting();
        void ambientStart(String mode);
        void ambientGain(String mode, float gain);
        void ambientStop(String mode);
        void doneChime();
        /** The end fade completed; remove the overlay. */
        void finished();
    }

    // ---------------------------------------------------------------- constants
    static final float TICK_MS = 1000f / 30f;

    static final int COLOR_RED = Color.rgb(0x26, 0x0a, 0x0a);
    static final int COLOR_GUIDING = Color.rgb(0x0a, 0x16, 0x25);
    static final int COLOR_CURIOUS = Color.rgb(0x29, 0x1d, 0x08);
    static final int TEXT_GUIDING = Color.rgb(176, 234, 242);
    static final int TEXT_CURIOUS = Color.rgb(239, 229, 151);

    private static final int TICK_WHITE_ON = 18;        // 600ms
    private static final int TICK_TEXT = 90;            // 3000ms: first message chunk
    private static final int TICK_WHITE_OFF = 93;       // 3100ms
    private static final int TICK_COVER_IN_START = 31;  // 1000ms
    private static final int TICK_COVER_SWAP = 80;      // 2667ms
    private static final int TICK_COVER_OUT_END = 130;  // 4333ms
    private static final float BLACK_VIGNETTE_STEP = 0.0125f; // 80 ticks to full
    private static final float INTRO_COVER_STEP = 0.02f;      // 50 ticks in, 50 out
    private static final float MSG_ADVANCE_MS = 5950f;
    private static final float COVER_IN_STEP = 0.1f;
    private static final float COVER_IN_STEP_FAST = 0.2f;     // click-advanced
    private static final float COVER_OUT_STEP = 0.1f;
    private static final int HOLD_TICKS = 3;                  // 100ms fully opaque
    private static final float END_FADE_MS = 1200f;
    private static final float AMBIENT_RETRIGGER_MS = 60300f;

    private enum Phase { WAITING, SHOWING, COVER_IN, HOLD, COVER_OUT, DONE }

    // ---------------------------------------------------------------- state (read by OverlayView)
    final String mode;
    final List<String> messages;
    private final Listener listener;
    private final Random random = new Random();

    private long startMs;
    private int tick;
    private boolean done;

    // "You Died" artwork. x/y/size are in 480x360 design units, size in percent.
    boolean ydVisible = true;
    float ydX, ydY, ydDir = 90f, ydSize = 1250f, ydGhost, ydFrame;

    private boolean backdropSwapped, whiteOn, whiteOff;
    private float black, cover;
    private Phase phase = Phase.WAITING;
    private int msgIdx, holdTick;
    private float chunkShownAt, coverRate = COVER_IN_STEP;
    private boolean ending;
    private float endFade;
    private float nextAmbient;

    DeathScreenEngine(String mode, List<String> messages, Listener listener) {
        this.mode = GlConfig.normalMode(mode);
        this.messages = messages.isEmpty() ? java.util.Collections.singletonList("\u2026") : messages;
        this.listener = listener;
        this.nextAmbient = GlConfig.MODE_CURIOUS.equals(this.mode) ? 1500f : 750f;
    }

    // ---------------------------------------------------------------- frame state accessors

    int tick() { return tick; }
    boolean isDone() { return done; }
    float endFade() { return endFade; }
    int backdropColor() { return backdropSwapped ? modeColor() : COLOR_RED; }
    int modeColor() { return GlConfig.MODE_CURIOUS.equals(mode) ? COLOR_CURIOUS : COLOR_GUIDING; }
    int textColor() { return GlConfig.MODE_CURIOUS.equals(mode) ? TEXT_CURIOUS : TEXT_GUIDING; }
    float coverOpacity() { return cover; }
    float vignetteWhite() { return whiteOn && !whiteOff ? 1f : 0f; }
    float vignetteBlack() { return black; }
    int staticFrame() { return tick % 10; }
    boolean textVisible() { return phase == Phase.SHOWING && !ending; }
    String chunk() { return messages.get(Math.min(msgIdx, messages.size() - 1)); }
    float swayX() { return (float) (5 * cosd(tick)); }
    float swayY() { return (float) (3 * sind(tick)); }

    // ---------------------------------------------------------------- driving

    void start(long nowMs) {
        startMs = nowMs;
        listener.sting();
    }

    /** Catch the engine up to wall time. Returns true if at least one tick ran. */
    boolean update(long nowMs) {
        int target = (int) ((nowMs - startMs) / TICK_MS);
        boolean ran = false;
        while (!done && tick < target) {
            step();
            ran = true;
        }
        return ran;
    }

    /** A tap on the screen. */
    void advance() {
        beginTransition(true);
    }

    /** Dismiss: skip straight to the end fade (no chime, like ESC on the desktop). */
    void abort() {
        if (!ending && !done) enterEnding(false);
    }

    // ---------------------------------------------------------------- per-subsystem ticks

    private static double sind(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cosd(double deg) { return Math.cos(Math.toRadians(deg)); }
    private int rand(int lo, int hi) { return lo + random.nextInt(hi - lo + 1); }

    private void step() {
        tick += 1;
        float t = tick * TICK_MS;
        tickYouDied();
        tickIntroCover();
        tickVignettes();
        tickAudio(t);
        tickMessages(t);
        tickEnding();
    }

    private void tickYouDied() {
        if (!ydVisible) return;
        int t = tick;
        if (t <= 15) {
            // phase A: huge and shaking, shrinking 1250 -> 875
            ydFrame += 1000;
            ydX = rand(-10, 10);
            ydY = rand(-10, 10);
            ydDir = (float) (90 + sind(ydFrame) * 10 / ydFrame);
            ydSize -= 25;
        } else if (t <= 65) {
            // phase B: damped shake (jitter shrinks as frame grows), size -> 200
            if (t == 16) ydFrame = 0;
            ydFrame += 10;
            ydX = rand(-500, 500) / ydFrame;
            ydY = rand(-500, 500) / ydFrame;
            ydDir = (float) (90 + sind(ydFrame) * 100 / ydFrame);
            ydSize += (200 - ydSize) / 2;
        } else if (t <= 95) {
            // phase C: fade out while growing, 200 -> 500
            ydGhost = Math.min(100, ydGhost + 2);
            ydSize += 10;
        } else if (t <= 125) {
            // phase D: 500 -> 800
            ydGhost = Math.min(100, ydGhost + 2);
            ydSize += 10;
        } else if (t <= 155) {
            // phase E: the last shroud, 800 -> 830
            ydGhost = Math.min(100, ydGhost + 1.25f);
            ydSize += 1;
        } else {
            ydVisible = false;
        }
    }

    private void tickIntroCover() {
        // Only while no message transition owns the cover.
        if (phase == Phase.COVER_IN || phase == Phase.HOLD || phase == Phase.COVER_OUT) return;
        int t = tick;
        if (t >= TICK_COVER_IN_START && t < TICK_COVER_SWAP) {
            cover = Math.min(1f, cover + INTRO_COVER_STEP);
        } else if (t == TICK_COVER_SWAP) {
            backdropSwapped = true; // under the now-opaque cover
            cover = 1f;
        } else if (t > TICK_COVER_SWAP && t <= TICK_COVER_OUT_END) {
            cover = Math.max(0f, cover - INTRO_COVER_STEP);
        }
    }

    private void tickVignettes() {
        if (tick == TICK_WHITE_ON) whiteOn = true;
        if (tick == TICK_WHITE_OFF) whiteOff = true;
        if (tick >= TICK_WHITE_OFF) black = Math.min(1f, black + BLACK_VIGNETTE_STEP);
    }

    private void tickAudio(float t) {
        if (ending) return; // no more ambient retriggers once the end fade begins
        if (t >= nextAmbient) {
            listener.ambientStart(mode);
            nextAmbient += AMBIENT_RETRIGGER_MS;
        }
    }

    private void tickMessages(float t) {
        if (ending) return;
        switch (phase) {
            case WAITING:
                if (tick >= TICK_TEXT) {
                    phase = Phase.SHOWING;
                    msgIdx = 0;
                    chunkShownAt = t;
                }
                break;
            case SHOWING:
                if (t - chunkShownAt >= MSG_ADVANCE_MS) beginTransition(false);
                break;
            case COVER_IN:
                cover = Math.min(1f, cover + coverRate);
                if (cover >= 1f) {
                    // Cover fully opaque: swap the text now, hidden underneath (or end).
                    if (msgIdx >= messages.size() - 1) {
                        enterEnding(true);
                    } else {
                        msgIdx += 1;
                        chunkShownAt = t; // the timer restarts at the swap, mid-cover
                        phase = Phase.HOLD;
                        holdTick = 0;
                    }
                }
                break;
            case HOLD:
                if (++holdTick >= HOLD_TICKS) phase = Phase.COVER_OUT;
                break;
            case COVER_OUT:
                cover = Math.max(0f, cover - COVER_OUT_STEP);
                if (cover <= 0f) phase = Phase.SHOWING;
                break;
            default:
                break;
        }
    }

    private void beginTransition(boolean clicked) {
        if (phase != Phase.SHOWING || ending) return;
        phase = Phase.COVER_IN;
        coverRate = clicked ? COVER_IN_STEP_FAST : COVER_IN_STEP;
    }

    private void enterEnding(boolean natural) {
        if (ending) return;
        ending = true;
        endFade = 0f;
        phase = Phase.DONE; // text hidden; whatever is on screen fades as one
        cover = 1f;         // stays opaque; the whole overlay fades instead
        if (natural && GlConfig.MODE_GUIDING.equals(mode)) {
            listener.ambientStop(mode);
            listener.doneChime(); // the resolution chime outlives the overlay
        }
    }

    private void tickEnding() {
        if (!ending) return;
        endFade = Math.min(1f, endFade + TICK_MS / END_FADE_MS);
        listener.ambientGain(mode, 1f - endFade);
        if (endFade >= 1f) {
            done = true;
            listener.ambientStop(mode);
            listener.finished();
        }
    }
}
