package dev.pixel.sync;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import java.util.UUID;

/** Shared dispatch for the visible sync tool and durable background export jobs. */
public final class SyncRunner {
    public static synchronized void start(Context c)throws Exception{
        SharedPreferences prefs=c.getSharedPreferences("sync",0);if(prefs.getBoolean("running",false))return;
        c.getPackageManager().getPackageInfo("com.termux",0);
        if(c.checkSelfPermission("com.termux.permission.RUN_COMMAND")!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("请在同步工具中授权 Termux 运行命令");
        try(LifeRecords records=new LifeRecords(c)){
            VaultExport.export(c,records);
            String script;try(java.io.InputStream stream=c.getAssets().open("sync.sh");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                byte[] bytes=new byte[4096];int count;while((count=stream.read(bytes))!=-1)out.write(bytes,0,count);script=out.toString("UTF-8");
            }
            String id=UUID.randomUUID().toString();
            Intent callback=new Intent(c,ResultReceiver.class).setData(Uri.parse("pixelsync://result/"+id)).putExtra("runId",id);
            PendingIntent result=PendingIntent.getBroadcast(c,0,callback,PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_MUTABLE);
            Intent command=new Intent("com.termux.RUN_COMMAND").setClassName("com.termux","com.termux.app.RunCommandService");
            command.putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/bash");
            command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",new String[]{"-c",script});
            command.putExtra("com.termux.RUN_COMMAND_WORKDIR","/data/data/com.termux/files/home");
            command.putExtra("com.termux.RUN_COMMAND_BACKGROUND",true);
            command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",result);
            records.beginSync();
            if(!prefs.edit().putString("runId",id).putBoolean("running",true).putLong("started",System.currentTimeMillis()).putString("status","同步执行中…").putString("log","等待 Termux 返回结果。最多等待网络步骤各 180 秒。").commit())throw new IllegalStateException("无法保存同步任务状态");
            try{if(c.startService(command)==null)throw new IllegalStateException("无法启动 Termux 服务");}
            catch(Exception error){prefs.edit().putBoolean("running",false).commit();records.endSync(false);throw error;}
        }
    }
    private SyncRunner(){}
}
