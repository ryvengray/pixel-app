package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
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
    private final TextView transcript,status,cancel,edit,stop;
    private SpeechRecognizer recognizer;
    private String partial="";
    private String committed="";
    private final Waveform waveform;
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
        oldStatus=activity.getWindow().getStatusBarColor();oldNavigation=activity.getWindow().getNavigationBarColor();WindowInsetsController bars=activity.getWindow().getInsetsController();oldAppearance=bars==null?0:bars.getSystemBarsAppearance();
        GradientDrawable back=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(8,15,30),Color.rgb(10,25,35),Color.rgb(7,12,24)});setBackground(back);setClickable(true);setElevation(dp(50));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        TextView heading=text("语音输入",22);heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);hp.topMargin=dp(54);addView(heading,hp);
        TextView hint=text("按住说话 · 松开发送",13);hint.setTextColor(Color.rgb(155,177,183));FrameLayout.LayoutParams htp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);htp.topMargin=dp(88);addView(hint,htp);
        transcript=text("开始说话，识别出的文字会显示在这里",21);transcript.setTextColor(Color.rgb(156,177,179));transcript.setPadding(dp(22),dp(22),dp(22),dp(22));transcript.setGravity(Gravity.TOP|Gravity.START);transcript.setTextIsSelectable(false);transcript.setLineSpacing(dp(7),1f);
        GradientDrawable transcriptCard=new GradientDrawable();transcriptCard.setColor(0x55374852);transcriptCard.setCornerRadius(dp(24));transcriptCard.setStroke(dp(1),0x557D9BA2);transcript.setBackground(transcriptCard);
        ScrollView scroll=new ScrollView(activity);scroll.setClipToPadding(false);scroll.addView(transcript);FrameLayout.LayoutParams textParams=new FrameLayout.LayoutParams(-1,-1);textParams.leftMargin=dp(22);textParams.rightMargin=dp(22);textParams.topMargin=dp(142);textParams.bottomMargin=dp(370);addView(scroll,textParams);
        status=text("正在连接语音服务…",14);status.setGravity(Gravity.CENTER);status.setTextColor(Color.rgb(190,211,209));FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,dp(36),Gravity.BOTTOM);sp.bottomMargin=dp(316);addView(status,sp);
        waveform=new Waveform(activity);FrameLayout.LayoutParams wp=new FrameLayout.LayoutParams(dp(220),dp(58),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);wp.bottomMargin=dp(352);addView(waveform,wp);
        View arc=new View(activity){private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();protected void onDraw(Canvas canvas){super.onDraw(canvas);paint.setColor(Color.rgb(75,80,76));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));path.reset();path.moveTo(dp(45),dp(8));path.cubicTo(getWidth()*.25f,dp(170),getWidth()*.75f,dp(170),getWidth()-dp(45),dp(8));canvas.drawPath(path,paint);}};
        FrameLayout.LayoutParams arcParams=new FrameLayout.LayoutParams(-1,dp(180),Gravity.BOTTOM);arcParams.bottomMargin=dp(36);addView(arc,arcParams);
        cancel=text("取消录音",15);cancel.setGravity(Gravity.CENTER);cancel.setContentDescription("取消录音，不发送");cancel.setOnClickListener(v->finish("",false,null));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(120),dp(54),Gravity.BOTTOM|Gravity.LEFT);cp.leftMargin=dp(20);cp.bottomMargin=dp(164);addView(cancel,cp);
        edit=text("编辑文字",15);edit.setGravity(Gravity.CENTER);edit.setContentDescription("停止录音并编辑文字");edit.setOnClickListener(v->{target=2;releaseLocked();});
        FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(120),dp(54),Gravity.BOTTOM|Gravity.RIGHT);ep.rightMargin=dp(20);ep.bottomMargin=dp(164);addView(edit,ep);
        stop=text("松开发送",17);stop.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));stop.setGravity(Gravity.CENTER);stop.setTextColor(Color.rgb(11,35,26));stop.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{Color.rgb(137,221,165),Color.rgb(94,191,142)}));stop.setContentDescription("停止录音，转写完成后发送文字");stop.setOnClickListener(v->{target=0;releaseLocked();});
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,dp(64),Gravity.BOTTOM);bp.bottomMargin=dp(80);bp.leftMargin=dp(26);bp.rightMargin=dp(26);addView(stop,bp);
        setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
    }
    private TextView text(String value,int size){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(Color.rgb(232,239,233));return v;}
    public void start(){
        if(!SpeechRecognizer.isRecognitionAvailable(activity)){finish("",false,"这台设备暂时没有可用的语音识别服务，请先打字");return;}
        ((android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);
        ((FrameLayout)activity.getWindow().getDecorView()).addView(this,new FrameLayout.LayoutParams(-1,-1));requestApplyInsets();
        activity.getWindow().setStatusBarColor(Color.BLACK);activity.getWindow().setNavigationBarColor(Color.BLACK);WindowInsetsController bars=activity.getWindow().getInsetsController();if(bars!=null)bars.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
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
        int next=0;float boundary=getHeight()-getPaddingBottom()-dp(150);if(y<boundary){if(x<getWidth()*.43f)next=1;else if(x>getWidth()*.57f)next=2;}
        if(next!=target){target=next;performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
        cancel.setTextColor(target==1?Color.rgb(255,140,140):Color.WHITE);edit.setTextColor(target==2?Color.rgb(164,224,174):Color.WHITE);stop.setText(target==1?"松开取消":target==2?"松开编辑":"松开发送");
    }
    public void release(float x,float y){if(released||finished)return;move(x,y);releaseLocked();}
    private void releaseLocked(){if(released||finished)return;released=true;
        main.removeCallbacks(restart);main.removeCallbacks(startupTimeout);
        if(target==1){finish("",false,null);return;}
        if(!listening||!ready){String text=allText();finish(text,target==2,text.isEmpty()?"录音尚未就绪，请稍后重新按住说话":null);return;}
        stop.setEnabled(false);edit.setEnabled(false);status.setText("正在整理文字…");main.postDelayed(timeout,5000);
        try{recognizer.stopListening();}catch(Exception e){fail("识别未完成，已有文字已保留");}
    }
    public void interrupt(){if(!finished){String all=(committed+" "+partial).trim();finish(all,true,all.isEmpty()?null:"录音已中断，文字已保留");}}
    private void finish(String text,boolean editText,String message){if(finished)return;finished=true;main.removeCallbacksAndMessages(null);closeRecognizer();
        if(getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)getParent()).removeView(this);
        activity.getWindow().setStatusBarColor(oldStatus);activity.getWindow().setNavigationBarColor(oldNavigation);WindowInsetsController bars=activity.getWindow().getInsetsController();if(bars!=null)bars.setSystemBarsAppearance(oldAppearance,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        if(message!=null)Toast.makeText(activity,message,Toast.LENGTH_LONG).show();if(!text.trim().isEmpty())listener.completed(text.trim(),editText);else listener.dismissed();
    }
    private String recognized(Bundle bundle){ArrayList<String> items=bundle==null?null:bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);return items==null||items.isEmpty()?"":items.get(0);}
    public void onReadyForSpeech(Bundle b){if(finished||released)return;ready=true;main.removeCallbacks(startupTimeout);status.setText("正在聆听 · 松开发送");performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}
    public void onPartialResults(Bundle b){if(finished)return;String text=recognized(b);if(!text.isEmpty()){partial=text;transcript.setTextColor(Color.rgb(239,246,239));showTranscript();status.setText("正在识别…");}}
    private void showTranscript(){String all=(committed+" "+partial).trim();transcript.setText(all);}
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
        if(!allText().isEmpty()){transcript.setTextColor(Color.rgb(239,246,239));showTranscript();}
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

    private static final class Waveform extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private float level;
        Waveform(Activity a){super(a);paint.setColor(Color.rgb(133,218,155));paint.setStrokeWidth(dp(a,3));paint.setStrokeCap(Paint.Cap.ROUND);}
        private static int dp(Activity a,float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
        void setLevel(float value){level=Math.max(0,Math.min(1,(value+2)/12f));invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float mid=getHeight()/2f;int count=19;float gap=getWidth()/(float)count;for(int i=0;i<count;i++){float wave=(float)Math.abs(Math.sin((SystemClock.uptimeMillis()/120.0)+i*.68));float h=dp((Activity)getContext(),5+level*32*wave);c.drawLine((i+.5f)*gap,mid-h/2,(i+.5f)*gap,mid+h/2,paint);}if(isAttachedToWindow())postInvalidateDelayed(70);}
    }
}
