package com.lumarec.pro;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Keeps UI authorization separate from the key needed to finish a locked-screen recording. */
public final class LumaRecApp extends Application {
    private static volatile int started;
    private final Handler handler=new Handler(Looper.getMainLooper());
    static boolean isForeground() { return started>0; }
    private static void backgroundLock() {
        SecureVault.lockUi(RecordingService.state!=RecordingService.IDLE);
    }
    @Override public void onCreate() {
        super.onCreate();
        VideoLibrary.clearLegacyExports(this);
        try { QuickRecordWidget.updateAll(this); } catch (Exception ignored) { }
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) { started++; }
            @Override public void onActivityStopped(Activity activity) {
                started=Math.max(0,started-1);
                if (started==0 && !activity.isChangingConfigurations()) backgroundLock();
            }
            @Override public void onActivityCreated(Activity a,Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
        BroadcastReceiver screen=new BroadcastReceiver() {
            @Override public void onReceive(Context context,Intent intent) { backgroundLock(); }
        };
        if (Build.VERSION.SDK_INT>=33) registerReceiver(screen,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen,new IntentFilter(Intent.ACTION_SCREEN_OFF));
        handler.post(new Runnable() {
            @Override public void run() { VideoLibrary.clearExpiredExports(LumaRecApp.this); handler.postDelayed(this,60_000); }
        });
    }
}
