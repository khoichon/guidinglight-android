package dev.khoichon.guidinglight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Broadcast flavour of the same calls (also used by the notification's Stop button). */
public class CallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        GlCall.handle(context, intent);
    }
}
