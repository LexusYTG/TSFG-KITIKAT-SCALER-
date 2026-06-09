package com.TuringSoftware.FrameGenerator.service.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Construye y actualiza la notificación persistente del servicio en primer plano.
 *
 * <p>Separada de {@code ScreenCaptureService} para que ese archivo deje de ser
 * titánico. No contiene lógica de captura ni de UI.
 */
public class CaptureNotification {

    public static final int    NOTIFICATION_ID = 1001;
    public static final String CHANNEL_ID      = "ScreenCaptureChannel";
    public static final String ACTION_STOP     = "com.TuringSoftware.FrameGenerator.STOP";

    private final Context context;

    public CaptureNotification(Context ctx) {
        this.context = ctx;
    }

    public void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Servicio de captura de pantalla activo");
            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    public Notification build(int currentFps) {
        int piFlags = (Build.VERSION.SDK_INT >= 31)
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stopPi = PendingIntent.getBroadcast(
            context, 2, new Intent(ACTION_STOP), piFlags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= 26)
            ? new Notification.Builder(context, CHANNEL_ID)
            : new Notification.Builder(context);

        builder.setContentTitle("Frame Generator Pro")
               .setContentText("Captura activa - FPS: " + currentFps)
               .setSmallIcon(android.R.drawable.ic_menu_camera)
               .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPi);
        return builder.build();
    }

    public void update(int currentFps) {
        NotificationManager nm = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, build(currentFps));
    }
}
