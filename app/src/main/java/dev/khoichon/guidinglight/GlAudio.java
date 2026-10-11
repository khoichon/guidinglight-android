package dev.khoichon.guidinglight;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;

/**
 * All death-screen audio. Each sound is a prepared MediaPlayer; the Scratch project's
 * hard-panned left/right ambient pair is reproduced with per-ear volume (the sources are
 * mono, so that is an exact pan), and the end fade is just the shared gain.
 */
final class GlAudio {
    private static final String TAG = "GuidingLight";

    private static final class Snd {
        final MediaPlayer mp = new MediaPlayer();
        final float pan; // -1 = left ear only, +1 = right ear only, 0 = both
        float gain = 1f;
        boolean ok;

        Snd(Context c, String file, float pan) {
            this.pan = pan;
            try (AssetFileDescriptor afd = c.getAssets().openFd("Sounds/" + file)) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                mp.prepare();
                ok = true;
                applyVolume();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "audio load failed: " + file, e);
            }
        }

        void applyVolume() {
            float l = pan > 0 ? 1f - pan : 1f;
            float r = pan < 0 ? 1f + pan : 1f;
            try { mp.setVolume(l * gain, r * gain); } catch (IllegalStateException ignored) { }
        }

        void setGain(float g) {
            gain = Math.max(0f, Math.min(1f, g));
            if (ok) applyVolume();
        }

        void play() {
            if (!ok) return;
            try {
                mp.seekTo(0);
                mp.start();
            } catch (IllegalStateException ignored) { }
        }

        void pause() {
            if (!ok) return;
            try { if (mp.isPlaying()) mp.pause(); } catch (IllegalStateException ignored) { }
        }

        boolean playing() {
            try { return ok && mp.isPlaying(); } catch (IllegalStateException e) { return false; }
        }

        void release() {
            ok = false;
            try { mp.release(); } catch (RuntimeException ignored) { }
        }
    }

    private final Snd sting, guidingL, guidingR, curiousL, curiousR, doneL, doneR;

    GlAudio(Context c) {
        sting = new Snd(c, "you-died.wav", 0f);
        guidingL = new Snd(c, "guiding-left.mp3", -1f);
        guidingR = new Snd(c, "guiding-right.mp3", 1f);
        curiousL = new Snd(c, "curious-left.mp3", -1f);
        curiousR = new Snd(c, "curious-right.mp3", 1f);
        doneL = new Snd(c, "guiding-done-left.mp3", -1f);
        doneR = new Snd(c, "guiding-done-right.mp3", 1f);
    }

    private Snd left(String mode) { return GlConfig.MODE_CURIOUS.equals(mode) ? curiousL : guidingL; }
    private Snd right(String mode) { return GlConfig.MODE_CURIOUS.equals(mode) ? curiousR : guidingR; }

    void sting() { sting.play(); }

    void ambientStart(String mode) {
        left(mode).setGain(1f);
        right(mode).setGain(1f);
        left(mode).play();
        right(mode).play();
    }

    void ambientGain(String mode, float g) {
        left(mode).setGain(g);
        right(mode).setGain(g);
    }

    void ambientStop(String mode) {
        left(mode).pause();
        right(mode).pause();
    }

    void doneChime() {
        doneL.play();
        doneR.play();
    }

    boolean chimePlaying() { return doneL.playing() || doneR.playing(); }

    /** Silence everything (a restarted call or an abort mid-sequence). */
    void stopAll() {
        for (Snd s : new Snd[]{sting, guidingL, guidingR, curiousL, curiousR, doneL, doneR}) s.pause();
    }

    void release() {
        for (Snd s : new Snd[]{sting, guidingL, guidingR, curiousL, curiousR, doneL, doneR}) s.release();
    }
}
