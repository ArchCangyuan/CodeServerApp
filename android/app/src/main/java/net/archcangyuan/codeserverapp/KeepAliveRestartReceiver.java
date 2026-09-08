package net.archcangyuan.codeserverapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class KeepAliveRestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!KeepAliveService.isEnabled(context)) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        } catch (RuntimeException ignored) {
            // Some Android builds temporarily prohibit background FGS starts.
            // START_STICKY remains the primary service restart path.
        }
    }
}
