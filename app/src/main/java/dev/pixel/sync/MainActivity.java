package dev.pixel.sync;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

public class MainActivity extends Activity {
 private PetScene scene;
 private ChatPanel chat;
 private boolean chatOpen;
 private SharedPreferences preferences;
 private boolean dark;
 private FrameLayout overlay;
 private FrameLayout root;
 private TextView title,caption,hint,menuButton,petTouch;
 private LinearLayout greeting;
 private Dialog menu;
 private int foreground,secondary;
 private int petL,petT,petW,petH;
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
  title=text("Gray",23);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
  FrameLayout.LayoutParams header=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);header.leftMargin=dp(28);header.topMargin=dp(26);overlay.addView(title,header);
  menuButton=text("•••",22);menuButton.setGravity(Gravity.CENTER);menuButton.setElevation(dp(16));menuButton.setContentDescription("打开功能和外观菜单");menuButton.setOnClickListener(v->showMenu());
  FrameLayout.LayoutParams button=new FrameLayout.LayoutParams(dp(52),dp(48),Gravity.TOP|Gravity.RIGHT);button.topMargin=dp(18);button.rightMargin=dp(20);overlay.addView(menuButton,button);
  greeting=new LinearLayout(this);greeting.setOrientation(LinearLayout.VERTICAL);greeting.setGravity(Gravity.CENTER);
  caption=text("在这里，陪着你",19);caption.setGravity(Gravity.CENTER);greeting.addView(caption);
  hint=text("轻触我，打个招呼",12);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(12),0,0);greeting.addView(hint);
  FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);gp.bottomMargin=dp(110);overlay.addView(greeting,gp);
  petTouch=new TextView(this);petTouch.setContentDescription("玉绿色宠物，点击和它聊天，长按打开菜单");
  petTouch.setElevation(dp(12));
  petTouch.setOnClickListener(v->{if(chatOpen)closeChat(true);else{scene.react();openChat(true);}});
  petTouch.setOnLongClickListener(v->{showMenu();return true;});
  FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(290),dp(300),Gravity.CENTER);pp.bottomMargin=dp(30);overlay.addView(petTouch,pp);
  chat=new ChatPanel(this,()->scene.react(),()->closeChat(true));
  FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,-1,Gravity.BOTTOM);overlay.addView(chat,cp);chat.setVisibility(View.GONE);
  root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());overlay.setPadding(safe.left,safe.top,safe.right,Math.max(safe.bottom,ime.bottom));return insets;});
  setContentView(root);applyColors();
  if(state!=null&&state.getBoolean("chatOpen",false))openChat(false);
 }
 private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);return v;}
 private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 private void applyColors(){
  foreground=Color.parseColor(dark?"#E5EDE3":"#263C31");secondary=Color.parseColor(dark?"#92A99A":"#708675");
  root.setBackgroundColor(Color.parseColor(dark?"#102019":"#F0F5E9"));
  title.setTextColor(foreground);caption.setTextColor(foreground);hint.setTextColor(secondary);menuButton.setTextColor(foreground);
  menuButton.setBackground(surface(Color.parseColor(dark?"#263D33":"#DDE8DC"),24));scene.setTheme(dark);chat.setTheme(dark);
  WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null){
   int appearance=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
   controller.setSystemBarsAppearance(dark?0:appearance,appearance);
   if(chatOpen){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);controller.show(WindowInsets.Type.systemBars());}
   else{controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(WindowInsets.Type.systemBars());}
  }
 }
 private void openChat(boolean animate){
  if(chatOpen)return;chatOpen=true;
  greeting.setVisibility(View.GONE);chat.animate().cancel();chat.setAlpha(animate?0f:1f);chat.setTranslationY(0);chat.setVisibility(View.VISIBLE);chat.bringToFront();petTouch.bringToFront();menuButton.bringToFront();
  if(animate)chat.animate().alpha(1f).setDuration(220).start();
  if(animate){
   petL=petTouch.getLeft();petT=petTouch.getTop();petW=petTouch.getWidth();petH=petTouch.getHeight();
   petTouch.setClickable(false);animatePetToCorner();
  }else{
   FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(80),dp(88),Gravity.TOP|Gravity.RIGHT);p.topMargin=dp(72);p.rightMargin=dp(16);
   scene.setLayoutParams(p);petTouch.setLayoutParams(p);
  }
  applyColors();chat.open();
 }
 private void closeChat(boolean animate){
  if(!chatOpen)return;chatOpen=false;
  chat.close();chat.animate().alpha(0f).setDuration(180).withEndAction(()->{if(!chatOpen){chat.setVisibility(View.GONE);chat.setAlpha(1f);}}).start();greeting.setVisibility(View.VISIBLE);
  if(animate){petTouch.setClickable(false);animatePetBack();}
  else{
   scene.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
   FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(290),dp(300),Gravity.CENTER);p.bottomMargin=dp(30);
   petTouch.setLayoutParams(p);
  }
  applyColors();
 }
 private void animatePetToCorner(){
  int size=dp(80),height=dp(88),top=dp(72),right=dp(16);
  scene.setPivotX(0);scene.setPivotY(0);
  scene.animate().scaleX((float)size/scene.getWidth()).scaleY((float)height/scene.getHeight()).translationX(scene.getWidth()-right-size).translationY(top).setDuration(380).setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(()->{
   FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(size,height,Gravity.TOP|Gravity.RIGHT);p.topMargin=top;p.rightMargin=right;
   scene.setLayoutParams(p);scene.setScaleX(1);scene.setScaleY(1);scene.setTranslationX(0);scene.setTranslationY(0);
  }).start();
  int targetLeft=overlay.getWidth()-overlay.getPaddingRight()-right-size,targetTop=overlay.getPaddingTop()+top;
  petTouch.setPivotX(0);petTouch.setPivotY(0);
  petTouch.animate().scaleX((float)size/petW).scaleY((float)height/petH).translationX(targetLeft-petL).translationY(targetTop-petT).setDuration(380).setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(()->{
   FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(size,height,Gravity.TOP|Gravity.RIGHT);p.topMargin=top;p.rightMargin=right;
   petTouch.setLayoutParams(p);petTouch.setScaleX(1);petTouch.setScaleY(1);petTouch.setTranslationX(0);petTouch.setTranslationY(0);petTouch.setClickable(true);
  }).start();
 }
 private void animatePetBack(){
  int width=getResources().getDisplayMetrics().widthPixels,height=getResources().getDisplayMetrics().heightPixels;
  int cl=scene.getLeft(),ct=scene.getTop();
  scene.setPivotX(0);scene.setPivotY(0);
  scene.animate().scaleX((float)width/scene.getWidth()).scaleY((float)height/scene.getHeight()).translationX(-cl).translationY(-ct).setDuration(380).setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(()->{
   scene.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));scene.setScaleX(1);scene.setScaleY(1);scene.setTranslationX(0);scene.setTranslationY(0);
  }).start();
  int cw=petTouch.getWidth(),ch=petTouch.getHeight(),cll=petTouch.getLeft(),ctt=petTouch.getTop();
  petTouch.setPivotX(0);petTouch.setPivotY(0);
  petTouch.animate().scaleX((float)petW/cw).scaleY((float)petH/ch).translationX(petL-cll).translationY(petT-ctt).setDuration(380).setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(()->{
   FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(petW,petH,Gravity.CENTER);p.bottomMargin=dp(30);
   petTouch.setLayoutParams(p);petTouch.setScaleX(1);petTouch.setScaleY(1);petTouch.setTranslationX(0);petTouch.setTranslationY(0);petTouch.setClickable(true);
  }).start();
 }
 private void showMenu(){
  if(menu!=null&&menu.isShowing())return;menu=new Dialog(this);
  int panel=Color.parseColor(dark?"#172B22":"#F5F7F0");
  LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setPadding(dp(22),dp(20),dp(22),dp(28));sheet.setBackground(surface(panel,28));
  TextView heading=text("功能与设置",23);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setTextColor(foreground);sheet.addView(heading);
  TextView subtitle=text("选择你想做的事",13);subtitle.setTextColor(secondary);subtitle.setPadding(0,dp(3),0,dp(17));sheet.addView(subtitle);
  menuSection(sheet,"GRAY");
  sheet.addView(menuAction("✦   和 Gray 聊聊","继续这段对话",()->{menu.dismiss();openChat(true);}));
  sheet.addView(menuAction("▤   长期记忆","查看和修正 Gray 记住的事",()->{menu.dismiss();startActivity(new Intent(this,MemoryActivity.class));}));
  sheet.addView(menuAction("⚙   助理设置","配对设备与服务地址",()->{menu.dismiss();startActivity(new Intent(this,AssistantSettingsActivity.class));}));
  menuSection(sheet,"工具");
  sheet.addView(menuAction("↻   Obsidian 同步","同步你的笔记库",()->{menu.dismiss();startActivity(new Intent(this,SyncActivity.class));}));
  sheet.addView(menuAction("▣   打印文件","选择文件或文字并设置打印",()->{menu.dismiss();startActivity(new Intent(this,PrintActivity.class));}));
  menuSection(sheet,"外观");
  LinearLayout themes=new LinearLayout(this);themes.setPadding(0,0,0,dp(8));String[] labels={"跟随系统","浅色","深色"};
  for(int i=0;i<labels.length;i++){final int choice=i;TextView theme=text((preferences.getInt("theme",0)==i?"✓  ":"")+labels[i],13);theme.setGravity(Gravity.CENTER);theme.setTextColor(foreground);theme.setBackground(surface(Color.parseColor(preferences.getInt("theme",0)==i?(dark?"#375B44":"#D3E8D2"):(dark?"#294536":"#E3ECDC")),13));
   theme.setOnClickListener(v->{preferences.edit().putInt("theme",choice).apply();dark=choice==0?systemDark():choice==2;menu.dismiss();applyColors();showMenu();});LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(44),1);tp.rightMargin=i<2?dp(7):0;themes.addView(theme,tp);}sheet.addView(themes);
  Switch reduce=new Switch(this);reduce.setText("减少动态效果");reduce.setTextSize(14);reduce.setTextColor(foreground);reduce.setPadding(dp(8),dp(9),dp(8),dp(9));reduce.setChecked(!scene.isMoving());
  reduce.setOnCheckedChangeListener((v,checked)->{preferences.edit().putBoolean("reduceMotion",checked).apply();scene.setMoving(!checked);});sheet.addView(reduce);
  if(chatOpen){TextView back=text("回到宠物",14);back.setTextColor(secondary);back.setGravity(Gravity.CENTER);back.setPadding(0,dp(16),0,0);back.setOnClickListener(v->{menu.dismiss();closeChat(true);});sheet.addView(back);}
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);scroll.addView(sheet);menu.setContentView(scroll);Window window=menu.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.25f);window.setGravity(Gravity.BOTTOM);}
  menu.setOnDismissListener(d->applyColors());menu.show();if(window!=null){window.setLayout(-1,-2);sheet.post(()->{int max=(int)(getResources().getDisplayMetrics().heightPixels*.84f);if(sheet.getHeight()>max)window.setLayout(-1,max);});}
 }
 private void menuSection(LinearLayout sheet,String title){TextView label=text(title,11);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));label.setTextColor(secondary);label.setPadding(dp(4),dp(13),0,dp(8));sheet.addView(label);}
 private View menuAction(String label,String detail,Runnable action){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(15),dp(10),dp(15),dp(10));card.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor(dark?"#406651":"#CADBC7")),surface(Color.parseColor(dark?"#294536":"#E3ECDC"),15),null));TextView name=text(label,15);name.setTextColor(foreground);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));card.addView(name);TextView sub=text(detail,12);sub.setTextColor(secondary);sub.setPadding(dp(27),dp(2),0,0);card.addView(sub);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(6);card.setLayoutParams(cp);card.setOnClickListener(v->action.run());return card;}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("chatOpen",chatOpen);}
 @Override public void onResume(){super.onResume();if(preferences.getInt("theme",0)==0)dark=systemDark();scene.resumeScene();applyColors();}
 @Override public void onPause(){scene.pauseScene();super.onPause();}
 @Override public void onDestroy(){if(menu!=null)menu.dismiss();chat.shutdown();super.onDestroy();}
}
