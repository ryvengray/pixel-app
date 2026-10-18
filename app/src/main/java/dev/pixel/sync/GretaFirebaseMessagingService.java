package dev.pixel.sync;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Receives Greta's reminder payloads and keeps the server token current. */
public class GretaFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL = "greta_reminders";
    @Override public void onNewToken(String token) { FcmManager.register(this, token); }
    @Override public void onMessageReceived(RemoteMessage message) {
        if(TopicRepository.blocksIncoming(this,message.getData().getOrDefault("message_id","")))return;
        String title = message.getNotification() == null ? "Greta" : message.getNotification().getTitle();
        String body = message.getNotification() == null ? message.getData().get("body") : message.getNotification().getBody();
        if (title == null || title.isEmpty()) title = "Greta";
        if (body == null || body.isEmpty()) body = "你有一条新的 Greta 消息";
        showNotification(title, body, message.getData().get("message_id"));
    }
    private void showNotification(String title, String body, String messageId) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Greta 提醒", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if(messageId!=null)open.putExtra("message_id",messageId);
        int notificationId=messageId==null?(int)(System.currentTimeMillis()&0x7fffffff):messageId.hashCode();
        PendingIntent pending = PendingIntent.getActivity(this, notificationId, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(this, CHANNEL) : new android.app.Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(pending);
        try { manager.notify(notificationId, builder.build()); } catch (SecurityException ignored) { }
    }
}
