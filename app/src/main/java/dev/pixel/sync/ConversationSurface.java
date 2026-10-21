package dev.pixel.sync;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Soft asymmetric reply surface and a quieter, right-aligned user accent. */
final class ConversationSurface extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contour=new Path();
    private final boolean assistant,dark;
    private final float density;
    private LinearGradient fill;
    private int alpha=255;

    ConversationSurface(boolean assistant,boolean dark,float density){this.assistant=assistant;this.dark=dark;this.density=density;}

    @Override protected void onBoundsChange(Rect bounds){
        super.onBoundsChange(bounds);float x=bounds.left,y=bounds.top,w=bounds.width(),h=bounds.height();
        int[] colors=assistant?(dark?new int[]{0xEB29483F,0xD9203440}:new int[]{0xF5F5FAEF,0xE3DCE9E1}):(dark?new int[]{0x00263D35,0xC2365145}:new int[]{0x00F5F8EF,0xD9EDF4E7});
        fill=new LinearGradient(x,y,x+Math.max(1,w),y+(assistant?h:0),colors,null,Shader.TileMode.CLAMP);
        float r=Math.min(28*density,Math.min(w,h)*.45f),edge=density;
        contour.reset();contour.moveTo(x+r,y+edge);
        contour.cubicTo(x+w*.46f,y+3*edge,x+w-r,y,x+w-r*.4f,y+r*.28f);
        contour.cubicTo(x+w-edge,y+r*.5f,x+w,y+h-r,x+w-r*.25f,y+h-r*.35f);
        contour.cubicTo(x+w-r*.55f,y+h,x+w*.55f,y+h-2*edge,x+r,y+h-edge);
        contour.cubicTo(x+r*.15f,y+h,x+edge,y+h-r*.25f,x+edge,y+h-r);
        contour.cubicTo(x+2*edge,y+r*.4f,x+r*.2f,y+edge,x+r,y+edge);contour.close();
    }

    @Override public void draw(Canvas canvas){
        if(fill==null)return;Rect b=getBounds();paint.setStyle(Paint.Style.FILL);paint.setShader(fill);paint.setAlpha(alpha);
        if(assistant)canvas.drawPath(contour,paint);else canvas.drawRoundRect(b.left,b.top,b.right,b.bottom,18*density,18*density,paint);
        paint.setShader(null);
        if(assistant){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density*.7f);paint.setColor(dark?0x3AABCBB7:0xA6FFFFFF);paint.setAlpha(Math.round(android.graphics.Color.alpha(paint.getColor())*alpha/255f));canvas.drawPath(contour,paint);}
        else{paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(density*2);paint.setColor(dark?0xB397CBA6:0x99638B70);paint.setAlpha(Math.round(android.graphics.Color.alpha(paint.getColor())*alpha/255f));canvas.drawLine(b.right-2*density,b.top+11*density,b.right-2*density,b.bottom-11*density,paint);}
    }

    @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
