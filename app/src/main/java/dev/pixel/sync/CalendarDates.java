package dev.pixel.sync;

import android.icu.util.ChineseCalendar;
import android.icu.util.Calendar;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Date-only arithmetic shared by the screen, widgets and agent. */
final class CalendarDates {
    private static final String[] MONTH={"正月","二月","三月","四月","五月","六月","七月","八月","九月","十月","冬月","腊月"};
    static final class Lunar {
        final int month,day;final boolean leap;
        Lunar(int m,int d,boolean l){month=m;day=d;leap=l;}
        String label(){return (leap?"闰":"")+MONTH[month-1]+dayName(day);}
        String shortLabel(){
            if(!leap){String key=month+"-"+day;switch(key){case "1-1":return "春节";case "1-15":return "元宵";case "5-5":return "端午";case "7-7":return "七夕";case "8-15":return "中秋";case "9-9":return "重阳";case "12-8":return "腊八";}}
            return day==1?(leap?"闰":"")+MONTH[month-1]:dayName(day);
        }
    }
    static String dayName(int d){String[] digits={"","一","二","三","四","五","六","七","八","九","十"};return d<=10?"初"+digits[d]:d<20?"十"+digits[d-10]:d==20?"二十":d<30?"廿"+digits[d-20]:"三十";}
    static Lunar lunar(LocalDate date){ChineseCalendar c=new ChineseCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Shanghai"));c.setTimeInMillis(date.atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());return new Lunar(c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH),c.get(Calendar.IS_LEAP_MONTH)==1);}
    private static final Map<String,List<LocalDate>> lunarCache=Collections.synchronizedMap(new LinkedHashMap<String,List<LocalDate>>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,List<LocalDate>> e){return size()>500;}});
    private static List<LocalDate> lunarCandidates(int year,int month,int day,boolean leap,String policy){
        String key=year+":"+month+":"+day+":"+leap+":"+policy;List<LocalDate> hit=lunarCache.get(key);if(hit!=null)return hit;
        List<LocalDate> exact=new ArrayList<>();Map<LocalDate,Integer> ordinary=new LinkedHashMap<>();Set<Integer> leapYears=new HashSet<>();ChineseCalendar c=new ChineseCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Shanghai"));
        // Include the adjacent months when checking whether a lunar year contains a leap month.
        // A Gregorian year can contain two occurrences of the same lunar month/day.
        for(LocalDate d=LocalDate.of(year,1,1).minusDays(60),end=LocalDate.of(year+1,1,1).plusDays(60);d.isBefore(end);d=d.plusDays(1)){
            c.setTimeInMillis(d.atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
            if(c.get(Calendar.MONTH)+1!=month)continue;boolean isLeap=c.get(Calendar.IS_LEAP_MONTH)==1;int n=c.get(Calendar.DAY_OF_MONTH),lunarYear=c.get(Calendar.EXTENDED_YEAR);
            if(isLeap)leapYears.add(lunarYear);if(d.getYear()!=year)continue;
            if(n==day&&isLeap==leap)exact.add(d);
            else if("last_day".equals(policy)&&isLeap==leap&&n==day-1&&c.getActualMaximum(Calendar.DAY_OF_MONTH)==n)exact.add(d);
            if(leap&&"regular".equals(policy)&&!isLeap&&n==day)ordinary.put(d,lunarYear);
        }
        for(Map.Entry<LocalDate,Integer> e:ordinary.entrySet())if(!leapYears.contains(e.getValue()))exact.add(e.getKey());Collections.sort(exact);List<LocalDate> result=Collections.unmodifiableList(exact);lunarCache.put(key,result);return result;
    }
    static LocalDate lunarInYear(int year,int month,int day,boolean leap,String policy){List<LocalDate> dates=lunarCandidates(year,month,day,leap,policy);return dates.isEmpty()?null:dates.get(0);}
    static LocalDate occurrence(org.json.JSONObject e,LocalDate after){
        LocalDate base=LocalDate.parse(e.optString("date"));if(!e.optBoolean("yearly",false))return base;boolean known=e.optBoolean("year_known",true);int firstYear=known?Math.max(after.getYear(),base.getYear()):after.getYear();
        for(int y=firstYear;y<firstYear+20;y++){
            LocalDate d;
            if("lunar".equals(e.optString("calendar"))){Lunar b=lunar(base);for(LocalDate candidate:lunarCandidates(y,e.optInt("lunar_month",b.month),e.optInt("lunar_day",b.day),e.optBoolean("lunar_leap",b.leap),e.optString("missing_day","last_day")))if(!candidate.isBefore(after)&&(!known||!candidate.isBefore(base)))return candidate;continue;}
            else {int day=base.getDayOfMonth();if(base.getMonthValue()==2&&day==29&&!Year.isLeap(y))d="march1".equals(e.optString("leap_day"))?LocalDate.of(y,3,1):LocalDate.of(y,2,28);else d=base.withYear(y);}
            if(d!=null&&!d.isBefore(after)&&(!known||!d.isBefore(base)))return d;
        }
        return null;
    }
    static long days(LocalDate a,LocalDate b){return ChronoUnit.DAYS.between(a,b);}
    private CalendarDates(){}
}
