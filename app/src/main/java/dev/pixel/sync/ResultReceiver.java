package dev.pixel.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

public class ResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        synchronized(SyncRunner.class){
        SharedPreferences prefs = context.getSharedPreferences("sync", 0);
        String id = intent.getStringExtra("runId");
        if (id == null || !id.equals(prefs.getString("runId", "")) || !prefs.getBoolean("running",false)) return;
        Bundle result = intent.getBundleExtra("result");
        boolean ok = result != null && result.getInt("exitCode", -999) == 0 && result.getInt("err", -1) == -1;
        String log = result == null ? "未收到 Termux 执行结果。" :
                result.getString("stdout", "") + "\n" + result.getString("stderr", "") + "\n" + result.getString("errmsg", "");
        try(LifeRecords records=new LifeRecords(context)){records.endSync(ok);}
        if(!ok)context.getSharedPreferences("life_sync",0).edit().putString("error","Obsidian 同步失败，将自动重试；记录已保存在手机").apply();
        prefs.edit().putBoolean("running", false).putString("status", ok ? "同步成功" : "同步失败")
                .putString("log", log).putLong("finished", System.currentTimeMillis()).commit();
        LifeSync.schedule(context);
        }
    }
}
