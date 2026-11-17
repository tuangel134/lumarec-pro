package com.lumarec.pro;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;
import java.io.File;

public final class QuickRecordWidget extends AppWidgetProvider {
    static final String ACTION_RECORD="com.lumarec.pro.WIDGET_RECORD";
    static final int CUSTOM_ICON=5, FLASHLIGHT_ICON=6;
    static final String[] ICON_NAMES={"Cámara","Círculo","Rayo","Estrella","Micrófono","Mi imagen","Linterna"};
    private static final int[] ICONS={R.drawable.ic_widget_camera,R.drawable.ic_widget_dot,R.drawable.ic_widget_bolt,R.drawable.ic_widget_star,R.drawable.ic_widget_mic,R.drawable.ic_widget_camera,R.drawable.ic_widget_flashlight};
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("quick_widget",Context.MODE_PRIVATE); }
    static String name(Context c) { return prefs(c).getString("name","Grabar"); }
    private static synchronized void ensureStyle(Context c) {
        SharedPreferences settings=prefs(c);
        if (!settings.getBoolean("transparent_icon_style",false)) {
            // Apply the requested flashlight style once, including to already placed widgets.
            settings.edit().putInt("icon",FLASHLIGHT_ICON).putBoolean("transparent_icon_style",true).apply();
        }
    }
    static int icon(Context c) { ensureStyle(c); return Math.max(0,Math.min(ICONS.length-1,prefs(c).getInt("icon",FLASHLIGHT_ICON))); }
    static void updateAll(Context context) {
        ensureStyle(context);
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        for (int id:manager.getAppWidgetIds(new ComponentName(context,QuickRecordWidget.class))) update(context,manager,id);
        CameraRecordWidget.updateAll(context);
        RecordTile.requestRefresh(context);
    }
    private static void update(Context context,AppWidgetManager manager,int id) {
        int state=RecordingService.state; boolean active=state!=RecordingService.IDLE;
        RemoteViews view=new RemoteViews(context.getPackageName(),R.layout.quick_record_widget);
        int selectedIcon=icon(context);
        File image=new File(context.getFilesDir(),"widget-logo.png"); Bitmap custom=null;
        if (selectedIcon==CUSTOM_ICON && image.isFile()) custom=BitmapFactory.decodeFile(image.getAbsolutePath());
        if (custom!=null) view.setImageViewBitmap(R.id.widget_icon,custom);
        else view.setImageViewResource(R.id.widget_icon,selectedIcon==FLASHLIGHT_ICON && active ? R.drawable.ic_widget_flashlight_active : ICONS[selectedIcon]);
        view.setContentDescription(R.id.widget_root,name(context)+(active ? ". Detener grabación." : ". Iniciar grabación rápida."));
        view.setOnClickPendingIntent(R.id.widget_root,recordAction(context,id)); manager.updateAppWidget(id,view);
    }
    static PendingIntent recordAction(Context context,int id) {
        return recordAction(context,id,null);
    }
    static PendingIntent recordAction(Context context,int id,String label) {
        boolean active=RecordingService.state!=RecordingService.IDLE;
        if (active) {
            Intent stop=new Intent(context,RecordingService.class).setAction(RecordingService.STOP).setData(Uri.parse("lumarec-widget://stop/"+id));
            return PendingIntent.getService(context,2000+id,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        } else {
            Intent start=new Intent(context,QuickRecordActivity.class).setAction(ACTION_RECORD).setData(Uri.parse("lumarec-widget://record/"+id))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            if (label!=null) start.putExtra("entry_name",label);
            return PendingIntent.getActivity(context,1000+id,start,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        }
    }
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids) { for (int id:ids) update(context,manager,id); }
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,Bundle options) { update(context,manager,id); }
    static boolean pin(Context context) {
        return pin(context,QuickRecordWidget.class);
    }
    static boolean pin(Context context,Class<?> widget) {
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        return manager.isRequestPinAppWidgetSupported() && manager.requestPinAppWidget(new ComponentName(context,widget),null,null);
    }
}
