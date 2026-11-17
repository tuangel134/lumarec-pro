package com.lumarec.pro;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Android's permission-protected Quick Settings entry; recording starts through a visible activity. */
public final class RecordTile extends TileService {
    private boolean listening;
    private final BroadcastReceiver updates=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent intent) { refresh(); }
    };
    static void requestRefresh(Context c) {
        try { TileService.requestListeningState(c,new ComponentName(c,RecordTile.class)); } catch (Exception ignored) { }
    }
    @Override public void onStartListening() {
        super.onStartListening();
        if (!listening) {
            IntentFilter filter=new IntentFilter(RecordingService.UPDATE);
            if (Build.VERSION.SDK_INT>=33) registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(updates,filter);
            listening=true;
        }
        refresh();
    }
    @Override public void onStopListening() { if (listening) { unregisterReceiver(updates); listening=false; } super.onStopListening(); }
    private void refresh() {
        Tile tile=getQsTile(); if (tile==null) return;
        int state=RecordingService.state;
        tile.setLabel("LumaRec"); tile.setIcon(Icon.createWithResource(this,R.drawable.ic_widget_camera));
        tile.setState(state==RecordingService.SAVING ? Tile.STATE_UNAVAILABLE : state==RecordingService.IDLE ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.setSubtitle(state==RecordingService.IDLE ? "Grabar" : state==RecordingService.PAUSED ? "En pausa · detener" : state==RecordingService.SAVING ? "Guardando" : "Detener grabación");
        tile.setContentDescription(state==RecordingService.IDLE ? "LumaRec. Iniciar grabación de cámara." : "LumaRec. Detener y guardar grabación.");
        tile.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        if (RecordingService.state==RecordingService.SAVING) return;
        if (RecordingService.state!=RecordingService.IDLE) {
            startService(new Intent(this,RecordingService.class).setAction(RecordingService.STOP)); refresh(); return;
        }
        if (isLocked()) unlockAndRun(this::startRecording); else startRecording();
    }
    private void startRecording() {
        Intent intent=new Intent(this,QuickRecordActivity.class).setAction(QuickRecordWidget.ACTION_RECORD)
                .putExtra("entry_name","LumaRec · Cámara").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            if (Build.VERSION.SDK_INT>=34) startActivityAndCollapse(PendingIntent.getActivity(this,7100,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(intent);
        } catch (Exception error) { Toast.makeText(this,"Abre LumaRec para iniciar la grabación.",Toast.LENGTH_LONG).show(); }
    }
    @Override public void onDestroy() { if (listening) { unregisterReceiver(updates); listening=false; } super.onDestroy(); }
}
