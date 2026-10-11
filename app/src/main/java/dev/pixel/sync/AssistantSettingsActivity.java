package dev.pixel.sync;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

/** Settings deliberately keep endpoints public and encrypt only secrets. */
public class AssistantSettingsActivity extends Activity {
    private EditText endpoint, deepseekKey;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private EditText field(LinearLayout body,String label,boolean secret){
        TextView text=new TextView(this);text.setText(label);text.setTextSize(14);body.addView(text);
        EditText edit=new EditText(this);edit.setSingleLine(true);if(secret)edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);body.addView(edit,new LinearLayout.LayoutParams(-1,-2));return edit;
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);getActionBar();
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(28),dp(24),dp(36));scroll.addView(body);setContentView(scroll);
        TextView title=new TextView(this);title.setText("助理设置");title.setTextSize(26);body.addView(title);
        TextView help=new TextView(this);help.setText("服务端通过一次性二维码配对，无需输入访问令牌。DeepSeek 本地密钥只在服务端不可用时作为手机直连的兜底，并由 Android Keystore 加密保存。\n");body.addView(help);
        endpoint=field(body,"服务地址（HTTPS，例如 https://gray.example.com）",false);
        deepseekKey=field(body,"本地 DeepSeek 密钥（可选兜底）",true);
        SharedPreferences prefs=getSharedPreferences("gray_settings",MODE_PRIVATE);endpoint.setText(prefs.getString("server_url", ""));
        try { SecureStore secure=new SecureStore(this);deepseekKey.setText(secure.get("deepseek_key")); } catch(Exception ignored) { }
        Button pair=new Button(this);pair.setText("扫码配对这台设备");pair.setOnClickListener(v->scanPairing());body.addView(pair);
        Button save=new Button(this);save.setText("保存设置");save.setOnClickListener(v->save());body.addView(save);
        TextView note=new TextView(this);note.setText("先在本地电脑运行 docker compose exec gray-service python -m app.pairing，它会显示一个十分钟有效的二维码。服务端密钥只保存在本地电脑的 server/.env，绝不填写到这里。\n\n长期记忆始终以手机为主库。服务端只保存同步副本，用来运行提醒和主动任务。");body.addView(note);
    }
    private void scanPairing(){GmsBarcodeScannerOptions options=new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();GmsBarcodeScanning.getClient(this,options).startScan().addOnSuccessListener(barcode->{String raw=barcode.getRawValue();if(raw==null)return;pair(raw);}).addOnFailureListener(error->Toast.makeText(this,"无法扫描二维码："+error.getMessage(),Toast.LENGTH_LONG).show());}
    private void pair(String code){new Thread(()->{try{AssistantApi.claimPairing(this,code,android.os.Build.MODEL);runOnUiThread(()->{endpoint.setText(getSharedPreferences("gray_settings",MODE_PRIVATE).getString("server_url",""));Toast.makeText(this,"这台设备已配对",Toast.LENGTH_SHORT).show();});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"配对失败："+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();}
    private void save(){try{getSharedPreferences("gray_settings",MODE_PRIVATE).edit().putString("server_url",endpoint.getText().toString().trim().replaceAll("/+$","")).apply();SecureStore secure=new SecureStore(this);secure.put("deepseek_key",deepseekKey.getText().toString());Toast.makeText(this,"已保存",Toast.LENGTH_SHORT).show();finish();}catch(Exception e){Toast.makeText(this,"无法安全保存："+e.getMessage(),Toast.LENGTH_LONG).show();}}
}
