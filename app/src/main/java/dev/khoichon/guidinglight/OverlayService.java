package dev.khoichon.guidinglight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.List;

/**
 * Hosts the death screen. The visuals are a TYPE_APPLICATION_OVERLAY window added through
 * WindowManager (so they sit over whatever app is in front instead of being an activity),
 * driven by the engine from a Choreographer frame callback. It runs as a short-lived
 * foreground service so audio and the window survive the caller going away; it stops
 * itself once the end fade (and, in guiding mode, the end chime) has finished.
 */
public class OverlayService extends Service implements DeathScreenEngine.Listener, OverlayView.Listener {
    /** The live instance, so repeat calls restart in place instead of re-starting the service. */
    static volatile OverlayService current;

    private static final String CHANNEL = "gl_overlay";
    private static final int NOTIF_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private OverlayView view;
    private DeathScreenEngine engine;
    private GlAudio audio;
    private boolean frameScheduled;
    private int stopPolls;

    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            onFrame();
        }
    };

    private final Runnable stopPoll = new Runnable() {
        @Override public void run() {
            // the guiding end chime outlives the overlay: wait for it (bounded) before stopping
            if (audio != null && audio.chimePlaying() && ++stopPolls < 50) {
                main.postDelayed(this, 200);
            } else {
                shutDown();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (intent != null && GlCall.ACTION_SHOW.equals(intent.getAction())) {
            String mode = GlConfig.normalMode(intent.getStringExtra(GlCall.EXTRA_MODE));
            String[] arr = intent.getStringArrayExtra(GlCall.EXTRA_MESSAGES);
            List<String> override = arr == null ? null : Arrays.asList(arr);
            begin(mode, GlConfig.messagesFor(this, mode, override));
        } else if (engine == null) {
            shutDown();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPi = PendingIntent.getBroadcast(this, 1,
                new Intent(this, CallReceiver.class).setAction(GlCall.ACTION_ABORT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_gl)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_gl),
                        getString(R.string.notif_stop), stopPi).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ---------------------------------------------------------------- sequence control

    /** Start (or restart in place) the death screen. Main thread. */
    void begin(String mode, List<String> messages) {
        main.removeCallbacks(stopPoll);
        GlAssets.load(this); // decoded once per process
        if (audio == null) audio = new GlAudio(this);
        if (engine != null) audio.stopAll(); // restarting over a running sequence

        if (view == null) {
            view = new OverlayView(this);
            view.setListener(this);
            try {
                wm.addView(view, layoutParams());
            } catch (RuntimeException e) { // e.g. permission revoked in the meantime
                view = null;
                shutDown();
                return;
            }
        }
        view.setAlpha(1f);
        engine = new DeathScreenEngine(mode, messages, this);
        view.bind(engine);
        engine.start(SystemClock.uptimeMillis());
        scheduleFrame();
    }

    void abort() {
        if (engine != null) engine.abort();
    }

    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(frameCb);
    }

    private void onFrame() {
        if (engine == null || view == null) return;
        engine.update(SystemClock.uptimeMillis());
        view.setAlpha(1f - engine.endFade());
        view.invalidate();
        if (!engine.isDone()) scheduleFrame();
    }

    private void onSequenceDone() {
        removeOverlay();
        engine = null;
        stopPolls = 0;
        main.post(stopPoll);
    }

    private void shutDown() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ---------------------------------------------------------------- overlay window

    private Rect displayBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            return wm.getMaximumWindowMetrics().getBounds();
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    private WindowManager.LayoutParams layoutParams() {
        Rect b = displayBounds();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                b.width(), b.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE      // never steal focus from the app below
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    private void removeOverlay() {
        if (view == null) return;
        try {
            wm.removeViewImmediate(view);
        } catch (RuntimeException ignored) { }
        view = null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (view != null) {
            try {
                wm.updateViewLayout(view, layoutParams()); // rotation: re-fit the full-screen window
            } catch (RuntimeException ignored) { }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (engine == null && level >= TRIM_MEMORY_BACKGROUND) GlAssets.release();
    }

    @Override
    public void onDestroy() {
        current = null;
        main.removeCallbacksAndMessages(null);
        Choreographer.getInstance().removeFrameCallback(frameCb);
        removeOverlay();
        if (audio != null) { audio.release(); audio = null; }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- engine listener -> audio

    @Override public void sting() { if (audio != null) audio.sting(); }
    @Override public void ambientStart(String mode) { if (audio != null) audio.ambientStart(mode); }
    @Override public void ambientGain(String mode, float gain) { if (audio != null) audio.ambientGain(mode, gain); }
    @Override public void ambientStop(String mode) { if (audio != null) audio.ambientStop(mode); }
    @Override public void doneChime() { if (audio != null) audio.doneChime(); }

    @Override
    public void finished() {
        // called from inside engine.update(); tear down on the next loop turn
        final DeathScreenEngine finishing = engine;
        main.post(new Runnable() {
            @Override public void run() {
                if (finishing == engine) onSequenceDone(); // ignore if a new call already replaced it
            }
        });
    }

    // ---------------------------------------------------------------- overlay view listener

    @Override public void onAdvance() { if (engine != null) engine.advance(); }
    @Override public void onAbort() { abort(); }
}
