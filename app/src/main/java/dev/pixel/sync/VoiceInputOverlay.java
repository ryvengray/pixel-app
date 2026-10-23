package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.speech.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

/** Owns a single recognizer session. Release intent is immutable after lifting the finger. */
public final class VoiceInputOverlay extends FrameLayout implements RecognitionListener {
    public interface Listener {void completed(String text,boolean edit);void dismissed();}
    private final Activity activity;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final TextView transcript,status,cancel,edit,stop,releaseHint;
    private SpeechRecognizer recognizer;
    private String partial="";
    private String committed="";
    private final Waveform waveform;
    private final View fan;
    private final ScrollView transcriptScroll;
    private final boolean dark;
    private final int ink,muted,accent;
    private float holdRawX=Float.NaN,holdRawY=Float.NaN,originX,originY,fanRadius;
    private float cancelAngle=215,editAngle=270;
    private float cancelX,cancelY,editX,editY,fingerX,fingerY;
    private boolean fingerMoved;
    private String releaseMessage="";
    private float cancelProgress,editProgress,cancelGoal,editGoal;
    private final GradientDrawable cancelSurface=new GradientDrawable(),editSurface=new GradientDrawable();
    private boolean feedbackAnimating;
    private long feedbackTime;
    private final Runnable feedbackFrame=this::animateFeedback;
    private boolean ready,released,finished,listening,onDevice,fallbackUsed;
    private int generation,recoveries;
    private final Runnable restart=this::listenAgain;
    private final Runnable startupTimeout=()->{
        if(finished||released||ready)return;
        if(!fallbackToSystem())fail("语音服务没有响应，请稍后重试");
    };
    private int target; // 0 send, 1 cancel, 2 edit
    private final int oldStatus,oldNavigation,oldAppearance;
    private final long started=SystemClock.elapsedRealtime();
    private final Runnable timeout=()->fail(allText().isEmpty()?"没听清，再试一次":"识别未完成，文字已保留，请检查后发送");
    private final Runnable ticker=new Runnable(){public void run(){if(finished||released)return;long seconds=(SystemClock.elapsedRealtime()-started)/1000;status.setText(ready?"正在听 · "+String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60):"正在准备…");main.postDelayed(this,1000);}};
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    public VoiceInputOverlay(Activity activity,Listener listener){
        super(activity);this.activity=activity;this.listener=listener;
        int theme=activity.getSharedPreferences("home",0).getInt("theme",0);
        dark=theme==2||(theme==0&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        ink=dark?0xFFE6EEF3:0xFF192F3C;muted=dark?0xFF8095A5:0xFF607784;accent=dark?0xFF8CDCD8:0xFF187675;
        oldStatus=activity.getWindow().getStatusBarColor();oldNavigation=activity.getWindow().getNavigationBarColor();WindowInsetsController bars=activity.getWindow().getInsetsController();oldAppearance=bars==null?0:bars.getSystemBarsAppearance();
        GradientDrawable back=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{dark?0xFF090F17:0xFFF4F8FA,dark?0xFF101E29:0xFFEAF2F5});
        setBackground(back);setClickable(true);setElevation(dp(50));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        TextView heading=text("GRETA  /  VOICE",11);heading.setTextColor(muted);heading.setLetterSpacing(.14f);
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-2,dp(36),Gravity.TOP|Gravity.LEFT);hp.leftMargin=dp(28);hp.topMargin=dp(24);heading.setGravity(Gravity.CENTER_VERTICAL);addView(heading,hp);
        status=text("正在连接…",11);status.setTextColor(muted);status.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);status.setSingleLine(true);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(156),dp(36),Gravity.TOP|Gravity.RIGHT);sp.topMargin=dp(24);sp.rightMargin=dp(28);addView(status,sp);
        transcript=text("说说你的想法…",27);transcript.setTextColor(muted);transcript.setGravity(Gravity.TOP|Gravity.START);transcript.setTextIsSelectable(false);transcript.setLineSpacing(dp(9),1f);transcript.setIncludeFontPadding(false);transcript.setPadding(0,dp(10),0,dp(20));
        transcriptScroll=new ScrollView(activity);transcriptScroll.setVerticalScrollBarEnabled(false);transcriptScroll.setOverScrollMode(OVER_SCROLL_NEVER);transcriptScroll.addView(transcript);
        FrameLayout.LayoutParams textParams=new FrameLayout.LayoutParams(-1,-1);textParams.leftMargin=dp(28);textParams.rightMargin=dp(28);textParams.topMargin=dp(116);textParams.bottomMargin=dp(356);addView(transcriptScroll,textParams);
        waveform=new Waveform(activity,accent);FrameLayout.LayoutParams wp=new FrameLayout.LayoutParams(dp(112),dp(24),Gravity.TOP|Gravity.LEFT);wp.leftMargin=dp(28);wp.topMargin=dp(76);addView(waveform,wp);

        fan=new View(activity){
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas){
                RectF circle=new RectF(originX-fanRadius,originY-fanRadius,originX+fanRadius,originY+fanRadius);
                int cancelColor=dark?0xFFFFA7B1:0xFFA9475B;
                drawHalo(canvas,paint,cancelX,cancelY-dp(7),cancelColor,cancelProgress);
                drawHalo(canvas,paint,editX,editY-dp(7),accent,editProgress);
                int base=dark?0x267EA3B6:0x303B697D;
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,dp(1)*.65f));paint.setColor(Color.WHITE);
                if(fanRadius>0){
                    paint.setShader(new SweepGradient(originX,originY,new int[]{base,blend(base,alpha(cancelColor,.8f),cancelProgress),blend(base,alpha(accent,.8f),editProgress),base},new float[]{0,cancelAngle/360f,editAngle/360f,1}));
                    canvas.drawArc(circle,cancelAngle,editAngle-cancelAngle,false,paint);paint.setShader(null);
                }
                float strength=Math.max(cancelProgress,editProgress);
                int tint=blend(accent,cancelColor,cancelProgress/Math.max(.001f,cancelProgress+editProgress));
                paint.setColor(blend(dark?0x557FAAB7:0x55457285,tint,strength));
                canvas.drawCircle(originX,originY,dp(27),paint);
                paint.setStyle(Paint.Style.FILL);paint.setColor(alpha(tint,.05f+.09f*strength));
                canvas.drawCircle(originX,originY,dp(23),paint);
                paint.setColor(alpha(cancelColor,cancelProgress));canvas.drawCircle(cancelX,cancelY-dp(8),dp(2),paint);
                paint.setColor(alpha(accent,editProgress));canvas.drawCircle(editX,editY-dp(8),dp(2),paint);
                drawTargetBoundary(canvas,paint,cancelX,cancelY,cancelColor,cancelProgress);
                drawTargetBoundary(canvas,paint,editX,editY,accent,editProgress);
                if(fingerMoved){
                    int cursor=target==1?cancelColor:target==2?accent:ink;
                    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(alpha(cursor,.45f));
                    canvas.drawLine(originX,originY,fingerX,fingerY,paint);
                    canvas.drawCircle(fingerX,fingerY,dp(22),paint);
                    // Offset the bright marker so the fingertip cannot hide it.
                    paint.setStyle(Paint.Style.FILL);paint.setColor(cursor);canvas.drawCircle(fingerX,fingerY-dp(28),dp(4),paint);
                }
            }
        };
        fan.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);addView(fan,new FrameLayout.LayoutParams(-1,-1));
        cancel=text("取消",12);cancel.setCompoundDrawables(null,new ActionIcon(false),null,null);cancel.setCompoundDrawablePadding(dp(5));cancel.setGravity(Gravity.CENTER);cancel.setContentDescription("取消录音，不发送");cancel.setOnClickListener(v->{target=1;releaseLocked();});
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(76),dp(66),Gravity.BOTTOM|Gravity.LEFT);cp.leftMargin=dp(20);cp.bottomMargin=dp(164);addView(cancel,cp);
        edit=text("编辑",12);edit.setCompoundDrawables(null,new ActionIcon(true),null,null);edit.setCompoundDrawablePadding(dp(5));edit.setGravity(Gravity.CENTER);edit.setContentDescription("停止录音并编辑文字");edit.setOnClickListener(v->{target=2;releaseLocked();});
        FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(76),dp(66),Gravity.BOTTOM|Gravity.RIGHT);ep.rightMargin=dp(20);ep.bottomMargin=dp(164);addView(edit,ep);
        stop=text("松开发送",12);stop.setTag("voice_release");stop.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));stop.setGravity(Gravity.CENTER);stop.setIncludeFontPadding(false);stop.setTextColor(accent);

        stop.setContentDescription("停止录音，转写完成后发送文字");stop.setOnClickListener(v->{target=0;releaseLocked();});
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(112),dp(44),Gravity.BOTTOM|Gravity.RIGHT);bp.bottomMargin=dp(16);addView(stop,bp);styleTarget(cancel,0,1);styleTarget(edit,0,2);
        releaseHint=text("",16);releaseHint.setGravity(Gravity.CENTER);releaseHint.setIncludeFontPadding(false);releaseHint.setLineSpacing(dp(5),1);releaseHint.setPadding(dp(12),dp(8),dp(12),dp(8));
        releaseHint.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(dp(216),dp(64));addView(releaseHint,rp);updateReleaseHint(0);
        setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
    }
    public void setHoldOrigin(float rawX,float rawY){holdRawX=rawX;holdRawY=rawY;requestLayout();}
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        super.onLayout(changed,left,top,right,bottom);
        int[] location=new int[2];getLocationOnScreen(location);
        originX=Float.isNaN(holdRawX)?getWidth()-getPaddingRight()-dp(72):holdRawX-location[0];
        originY=Float.isNaN(holdRawY)?getHeight()-getPaddingBottom()-dp(30):holdRawY-location[1];
        layoutFanTargets();
        place(cancel,cancelX,cancelY);place(edit,editX,editY);
        float stopX=Math.max(getPaddingLeft()+stop.getMeasuredWidth()/2f+dp(8),Math.min(originX,getWidth()-getPaddingRight()-stop.getMeasuredWidth()/2f-dp(8)));
        float stopY=Math.min(originY,getHeight()-getPaddingBottom()-stop.getMeasuredHeight()/2f);
        place(stop,stopX,stopY);
        if(!fingerMoved){fingerX=originX;fingerY=originY;}
        layoutReleaseHint();fan.invalidate();
    }
    private void layoutFanTargets(){
        // Reserve the full 62dp selected ring, its 10dp feather and a 12dp edge gap.
        float inset=dp(84),left=getPaddingLeft()+inset,right=getWidth()-getPaddingRight()-inset;
        float top=getPaddingTop()+inset,bottom=getHeight()-getPaddingBottom()-inset;
        // Anchor edit to the right safe edge, then choose an upper-left cancel
        // position on the SAME circle. Solve both centers before placing either.
        for(float radius=dp(184);radius>=dp(80);radius-=Math.max(1,dp(2))){
            float dx=right-originX;if(Math.abs(dx)>radius)continue;
            float ey=originY-(float)Math.sqrt(radius*radius-dx*dx);
            if(ey<top||ey>bottom)continue;
            float ea=(float)Math.toDegrees(Math.atan2(ey-originY,dx))+360;
            float bestAngle=0,bestCost=Float.MAX_VALUE,bestX=0,bestY=0;
            for(float ca=190;ca<=235;ca+=.5f){
                float cx=originX+(float)Math.cos(Math.toRadians(ca))*radius;
                float cy=originY+(float)Math.sin(Math.toRadians(ca))*radius;
                if(cx<left||cx>right||cy<top||cy>bottom||ca>=ea)continue;
                if(Math.hypot(cx-right,cy-ey)<dp(132))continue;
                float cost=Math.abs(ca-215);
                if(cost<bestCost){bestCost=cost;bestAngle=ca;bestX=cx;bestY=cy;}
            }
            if(bestCost<Float.MAX_VALUE){
                fanRadius=radius;cancelX=bestX;cancelY=bestY;editX=right;editY=ey;cancelAngle=bestAngle;editAngle=ea;return;
            }
        }
        // Extremely constrained windows cannot fit two full rings; keep controls
        // inside their safe area rather than drawing them beyond the display.
        cancelX=Math.max(left,Math.min(originX-dp(130),right));cancelY=Math.max(top,Math.min(originY-dp(110),bottom));
        editX=right;editY=Math.max(top,Math.min(originY-dp(184),bottom));fanRadius=0;
    }
    private void place(View view,float x,float y){int left=Math.round(x-view.getMeasuredWidth()/2f),top=Math.round(y-view.getMeasuredHeight()/2f);view.layout(left,top,left+view.getMeasuredWidth(),top+view.getMeasuredHeight());}
    private void layoutReleaseHint(){
        float half=releaseHint.getMeasuredWidth()/2f;
        float x=Math.max(getPaddingLeft()+half+dp(16),Math.min(fingerX,getWidth()-getPaddingRight()-half-dp(16)));
        float y=Math.max(getPaddingTop()+dp(152),Math.min(fingerY-dp(86),Math.min(cancelY,editY)-dp(116)));
        place(releaseHint,x,y);
    }
    private void updateReleaseHint(int near){
        String title=target==1?"松开将取消":target==2?"松开后编辑":"松开将发送";
        String detail=target==1?"本段语音不会发送":target==2?"先修改文字，不会立即发送":near==1?"继续滑向取消，选中后再松手":near==2?"继续滑向编辑，选中后再松手":"滑向取消或编辑可切换操作";
        String message=title+"\n"+detail;
        if(message.equals(releaseMessage))return;releaseMessage=message;
        android.text.SpannableString text=new android.text.SpannableString(message);
        text.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,title.length(),0);
        text.setSpan(new android.text.style.AbsoluteSizeSpan(11,true),title.length()+1,message.length(),0);
        releaseHint.setText(text);releaseHint.setContentDescription(title+"，"+detail);
        int color=target==1?(dark?0xFFFFA7B1:0xFFA9475B):target==2?accent:ink;
        releaseHint.setTextColor(color);
        GradientDrawable surface=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{dark?0xF520303E:0xFAFFFFFF,dark?0xF014222E:0xFAEFF6F8});
        surface.setCornerRadius(dp(16));surface.setStroke(dp(1),alpha(color,target==0?.2f:.65f));releaseHint.setBackground(surface);
        cancel.setText(target==1?"取消 ✓":"取消");edit.setText(target==2?"编辑 ✓":"编辑");
    }
    private void drawTargetBoundary(Canvas canvas,Paint paint,float x,float y,int color,float progress){
        float radius=dp(52)+dp(10)*progress,feather=dp(10),outer=radius+feather;
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);
        // A translucent radial band feathers both edges without a hard neon outline.
        paint.setShader(new RadialGradient(x,y,outer,
            new int[]{alpha(color,0),alpha(color,0),alpha(color,(dark?.12f:.09f)+.16f*progress),alpha(color,0)},
            new float[]{0,(radius-feather)/outer,radius/outer,1},Shader.TileMode.CLAMP));
        canvas.drawCircle(x,y,outer,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,dp(1)*.65f));
        paint.setColor(alpha(color,.08f+.24f*progress));canvas.drawCircle(x,y,radius,paint);
    }
    private static int blend(int from,int to,float progress){
        float t=Math.max(0,Math.min(1,progress));
        return Color.argb(Math.round(Color.alpha(from)+(Color.alpha(to)-Color.alpha(from))*t),Math.round(Color.red(from)+(Color.red(to)-Color.red(from))*t),Math.round(Color.green(from)+(Color.green(to)-Color.green(from))*t),Math.round(Color.blue(from)+(Color.blue(to)-Color.blue(from))*t));
    }
    private static int alpha(int color,float opacity){return (color&0x00FFFFFF)|(Math.round(255*Math.max(0,Math.min(1,opacity)))<<24);}
    private void drawHalo(Canvas canvas,Paint paint,float x,float y,int color,float progress){
        if(progress<.001f)return;
        float radius=dp(34)+dp(14)*progress;
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);
        paint.setShader(new RadialGradient(x,y,radius,new int[]{alpha(color,(dark?.21f:.12f)*progress),alpha(color,.05f*progress),alpha(color,0)},new float[]{0,.5f,1},Shader.TileMode.CLAMP));
        canvas.drawCircle(x,y,radius,paint);paint.setShader(null);
    }
    private void styleTarget(TextView view,float progress,int action){
        int color=action==1?(dark?0xFFFFA7B1:0xFFA9475B):accent;
        GradientDrawable background=action==1?cancelSurface:editSurface;
        background.setCornerRadius(dp(18));background.setOrientation(GradientDrawable.Orientation.TL_BR);
        background.setColors(new int[]{alpha(color,(dark?.15f:.09f)*progress),alpha(color,.035f*progress),alpha(color,0)});
        if(view.getBackground()!=background)view.setBackground(background);
        int foreground=blend(muted,color,progress);view.setTextColor(foreground);
        for(Drawable icon:view.getCompoundDrawables())if(icon instanceof ActionIcon){((ActionIcon)icon).color=foreground;icon.invalidateSelf();}
        view.invalidate();
    }
    private float proximity(float distance){
        float range=Math.max(dp(100),fanRadius*.85f);
        float t=Math.max(0,Math.min(1,(range-distance)/(range-dp(26))));
        return t*t*(3-2*t);
    }
    private void updateFeedback(float cancelDistance,float editDistance){
        cancelGoal=target==1?1:proximity(cancelDistance);editGoal=target==2?1:proximity(editDistance);
        if(activity.getSharedPreferences("home",0).getBoolean("reduceMotion",false)){
            fan.removeCallbacks(feedbackFrame);feedbackAnimating=false;cancelProgress=cancelGoal;editProgress=editGoal;applyFeedback();return;
        }
        if(!feedbackAnimating){feedbackAnimating=true;feedbackTime=SystemClock.uptimeMillis();fan.postOnAnimation(feedbackFrame);}
    }
    private void animateFeedback(){
        if(finished||!isAttachedToWindow()){feedbackAnimating=false;return;}
        long now=SystemClock.uptimeMillis();float step=1-(float)Math.exp(-Math.min(48,Math.max(1,now-feedbackTime))/65f);feedbackTime=now;
        cancelProgress+=(cancelGoal-cancelProgress)*step;editProgress+=(editGoal-editProgress)*step;
        boolean settled=Math.abs(cancelGoal-cancelProgress)<.002f&&Math.abs(editGoal-editProgress)<.002f;
        if(settled){cancelProgress=cancelGoal;editProgress=editGoal;}
        applyFeedback();
        if(settled)feedbackAnimating=false;else fan.postOnAnimation(feedbackFrame);
    }
    private void applyFeedback(){
        styleTarget(cancel,cancelProgress,1);styleTarget(edit,editProgress,2);
        stop.setTextColor(blend(accent,dark?0xFFFFA7B1:0xFFA9475B,cancelProgress));fan.invalidate();
    }
    private TextView text(String value,int size){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setSoundEffectsEnabled(false);v.setTextColor(ink);return v;}
    public void start(){
        if(!SpeechRecognizer.isRecognitionAvailable(activity)){finish("",false,"这台设备暂时没有可用的语音识别服务，请先打字");return;}
        ((android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);
        ((FrameLayout)activity.getWindow().getDecorView()).addView(this,new FrameLayout.LayoutParams(-1,-1));requestApplyInsets();
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);WindowInsetsController bars=activity.getWindow().getInsetsController();if(bars!=null)bars.setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        onDevice=Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(activity);
        openRecognizer();main.post(ticker);
    }
    private Intent recognitionIntent(){
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        // A missing offline Chinese model must not also constrain the system fallback.
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,onDevice);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);return intent;
    }
    private void openRecognizer(){
        if(finished||released)return;
        try{
            recognizer=onDevice?SpeechRecognizer.createOnDeviceSpeechRecognizer(activity):SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new SessionListener(++generation));listenAgain();
        }catch(Exception e){if(!fallbackToSystem())fail("语音识别未能启动，请稍后重试");}
    }
    private void closeRecognizer(){
        ++generation;SpeechRecognizer old=recognizer;recognizer=null;listening=false;ready=false;
        main.removeCallbacks(restart);main.removeCallbacks(startupTimeout);
        if(old!=null){try{old.cancel();}catch(Exception ignored){}try{old.destroy();}catch(Exception ignored){}}
    }
    private boolean fallbackToSystem(){
        if(finished||released||!onDevice||fallbackUsed)return false;
        fallbackUsed=true;keepPartial();closeRecognizer();onDevice=false;
        status.setText("正在切换语音服务…");main.postDelayed(this::openRecognizer,300);return true;
    }
    private String allText(){return (committed+" "+partial).trim();}
    private void keepPartial(){if(!partial.isEmpty()){committed=allText();partial="";}}
    private void fail(String message){finish(allText(),true,message);}
    private void scheduleRestart(long delay){
        if(finished||released)return;
        listening=false;ready=false;main.removeCallbacks(startupTimeout);main.removeCallbacks(restart);
        status.setText("继续说话 · 松开发送");main.postDelayed(restart,delay);
    }
    /** Old recognizers can deliver callbacks after cancel/destroy or a service switch. */
    private final class SessionListener implements RecognitionListener {
        private final int session;
        SessionListener(int session){this.session=session;}
        private boolean current(){return !finished&&session==generation;}
        public void onReadyForSpeech(Bundle b){if(current())VoiceInputOverlay.this.onReadyForSpeech(b);}
        public void onBeginningOfSpeech(){if(current())VoiceInputOverlay.this.onBeginningOfSpeech();}
        public void onRmsChanged(float n){if(current())VoiceInputOverlay.this.onRmsChanged(n);}
        public void onBufferReceived(byte[] b){if(current())VoiceInputOverlay.this.onBufferReceived(b);}
        public void onEndOfSpeech(){if(current())VoiceInputOverlay.this.onEndOfSpeech();}
        public void onError(int error){if(current())VoiceInputOverlay.this.onError(error);}
        public void onResults(Bundle b){if(current())VoiceInputOverlay.this.onResults(b);}
        public void onPartialResults(Bundle b){if(current())VoiceInputOverlay.this.onPartialResults(b);}
        public void onEvent(int n,Bundle b){if(current())VoiceInputOverlay.this.onEvent(n,b);}
    }
    public void move(float rawX,float rawY){if(finished||released)return;int[] location=new int[2];getLocationOnScreen(location);float x=rawX-location[0],y=rawY-location[1];
        float cancelDistance=(float)Math.hypot(x-cancelX,y-cancelY),editDistance=(float)Math.hypot(x-editX,y-editY);
        int next=0;
        if(target==1&&cancelDistance<dp(62))next=1;
        else if(target==2&&editDistance<dp(62))next=2;
        else if(Math.min(cancelDistance,editDistance)<dp(52))next=cancelDistance<editDistance?1:2;
        if(next!=target){target=next;performHapticFeedback(target==0?HapticFeedbackConstants.CLOCK_TICK:HapticFeedbackConstants.CONFIRM);}
        fingerX=x;fingerY=y;fingerMoved=Math.hypot(x-originX,y-originY)>dp(12);
        int near=Math.min(cancelDistance,editDistance)<fanRadius*.8f?(cancelDistance<editDistance?1:2):0;
        updateReleaseHint(near);layoutReleaseHint();
        updateFeedback(cancelDistance,editDistance);fan.invalidate();
        stop.setText(target==1?"松开取消":target==2?"松开编辑":"松开发送");
    }
    public void release(){if(released||finished)return;
        // Commit exactly the action already shown to the user. Lift-off jitter
        // must not turn a visibly selected cancellation back into a send.
        releaseLocked();
    }
    private void releaseLocked(){if(released||finished)return;released=true;
        main.removeCallbacks(restart);main.removeCallbacks(startupTimeout);
        if(target==1){finish("",false,null);return;}
        if(!listening||!ready){String text=allText();finish(text,target==2,text.isEmpty()?"录音尚未就绪，请稍后重新按住说话":null);return;}
        stop.setEnabled(false);edit.setEnabled(false);status.setText("正在整理文字…");main.postDelayed(timeout,5000);
        try{recognizer.stopListening();}catch(Exception e){fail("识别未完成，已有文字已保留");}
    }
    public void interrupt(){if(!finished){String all=(committed+" "+partial).trim();finish(all,true,all.isEmpty()?null:"录音已中断，文字已保留");}}
    private void finish(String text,boolean editText,String message){if(finished)return;
        // Cancellation wins over late recognition errors, timeouts and interruptions.
        if(target==1){text="";editText=false;message=null;}
        finished=true;main.removeCallbacksAndMessages(null);fan.removeCallbacks(feedbackFrame);feedbackAnimating=false;closeRecognizer();
        if(getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)getParent()).removeView(this);
        activity.getWindow().setStatusBarColor(oldStatus);activity.getWindow().setNavigationBarColor(oldNavigation);WindowInsetsController bars=activity.getWindow().getInsetsController();if(bars!=null)bars.setSystemBarsAppearance(oldAppearance,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        if(message!=null)Toast.makeText(activity,message,Toast.LENGTH_LONG).show();if(!text.trim().isEmpty())listener.completed(text.trim(),editText);else listener.dismissed();
    }
    private String recognized(Bundle bundle){ArrayList<String> items=bundle==null?null:bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);return items==null||items.isEmpty()?"":items.get(0);}
    public void onReadyForSpeech(Bundle b){if(finished||released)return;ready=true;main.removeCallbacks(startupTimeout);status.setText("正在聆听 · 松开发送");performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}
    public void onPartialResults(Bundle b){if(finished)return;String text=recognized(b);if(!text.isEmpty()){partial=text;transcript.setTextColor(ink);showTranscript();status.setText("正在识别…");}}
    private void showTranscript(){String all=(committed+" "+partial).trim();transcript.setText(all);transcriptScroll.post(()->transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN));}
    private void listenAgain(){
        if(finished||released||recognizer==null||listening)return;
        ready=false;listening=true;main.removeCallbacks(startupTimeout);main.postDelayed(startupTimeout,12000);
        try{recognizer.startListening(recognitionIntent());}
        catch(Exception e){listening=false;if(!fallbackToSystem())fail("语音识别未能启动，请稍后重试");}
    }
    public void onResults(Bundle b){
        if(finished)return;listening=false;main.removeCallbacks(startupTimeout);recoveries=0;
        String text=recognized(b);
        if(!text.isEmpty()){if(!committed.isEmpty())committed+=" ";committed+=text;partial="";}else keepPartial();
        if(!allText().isEmpty()){transcript.setTextColor(ink);showTranscript();}
        if(released){finish(allText(),target==2,null);return;}
        // A phrase ending during a pause does not end the user's hold gesture.
        scheduleRestart(180);
    }
    public void onError(int error){
        if(finished)return;listening=false;main.removeCallbacks(startupTimeout);
        android.util.Log.w("GretaVoice","Recognition error="+error+", onDevice="+onDevice+", released="+released);
        boolean silence=error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if(released){String text=allText();finish(text,target==2||!silence,text.isEmpty()?(silence?"没听清，再试一次":errorMessage(error)):null);return;}
        if(silence){keepPartial();scheduleRestart(250);return;}
        if(error!=SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS&&fallbackToSystem())return;
        boolean transientError=error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY||error==SpeechRecognizer.ERROR_CLIENT||error==SpeechRecognizer.ERROR_SERVER_DISCONNECTED;
        if(transientError&&recoveries++<2){keepPartial();closeRecognizer();status.setText("正在重新连接语音服务…");main.postDelayed(this::openRecognizer,500);return;}
        fail(errorMessage(error));
    }
    private String errorMessage(int error){
        switch(error){
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:return "需要麦克风权限，请在应用权限中允许录音";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:return "当前语音服务不支持中文，请检查系统语音设置";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:return "中文语音包尚未就绪，请在系统语音设置中下载";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:return "语音服务连接失败，请检查网络后重试";
            case SpeechRecognizer.ERROR_AUDIO:return "麦克风未能启动，请检查是否被其他应用占用";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:return "语音服务正忙，请稍后重试";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:return "语音请求过于频繁，请稍后重试";
            default:return "语音服务暂时不可用（"+error+"），请重试或编辑已有文字";
        }
    }
    public void onBeginningOfSpeech(){} public void onRmsChanged(float n){main.post(()->{if(!finished&&!released)waveform.setLevel(n);});} public void onBufferReceived(byte[] b){} public void onEndOfSpeech(){} public void onEvent(int n,Bundle b){}

    /** Small vector icons remain sharp on every screen density. */
    private final class ActionIcon extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final boolean pencil;private int color=muted;
        ActionIcon(boolean pencil){this.pencil=pencil;setBounds(0,0,dp(22),dp(22));}
        @Override public void draw(Canvas canvas){
            canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
            paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            if(pencil){Path path=new Path();path.moveTo(5,16);path.lineTo(16,5);path.lineTo(20,9);path.lineTo(9,20);path.lineTo(4,21);path.close();canvas.drawPath(path,paint);canvas.drawLine(14,7,18,11,paint);}
            else{canvas.drawLine(6,6,18,18,paint);canvas.drawLine(18,6,6,18,paint);}
            canvas.restore();
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private static final class Waveform extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private float level;private final boolean reduced;
        Waveform(Activity a,int color){super(a);paint.setColor(color);paint.setStrokeWidth(dp(a,2));paint.setStrokeCap(Paint.Cap.ROUND);reduced=a.getSharedPreferences("home",0).getBoolean("reduceMotion",false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        private static int dp(Activity a,float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
        void setLevel(float value){level=Math.max(0,Math.min(1,(value+2)/12f));invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float mid=getHeight()/2f;int count=25;float gap=getWidth()/(float)count;
            for(int i=0;i<count;i++){
                float envelope=1-Math.abs(i-(count-1)/2f)/(count/2f);
                float wave=reduced?.6f:(float)Math.abs(Math.sin((SystemClock.uptimeMillis()/160.0)+i*.7));
                float h=dp((Activity)getContext(),2+level*12*wave*envelope);
                paint.setAlpha(100+(int)(155*envelope));c.drawLine((i+.5f)*gap,mid-h,(i+.5f)*gap,mid+h,paint);
            }
            if(!reduced&&isAttachedToWindow()){level*=.97f;postInvalidateDelayed(50);}
        }
    }
}
