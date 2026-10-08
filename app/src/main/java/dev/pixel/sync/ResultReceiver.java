package dev.pixel.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

public class ResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("sync", 0);
        String id = intent.getStringExtra("runId");
        if (id == null || !id.equals(prefs.getString("runId", ""))) return;
        Bundle result = intent.getBundleExtra("result");
        boolean ok = result != null && result.getInt("exitCode", -999) == 0 && result.getInt("err", -1) == -1;
        String log = result == null ? "未收到 Termux 执行结果。" :
                result.getString("stdout", "") + "\n" + result.getString("stderr", "") + "\n" + result.getString("errmsg", "");
        prefs.edit().putBoolean("running", false).putString("status", ok ? "同步成功" : "同步失败")
                .putString("log", log).putLong("finished", System.currentTimeMillis()).apply();
    }
}
