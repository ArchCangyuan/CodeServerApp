package net.archcangyuan.codeserverapp;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

public final class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "your_workspace_keep_alive";
    private static final int NOTIFICATION_ID = 1001;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent launchIntent = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("YourWorkspace is keeping sessions alive")
            .setContentText("Tap to return to your remote workspace")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "YourWorkspace:SessionKeepAlive"
        );
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Session keep-alive",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps remote workspace sessions connected when enabled");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
