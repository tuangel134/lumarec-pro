package com.lumarec.pro;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

/** A camera-labelled widget, independent from the original transparent icon widget. */
public class CameraRecordWidget extends AppWidgetProvider {
    protected boolean panel() { return false; }
    static void updateAll(Context c) {
        AppWidgetManager manager=AppWidgetManager.getInstance(c);
        CameraRecordWidget[] providers={new CameraRecordWidget(),new RecordingPanelWidget()};
        for (CameraRecordWidget provider:providers)
            for (int id:manager.getAppWidgetIds(new ComponentName(c,provider.getClass()))) provider.update(c,manager,id);
    }
    private void update(Context c,AppWidgetManager manager,int id) {
        int state=RecordingService.state; boolean active=state!=RecordingService.IDLE;
        RemoteViews view=new RemoteViews(c.getPackageName(),panel() ? R.layout.recording_panel_widget : R.layout.camera_record_widget);
        view.setInt(R.id.camera_widget_root,"setBackgroundResource",active ? R.drawable.widget_recording : R.drawable.widget_idle);
        view.setTextViewText(R.id.camera_widget_title,panel() ? "LumaRec" : active ? "Detener" : "Grabar");
        view.setContentDescription(R.id.camera_widget_root,active ? "LumaRec. Detener y guardar la grabación." : "LumaRec. Iniciar grabación de cámara.");
        if (panel()) {
            view.setTextViewText(R.id.camera_widget_status,state==RecordingService.PAUSED ? "En pausa" : state==RecordingService.PREPARING ? "Preparando" : state==RecordingService.SAVING ? "Guardando" : active ? "Grabando" : "Lista");
            view.setChronometer(R.id.camera_widget_timer,SystemClock.elapsedRealtime()-RecordingService.elapsed(),null,state==RecordingService.RECORDING);
            if (state!=RecordingService.RECORDING) view.setTextViewText(R.id.camera_widget_timer,VideoLibrary.time(RecordingService.elapsed()));
            view.setTextViewText(R.id.camera_widget_record,active ? "Detener" : "Grabar");
            view.setOnClickPendingIntent(R.id.camera_widget_record,QuickRecordWidget.recordAction(c,id,"LumaRec · Cámara"));
            view.setBoolean(R.id.camera_widget_record,"setEnabled",state!=RecordingService.SAVING);
            view.setTextViewText(R.id.camera_widget_pause,state==RecordingService.PAUSED ? "Seguir" : "Pausar");
            view.setViewVisibility(R.id.camera_widget_pause,state==RecordingService.RECORDING || state==RecordingService.PAUSED ? View.VISIBLE : View.GONE);
            Intent pause=new Intent(c,RecordingService.class).setAction(RecordingService.PAUSE).setData(Uri.parse("lumarec-widget://pause/"+id));
            view.setOnClickPendingIntent(R.id.camera_widget_pause,PendingIntent.getService(c,4000+id,pause,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
            PendingIntent open=PendingIntent.getActivity(c,3000+id,new Intent(c,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            view.setOnClickPendingIntent(R.id.camera_widget_root,open);
        } else {
            view.setOnClickPendingIntent(R.id.camera_widget_root,QuickRecordWidget.recordAction(c,id,"LumaRec · Cámara"));
            view.setBoolean(R.id.camera_widget_root,"setEnabled",state!=RecordingService.SAVING);
        }
        manager.updateAppWidget(id,view);
    }
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids) { for (int id:ids) update(c,manager,id); }
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options) { update(c,manager,id); }
}
