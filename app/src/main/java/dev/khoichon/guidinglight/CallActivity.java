package dev.khoichon.guidinglight;

import android.app.Activity;
import android.os.Bundle;

/** Invisible entry point for startActivity() callers (intent actions + guidinglight:// links). */
public class CallActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GlCall.handle(this, getIntent());
        finish();
        overridePendingTransition(0, 0);
    }
}
