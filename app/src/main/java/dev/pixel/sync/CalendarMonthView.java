package dev.pixel.sync;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.*;
import java.util.*;
import org.json.*;

/** Shared month renderer for the live view and RemoteViews bitmap. */
final class CalendarMonthView extends View {
    interface Selected {void select(LocalDate date);}
    private YearMonth month;private LocalDate selected;private boolean dark,cycle;private JSONObject prediction;private List<JSONObject> records=Collections.emptyList();private Set<LocalDate> events=new HashSet<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final float density;private Selected callback;private boolean widget;
    private java.util.function.IntConsumer monthChange;
    private float downX,downY;
    private int pointer=-1;
    private boolean moved,horizontal,vertical;
    CalendarMonthView(Context c){super(c);density=getResources().getDisplayMetrics().density;setContentDescription("公历与农历月历，点击日期查看当天事项");setClickable(true);}
    void bind(YearMonth month,LocalDate selected,boolean dark,boolean cycle,CalendarStore store,Selected callback)throws Exception{
        this.month=month;this.selected=selected;this.dark=dark;this.cycle=cycle;this.callback=callback;prediction=store.prediction();records=cycle?store.cycles():Collections.emptyList();events.clear();JSONArray list=store.events(month.atDay(1),month.atEndOfMonth());for(int i=0;i<list.length();i++)events.add(LocalDate.parse(list.getJSONObject(i).getString("occurrence")));requestLayout();invalidate();
    }
    void forWidget(){widget=true;}
    void setMonthChangeListener(java.util.function.IntConsumer listener){monthChange=listener;}
    private int rows(){return month==null?5:(month.atDay(1).getDayOfWeek().getValue()-1+month.lengthOfMonth()+6)/7;}
    protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getMode(h)==MeasureSpec.EXACTLY?MeasureSpec.getSize(h):Math.round((28+rows()*52)*density));}
    int phase(LocalDate day){
        if(!cycle)return 0;
        for(JSONObject r:records){LocalDate a=LocalDate.parse(r.optString("start"));String end=r.optString("end","");LocalDate b=end.isEmpty()?LocalDate.now():LocalDate.parse(end);if(!day.isBefore(a)&&!day.isAfter(b))return 1;}
        if(prediction!=null&&prediction.optBoolean("available")){
            if(!day.isBefore(LocalDate.parse(prediction.optString("next_start")))&&!day.isAfter(LocalDate.parse(prediction.optString("next_end"))))return 2;
            if(prediction.has("ovulation")){if(day.equals(LocalDate.parse(prediction.optString("ovulation"))))return 4;if(!day.isBefore(LocalDate.parse(prediction.optString("fertile_start")))&&!day.isAfter(LocalDate.parse(prediction.optString("fertile_end"))))return 3;}
        }return 0;
    }
    protected void onDraw(Canvas canvas){super.onDraw(canvas);if(month==null)return;drawMonth(canvas,getWidth(),getHeight());}
    private void text(Canvas c,String s,float x,float y,float size,int color){paint.setStyle(Paint.Style.FILL);paint.setPathEffect(null);paint.setColor(color);paint.setTypeface(Typeface.create(getContext().getSharedPreferences("home",0).getInt("font",0)==1?"serif":"sans-serif",Typeface.NORMAL));paint.setTextSize(size);paint.setTextAlign(Paint.Align.CENTER);c.drawText(s,x,y,paint);}
    void drawMonth(Canvas c,int width,int height){
        if(month==null)return;
        float scale=width/350f,head=widget?24:28*scale,row=(height-head)/rows(),cell=width/7f;
        int ink=dark?0xFFE7F0EC:0xFF243F37,muted=dark?0xFFA8BDB8:0xFF667E70;
        int rose=dark?0xFF563443:0xFFF7DCE5,roseInk=dark?0xFFFFDDE7:0xFF8D3455;
        int roseMuted=dark?0xFFE6B5C6:0xFF95546B,green=dark?0xFF98D8B0:0xFF276648;
        int purple=dark?0xFFD0BCE9:0xFF76519B,accent=dark?0xFFB7DAC5:0xFF38694F;
        String[] week={"一","二","三","四","五","六","日"};for(int i=0;i<7;i++)text(c,week[i],cell*(i+.5f),widget?16:17*scale,widget?11:11*scale,muted);
        LocalDate first=month.atDay(1).minusDays(month.atDay(1).getDayOfWeek().getValue()-1);
        for(int n=0;n<rows()*7;n++){
            LocalDate day=first.plusDays(n);float left=n%7*cell,top=head+n/7*row,x=left+cell/2;boolean inside=YearMonth.from(day).equals(month);int phase=inside?phase(day):0;
            RectF rect=new RectF(left+2*scale,top+2*scale,left+cell-2*scale,top+row-3*scale);
            if(phase==1||phase==2){
                paint.setStyle(Paint.Style.FILL);paint.setColor(rose);
                c.drawRoundRect(rect,10*scale,10*scale,paint);
                if(phase==2){
                    paint.setStyle(Paint.Style.STROKE);paint.setColor(dark?0xFFE3A7B7:0xFFAD6279);paint.setStrokeWidth(scale);
                    paint.setPathEffect(new DashPathEffect(new float[]{3*scale,3*scale},0));
                    c.drawRoundRect(rect,10*scale,10*scale,paint);paint.setPathEffect(null);
                }
            }
            if(day.equals(selected)){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(scale);paint.setColor(accent);c.drawRoundRect(rect,10*scale,10*scale,paint);}
            boolean otherCycleDay=inside&&phase==0&&isOtherCycleDay(day);
            int dateInk=phase==1||phase==2?roseInk:phase>=3?purple:otherCycleDay?green:day.equals(LocalDate.now())?accent:ink;
            int lunarInk=phase==1||phase==2?roseMuted:phase>=3?purple:otherCycleDay?green:muted;
            text(c,String.valueOf(day.getDayOfMonth()),x,top+(widget?row*.44f:21*scale),widget?14:15*scale,inside?dateInk:(dark?0xFF627775:0xFFA5B4A8));
            text(c,CalendarDates.lunar(day).shortLabel(),x,top+(widget?row*.77f:38*scale),widget?11:10.5f*scale,inside?lunarInk:(dark?0xFF627775:0xFFA5B4A8));
            if(phase==4)flower(c,rect.right-6*scale,rect.top+6*scale,scale,purple);

            if(events.contains(day)){paint.setStyle(Paint.Style.FILL);paint.setColor(accent);c.drawCircle(x,top+row-(widget?2:4*scale),1.5f*scale,paint);}
        }
    }
    private boolean isOtherCycleDay(LocalDate day){
        // Only color dates inside the cycle we can estimate. Missing or irregular
        // predictions must not turn arbitrary dates into an apparent safe interval.
        return cycle&&prediction!=null&&prediction.has("ovulation")&&prediction.has("last_start")
            &&!day.isBefore(LocalDate.parse(prediction.optString("last_start")))
            &&day.isBefore(LocalDate.parse(prediction.optString("next_start")));
    }
    private void flower(Canvas canvas,float x,float y,float scale,int color){
        paint.setStyle(Paint.Style.FILL);paint.setPathEffect(null);paint.setColor(color);
        for(int i=0;i<5;i++){
            double angle=-Math.PI/2+i*Math.PI*2/5;
            canvas.drawCircle(x+(float)Math.cos(angle)*2.2f*scale,y+(float)Math.sin(angle)*2.2f*scale,1.6f*scale,paint);
        }
        paint.setColor(dark?0xFF26313C:0xFFFFFAFD);canvas.drawCircle(x,y,1.1f*scale,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(month==null)return false;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                pointer=e.getPointerId(0);downX=e.getX();downY=e.getY();moved=horizontal=vertical=false;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                endGesture();return true;
            case MotionEvent.ACTION_MOVE:
                int index=e.findPointerIndex(pointer);if(index<0)return true;
                float dx=Math.abs(e.getX(index)-downX),dy=Math.abs(e.getY(index)-downY);
                int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if(dx>slop||dy>slop)moved=true;
                if(!horizontal&&!vertical&&moved){
                    horizontal=dx>dy*1.2f;
                    vertical=!horizontal;
                    if(vertical&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                int up=e.findPointerIndex(pointer);if(up<0){endGesture();return true;}
                float deltaX=e.getX(up)-downX,deltaY=e.getY(up)-downY;
                boolean swipe=!vertical&&Math.abs(deltaX)>=Math.max(48*density,getWidth()*.12f)&&Math.abs(deltaX)>Math.abs(deltaY)*1.2f;
                boolean tap=!moved&&Math.abs(deltaX)<=ViewConfiguration.get(getContext()).getScaledTouchSlop()&&Math.abs(deltaY)<=ViewConfiguration.get(getContext()).getScaledTouchSlop();
                float x=e.getX(up),y=e.getY(up);endGesture();
                if(swipe&&monthChange!=null){monthChange.accept(deltaX<0?1:-1);return true;}
                if(tap&&x>=0&&x<getWidth()&&y<getHeight()){
                    float head=28*getWidth()/350f;
                    int col=(int)(x/(getWidth()/7f)),row=(int)((y-head)/((getHeight()-head)/rows()));
                    if(y>=head&&row>=0&&row<rows()&&callback!=null){
                        LocalDate d=month.atDay(1).minusDays(month.atDay(1).getDayOfWeek().getValue()-1).plusDays(row*7L+col);
                        performClick();callback.select(d);
                    }
                }
                return true;
            default:return true;
        }
    }
    private void endGesture(){pointer=-1;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);
        if(monthChange!=null){info.setScrollable(true);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
    }
    @Override public boolean performAccessibilityAction(int action,android.os.Bundle args){
        if(monthChange!=null&&(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)){
            monthChange.accept(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1);return true;
        }
        return super.performAccessibilityAction(action,args);
    }
    public boolean performClick(){super.performClick();return true;}
}
