package dev.pixel.sync;

import android.content.Context;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Registers the current FCM token only after this device has been paired. */
public final class FcmManager {
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private FcmManager() { }
    public static void refresh(Context context) {
        if (!BuildConfig.ENABLE_FCM) return;
        Context app = context.getApplicationContext();
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) return;
            register(app, task.getResult());
        });
    }
    public static void register(Context context, String token) {
        if (!BuildConfig.ENABLE_FCM) return;
        Context app = context.getApplicationContext();
        NETWORK.execute(() -> {
            try { AssistantApi.registerDevice(app, token); } catch (Exception ignored) { }
        });
    }
}
