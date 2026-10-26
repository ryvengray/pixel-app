package dev.pixel.sync;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;

/** External photos open here as an unsaved life record, with printing one tap away. */
public final class ImageRecordActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private ImageView preview;
    private EditText note;
    private TextView status,date,save,print;
    private LifeImages.Image image;
    private String recordId,day;
    private boolean dark,saving,preparingPrint,destroyed;
    private int foreground,muted,accent,background;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(foreground);return v;}
    @Override public void onCreate(Bundle saved){
        int choice=getSharedPreferences("home",0).getInt("theme",0);dark=choice==2||(choice==0&&(getResources().getConfiguration().uiMode&48)==32);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);
        foreground=Color.parseColor(dark?"#E5EDE3":"#203B2A");muted=Color.parseColor(dark?"#A7BBAA":"#687D70");accent=Color.parseColor(dark?"#8BCB9A":"#327C4E");background=Color.parseColor(dark?"#102019":"#F2F6EE");
        recordId=saved==null?UUID.randomUUID().toString():saved.getString("recordId");day=saved==null?LocalDate.now().toString():saved.getString("day",LocalDate.now().toString());
        getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(background);getWindow().setNavigationBarColor(background);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(background);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(22),dp(12),dp(22),dp(28));body.setBackgroundColor(background);scroll.addView(body);setContentView(scroll);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);FormKeyboard.applyInsets(scroll,scroll);
        WindowInsetsController bars=getWindow().getInsetsController();if(bars!=null)bars.setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        TextView back=text("‹  返回",15);back.setMinHeight(dp(44));back.setGravity(Gravity.CENTER_VERTICAL);back.setOnClickListener(v->finish());body.addView(back);
        TextView title=text("留下一张照片",26);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));body.addView(title);
        TextView description=text("加几句话，把这一刻记进生活里。",14);description.setTextColor(muted);description.setPadding(0,dp(8),0,dp(20));body.addView(description);
        LinearLayout modes=new LinearLayout(this);modes.setPadding(0,0,0,dp(16));TextView record=text("生活记录",15);record.setGravity(Gravity.CENTER);record.setTextColor(dark?background:Color.WHITE);record.setBackground(surface(accent,18));record.setContentDescription("生活记录，已选择");modes.addView(record,new LinearLayout.LayoutParams(0,dp(44),1));
        print=text("打印",15);print.setTag("image_record_print");print.setGravity(Gravity.CENTER);print.setBackground(surface(Color.parseColor(dark?"#294536":"#E3ECDC"),18));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,dp(44),1);pp.leftMargin=dp(10);modes.addView(print,pp);print.setOnClickListener(v->openPrint());body.addView(modes);
        preview=new ImageView(this);preview.setTag("image_record_preview");preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackground(surface(dark?0xFF1C3326:Color.WHITE,20));preview.setClipToOutline(true);preview.setContentDescription("这条生活记录的图片");body.addView(preview,new LinearLayout.LayoutParams(-1,dp(250)));
        status=text("正在导入图片…",13);status.setTag("image_record_state");status.setTextColor(muted);status.setPadding(0,dp(10),0,dp(12));body.addView(status);
        date=text("日期 · "+day+"  ›",14);date.setMinHeight(dp(44));date.setGravity(Gravity.CENTER_VERTICAL);date.setOnClickListener(v->{LocalDate current=LocalDate.parse(day);new DatePickerDialog(this,(picker,y,m,d)->{day=LocalDate.of(y,m+1,d).toString();date.setText("日期 · "+day+"  ›");},current.getYear(),current.getMonthValue()-1,current.getDayOfMonth()).show();});body.addView(date);
        note=new EditText(this);note.setTag("image_record_note");note.setHint("为这张照片写几句…（可选）");note.setContentDescription("图片记录的补充文字");note.setTextColor(foreground);note.setHintTextColor(muted);note.setTextSize(16);note.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);note.setMinLines(3);note.setMaxLines(6);note.setGravity(Gravity.TOP);note.setPadding(dp(16),dp(14),dp(16),dp(14));note.setBackground(surface(dark?0xFF1C3326:Color.WHITE,18));note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        CharSequence incoming=getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);note.setText(saved!=null?saved.getString("note",""):incoming==null?"":incoming.toString());body.addView(note,new LinearLayout.LayoutParams(-1,-2));
        save=text("保存到生活记录",16);save.setTag("image_record_save");save.setGravity(Gravity.CENTER);save.setTextColor(dark?background:Color.WHITE);save.setBackground(surface(accent,20));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(52));sp.topMargin=dp(20);body.addView(save,sp);save.setOnClickListener(v->saveRecord());
        setReady(false);LifeImages.Image restored=saved==null||saved.getString("image")==null?null:new LifeImages.Image(saved.getString("image"),saved.getString("name"),saved.getString("mime"),saved.getString("hash"));load(restored);
    }
    private void setReady(boolean ready){boolean enabled=ready&&!saving&&!preparingPrint;save.setEnabled(enabled);save.setAlpha(enabled?1f:.4f);print.setEnabled(enabled);print.setAlpha(enabled?1f:.4f);}
    private void load(LifeImages.Image restored){
        setReady(false);status.setText("正在导入图片…");status.setOnClickListener(null);
        worker.execute(()->{try{
            LifeImages.Image loaded=restored;
            if(loaded==null||!LifeImages.file(this,true,loaded.fileName).isFile())loaded=LifeImages.importImage(this,getIntent().getData(),getIntent().getStringExtra(PrintActivity.EXTRA_FILE_NAME));
            File file=LifeImages.file(this,true,loaded.fileName);if(!LifeImages.hash(file).equals(loaded.hash))throw new java.io.IOException("图片发生变化，请重新分享");
            Bitmap bitmap=LifeImages.preview(file,1280);LifeImages.Image ready=loaded;
            main.post(()->{if(destroyed){bitmap.recycle();return;}image=ready;preview.setImageBitmap(bitmap);status.setText("图片已就绪 · 保存后可在生活记录里查看");setReady(true);});
        }catch(Exception e){main.post(()->{if(destroyed)return;status.setText((e instanceof SecurityException?"没有读取权限，请从原应用重新分享":e.getMessage()==null?"图片读取失败":e.getMessage())+" · 点击重试");status.setOnClickListener(v->load(null));});}});
    }
    private void openPrint(){
        if(image==null||saving||preparingPrint)return;preparingPrint=true;setReady(false);status.setText("正在准备打印图片…");LifeImages.Image photo=image;
        worker.execute(()->{try{Uri uri=LifeImages.preparePrint(this,true,photo);main.post(()->{if(destroyed)return;preparingPrint=false;setReady(true);status.setText("图片已就绪 · 保存后可在生活记录里查看");Intent next=new Intent(this,PrintActivity.class).setDataAndType(uri,getContentResolver().getType(uri)).putExtra(PrintActivity.EXTRA_FILE_NAME,photo.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);next.setClipData(ClipData.newRawUri("图片",uri));startActivity(next);});}catch(Exception e){main.post(()->{if(destroyed)return;preparingPrint=false;setReady(true);status.setText("图片准备失败，请重试");});}});
    }
    private void saveRecord(){
        RecordLocation.withPermission(this,this::saveWithLocation);
    }
    private void saveWithLocation(){
        if(image==null||saving||preparingPrint)return;saving=true;setReady(false);note.setEnabled(false);date.setEnabled(false);save.setText("正在保存…");String content=note.getText().toString().trim(),recordDay=day;LifeImages.Image attachment=image;
        worker.execute(()->{try(LifeRecords records=new LifeRecords(this)){
            JSONObject payload=new JSONObject().put("content",content.isEmpty()?"图片记录":content).put("date",recordDay).put("items",new JSONArray());records.saveWithImage(recordId,payload,content,attachment);LifeSync.schedule(this);
            main.post(()->{if(destroyed)return;Toast.makeText(this,"已保存生活记录",Toast.LENGTH_SHORT).show();startActivity(new Intent(this,RecordsActivity.class));finish();});
        }catch(Exception e){main.post(()->{if(destroyed)return;saving=false;setReady(true);note.setEnabled(true);date.setEnabled(true);save.setText("保存到生活记录");status.setText("保存失败，图片和文字仍保留 · "+(e.getMessage()==null?"请重试":e.getMessage()));});}});
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putString("recordId",recordId);state.putString("day",day);state.putString("note",note.getText().toString());if(image!=null){state.putString("image",image.fileName);state.putString("name",image.name);state.putString("mime",image.mime);state.putString("hash",image.hash);}super.onSaveInstanceState(state);}
    @Override public void onDestroy(){destroyed=true;worker.shutdown();super.onDestroy();}
}
