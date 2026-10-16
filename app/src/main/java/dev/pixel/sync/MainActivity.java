package dev.pixel.sync;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
 private PetScene scene;
 private HomeConversationView home;
 private SharedPreferences preferences;
 private boolean dark;
 private FrameLayout overlay;
 private FrameLayout root;
 private TextView title,menuButton,historyButton,petTouch;
 private Dialog menu;
 private Dialog fileActionDialog;
 private int foreground,secondary;
 private static final int MENU_WIDTH_DP=52,MENU_HEIGHT_DP=48,MENU_TOP_DP=18,MENU_RIGHT_DP=20;
 private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private boolean systemDark(){return (getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);preferences=getSharedPreferences("home",0);
  int choice=preferences.getInt("theme",0);dark=choice==0?systemDark():choice==2;
  getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
  root=new FrameLayout(this);
  root.setBackgroundColor(Color.parseColor(dark?"#102019":"#F0F5E9"));
  scene=new PetScene(this);scene.setTheme(dark);scene.setMoving(!preferences.getBoolean("reduceMotion",false));
  root.addView(scene,new FrameLayout.LayoutParams(-1,-1));overlay=new FrameLayout(this);root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
  title=text("Greta",23);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
  FrameLayout.LayoutParams header=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);header.leftMargin=dp(28);header.topMargin=dp(26);overlay.addView(title,header);
  menuButton=text("•••",22);menuButton.setGravity(Gravity.CENTER);menuButton.setElevation(dp(16));menuButton.setContentDescription("打开功能和外观菜单");menuButton.setOnClickListener(v->showMenu());
  FrameLayout.LayoutParams button=new FrameLayout.LayoutParams(dp(MENU_WIDTH_DP),dp(MENU_HEIGHT_DP),Gravity.TOP|Gravity.RIGHT);button.topMargin=dp(MENU_TOP_DP);button.rightMargin=dp(MENU_RIGHT_DP);overlay.addView(menuButton,button);
  petTouch=new TextView(this);petTouch.setContentDescription("玉绿色宠物，点击打个招呼，长按打开菜单");
  petTouch.setElevation(dp(12));
  petTouch.setOnClickListener(v->scene.react());
  petTouch.setOnLongClickListener(v->{showMenu();return true;});
  FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(290),dp(300),Gravity.CENTER);pp.bottomMargin=dp(30);overlay.addView(petTouch,pp);
  home=new HomeConversationView(this,()->scene.react());home.setElevation(dp(14));
  overlay.addView(home,new FrameLayout.LayoutParams(-1,-1));
  historyButton=text("历史",14);historyButton.setGravity(Gravity.CENTER);historyButton.setElevation(dp(16));historyButton.setContentDescription("查看完整聊天历史");historyButton.setOnClickListener(v->home.showHistory());
  FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(52),dp(48),Gravity.TOP|Gravity.RIGHT);hp.topMargin=dp(18);hp.rightMargin=dp(80);overlay.addView(historyButton,hp);
  root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());overlay.setPadding(safe.left,safe.top,safe.right,safe.bottom);home.setPadding(0,0,0,Math.max(0,ime.bottom-safe.bottom));home.onKeyboard(insets.isVisible(WindowInsets.Type.ime()));return insets;});
  setContentView(root);applyColors();
  if(state==null)root.post(()->handleIncomingFile(getIntent()));
 }
 private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);return v;}
 private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 private void applyColors(){
  foreground=Color.parseColor(dark?"#E5EDE3":"#263C31");secondary=Color.parseColor(dark?"#92A99A":"#708675");
  root.setBackgroundColor(Color.parseColor(dark?"#102019":"#F0F5E9"));
  title.setTextColor(foreground);menuButton.setTextColor(foreground);historyButton.setTextColor(foreground);
  menuButton.setBackground(surface(Color.parseColor(dark?"#263D33":"#DDE8DC"),24));scene.setTheme(dark);home.setTheme(dark);
  WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null){
   int appearance=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
   controller.setSystemBarsAppearance(dark?0:appearance,appearance);
   controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(WindowInsets.Type.systemBars());
  }
 }
 private void showMenu(){
  if(menu!=null&&menu.isShowing())return;menu=new Dialog(this);
  int panel=Color.parseColor(dark?"#172B22":"#F5F7F0");
  LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setPadding(dp(22),dp(20),dp(22),dp(28));sheet.setBackground(surface(panel,28));
  TextView heading=text("功能与设置",23);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setTextColor(foreground);sheet.addView(heading);
  TextView subtitle=text("选择你想做的事",13);subtitle.setTextColor(secondary);subtitle.setPadding(0,dp(3),0,dp(17));sheet.addView(subtitle);
  menuSection(sheet,"GRETA");
  sheet.addView(menuAction("✦   和 Greta 聊聊","继续这段对话",()->{menu.dismiss();home.focusInput();}));
  sheet.addView(menuAction("▤   长期记忆","查看和修正 Greta 记住的事",()->{menu.dismiss();startActivity(new Intent(this,MemoryActivity.class));}));
  sheet.addView(menuAction("⚙   助理设置","配对设备与服务地址",()->{menu.dismiss();startActivity(new Intent(this,AssistantSettingsActivity.class));}));
  Switch proactive=new Switch(this);proactive.setText("偶尔主动聊两句");proactive.setTextColor(foreground);proactive.setChecked(getSharedPreferences("home_conversation",0).getBoolean("proactive",true));proactive.setOnCheckedChangeListener((v,checked)->getSharedPreferences("home_conversation",0).edit().putBoolean("proactive",checked).apply());sheet.addView(proactive);
  sheet.addView(menuAction("◷   提醒与最近结果","查看工具结果、完成或延后提醒",()->{menu.dismiss();home.showResults();}));
  menuSection(sheet,"工具");
  sheet.addView(menuAction("↻   Obsidian 同步","同步你的笔记库",()->{menu.dismiss();startActivity(new Intent(this,SyncActivity.class));}));
  sheet.addView(menuAction("▣   打印文件","选择文件或文字并设置打印",()->{menu.dismiss();startActivity(new Intent(this,PrintActivity.class));}));
  menuSection(sheet,"外观");
  LinearLayout themes=new LinearLayout(this);themes.setPadding(0,0,0,dp(8));String[] labels={"跟随系统","浅色","深色"};
  for(int i=0;i<labels.length;i++){final int choice=i;TextView theme=text((preferences.getInt("theme",0)==i?"✓  ":"")+labels[i],13);theme.setGravity(Gravity.CENTER);theme.setTextColor(foreground);theme.setBackground(surface(Color.parseColor(preferences.getInt("theme",0)==i?(dark?"#375B44":"#D3E8D2"):(dark?"#294536":"#E3ECDC")),13));
   theme.setOnClickListener(v->{preferences.edit().putInt("theme",choice).apply();dark=choice==0?systemDark():choice==2;menu.dismiss();applyColors();showMenu();});LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(44),1);tp.rightMargin=i<2?dp(7):0;themes.addView(theme,tp);}sheet.addView(themes);
  Switch reduce=new Switch(this);reduce.setText("减少动态效果");reduce.setTextSize(14);reduce.setTextColor(foreground);reduce.setPadding(dp(8),dp(9),dp(8),dp(9));reduce.setChecked(!scene.isMoving());
  reduce.setOnCheckedChangeListener((v,checked)->{preferences.edit().putBoolean("reduceMotion",checked).apply();scene.setMoving(!checked);});sheet.addView(reduce);

  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);scroll.addView(sheet);menu.setContentView(scroll);Window window=menu.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.25f);window.setGravity(Gravity.BOTTOM);}
  menu.setOnDismissListener(d->applyColors());menu.show();if(window!=null){window.setLayout(-1,-2);sheet.post(()->{int max=(int)(getResources().getDisplayMetrics().heightPixels*.84f);if(sheet.getHeight()>max)window.setLayout(-1,max);});}
 }
 private void handleIncomingFile(Intent intent){
  if(intent==null)return;String action=intent.getAction();Uri uri=null;
  if(Intent.ACTION_VIEW.equals(action))uri=intent.getData();
  else if(Intent.ACTION_SEND.equals(action)){
   if(Build.VERSION.SDK_INT>=33)uri=intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class);else uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
   if(uri==null&&intent.getClipData()!=null&&intent.getClipData().getItemCount()>0)uri=intent.getClipData().getItemAt(0).getUri();
  }
  if(uri==null)return;
  int grantFlags=intent.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  if((intent.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}
  showFileAction(uri,fileName(uri),grantFlags);
 }
 private String fileName(Uri uri){
  try(Cursor cursor=getContentResolver().query(uri,null,null,null,null)){if(cursor!=null&&cursor.moveToFirst()){int index=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(index>=0){String name=cursor.getString(index);if(name!=null&&!name.isEmpty())return name;}}}catch(Exception ignored){}
  String tail=uri.getLastPathSegment();return tail==null||tail.isEmpty()?"PDF 文件":tail;
 }
 private void showFileAction(Uri uri,String fileName,int grantFlags){
  if(fileActionDialog!=null&&fileActionDialog.isShowing())fileActionDialog.dismiss();fileActionDialog=new Dialog(this);
  int panel=Color.parseColor(dark?"#172B22":"#F7F9F3");LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(24),dp(22),dp(24),dp(18));card.setBackground(surface(panel,24));
  TextView heading=text("使用这个文件做什么？",21);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setTextColor(foreground);card.addView(heading);
  TextView file=text(fileName,13);file.setTextColor(secondary);file.setMaxLines(2);file.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);file.setPadding(0,dp(7),0,dp(18));card.addView(file);
  LinearLayout printAction=new LinearLayout(this);printAction.setOrientation(LinearLayout.VERTICAL);printAction.setPadding(dp(17),dp(14),dp(17),dp(14));printAction.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor(dark?"#406651":"#CADBC7")),surface(Color.parseColor(dark?"#294536":"#E3ECDC"),16),null));
  TextView printTitle=text("▣   打印",16);printTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));printTitle.setTextColor(foreground);printAction.addView(printTitle);TextView printHelp=text("选择打印机、纸张和颜色",12);printHelp.setTextColor(secondary);printHelp.setPadding(dp(26),dp(3),0,0);printAction.addView(printHelp);
  printAction.setOnClickListener(v->{Intent next=new Intent(this,PrintActivity.class).setData(uri).putExtra(PrintActivity.EXTRA_FILE_NAME,fileName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|grantFlags);next.setClipData(ClipData.newRawUri("PDF",uri));fileActionDialog.dismiss();startActivity(next);});card.addView(printAction,new LinearLayout.LayoutParams(-1,-2));
  TextView cancel=text("取消",14);cancel.setTextColor(secondary);cancel.setGravity(Gravity.CENTER);cancel.setPadding(0,dp(18),0,dp(3));cancel.setOnClickListener(v->fileActionDialog.dismiss());card.addView(cancel,new LinearLayout.LayoutParams(-1,-2));
  fileActionDialog.setContentView(card);Window window=fileActionDialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.3f);window.setGravity(Gravity.CENTER);}fileActionDialog.show();if(window!=null)window.setLayout(getResources().getDisplayMetrics().widthPixels-dp(40),-2);
 }
 private void menuSection(LinearLayout sheet,String title){TextView label=text(title,11);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));label.setTextColor(secondary);label.setPadding(dp(4),dp(13),0,dp(8));sheet.addView(label);}
 private View menuAction(String label,String detail,Runnable action){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(15),dp(10),dp(15),dp(10));card.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor(dark?"#406651":"#CADBC7")),surface(Color.parseColor(dark?"#294536":"#E3ECDC"),15),null));TextView name=text(label,15);name.setTextColor(foreground);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));card.addView(name);TextView sub=text(detail,12);sub.setTextColor(secondary);sub.setPadding(dp(27),dp(2),0,0);card.addView(sub);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(6);card.setLayoutParams(cp);card.setOnClickListener(v->action.run());return card;}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIncomingFile(intent);home.syncInbox();}
 @Override public void onResume(){super.onResume();if(preferences.getInt("theme",0)==0)dark=systemDark();scene.resumeScene();applyColors();home.resume();home.openIncoming(getIntent().getStringExtra("message_id"));getIntent().removeExtra("message_id");}
 @Override public void onPause(){home.pause();scene.pauseScene();super.onPause();}
 @Override public void onDestroy(){if(menu!=null)menu.dismiss();if(fileActionDialog!=null)fileActionDialog.dismiss();home.shutdown();super.onDestroy();}
}
