package dev.pixel.sync;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Subtle writing feedback; the highlight is not a message length limit. */
final class ComposerUnderline extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private ValueAnimator animator;
    private float fraction,target;
    private boolean dark;
    private LinearGradient highlight;

    ComposerUnderline(Context context){super(context);density=getResources().getDisplayMetrics().density;}

    void update(boolean dark,boolean focused,int characters,boolean animate){
        if(this.dark!=dark||highlight==null){this.dark=dark;updateGradient();}
        // Saturate gradually so long drafts remain unrestricted.
        float next=characters==0?(focused?.08f:0f):.12f+.83f*(1f-(float)Math.exp(-characters/40f));
        if(Math.abs(next-target)<.0001f){if(!animate&&animator!=null){animator.cancel();animator=null;fraction=target;invalidate();}return;}
        target=next;if(animator!=null){animator.cancel();animator=null;}
        if(!animate||!isAttachedToWindow()){fraction=target;invalidate();return;}
        animator=ValueAnimator.ofFloat(fraction,target);animator.setDuration(180);animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value->{fraction=(float)value.getAnimatedValue();invalidate();});animator.start();
    }

    private void updateGradient(){
        highlight=new LinearGradient(0,0,Math.max(1,getWidth()),0,
                dark?new int[]{Color.rgb(112,194,151),Color.rgb(199,242,215)}:new int[]{Color.rgb(51,112,81),Color.rgb(92,157,122)},null,Shader.TileMode.CLAMP);invalidate();
    }

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);updateGradient();}

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float inset=density,y=getHeight()/2f;
        paint.setShader(null);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(density);
        paint.setColor(dark?0x667F9C91:0x66718B79);canvas.drawLine(inset,y,getWidth()-inset,y,paint);
        if(fraction>0){paint.setColor(Color.WHITE);paint.setShader(highlight);paint.setStrokeWidth(2*density);canvas.drawLine(inset,y,inset+Math.max(0,getWidth()-2*inset)*fraction,y,paint);paint.setShader(null);}
    }

    @Override protected void onDetachedFromWindow(){if(animator!=null){animator.cancel();animator=null;}fraction=target;super.onDetachedFromWindow();}
}
