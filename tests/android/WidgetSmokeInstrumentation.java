package com.lumarec.pro;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Run only in the disposable Android 16 emulator; never bundled into the release APK. */
public final class WidgetSmokeInstrumentation extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try { test(); result.putString("stream","PASS: "+checks+" Android 16 widget, private recording and playback checks.\n"); finish(Activity.RESULT_OK,result); }
        catch (Throwable error) { result.putString("stream","FAIL: "+error+"\n"+android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED,result); }
    }
    private void test() throws Exception {
        Context c=getTargetContext();
        getUiAutomation().grantRuntimePermission(c.getPackageName(),android.Manifest.permission.CAMERA);
        getUiAutomation().grantRuntimePermission(c.getPackageName(),android.Manifest.permission.RECORD_AUDIO);
        getUiAutomation().grantRuntimePermission(c.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        Activity activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
        require(LumaRecApp.isForeground(),"Activity is foreground");
        if (!SecureVault.configured(c)) SecureVault.setup(c,"0123".toCharArray(),true);
        else require(SecureVault.unlock(c,"0123".toCharArray()),"Unlock existing disposable vault");
        byte[] master=SecureVault.key();
        try { QuickVault.ensure(c,master); } finally { Arrays.fill(master,(byte)0); }
        require(QuickVault.ready(c),"Write-only capture prepared");
        c.getSharedPreferences("recorder",Context.MODE_PRIVATE).edit().putInt("width",1280).putInt("height",720).putInt("fps",30).putBoolean("audio",false).putInt("delay",10).putInt("duration",0).commit();
        QuickRecordWidget.prefs(c).edit().putString("name","Mi grabación").putInt("icon",2).commit();
        AppWidgetManager manager=AppWidgetManager.getInstance(c); AppWidgetHost host=new AppWidgetHost(c,413);
        int id=host.allocateAppWidgetId();
        getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");
        try { require(manager.bindAppWidgetIdIfAllowed(id,new ComponentName(c,QuickRecordWidget.class)),"Widget binds to its host"); }
        finally { getUiAutomation().dropShellPermissionIdentity(); }
        host.startListening(); AppWidgetHostView[] widget={null};
        runOnMainSync(()->{ widget[0]=host.createView(activity,id,manager.getAppWidgetInfo(id)); activity.setContentView(widget[0]); });
        QuickRecordWidget.updateAll(c);
        waitUntil(()->widget[0].findViewById(R.id.widget_icon)!=null,10_000,"Widget layout loads");
        runOnMainSync(()->require(widget[0].findViewById(R.id.widget_root).getContentDescription().toString().contains("Mi grabación"),"Custom widget name is accessible"));
        SecureVault.lock(); require(!SecureVault.unlocked() && !SecureVault.hasRecordingKey(),"Reading key is locked before tapping widget");
        runOnMainSync(()->require(widget[0].findViewById(R.id.widget_root).performClick(),"Widget tap is handled"));
        waitUntil(()->RecordingService.state==RecordingService.RECORDING,35_000,"Widget starts recording on Android 16");
        require(!SecureVault.unlocked() && !SecureVault.hasRecordingKey(),"Starting capture did not unlock the reading key");
        SystemClock.sleep(2600);
        QuickRecordWidget.updateAll(c);
        waitUntil(()->widget[0].findViewById(R.id.widget_root).getContentDescription().toString().contains("Detener"),10_000,"Widget exposes Stop control");
        runOnMainSync(()->require(widget[0].findViewById(R.id.widget_root).performClick(),"Stop tap is handled"));
        waitUntil(()->RecordingService.state==RecordingService.IDLE,35_000,"Widget stops and saves");
        require(RecordingService.lastVideo.startsWith("vault:"),"Capture is saved privately");
        require(!SecureVault.unlocked(),"Vault stays locked after saving");
        require(SecureVault.unlock(c,"0123".toCharArray()),"Code unlocks the captured video");
        String video=RecordingService.lastVideo.substring(6); byte[] readingKey=SecureVault.key(); ByteArrayOutputStream clear=new ByteArrayOutputStream();
        try { SecureVault.decryptTo(c,video,clear,readingKey); } finally { Arrays.fill(readingKey,(byte)0); }
        require(clear.size()>1000,"Private capture decrypts after unlock");
        byte[] first=clear.toByteArray(); require(first[4]=='f' && first[5]=='t' && first[6]=='y' && first[7]=='p',"Capture is an MP4"); Arrays.fill(first,(byte)0);
        Activity player=startActivitySync(new Intent(c,PrivatePlayerActivity.class).putExtra("video",video).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.lang.reflect.Field ready=PrivatePlayerActivity.class.getDeclaredField("ready"); ready.setAccessible(true);
        waitUntil(()->{ try { return ready.getBoolean(player); } catch (Exception e) { return false; } },25_000,"Private player prepares the captured video");
        runOnMainSync(player::finish); host.deleteAppWidgetId(id); host.stopListening(); runOnMainSync(activity::finish);
    }
    private interface Condition { boolean ok(); }
    private void waitUntil(Condition condition,long timeout,String label) throws Exception {
        long until=SystemClock.elapsedRealtime()+timeout;
        while (!condition.ok() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(150);
        require(condition.ok(),label+" (state="+RecordingService.state+", message="+RecordingService.message+")");
    }
    private void require(boolean result,String label) { if (!result) throw new AssertionError(label); checks++; }
}
