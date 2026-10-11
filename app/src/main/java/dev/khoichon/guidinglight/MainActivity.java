package dev.khoichon.guidinglight;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

/** Config screen: message list + mode, overlay permission, "Call it now". */
public class MainActivity extends Activity {
    private EditText messages;
    private RadioGroup modeGroup;
    private TextView modeHint, permStatus, linkPreview;
    private Button btnGrant;

    private String mode;
    private boolean loading;
    private boolean askedNotifications;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveRunnable = new Runnable() {
        @Override public void run() { saveNow(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        messages = findViewById(R.id.messages);
        modeGroup = findViewById(R.id.modeGroup);
        modeHint = findViewById(R.id.modeHint);
        permStatus = findViewById(R.id.permStatus);
        linkPreview = findViewById(R.id.linkPreview);
        btnGrant = findViewById(R.id.btnGrant);

        mode = GlConfig.getMode(this);
        loadUi();

        messages.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (loading) return;
                updatePreview();
                handler.removeCallbacks(saveRunnable);
                handler.postDelayed(saveRunnable, 120);
            }
        });

        modeGroup.setOnCheckedChangeListener((group, id) -> {
            if (loading) return;
            String next = id == R.id.radioCurious ? GlConfig.MODE_CURIOUS : GlConfig.MODE_GUIDING;
            if (next.equals(mode)) return;
            saveNow();              // keep the outgoing mode's text
            mode = next;
            GlConfig.setMode(this, mode);
            loadUi();               // swap the textarea to the other mode's list
        });

        btnGrant.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));

        findViewById(R.id.btnPlay).setOnClickListener(v -> {
            saveNow();
            GlCall.show(this, mode, null); // uses the saved configuration
        });

        findViewById(R.id.btnCopyLink).setOnClickListener(v ->
                copy(GlLinks.link(mode, messages.getText().toString())));
        findViewById(R.id.btnCopyAdb).setOnClickListener(v ->
                copy(GlLinks.adb(mode, messages.getText().toString())));
        findViewById(R.id.btnCopyIntent).setOnClickListener(v ->
                copy(GlLinks.javaIntent(mode, messages.getText().toString())));
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("guidinglight", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void updatePreview() {
        linkPreview.setText(GlLinks.link(mode, messages.getText().toString()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermission();
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotifications = true; // lets the "Stop" notification action show up
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(saveRunnable);
        saveNow();
        super.onPause();
    }

    private void refreshPermission() {
        boolean ok = Settings.canDrawOverlays(this);
        permStatus.setText(ok ? R.string.perm_ok : R.string.perm_missing);
        btnGrant.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private void loadUi() {
        loading = true;
        boolean curious = GlConfig.MODE_CURIOUS.equals(mode);
        modeGroup.check(curious ? R.id.radioCurious : R.id.radioGuiding);
        messages.setText(GlConfig.getMessagesText(this, mode));
        modeHint.setText(curious ? R.string.hint_curious : R.string.hint_guiding);
        loading = false;
        updatePreview();
    }

    private void saveNow() {
        GlConfig.saveMessagesText(this, mode, messages.getText().toString());
    }
}
