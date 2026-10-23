package dev.pixel.sync;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Quiet glass highlights behind app content; widgets remain static. */
final class CalendarGlints extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final boolean dark;
    CalendarGlints(Context c,boolean dark){super(c);this.dark=dark;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);boolean reduced=getContext().getSharedPreferences("home",0).getBoolean("reduceMotion",false);float d=getResources().getDisplayMetrics().density;double time=reduced?0:android.os.SystemClock.uptimeMillis()/2400.0;
        for(int n=0;n<8;n++){float x=getWidth()*(.08f+((n*31)%83)/100f),y=getHeight()*(.10f+((n*47)%79)/100f);float a=(float)Math.pow(Math.max(0,Math.sin(time+n*1.73)),8);paint.setColor(dark?Color.rgb(205,236,231):Color.rgb(255,255,247));paint.setAlpha(reduced?12:(int)(a*(dark?72:115)));canvas.drawCircle(x,y,(1+n%2*.4f)*d,paint);}
        if(!reduced&&isShown()&&getWindowVisibility()==VISIBLE)postInvalidateDelayed(120);
    }
    protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE)invalidate();}
}
