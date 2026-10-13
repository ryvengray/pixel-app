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
  FrameLayout root=new FrameLayout(this);
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
  greeting.setVisibility(View.GONE);chat.animate().cancel();chat.setAlpha(0f);chat.setTranslationY(dp(18));chat.setVisibility(View.VISIBLE);chat.bringToFront();petTouch.bringToFront();menuButton.bringToFront();
  chat.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(1.5f)).start();
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
  chat.close();chat.animate().alpha(0f).translationY(dp(18)).setDuration(220).setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(()->{if(!chatOpen){chat.setVisibility(View.GONE);chat.setAlpha(1f);chat.setTranslationY(0);}}).start();greeting.setVisibility(View.VISIBLE);
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
  LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setPadding(dp(24),dp(18),dp(24),dp(24));sheet.setBackground(surface(Color.parseColor(dark?"#172B22":"#F5F7F0"),28));
  TextView heading=text("你的 Gray",22);heading.setTextColor(foreground);heading.setPadding(0,0,0,dp(16));sheet.addView(heading);
  sheet.addView(menuAction("和 Gray 聊聊",()->{menu.dismiss();openChat(true);}));
  sheet.addView(menuAction("我的长期记忆",()->{menu.dismiss();startActivity(new Intent(this,MemoryActivity.class));}));
  sheet.addView(menuAction("助理设置",()->{menu.dismiss();startActivity(new Intent(this,AssistantSettingsActivity.class));}));
  sheet.addView(menuAction("Obsidian 同步",()->{menu.dismiss();startActivity(new Intent(this,SyncActivity.class));}));
  sheet.addView(menuAction("打印文件",()->{menu.dismiss();startActivity(new Intent(this,PrintActivity.class));}));
  TextView label=text("外观",13);label.setTextColor(secondary);label.setPadding(0,dp(20),0,dp(8));sheet.addView(label);
  LinearLayout themes=new LinearLayout(this);String[] labels={"跟随系统","浅色","深色"};
  for(int i=0;i<labels.length;i++){final int choice=i;Button theme=menuAction(labels[i],()->{
   preferences.edit().putInt("theme",choice).apply();dark=choice==0?systemDark():choice==2;menu.dismiss();applyColors();showMenu();
  });if(preferences.getInt("theme",0)==i)theme.setText("✓ "+labels[i]);themes.addView(theme,new LinearLayout.LayoutParams(0,dp(52),1));}sheet.addView(themes);
  Switch reduce=new Switch(this);reduce.setText("减少动态效果");reduce.setTextColor(foreground);reduce.setPadding(0,dp(18),0,dp(18));reduce.setChecked(!scene.isMoving());
  reduce.setOnCheckedChangeListener((v,checked)->{preferences.edit().putBoolean("reduceMotion",checked).apply();scene.setMoving(!checked);});sheet.addView(reduce);
  TextView about=text("聊天和记忆由你的私有服务与本地副本共同管理",12);about.setTextColor(secondary);sheet.addView(about);sheet.addView(menuAction("回到宠物",()->{menu.dismiss();if(chatOpen)closeChat(true);}));
  menu.setContentView(sheet);Window window=menu.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.25f);window.setGravity(Gravity.BOTTOM);}
  menu.setOnDismissListener(d->applyColors());menu.show();if(window!=null)window.setLayout(-1,-2);
 }
 private Button menuAction(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(foreground);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor(dark?"#406651":"#CADBC7")),surface(Color.parseColor(dark?"#294536":"#E3ECDC"),16),null));b.setElevation(0);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());return b;}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("chatOpen",chatOpen);}
 @Override public void onResume(){super.onResume();if(preferences.getInt("theme",0)==0)dark=systemDark();scene.resumeScene();applyColors();}
 @Override public void onPause(){scene.pauseScene();super.onPause();}
 @Override public void onDestroy(){if(menu!=null)menu.dismiss();chat.shutdown();super.onDestroy();}
}
