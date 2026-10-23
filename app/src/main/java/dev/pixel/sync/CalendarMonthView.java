package dev.pixel.sync;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.time.*;
import java.util.*;
import org.json.*;

/** Shared month renderer for the live view and RemoteViews bitmap. */
final class CalendarMonthView extends View {
    interface Selected {void select(LocalDate date);}
    private YearMonth month;private LocalDate selected;private boolean dark,cycle;private JSONObject prediction;private List<JSONObject> records=Collections.emptyList();private Set<LocalDate> events=new HashSet<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final float density;private Selected callback;private boolean widget;
    CalendarMonthView(Context c){super(c);density=getResources().getDisplayMetrics().density;setContentDescription("公历与农历月历，点击日期查看当天事项");setClickable(true);}
    void bind(YearMonth month,LocalDate selected,boolean dark,boolean cycle,CalendarStore store,Selected callback)throws Exception{
        this.month=month;this.selected=selected;this.dark=dark;this.cycle=cycle;this.callback=callback;prediction=store.prediction();records=cycle?store.cycles():Collections.emptyList();events.clear();JSONArray list=store.events(month.atDay(1),month.atEndOfMonth());for(int i=0;i<list.length();i++)events.add(LocalDate.parse(list.getJSONObject(i).getString("occurrence")));requestLayout();invalidate();
    }
    void forWidget(){widget=true;}
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
        if(month==null)return;float scale=width/350f,head=widget?24:28*scale,row=(height-head)/rows(),cell=width/7f;int ink=Color.parseColor(dark?"#E7F0EC":"#243F37"),muted=Color.parseColor(dark?"#A8BDB8":"#667E70"),rose=Color.parseColor(dark?"#4DB55E81":"#D593A73B"),purple=Color.parseColor(dark?"#9275BD40":"#B39BC72A"),accent=Color.parseColor(dark?"#B7DAC5":"#38694F");
        String[] week={"一","二","三","四","五","六","日"};for(int i=0;i<7;i++)text(c,week[i],cell*(i+.5f),widget?16:17*scale,widget?11:11*scale,muted);
        LocalDate first=month.atDay(1).minusDays(month.atDay(1).getDayOfWeek().getValue()-1);
        for(int n=0;n<rows()*7;n++){
            LocalDate day=first.plusDays(n);float left=n%7*cell,top=head+n/7*row,x=left+cell/2;boolean inside=YearMonth.from(day).equals(month);int phase=inside?phase(day):0;
            RectF rect=new RectF(left+2*scale,top+2*scale,left+cell-2*scale,top+row-3*scale);
            if(phase!=0){paint.setStyle(Paint.Style.FILL);paint.setColor(phase<=2?rose:purple);c.drawRoundRect(rect,10*scale,10*scale,paint);if(phase==2){paint.setStyle(Paint.Style.STROKE);paint.setColor(dark?0xFFE3A7B7:0xFFAD6279);paint.setStrokeWidth(scale);paint.setPathEffect(new DashPathEffect(new float[]{3*scale,3*scale},0));c.drawRoundRect(rect,10*scale,10*scale,paint);paint.setPathEffect(null);}}
            if(day.equals(selected)){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(scale);paint.setColor(accent);c.drawRoundRect(rect,10*scale,10*scale,paint);}
            if(phase==4){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(scale);paint.setColor(dark?0xFFD0BCE9:0xFF81649B);c.drawCircle(x,top+(widget?16:16*scale),widget?11:12*scale,paint);}
            text(c,String.valueOf(day.getDayOfMonth()),x,top+(widget?20:21*scale),widget?14:15*scale,inside?(day.equals(LocalDate.now())?accent:ink):(dark?0xFF627775:0xFFA5B4A8));
            text(c,CalendarDates.lunar(day).shortLabel(),x,top+(widget?35:38*scale),widget?11:10.5f*scale,inside?muted:(dark?0xFF627775:0xFFA5B4A8));
            if(events.contains(day)){paint.setStyle(Paint.Style.FILL);paint.setColor(accent);c.drawCircle(x,top+row-4*scale,1.5f*scale,paint);}
        }
    }
    public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_UP&&month!=null){float head=28*getWidth()/350f;int col=Math.min(6,(int)(e.getX()/(getWidth()/7f))),row=(int)((e.getY()-head)/((getHeight()-head)/rows()));if(e.getY()>=head&&row>=0&&row<rows()){LocalDate d=month.atDay(1).minusDays(month.atDay(1).getDayOfWeek().getValue()-1).plusDays(row*7L+col);if(callback!=null){performClick();callback.select(d);}}return true;}return true;}
    public boolean performClick(){super.performClick();return true;}
}
