package dev.pixel.sync;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.widget.*;
import java.util.concurrent.*;

/** Local six-color preview; device transport is configured separately. */
public final class InkImageActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Bitmap result;
    private java.io.File incoming;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.WHITE);
        TextView status=new TextView(this);status.setText("正在转换水墨屏图片…");status.setTextColor(Color.BLACK);status.setPadding(24,32,24,24);root.addView(status);
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(Color.WHITE);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        Button send=new Button(this);send.setText("显示到水墨屏并加入背景图库");send.setEnabled(false);root.addView(send);
        send.setOnClickListener(v->{send.setEnabled(false);status.setText("正在上传并生成今日画面…");worker.execute(()->{try{
            upload(incoming);
            runOnUiThread(()->{if(!isDestroyed()){status.setText("照片已加入图库，今日画面已提交，等待屏幕刷新");send.setEnabled(true);}});
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){status.setText("发送失败："+e.getMessage()+"，可重试");send.setEnabled(true);}});}});});
        Button close=new Button(this);close.setText("返回");close.setOnClickListener(v->finish());root.addView(close);setContentView(root);
        worker.execute(()->{try{
            LifeImages.Image imported=LifeImages.importImage(this,getIntent().getData(),"水墨屏图片");
            incoming=LifeImages.file(this,true,imported.fileName);
            Bitmap source=LifeImages.preview(LifeImages.file(this,true,imported.fileName),1600);
            Bitmap converted=sixColor(source);source.recycle();
            runOnUiThread(()->{if(isDestroyed()){converted.recycle();return;}result=converted;image.setImageBitmap(converted);send.setEnabled(true);status.setText("E1002 六色预览 · "+converted.getWidth()+" × "+converted.getHeight()+"\n800 × 480 · 等比留白 · 照片预览 · 发送后将叠加今日天气和待办");});
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("转换失败，请重新分享图片");});}});
    }
    private void upload(java.io.File file)throws Exception{
        String base=getSharedPreferences("gray_settings",MODE_PRIVATE).getString("server_url","").replaceAll("/+$","");
        String token=new SecureStore(this).get("device_token");
        if(base.isEmpty()||token==null||token.isEmpty())throw new java.io.IOException("请先配对服务");
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(base+"/v1/ink/backgrounds?display=true").openConnection();
        try{
            connection.setRequestMethod("POST");connection.setConnectTimeout(10000);connection.setReadTimeout(120000);
            connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("Content-Type","application/octet-stream");
            connection.setDoOutput(true);connection.setFixedLengthStreamingMode(file.length());
            try(java.io.InputStream in=new java.io.FileInputStream(file);java.io.OutputStream out=connection.getOutputStream()){byte[] bytes=new byte[16384];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}
            int code=connection.getResponseCode();if(code<200||code>=300)throw new java.io.IOException("服务返回 "+code);
        }finally{connection.disconnect();}
    }
    static Bitmap sixColor(Bitmap source){
        int w=800,h=480;Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(out);canvas.drawColor(Color.WHITE);
        float scale=Math.min(w/(float)source.getWidth(),h/(float)source.getHeight());
        float dw=source.getWidth()*scale,dh=source.getHeight()*scale;
        canvas.drawBitmap(source,null,new RectF((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2),new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] pixels=new int[w*h];out.getPixels(pixels,0,w,0,0,w,h);
        int[] palette={Color.WHITE,Color.BLACK,Color.RED,Color.YELLOW,Color.GREEN,Color.BLUE};
        float[][] error=new float[3][w+2],next=new float[3][w+2];
        for(int y=0;y<h;y++){
            for(int x=0;x<w;x++){
                int i=y*w+x,c=pixels[i];float[] rgb={Color.red(c)+error[0][x+1],Color.green(c)+error[1][x+1],Color.blue(c)+error[2][x+1]};
                int best=0;float distance=Float.MAX_VALUE;
                for(int k=0;k<palette.length;k++){int p=palette[k];float dr=rgb[0]-Color.red(p),dg=rgb[1]-Color.green(p),db=rgb[2]-Color.blue(p);float d=dr*dr+dg*dg+db*db;if(d<distance){distance=d;best=k;}}
                int chosen=palette[best];pixels[i]=chosen;int[] channels={Color.red(chosen),Color.green(chosen),Color.blue(chosen)};
                for(int k=0;k<3;k++){float e=rgb[k]-channels[k];error[k][x+2]+=e*7/16;next[k][x]+=e*3/16;next[k][x+1]+=e*5/16;next[k][x+2]+=e/16;}
            }
            float[][] swap=error;error=next;next=swap;for(float[] row:next)java.util.Arrays.fill(row,0);
        }
        out.setPixels(pixels,0,w,0,0,w,h);return out;
    }
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();if(result!=null)result.recycle();}
}
