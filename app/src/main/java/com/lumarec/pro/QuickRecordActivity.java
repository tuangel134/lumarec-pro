package com.lumarec.pro;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** A user-launched foreground activity supplies camera/microphone eligibility on Android 16. */
public final class QuickRecordActivity extends Activity {
    private final Handler handler=new Handler();
    private boolean resumed, initiating, blocked, permissionRequest;
    private TextView status;
    private Button open;
    private final BroadcastReceiver updates=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent intent) { observe(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); PrivacyUi.protect(this,SecureVault.configured(this));
        if (!QuickRecordWidget.ACTION_RECORD.equals(getIntent().getAction())) { finish(); return; }
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24),dp(24),dp(24),dp(24)); root.setBackgroundColor(Color.rgb(8,7,9));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(dp(24)+bars.left,dp(24)+bars.top,dp(24)+bars.right,dp(24)+bars.bottom); return insets;
        });
        String entryName=getIntent().getStringExtra("entry_name");
        TextView title=new TextView(this); title.setText(entryName==null ? QuickRecordWidget.name(this) : entryName); title.setTextSize(26); title.setTextColor(Color.rgb(255,55,83)); title.setGravity(Gravity.CENTER); root.addView(title);
        status=new TextView(this); status.setText("Iniciando grabación rápida…"); status.setTextSize(16); status.setTextColor(Color.WHITE); status.setGravity(Gravity.CENTER); status.setPadding(0,dp(18),0,dp(18)); root.addView(status);
        open=new Button(this); open.setText("Abrir LumaRec"); open.setAllCaps(false); open.setFilterTouchesWhenObscured(true); root.addView(open,new LinearLayout.LayoutParams(-1,dp(52)));
        open.setOnClickListener(v->{ startActivity(new Intent(this,MainActivity.class).putExtra("widget_setup",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); });
        Button close=new Button(this); close.setText("Cerrar"); close.setAllCaps(false); close.setFilterTouchesWhenObscured(true); root.addView(close,new LinearLayout.LayoutParams(-1,dp(52))); close.setOnClickListener(v->finish());
        setContentView(root); root.requestApplyInsets();
        IntentFilter filter=new IntentFilter(RecordingService.UPDATE);
        if (Build.VERSION.SDK_INT>=33) registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(updates,filter);
    }
    @Override protected void onResume() { super.onResume(); resumed=true; handler.postDelayed(this::startWhenVisible,180); }
    @Override protected void onPause() { resumed=false; handler.removeCallbacksAndMessages(null); super.onPause(); }
    private void startWhenVisible() {
        if (!resumed || isFinishing() || status==null || initiating || blocked || permissionRequest) return;
        if (!hasWindowFocus()) { handler.postDelayed(this::startWhenVisible,120); return; }
        if (RecordingService.state!=RecordingService.IDLE) {
            if (RecordingService.state==RecordingService.RECORDING || RecordingService.state==RecordingService.PAUSED) {
                Toast.makeText(this,"LumaRec ya está grabando.",Toast.LENGTH_SHORT).show(); finish();
            } else { status.setText("Hay una grabación en curso. Espera a que termine."); blocked=true; }
            return;
        }
        List<String> permissions=new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.CAMERA);
        if (getSharedPreferences("recorder",MODE_PRIVATE).getBoolean("audio",true) && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) { permissionRequest=true; requestPermissions(permissions.toArray(new String[0]),80); return; }
        if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            fail("Activa las notificaciones del servicio de grabación."); open.setText("Activar notificaciones");
            open.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))); return;
        }
        if (SecureVault.configured(this) && !QuickVault.ready(this) && !SecureVault.unlocked()) {
            fail("Prepara el widget una vez en Ajustes → Widget de grabación rápida. Después grabará con un toque, con la bóveda bloqueada."); return;
        }
        try {
            Intent request=RecordRequest.fromPreferences(this).putExtra("quick_widget",true);
            initiating=true; status.setText("Preparando cámara…"); startForegroundService(request);
            handler.postDelayed(()->{ if (resumed && initiating) observe(); },700);
        } catch (Exception error) { fail(error instanceof IllegalStateException && error.getMessage()!=null ? error.getMessage() : "No se pudo iniciar. Revisa permisos, cámara y espacio libre en LumaRec."); }
    }
    private void observe() {
        if (!initiating || isFinishing()) return;
        int state=RecordingService.state;
        if (state==RecordingService.RECORDING || state==RecordingService.PAUSED) {
            Toast.makeText(this,"Grabación iniciada. Detén desde el acceso rápido o la app.",Toast.LENGTH_SHORT).show(); finish();
        } else if (state==RecordingService.IDLE) fail(RecordingService.message.isEmpty() ? "No se pudo iniciar la grabación." : RecordingService.message);
        else status.setText(state==RecordingService.SAVING ? "Guardando vídeo…" : "Preparando cámara…");
    }
    private void fail(String message) { initiating=false; blocked=true; if (status!=null) status.setText(message); QuickRecordWidget.updateAll(this); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants); if (request!=80) return; permissionRequest=false;
        for (int grant:grants) if (grant!=PackageManager.PERMISSION_GRANTED) { fail("Permiso no concedido. Actívalo en los ajustes de LumaRec para grabar desde el widget."); return; }
        handler.postDelayed(this::startWhenVisible,250);
    }
    @Override protected void onDestroy() {
        if (status!=null) unregisterReceiver(updates); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
}
