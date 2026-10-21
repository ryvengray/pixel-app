package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
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
    private boolean ready,released,finished;
    private int target; // 0 send, 1 cancel, 2 edit
    private final int oldStatus,oldNavigation,oldAppearance;
    private final long started=SystemClock.elapsedRealtime();
    private final Runnable timeout=()->finish((committed+" "+partial).trim(),true,"识别未完成，文字已保留，请检查后发送");
    private final Runnable ticker=new Runnable(){public void run(){if(finished||released)return;long seconds=(SystemClock.elapsedRealtime()-started)/1000;status.setText(ready?"正在听 · "+String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60):"正在准备…");main.postDelayed(this,1000);}};
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    public VoiceInputOverlay(Activity activity,Listener listener){
        super(activity);this.activity=activity;this.listener=listener;
        oldStatus=activity.getWindow().getStatusBarColor();oldNavigation=activity.getWindow().getNavigationBarColor();WindowInsetsController bars=activity.getWindow().getInsetsController();oldAppearance=bars==null?0:bars.getSystemBarsAppearance();
        setBackgroundColor(Color.BLACK);setClickable(true);setElevation(dp(50));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        transcript=text("",23);transcript.setPadding(dp(30),dp(26),dp(30),dp(26));transcript.setGravity(Gravity.CENTER_VERTICAL);transcript.setTextIsSelectable(false);
        ScrollView scroll=new ScrollView(activity);scroll.addView(transcript);FrameLayout.LayoutParams textParams=new FrameLayout.LayoutParams(-1,-1);textParams.topMargin=dp(90);textParams.bottomMargin=dp(250);addView(scroll,textParams);
        status=text("正在准备…",14);status.setGravity(Gravity.CENTER);FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,dp(44),Gravity.BOTTOM);sp.bottomMargin=dp(230);addView(status,sp);
        waveform=new Waveform(activity);FrameLayout.LayoutParams wp=new FrameLayout.LayoutParams(dp(180),dp(64),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);wp.bottomMargin=dp(278);addView(waveform,wp);
        View arc=new View(activity){private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();protected void onDraw(Canvas canvas){super.onDraw(canvas);paint.setColor(Color.rgb(75,80,76));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));path.reset();path.moveTo(dp(45),dp(8));path.cubicTo(getWidth()*.25f,dp(170),getWidth()*.75f,dp(170),getWidth()-dp(45),dp(8));canvas.drawPath(path,paint);}};
        FrameLayout.LayoutParams arcParams=new FrameLayout.LayoutParams(-1,dp(180),Gravity.BOTTOM);arcParams.bottomMargin=dp(36);addView(arc,arcParams);
        cancel=text("取消",17);cancel.setGravity(Gravity.CENTER);cancel.setContentDescription("取消录音，不发送");cancel.setOnClickListener(v->finish("",false,null));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(96),dp(64),Gravity.BOTTOM|Gravity.LEFT);cp.leftMargin=dp(18);cp.bottomMargin=dp(175);addView(cancel,cp);
        edit=text("编辑",17);edit.setGravity(Gravity.CENTER);edit.setContentDescription("停止录音并编辑文字");edit.setOnClickListener(v->{target=2;releaseLocked();});
        FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(96),dp(64),Gravity.BOTTOM|Gravity.RIGHT);ep.rightMargin=dp(18);ep.bottomMargin=dp(175);addView(edit,ep);
        stop=text("松开发送",16);stop.setGravity(Gravity.CENTER);stop.setContentDescription("停止录音，转写完成后发送文字");stop.setOnClickListener(v->{target=0;releaseLocked();});
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,dp(96),Gravity.BOTTOM);bp.bottomMargin=dp(30);bp.leftMargin=dp(110);bp.rightMargin=dp(110);addView(stop,bp);
        setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
    }
    private TextView text(String value,int size){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(Color.rgb(232,239,233));return v;}
    public void start(){
        if(!SpeechRecognizer.isRecognitionAvailable(activity)){finish("",false,"这台设备暂时没有可用的语音识别服务，请先打字");return;}
        ((android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);
        ((FrameLayout)activity.getWindow().getDecorView()).addView(this,new FrameLayout.LayoutParams(-1,-1));requestApplyInsets();
        activity.getWindow().setStatusBarColor(Color.BLACK);activity.getWindow().setNavigationBarColor(Color.BLACK);WindowInsetsController bars=activity.getWindow().getInsetsController();if(bars!=null)bars.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        try{recognizer=SpeechRecognizer.createSpeechRecognizer(activity);recognizer.setRecognitionListener(this);
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);recognizer.startListening(intent);main.post(ticker);
        }catch(Exception e){finish("",false,"语音识别未能启动，请重试或打字");}
    }
    public void move(float rawX,float rawY){if(finished||released)return;int[] location=new int[2];getLocationOnScreen(location);float x=rawX-location[0],y=rawY-location[1];
        int next=0;float boundary=getHeight()-getPaddingBottom()-dp(150);if(y<boundary){if(x<getWidth()*.43f)next=1;else if(x>getWidth()*.57f)next=2;}
        if(next!=target){target=next;performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
        cancel.setTextColor(target==1?Color.rgb(255,140,140):Color.WHITE);edit.setTextColor(target==2?Color.rgb(164,224,174):Color.WHITE);stop.setText(target==1?"松开取消":target==2?"松开编辑":"松开发送");
    }
    public void release(float x,float y){if(released||finished)return;move(x,y);releaseLocked();}
    private void releaseLocked(){if(released||finished)return;released=true;
        if(target==1){finish("",false,null);return;}if(!ready){finish("",false,"还没准备好，请重新按住说话");return;}
        stop.setEnabled(false);edit.setEnabled(false);status.setText("正在整理文字…");main.postDelayed(timeout,5000);if(recognizer!=null)recognizer.stopListening();
    }
    public void interrupt(){if(!finished)finish(partial,true,partial.isEmpty()?null:"录音已中断，文字已保留");}
    private void finish(String text,boolean editText,String message){if(finished)return;finished=true;main.removeCallbacksAndMessages(null);if(recognizer!=null){recognizer.cancel();recognizer.destroy();recognizer=null;}
        if(getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)getParent()).removeView(this);
        activity.getWindow().setStatusBarColor(oldStatus);activity.getWindow().setNavigationBarColor(oldNavigation);WindowInsetsController bars=activity.getWindow().getInsetsController();if(bars!=null)bars.setSystemBarsAppearance(oldAppearance,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        if(message!=null)Toast.makeText(activity,message,Toast.LENGTH_LONG).show();if(!text.trim().isEmpty())listener.completed(text.trim(),editText);else listener.dismissed();
    }
    private String recognized(Bundle bundle){ArrayList<String> items=bundle==null?null:bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);return items==null||items.isEmpty()?"":items.get(0);}
    public void onReadyForSpeech(Bundle b){if(finished||released)return;ready=true;status.setText("正在听");performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}
    public void onPartialResults(Bundle b){if(finished)return;String text=recognized(b);if(!text.isEmpty()){partial=text;showTranscript();}}
    private void showTranscript(){String all=(committed+" "+partial).trim();transcript.setText(all);}
    private void listenAgain(){if(finished||released||recognizer==null)return;try{Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);recognizer.startListening(intent);}catch(Exception ignored){main.postDelayed(this::listenAgain,250);}}
    public void onResults(Bundle b){if(finished)return;String text=recognized(b);if(!text.isEmpty()){if(!committed.isEmpty())committed+=" ";committed+=text;partial="";showTranscript();}
        if(released){finish((committed+" "+partial).trim(),target==2,null);return;}
        // Android recognizers commonly close a phrase after a pause. Re-arm while the user still holds.
        main.postDelayed(this::listenAgain,180);
    }
    public void onError(int error){if(finished)return;if(!released&&(error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT)){main.postDelayed(this::listenAgain,180);return;}String message=error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS?"需要麦克风权限":error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT?"没听清，再试一次":"语音识别中断，请重试或编辑已有文字";finish((committed+" "+partial).trim(),true,message);}
    public void onBeginningOfSpeech(){} public void onRmsChanged(float n){waveform.setLevel(n);} public void onBufferReceived(byte[] b){} public void onEndOfSpeech(){} public void onEvent(int n,Bundle b){}

    private static final class Waveform extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private float level;
        Waveform(Activity a){super(a);paint.setColor(Color.rgb(133,218,155));paint.setStrokeWidth(dp(a,3));paint.setStrokeCap(Paint.Cap.ROUND);}
        private static int dp(Activity a,float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
        void setLevel(float value){level=Math.max(0,Math.min(1,(value+2)/12f));invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float mid=getHeight()/2f;int count=19;float gap=getWidth()/(float)count;for(int i=0;i<count;i++){float wave=(float)Math.abs(Math.sin((SystemClock.uptimeMillis()/120.0)+i*.68));float h=dp((Activity)getContext(),5+level*32*wave);c.drawLine((i+.5f)*gap,mid-h/2,(i+.5f)*gap,mid+h/2,paint);}if(isAttachedToWindow())postInvalidateDelayed(70);}
    }
}
