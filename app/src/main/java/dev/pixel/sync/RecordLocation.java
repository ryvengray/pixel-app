package dev.pixel.sync;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground, one-shot location. Never accepts model-supplied coordinates. */
public final class RecordLocation {
    private static boolean allowed(Context context){return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    static void withPermission(Activity activity,Runnable next){
        if(allowed(activity)||activity.getSharedPreferences("record_location",0).getBoolean("asked",false)){next.run();return;}
        PermissionRequest request=new PermissionRequest();request.next=next;
        activity.getFragmentManager().beginTransaction().add(request,"record_location_permission").commit();
    }
    public static final class PermissionRequest extends Fragment {
        Runnable next;
        @Override public void onCreate(Bundle state){super.onCreate(state);if(state==null){getActivity().getSharedPreferences("record_location",0).edit().putBoolean("asked",true).apply();requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},76);}}
        @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==76){if(next!=null)next.run();if(isAdded())getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();}}
    }
    // Called on the saving worker; a cold GPS fix must not hold up a diary indefinitely.
    static JSONObject capture(Context context){
        if(!allowed(context)||Looper.myLooper()==Looper.getMainLooper())return null;
        LocationManager manager=context.getSystemService(LocationManager.class);if(manager==null)return null;
        AtomicReference<Location> best=new AtomicReference<>();List<CancellationSignal> signals=new ArrayList<>();
        try{
            List<String> providers=new ArrayList<>();
            for(String provider:Arrays.asList(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER))try{
                if(!manager.isProviderEnabled(provider))continue;providers.add(provider);choose(best,manager.getLastKnownLocation(provider));
            }catch(RuntimeException ignored){}
            CountDownLatch done=new CountDownLatch(providers.size());
            for(String provider:providers){CancellationSignal signal=new CancellationSignal();signals.add(signal);try{
                manager.getCurrentLocation(provider,signal,context.getMainExecutor(),location->{choose(best,location);done.countDown();});
            }catch(RuntimeException ignored){done.countDown();}}
            done.await(5,TimeUnit.SECONDS);
            return snapshot(best.get());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return null;}
        finally{for(CancellationSignal signal:signals)signal.cancel();}
    }
    private static void choose(AtomicReference<Location> best,Location candidate){
        if(!fresh(candidate))return;
        best.updateAndGet(previous->previous==null||candidate.getElapsedRealtimeNanos()>previous.getElapsedRealtimeNanos()?new Location(candidate):previous);
    }
    private static boolean fresh(Location location){
        if(location==null)return false;long age=SystemClock.elapsedRealtimeNanos()-location.getElapsedRealtimeNanos();return age>=0&&age<=TimeUnit.MINUTES.toNanos(2);
    }
    static JSONObject snapshot(Location location){
        if(!fresh(location)||!Double.isFinite(location.getLatitude())||!Double.isFinite(location.getLongitude())||Math.abs(location.getLatitude())>90||Math.abs(location.getLongitude())>180)return null;
        try{JSONObject value=new JSONObject().put("latitude",location.getLatitude()).put("longitude",location.getLongitude()).put("captured_at",location.getTime());if(location.hasAccuracy())value.put("accuracy_m",(double)location.getAccuracy());return value;}catch(Exception invalid){return null;}
    }
    static String label(JSONObject location){return location==null?"":String.format(Locale.US,"%.6f, %.6f",location.optDouble("latitude"),location.optDouble("longitude"));}
}
