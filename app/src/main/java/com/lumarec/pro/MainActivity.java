package com.lumarec.pro;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Size;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(8,7,9), CARD=Color.rgb(25,17,21), TEXT=Color.rgb(249,244,246), MUTED=Color.rgb(171,153,160), GREEN=Color.rgb(255,55,83), RED=Color.rgb(255,55,83);
    private SharedPreferences prefs, brandPrefs;
    private final Handler handler=new Handler();
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private List<CameraCatalog.Camera> cameras=new ArrayList<>();
    private CameraCatalog.Camera selected;
    private CameraCatalog.Quality quality;
    private int fps=30, tab=0, countdown=0;
    private boolean resumed, launching, permissionForRecording, vaultWorking, exportWorking;
    private long seenRevision;
    private LinearLayout root, body, navigation;
    private TextView timer, status, subtitle;
    private Button record, pause;
    private String libraryQuery="";
    private boolean libraryFavorites;
    private List<VideoLibrary.Video> libraryVideos=new ArrayList<>();
    private LinearLayout libraryResults;
    private final List<AlertDialog> protectedDialogs=new ArrayList<>();
    private final Runnable filterLibrary=()->{ if (tab==1 && libraryResults!=null) renderLibraryResults(); };
    private final Runnable applyCameraControls=()->{
        if (RecordingService.state==RecordingService.RECORDING || RecordingService.state==RecordingService.PAUSED) command(RecordingService.CONTROLS);
    };
    private final BroadcastReceiver updates=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            if (!RecordingService.controlMessage.isEmpty()) { toast(RecordingService.controlMessage); RecordingService.controlMessage=""; }
            if (RecordingService.state==RecordingService.IDLE && RecordingService.revision!=seenRevision && !RecordingService.message.isEmpty()) toast(RecordingService.message);
            seenRevision=RecordingService.revision;
            render();
        }
    };
    private final Runnable ticker=new Runnable() {
        @Override public void run() { updateControls(); if (resumed) handler.postDelayed(this,300); }
    };
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs=getSharedPreferences("recorder",MODE_PRIVATE);
        brandPrefs=getSharedPreferences("recorder_brand",MODE_PRIVATE);
        files.execute(()->{
            VideoLibrary.clearExpiredExports(this);
            try { SecureVault.migrate(this); }
            catch (Exception error) { runOnUiThread(()->toast("Libera espacio para mover todos los vídeos antiguos al almacenamiento interno. Los que faltan siguen cifrados.")); }
        });
        if (savedInstanceState!=null) tab=savedInstanceState.getInt("tab",0);
        if (getIntent().getBooleanExtra("widget_setup",false)) tab=2;
        seenRevision=RecordingService.revision;
        IntentFilter filter=new IntentFilter(RecordingService.UPDATE);
        if (Build.VERSION.SDK_INT>=33) registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(updates,filter);
        String previousPending=prefs.getString("pending_uri",null);
        if (previousPending!=null && RecordingService.state==RecordingService.IDLE) files.execute(()->{
            try { getContentResolver().delete(Uri.parse(previousPending),null,null); } catch (Exception ignored) { }
            prefs.edit().remove("pending_uri").apply();
        });
        if (RecordingService.state==RecordingService.IDLE) files.execute(()->{
            if (RecordingService.state!=RecordingService.IDLE) return;
            File[] directories={getExternalFilesDir("LumaRecRecordings"),new File(getFilesDir(),"recordings")};
            for (File dir:directories) {
                File[] leftovers=dir==null ? null : dir.listFiles((d,name)->name.endsWith(".recording.mp4"));
                if (leftovers!=null) for (File file:leftovers) { if (RecordingService.state!=RecordingService.IDLE) return; file.delete(); }
            }
        });
        try { cameras=CameraCatalog.scan(this); } catch (Exception error) { toast("No se pudieron consultar las cámaras."); }
        normalize(); render();
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putInt("tab",tab); super.onSaveInstanceState(out); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (intent.getBooleanExtra("widget_setup",false)) { tab=2; render(); } }
    @Override protected void onResume() {
        super.onResume(); resumed=true;
        render(); handler.post(ticker);
    }
    @Override protected void onPause() {
        resumed=false; handler.removeCallbacks(ticker);
        if (SecureVault.configured(this)) {
            for (AlertDialog dialog:new ArrayList<>(protectedDialogs)) dialog.dismiss();
            protectedDialogs.clear();
        }
        if (countdown>0) { countdown=0; launching=false; toast("Temporizador cancelado: mantén la app abierta hasta iniciar la grabación."); }
        super.onPause();
    }
    @Override protected void onDestroy() {
        unregisterReceiver(updates); handler.removeCallbacksAndMessages(null); files.shutdownNow(); super.onDestroy();
    }
    private boolean busy() { return RecordingService.state!=RecordingService.IDLE || launching || countdown>0 || vaultWorking || exportWorking; }
    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density*value+0.5f); }
    private void normalize() {
        if (cameras.isEmpty()) return;
        selected=cameras.get(0);
        for (CameraCatalog.Camera camera:cameras) if (camera.id.equals(prefs.getString("camera",selected.id))) selected=camera;
        quality=selected.qualities.get(0);
        for (CameraCatalog.Quality q:selected.qualities) if (q.width==prefs.getInt("width",0) && q.height==prefs.getInt("height",0)) quality=q;
        boolean hevc=prefs.getBoolean("hevc",false);
        List<Integer> rates=CameraCatalog.frameRates(selected,quality,hevc);
        if (rates.isEmpty()) { hevc=false; prefs.edit().putBoolean("hevc",false).apply(); rates=CameraCatalog.frameRates(selected,quality,false); }
        fps=prefs.getInt("fps",30);
        if (!rates.contains(fps)) fps=rates.contains(30) ? 30 : rates.isEmpty() ? 15 : rates.get(rates.size()-1);
        prefs.edit().putString("camera",selected.id).putInt("width",quality.width).putInt("height",quality.height).putInt("fps",fps).apply();
    }
    private GradientDrawable background(int color,int radius) {
        GradientDrawable drawable=new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); if (bold) view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        view.setLineSpacing(dp(3),1); return view;
    }
    private void gap(LinearLayout layout,int height) { View space=new View(this); layout.addView(space,new LinearLayout.LayoutParams(1,dp(height))); }
    private LinearLayout vertical() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout card() {
        LinearLayout v=vertical(); v.setPadding(dp(18),dp(18),dp(18),dp(18)); v.setBackground(background(CARD,20));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.bottomMargin=dp(14); body.addView(v,params); return v;
    }
    private Button button(String label,int color,int foreground) {
        Button button=new Button(this); button.setText(label); button.setTextColor(foreground); button.setAllCaps(false); button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); button.setBackground(background(color,16)); button.setMinHeight(dp(50));
        button.setPadding(dp(14),dp(8),dp(14),dp(8)); button.setFilterTouchesWhenObscured(true); return button;
    }
    private void render() {
        PrivacyUi.protect(this,SecureVault.configured(this));
        timer=null; status=null; record=null; pause=null;
        root=vertical(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(22),dp(16),dp(22),dp(12));
        File customLogo=new File(getFilesDir(),"launcher-logo.png");
        if (customLogo.isFile()) try {
            Bitmap image=BitmapFactory.decodeFile(customLogo.getAbsolutePath());
            if (image!=null) { ImageView mark=new ImageView(this); mark.setImageBitmap(image); mark.setBackground(background(CARD,24)); mark.setClipToOutline(true); LinearLayout.LayoutParams markSize=new LinearLayout.LayoutParams(dp(38),dp(38)); markSize.rightMargin=dp(10); header.addView(mark,markSize); }
        } catch (Exception ignored) { }
        TextView logo=text(brandPrefs.getString("app_name","LumaRec"),25,TEXT,true); header.addView(logo,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=text(SecureVault.configured(this) ? " PRIVADO " : " PRO ",10,BG,true); badge.setPadding(dp(8),dp(5),dp(8),dp(5)); badge.setBackground(background(GREEN,8)); header.addView(badge);
        root.addView(header);
        body=vertical(); body.setPadding(dp(22),dp(10),dp(22),dp(16));
        if (SecureVault.configured(this) && !SecureVault.unlocked()) {
            renderVaultUnlock(); ScrollView gate=new ScrollView(this); gate.setFillViewport(true); gate.addView(body); root.addView(gate,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root); root.requestApplyInsets(); return;
        }
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (tab==0) renderRecorder(); else if (tab==1) renderLibrary(); else renderSettings();
        navigation=new LinearLayout(this); navigation.setPadding(dp(14),dp(8),dp(14),dp(10)); navigation.setBackgroundColor(BG);
        String[] tabs={"●  Grabar","▤  Vídeos","⚙  Ajustes"};
        for (int i=0;i<3;i++) {
            final int destination=i; Button b=button(tabs[i],tab==i ? CARD : BG,tab==i ? GREEN : MUTED);
            b.setTextSize(13); b.setOnClickListener(v->{ tab=destination; render(); }); navigation.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));
        }
        root.addView(navigation); setContentView(root); root.requestApplyInsets(); updateControls();
    }
    private void renderRecorder() {
        body.addView(text("Tu cámara. A tu ritmo.",26,TEXT,true)); gap(body,5);
        body.addView(text("Vídeo continuo, incluso con la pantalla bloqueada.",14,MUTED,false)); gap(body,20);
        LinearLayout capture=card();
        LinearLayout stateRow=new LinearLayout(this); stateRow.setGravity(Gravity.CENTER_VERTICAL);
        status=text("●  LISTA PARA GRABAR",11,GREEN,true); stateRow.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        TextView local=text("SOLO EN TU MÓVIL",9,MUTED,true); stateRow.addView(local); capture.addView(stateRow); gap(capture,14);
        gap(capture,14); timer=text("00:00:00",38,TEXT,true); timer.setTypeface(Typeface.create("monospace",Typeface.NORMAL)); timer.setGravity(Gravity.CENTER); capture.addView(timer);
        subtitle=text(selected==null ? "No se encontró una cámara compatible" : selected.label+"  /  "+quality.height+"p  /  "+fps+" fps",12,MUTED,false); subtitle.setGravity(Gravity.CENTER); capture.addView(subtitle);
        gap(capture,17); record=button("●  Iniciar grabación",GREEN,BG); capture.addView(record,new LinearLayout.LayoutParams(-1,dp(55)));
        record.setOnClickListener(v->{ if (RecordingService.state!=RecordingService.IDLE) command(RecordingService.STOP); else if (countdown>0) { countdown=0; launching=false; render(); } else askToRecord(); });
        gap(capture,8); pause=button("Pausar",BG,TEXT); capture.addView(pause,new LinearLayout.LayoutParams(-1,dp(46))); pause.setOnClickListener(v->command(RecordingService.PAUSE));
        if (selected!=null) {
            LinearLayout config=card(); config.addView(text("CAPTURA",11,MUTED,true)); gap(config,10);
            int cameraIndex=cameras.indexOf(selected);
            selector(config,"Cámara",cameras,cameraIndex,index->{ prefs.edit().putString("camera",cameras.get(index).id).remove("width").remove("height").apply(); normalize(); render(); });
            selector(config,"Resolución",selected.qualities,selected.qualities.indexOf(quality),index->{ CameraCatalog.Quality q=selected.qualities.get(index); prefs.edit().putInt("width",q.width).putInt("height",q.height).apply(); normalize(); render(); });
            List<Integer> rates=CameraCatalog.frameRates(selected,quality,prefs.getBoolean("hevc",false));
            List<String> labels=new ArrayList<>(); for (int rate:rates) labels.add(rate+" fps");
            selector(config,"Fotogramas",labels,Math.max(0,rates.indexOf(fps)),index->{ prefs.edit().putInt("fps",rates.get(index)).apply(); normalize(); render(); });
            toggle(config,"Grabar audio","Micrófono con codificación AAC", "audio",true,true);
        }
        addCaptureControls();
        LinearLayout tip=card(); tip.addView(text("Grabación con pantalla bloqueada",15,TEXT,true)); gap(tip,7);
        tip.addView(text("Inicia aquí y después bloquea el móvil. Para pausar o detener, abre la app tocando la notificación. Si usas el widget, otro toque detiene y guarda.",13,MUTED,false));
        gap(tip,8); tip.addView(text(storageText(),12,GREEN,false));
    }
    private interface Selection { void changed(int index); }
    private void cameraControlsChanged() { handler.removeCallbacks(applyCameraControls); handler.postDelayed(applyCameraControls,180); }
    private boolean manualControlsEnabled() {
        int state=RecordingService.state;
        boolean highSpeed=state==RecordingService.IDLE ? selected!=null && quality!=null && CameraCatalog.highSpeedRange(selected,quality,fps)!=null : RecordingService.highSpeedSession;
        return !vaultWorking && !launching && countdown==0 && !highSpeed && (state==RecordingService.IDLE || state==RecordingService.RECORDING || state==RecordingService.PAUSED);
    }
    private void addCaptureControls() {
        if (selected==null || quality==null) return;
        android.hardware.camera2.CameraCharacteristics c=selected.characteristics;
        LinearLayout controls=card(); controls.addView(text("CONTROLES DE CÁMARA",11,MUTED,true)); gap(controls,8);
        boolean enabled=manualControlsEnabled();
        android.util.Range<Float> zoom=CameraControls.zoomRange(c);
        float current=zoom.clamp(prefs.getFloat("zoom",1f));
        TextView zoomLabel=text(String.format(Locale.getDefault(),"Zoom · %.2f×",current),14,TEXT,true); controls.addView(zoomLabel);
        SeekBar zoomBar=new SeekBar(this); zoomBar.setMax(1000);
        zoomBar.setProgress(zoom.getUpper().equals(zoom.getLower()) ? 0 : Math.round((current-zoom.getLower())*1000/(zoom.getUpper()-zoom.getLower())));
        zoomBar.setEnabled(enabled && zoom.getUpper()>zoom.getLower()); tint(zoomBar); controls.addView(zoomBar);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean user) {
                if (!user) return; float value=zoom.getLower()+(zoom.getUpper()-zoom.getLower())*progress/1000f;
                prefs.edit().putFloat("zoom",value).apply(); zoomLabel.setText(String.format(Locale.getDefault(),"Zoom · %.2f×",value)); cameraControlsChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { cameraControlsChanged(); }
        });
        android.util.Range<Integer> exposure=CameraControls.exposureRange(c);
        android.util.Rational step=c.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        final double unit=step==null ? 0 : step.doubleValue();
        int ev=exposure.clamp(prefs.getInt("exposure",0));
        TextView exposureLabel=text(String.format(Locale.getDefault(),"Exposición · %+.1f EV",ev*unit),14,TEXT,true); controls.addView(exposureLabel);
        SeekBar exposureBar=new SeekBar(this); exposureBar.setMax(exposure.getUpper()-exposure.getLower()); exposureBar.setProgress(ev-exposure.getLower());
        exposureBar.setEnabled(enabled && exposure.getUpper()>exposure.getLower()); tint(exposureBar); controls.addView(exposureBar);
        exposureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean user) {
                if (!user) return; int value=progress+exposure.getLower(); prefs.edit().putInt("exposure",value).apply();
                exposureLabel.setText(String.format(Locale.getDefault(),"Exposición · %+.1f EV",value*unit)); cameraControlsChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { cameraControlsChanged(); }
        });
        List<Integer> modes=CameraControls.whiteBalanceModes(c); List<String> names=new ArrayList<>();
        for (int mode:modes) names.add(CameraControls.whiteBalanceName(mode));
        liveSelector(controls,"Balance de blancos",names,Math.max(0,modes.indexOf(prefs.getInt("white_balance",android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO))),
                enabled && modes.size()>1,index->prefs.edit().putInt("white_balance",modes.get(index)).apply());
        liveSelector(controls,"Enfoque",List.of("Continuo","Infinito"),prefs.getInt("focus",0)==1 ? 1 : 0,
                enabled && CameraControls.infinity(c),index->prefs.edit().putInt("focus",index).apply());
        liveToggle(controls,"Luz de la cámara","Enciende el LED durante la grabación","torch",enabled && CameraControls.flash(c));
        boolean recording=RecordingService.state==RecordingService.RECORDING || RecordingService.state==RecordingService.PAUSED;
        liveToggle(controls,"Bloquear exposición","Durante la grabación, fija la luz medida","ae_lock",enabled && recording && Boolean.TRUE.equals(c.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)) && CameraControls.supports(c,android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK));
        liveToggle(controls,"Bloquear balance de blancos","Durante la grabación, fija el color medido","awb_lock",enabled && recording && Boolean.TRUE.equals(c.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)) && CameraControls.supports(c,android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK));
        gap(controls,8); controls.addView(text(enabled ? "Puedes ajustar estos controles durante la grabación. Las opciones dependen de lo que exponga la cámara de tu móvil."
                : "Los controles se activan al terminar la preparación. Los perfiles de alta velocidad usan ajustes automáticos.",12,MUTED,false));
    }
    private void tint(SeekBar bar) {
        android.content.res.ColorStateList colors=android.content.res.ColorStateList.valueOf(GREEN);
        bar.setProgressTintList(colors); bar.setThumbTintList(colors);
    }
    private void showProtected(AlertDialog dialog) {
        protectedDialogs.removeIf(previous->!previous.isShowing());
        protectedDialogs.add(dialog); dialog.show(); PrivacyUi.protect(dialog);
    }
    private void liveSelector(LinearLayout parent,String label,List<String> values,int selectedIndex,boolean enabled,Selection changed) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label,13,MUTED,false),new LinearLayout.LayoutParams(0,-2,0.45f));
        Spinner spinner=new Spinner(this); ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter); spinner.setSelection(selectedIndex); spinner.setEnabled(enabled);
        row.addView(spinner,new LinearLayout.LayoutParams(0,dp(48),0.55f)); parent.addView(row);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            int previous=selectedIndex;
            @Override public void onItemSelected(AdapterView<?> p,View v,int position,long id) { if (previous!=position) { previous=position; changed.changed(position); cameraControlsChanged(); } }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }
    private void liveToggle(LinearLayout parent,String label,String detail,String key,boolean enabled) {
        LinearLayout row=new LinearLayout(this); row.setPadding(0,dp(6),0,dp(6)); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout description=vertical(); description.addView(text(label,13,TEXT,false)); description.addView(text(detail,11,MUTED,false));
        row.addView(description,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this); toggle.setContentDescription(label); toggle.setChecked(prefs.getBoolean(key,false)); toggle.setEnabled(enabled);
        row.addView(toggle); parent.addView(row);
        toggle.setOnCheckedChangeListener((v,value)->{ prefs.edit().putBoolean(key,value).apply(); cameraControlsChanged(); });
    }
    private void selector(LinearLayout parent,String title,List<?> values,int initial,Selection listener) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(5),0,dp(5));
        row.addView(text(title,13,MUTED,false),new LinearLayout.LayoutParams(0,-2,0.4f));
        Spinner spinner=new Spinner(this); ArrayAdapter<Object> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new ArrayList<Object>(values));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter); spinner.setSelection(initial);
        spinner.setEnabled(!busy()); row.addView(spinner,new LinearLayout.LayoutParams(0,dp(46),0.6f)); parent.addView(row);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            int previous=initial;
            @Override public void onItemSelected(AdapterView<?> p,View view,int position,long id) { if (position!=previous) { previous=position; listener.changed(position); } }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }
    private void toggle(LinearLayout parent,String label,String detail,String key,boolean fallback,boolean supported) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(9),0,dp(9));
        LinearLayout labels=vertical(); labels.addView(text(label,14,TEXT,false)); labels.addView(text(detail,11,MUTED,false)); row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch control=new Switch(this); control.setContentDescription(label); control.setChecked(supported && prefs.getBoolean(key,fallback)); control.setEnabled(supported && !busy());
        row.addView(control); parent.addView(row);
        control.setOnCheckedChangeListener((v,checked)->{
            prefs.edit().putBoolean(key,checked).apply();
            if ("hevc".equals(key) || "high".equals(key)) { normalize(); render(); }
        });
    }
    private void renderSettings() {
        body.addView(text("Ajusta cada detalle.",26,TEXT,true)); gap(body,5); body.addView(text("Calidad, límites y control de la captura.",14,MUTED,false)); gap(body,20);
        addWidgetCard();
        addProfilesCard();
        LinearLayout qualityCard=card(); qualityCard.addView(text("CALIDAD DE VÍDEO",11,MUTED,true)); gap(qualityCard,8);
        boolean supports=selected!=null && quality!=null && CameraCatalog.encoderSupports(true,quality.width,quality.height,fps);
        toggle(qualityCard,"HEVC / H.265",supports ? "Archivos más pequeños; compatibilidad variable" : "No disponible para esta resolución y FPS","hevc",false,supports);
        toggle(qualityCard,"Bitrate alto","Más detalle; mayor consumo de espacio","high",true,true);
        toggle(qualityCard,"Estabilización digital",selected!=null && selected.stabilization ? "Puede recortar el campo de visión" : "No anunciada por esta cámara","stabilization",false,selected!=null && selected.stabilization);
        if (quality!=null) {
            gap(qualityCard,8); boolean hevc=prefs.getBoolean("hevc",false);
            int bitrate=CameraControls.customBitrate(prefs,CameraCatalog.bitrate(quality,fps,hevc,prefs.getBoolean("high",true)),hevc,quality.width,quality.height,fps);
            qualityCard.addView(text(String.format(Locale.getDefault(),"Vídeo solicitado: %.1f Mbps · aprox. %.0f MB/min",bitrate/1e6,(bitrate+(prefs.getBoolean("audio",true) ? prefs.getInt("audio_kbps",192)*1000 : 0))*60d/8e6),12,GREEN,false));
        }
        gap(qualityCard,10); qualityCard.addView(text("La lista incluye 4K a 60 fps en Huawei cuando se reconoce el codificador de vídeo. Algunos perfiles pueden aparecer en la app Huawei y no en Android; el móvil confirmará si admite la grabación. HDR y funciones exclusivas de Huawei pueden variar.",12,MUTED,false));
        addAdvancedSettings();
        LinearLayout limits=card(); limits.addView(text("TIEMPO Y ALMACENAMIENTO",11,MUTED,true)); gap(limits,8);
        List<String> delays=List.of("Sin espera","1 segundo","3 segundos","5 segundos","10 segundos","15 segundos","30 segundos"); int[] delayValues={0,1,3,5,10,15,30};
        selector(limits,"Temporizador",delays,indexOf(delayValues,prefs.getInt("delay",0)),i->prefs.edit().putInt("delay",delayValues[i]).apply());
        List<String> durations=new ArrayList<>(List.of("Sin límite","1 minuto","5 minutos","15 minutos","30 minutos","1 hora"));
        List<Integer> durationValues=new ArrayList<>(List.of(0,60,300,900,1800,3600)); int currentDuration=prefs.getInt("duration",0);
        if (!durationValues.contains(currentDuration)) { durationValues.add(currentDuration); durations.add(VideoLibrary.time(currentDuration*1000L)); }
        selector(limits,"Duración",durations,durationValues.indexOf(currentDuration),i->prefs.edit().putInt("duration",durationValues.get(i)).apply());
        List<String> sizes=new ArrayList<>(List.of("Espacio disponible","100 MB","500 MB","1 GB","4 GB"));
        List<Integer> sizeValues=new ArrayList<>(List.of(0,100,500,1000,4000)); int currentSize=prefs.getInt("maxMB",0);
        if (!sizeValues.contains(currentSize)) { sizeValues.add(currentSize); sizes.add(currentSize+" MB"); }
        selector(limits,"Tamaño máx.",sizes,sizeValues.indexOf(currentSize),i->prefs.edit().putInt("maxMB",sizeValues.get(i)).apply());
        gap(limits,8); Button customLimits=button("Escribir límites personalizados",BG,TEXT); customLimits.setEnabled(!busy()); customLimits.setOnClickListener(v->limitsDialog()); limits.addView(customLimits);
        gap(limits,8); limits.addView(text("La duración cuenta solo el tiempo grabado. Al alcanzar un límite, el vídeo se guarda. Se reserva espacio para finalizar el archivo.",12,MUTED,false));
        addExportCard();
        LinearLayout reliability=card(); reliability.addView(text("Permisos y batería",16,TEXT,true)); gap(reliability,8);
        reliability.addView(text("En Huawei, permite la ejecución en segundo plano en los ajustes de batería / inicio de aplicaciones. El sistema puede detener la cámara por batería, temperatura o por otra app.",13,MUTED,false)); gap(reliability,12);
        Button permissions=button("Abrir ajustes de la app",BG,GREEN); permissions.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))); reliability.addView(permissions,new LinearLayout.LayoutParams(-1,dp(48)));
        gap(reliability,9); Button battery=button("Ajustes de batería",BG,TEXT); battery.setOnClickListener(v->{ try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); } }); reliability.addView(battery,new LinearLayout.LayoutParams(-1,dp(48)));
        addBrandingCard();
        addNotificationCard();
        addVaultCard();
        LinearLayout privacy=card(); privacy.addView(text("Tus vídeos se quedan contigo",16,TEXT,true)); gap(privacy,8);
        privacy.addView(text(SecureVault.configured(this) ? "Vídeos cifrados en el almacenamiento interno de LumaRec, sin acceso a Internet ni copias de seguridad. Se reproducen dentro de la app. Las capturas de pantalla están bloqueadas. Compartir o devolver a la galería requiere tu confirmación." : "Sin cuenta, anuncios ni acceso a Internet. Activa la bóveda privada para cifrar los vídeos y mantenerlos fuera de la galería.",13,MUTED,false));
        gap(privacy,12); privacy.addView(text("LumaRec Pro 1.4.0  ·  Android 11–16",11,GREEN,true));
    }
    private void addWidgetCard() {
        LinearLayout widget=card(); widget.addView(text("WIDGETS Y ACCESOS RÁPIDOS",11,MUTED,true)); gap(widget,8);
        widget.addView(text("Cámara, panel e icono transparente",16,TEXT,true)); gap(widget,8);
        widget.addView(text("El widget de cámara 1×1 y el panel con tiempo, pausa y parada son accesos independientes. El icono transparente que ya usabas conserva su nombre e imagen. Un toque inicia y otro detiene, usando los ajustes actuales sin temporizador.",13,MUTED,false)); gap(widget,10);
        if (SecureVault.configured(this)) {
            widget.addView(text("El widget guarda los vídeos cifrados aunque la bóveda esté bloqueada. Verlos sigue requiriendo tu código.",12,GREEN,false)); gap(widget,10);
        }
        Button cameraWidget=button("Añadir cámara 1×1",GREEN,BG); cameraWidget.setEnabled(!busy()); widget.addView(cameraWidget,new LinearLayout.LayoutParams(-1,dp(48)));
        cameraWidget.setOnClickListener(v->prepareWidget(true,CameraRecordWidget.class)); gap(widget,8);
        Button panelWidget=button("Añadir panel de controles",BG,TEXT); panelWidget.setEnabled(!busy()); widget.addView(panelWidget,new LinearLayout.LayoutParams(-1,dp(48)));
        panelWidget.setOnClickListener(v->prepareWidget(true,RecordingPanelWidget.class)); gap(widget,8);
        Button personalize=button("Configurar icono transparente",BG,TEXT); personalize.setEnabled(!busy()); widget.addView(personalize,new LinearLayout.LayoutParams(-1,dp(48)));
        personalize.setOnClickListener(v->widgetDialog()); gap(widget,8);
        Button add=button("Añadir icono transparente 1×1",BG,TEXT); add.setEnabled(!busy()); widget.addView(add,new LinearLayout.LayoutParams(-1,dp(48)));
        add.setOnClickListener(v->prepareWidget(true,QuickRecordWidget.class)); gap(widget,8);
        Button tile=button("Añadir a los ajustes rápidos de Android",BG,TEXT); tile.setEnabled(!busy()); widget.addView(tile,new LinearLayout.LayoutParams(-1,dp(48)));
        tile.setOnClickListener(v->prepareWidget(true,RecordTile.class));
    }
    private void widgetDialog() {
        if (busy()) return;
        LinearLayout content=vertical(); content.setPadding(dp(8),dp(8),dp(8),0);
        EditText name=input("Nombre del widget",false); name.setText(QuickRecordWidget.name(this)); name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)}); content.addView(name); gap(content,10);
        Spinner icon=new Spinner(this); ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,QuickRecordWidget.ICON_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); icon.setAdapter(adapter); icon.setSelection(QuickRecordWidget.icon(this)); content.addView(icon); gap(content,10);
        Button image=button("Elegir mi propia imagen",BG,TEXT); content.addView(image); gap(content,8);
        content.addView(text("El icono se actualiza en todos tus widgets. El nombre identifica el acceso dentro de la app; no aparece en la pantalla de inicio.",12,MUTED,false));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Tu widget rápido").setView(content).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
        image.setOnClickListener(v->{
            try {
                String chosen=name.getText().toString().trim(); if (chosen.isEmpty()) chosen="Grabar";
                QuickRecordWidget.prefs(this).edit().putString("name",chosen).apply(); dialog.dismiss();
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),72);
            } catch (Exception error) { toast("No se pudo abrir el selector de imágenes."); }
        });
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            int selectedIcon=icon.getSelectedItemPosition();
            if (selectedIcon==QuickRecordWidget.CUSTOM_ICON && !new File(getFilesDir(),"widget-logo.png").isFile()) { toast("Elige primero tu propia imagen."); return; }
            String chosen=name.getText().toString().trim(); if (chosen.isEmpty()) chosen="Grabar";
            QuickRecordWidget.prefs(this).edit().putString("name",chosen).putInt("icon",selectedIcon).apply();
            dialog.dismiss(); QuickRecordWidget.updateAll(this); prepareWidget(false);
        }));
        showProtected(dialog);
    }
    private void prepareWidget(boolean addToHome) {
        prepareWidget(addToHome,CameraRecordWidget.class);
    }
    private void prepareWidget(boolean addToHome,Class<?> widgetType) {
        if (busy()) return;
        boolean privateVault=SecureVault.configured(this); byte[] master=privateVault ? SecureVault.key() : null;
        if (privateVault && (!SecureVault.unlocked() || master==null)) { if (master!=null) java.util.Arrays.fill(master,(byte)0); render(); return; }
        vaultWorking=true; render();
        if (privateVault && !QuickVault.ready(this)) toast("Preparando el guardado privado del widget…");
        files.execute(()->{
            String problem=null;
            try { if (privateVault) QuickVault.ensure(this,master); }
            catch (Exception error) { problem="No se pudo preparar la bóveda para el widget. Mantén la app abierta y vuelve a intentarlo."; }
            finally { if (master!=null) java.util.Arrays.fill(master,(byte)0); }
            final String result=problem;
            runOnUiThread(()->{
                vaultWorking=false; if (isDestroyed()) return; QuickRecordWidget.updateAll(this); render();
                if (result!=null) { toast(result); return; }
                if (addToHome && resumed) {
                    if (widgetType==RecordTile.class) {
                        if (Build.VERSION.SDK_INT>=33) try {
                            getSystemService(android.app.StatusBarManager.class).requestAddTileService(new ComponentName(this,RecordTile.class),"LumaRec",Icon.createWithResource(this,R.drawable.ic_widget_camera),getMainExecutor(),
                                    code->toast(code==android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || code==android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ? "Acceso de LumaRec disponible en los ajustes rápidos." : "Puedes añadir LumaRec editando el panel de ajustes rápidos."));
                            return;
                        } catch (Exception ignored) { }
                        toast("Desliza el panel de ajustes rápidos, pulsa Editar y añade LumaRec."); return;
                    }
                    boolean requested=false; try { requested=QuickRecordWidget.pin(this,widgetType); } catch (Exception ignored) { }
                    toast(requested ? "Confirma el widget en la pantalla de inicio." : "Mantén pulsada la pantalla de inicio → Widgets → LumaRec y elige el acceso.");
                } else toast("Widget actualizado.");
            });
        });
    }
    private void addProfilesCard() {
        LinearLayout card=card(); card.addView(text("PERFILES DE GRABACIÓN",11,MUTED,true)); gap(card,8);
        List<RecordingPresets.Preset> profiles=RecordingPresets.list(this);
        card.addView(text(profiles.size()+" perfil(es) guardado(s)",16,TEXT,true)); gap(card,8);
        card.addView(text("Guarda cámara, resolución, FPS, audio, zoom, luz, orientación y límites. Después recupera tus ajustes en un toque.",13,MUTED,false)); gap(card,10);
        Button save=button("Guardar ajustes actuales",GREEN,BG); save.setEnabled(!busy()); card.addView(save); save.setOnClickListener(v->{
            EditText name=input("Nombre del perfil",false); name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nuevo perfil").setView(name).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
            dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
                try { RecordingPresets.save(this,name.getText().toString()); dialog.dismiss(); render(); toast("Perfil guardado."); }
                catch (Exception error) { toast("Escribe un nombre para guardar el perfil."); }
            })); showProtected(dialog);
        }); gap(card,8);
        Button load=button("Usar un perfil",BG,TEXT); load.setEnabled(!busy() && !profiles.isEmpty()); card.addView(load); load.setOnClickListener(v->{
            String[] names=new String[profiles.size()]; for (int i=0;i<names.length;i++) names[i]=profiles.get(i).name;
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Usar perfil").setItems(names,(d,index)->{ RecordingPresets.load(this,profiles.get(index)); normalize(); render(); toast("Perfil aplicado. Revisa la cámara y la calidad seleccionadas."); }).create();
            showProtected(dialog);
        }); gap(card,8);
        Button remove=button("Eliminar un perfil",BG,MUTED); remove.setEnabled(!busy() && !profiles.isEmpty()); card.addView(remove); remove.setOnClickListener(v->{
            String[] names=new String[profiles.size()]; for (int i=0;i<names.length;i++) names[i]=profiles.get(i).name;
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Eliminar perfil").setItems(names,(d,index)->{ RecordingPresets.delete(this,profiles.get(index)); render(); }).create();
            showProtected(dialog);
        });
    }
    private void addAdvancedSettings() {
        LinearLayout advanced=card(); advanced.addView(text("FORMATO Y AUDIO",11,MUTED,true)); gap(advanced,8);
        selector(advanced,"Orientación",List.of("Según pantalla","Vertical","Horizontal","Vertical invertida","Horizontal invertida"),Math.max(0,Math.min(4,prefs.getInt("orientation_mode",0))),
                value->prefs.edit().putInt("orientation_mode",value).apply());
        int[] bitrates={0,4,8,12,20,35,50,70,100,150};
        selector(advanced,"Bitrate de vídeo",List.of("Automático","4 Mbps","8 Mbps","12 Mbps","20 Mbps","35 Mbps","50 Mbps","70 Mbps","100 Mbps","150 Mbps"),
                indexOf(bitrates,prefs.getInt("custom_mbps",0)),index->{ prefs.edit().putInt("custom_mbps",bitrates[index]).apply(); render(); });
        int[] audioRates={64,128,192,256};
        selector(advanced,"Calidad AAC",List.of("64 kbps","128 kbps","192 kbps","256 kbps"),indexOf(audioRates,prefs.getInt("audio_kbps",192)),
                index->prefs.edit().putInt("audio_kbps",audioRates[index]).apply());
        List<String> sources=new ArrayList<>(List.of("Micrófono","Optimizado para vídeo"));
        List<Integer> sourceValues=new ArrayList<>(List.of(android.media.MediaRecorder.AudioSource.MIC,android.media.MediaRecorder.AudioSource.CAMCORDER));
        if ("true".equals(getSystemService(android.media.AudioManager.class).getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))) {
            sources.add("Sin procesar"); sourceValues.add(android.media.MediaRecorder.AudioSource.UNPROCESSED);
        }
        selector(advanced,"Fuente de audio",sources,Math.max(0,sourceValues.indexOf(prefs.getInt("audio_source",android.media.MediaRecorder.AudioSource.MIC))),
                index->prefs.edit().putInt("audio_source",sourceValues.get(index)).apply());
        gap(advanced,8); advanced.addView(text("El bitrate se ajusta al rango admitido por el codificador. Un valor alto consume más espacio y no garantiza más detalle.",12,MUTED,false)); gap(advanced,10);
        Button prefix=button("Prefijo de archivos en la galería",BG,TEXT); prefix.setEnabled(!busy()); advanced.addView(prefix);
        prefix.setOnClickListener(v->{
            EditText value=input("Prefijo",false); value.setText(prefs.getString("file_prefix","LumaRec")); value.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nombre de las nuevas grabaciones").setView(value).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(d,w)->{
                String name=value.getText().toString().trim(); prefs.edit().putString("file_prefix",name.isEmpty() ? "LumaRec" : name).apply(); render();
            }).create(); showProtected(dialog);
        });
    }
    private void limitsDialog() {
        LinearLayout content=vertical(); content.setPadding(dp(12),0,dp(12),0);
        content.addView(text("Duración máxima en segundos · 0 = sin límite",12,MUTED,false));
        EditText seconds=input("Segundos",false); seconds.setInputType(InputType.TYPE_CLASS_NUMBER); seconds.setText(String.valueOf(prefs.getInt("duration",0))); content.addView(seconds); gap(content,10);
        content.addView(text("Tamaño máximo en MB · 0 = espacio disponible",12,MUTED,false));
        EditText size=input("MB",false); size.setInputType(InputType.TYPE_CLASS_NUMBER); size.setText(String.valueOf(prefs.getInt("maxMB",0))); content.addView(size);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Límites personalizados").setView(content).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                int time=Integer.parseInt(seconds.getText().toString()),mb=Integer.parseInt(size.getText().toString());
                if (time<0 || time>86400 || mb<0 || mb>100000) throw new IllegalArgumentException();
                prefs.edit().putInt("duration",time).putInt("maxMB",mb).apply(); dialog.dismiss(); render();
            } catch (Exception error) { toast("Usa de 0 a 86400 segundos y de 0 a 100000 MB."); }
        })); showProtected(dialog);
    }
    private void addExportCard() {
        LinearLayout export=card(); export.addView(text("EXPORTAR VÍDEOS",11,MUTED,true)); gap(export,8);
        export.addView(text(ExportFolder.name(this),16,TEXT,true)); gap(export,8);
        export.addView(text("Elige una carpeta y exporta copias desde Vídeos. Los originales permanecen en la app; la bóveda no se exporta automáticamente.",13,MUTED,false)); gap(export,10);
        Button choose=button("Elegir carpeta de exportación",BG,TEXT); choose.setEnabled(!vaultWorking); export.addView(choose); choose.setOnClickListener(v->chooseExportFolder());
        if (ExportFolder.tree(this)!=null) {
            gap(export,8); Button clear=button("Olvidar esta carpeta",BG,MUTED); export.addView(clear); clear.setOnClickListener(v->{ ExportFolder.clear(this); render(); });
        }
    }
    private void chooseExportFolder() {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),73); }
        catch (Exception error) { toast("No se pudo abrir el selector de carpetas."); }
    }
    private void renderVaultUnlock() {
        body.setGravity(Gravity.CENTER); body.setPadding(dp(28),dp(24),dp(28),dp(24));
        TextView symbol=text("🔒",34,GREEN,true); symbol.setGravity(Gravity.CENTER); body.addView(symbol); gap(body,12);
        TextView title=text("Bóveda privada",25,TEXT,true); title.setGravity(Gravity.CENTER); body.addView(title); gap(body,8);
        boolean knock=SecureVault.knockMode(this);
        TextView detail=text(knock ? "Toca los cuadros en tu orden de cuatro toques. Puedes repetir un cuadro." : "Introduce tu contraseña. Después puedes elegir el código de cuatro toques en Ajustes.",14,MUTED,false);
        detail.setGravity(Gravity.CENTER); body.addView(detail); gap(body,16);
        LinearLayout gate=body;
        TextView feedback=text("",13,GREEN,false); feedback.setGravity(Gravity.CENTER);
        if (knock) {
            KnockCodeView[] pad=new KnockCodeView[1];
            pad[0]=new KnockCodeView(this,code->unlockVault(code,gate,feedback,()->pad[0].reset()));
            body.addView(pad[0],new LinearLayout.LayoutParams(-1,-2));
            cooldown(gate,feedback,blocked->pad[0].setAccepting(!blocked));
        } else {
            EditText password=input("Contraseña",true); body.addView(password,new LinearLayout.LayoutParams(-1,dp(56))); gap(body,12);
            Button unlock=button("Desbloquear",GREEN,BG); body.addView(unlock,new LinearLayout.LayoutParams(-1,dp(54)));
            unlock.setOnClickListener(v->{
                char[] pass=password.getText().toString().toCharArray(); password.setText(""); unlock.setEnabled(false);
                unlockVault(pass,gate,feedback,()->unlock.setEnabled(true));
            });
            cooldown(gate,feedback,blocked->unlock.setEnabled(!blocked));
        }
        body.addView(feedback,new LinearLayout.LayoutParams(-1,dp(40)));
        gap(body,10); body.addView(text("El acceso no se puede recuperar. Guarda tu secuencia o contraseña: sin ella no se pueden abrir los vídeos. No desinstales la app si quieres conservarlos.",12,MUTED,false));
    }
    private interface Blocked { void update(boolean blocked); }
    private void cooldown(LinearLayout gate,TextView feedback,Blocked listener) {
        handler.post(new Runnable() {
            boolean waiting;
            @Override public void run() {
                if (isDestroyed() || body!=gate || SecureVault.unlocked()) return;
                long remaining=SecureVault.remainingMillis(MainActivity.this);
                if (remaining>0) { waiting=true; feedback.setText("Demasiados intentos. Espera "+((remaining+999)/1000)+" s."); listener.update(true); handler.postDelayed(this,1000); }
                else { if (waiting) feedback.setText("Ya puedes volver a intentarlo."); listener.update(false); }
            }
        });
    }
    private void unlockVault(char[] credential,LinearLayout gate,TextView feedback,Runnable reset) {
        feedback.setText("Verificando…");
        files.execute(()->{
            boolean ok=SecureVault.unlock(this,credential); java.util.Arrays.fill(credential,'\0');
            runOnUiThread(()->{
                if (isDestroyed()) return;
                if (ok && resumed) render();
                else {
                    if (body!=gate) return;
                    reset.run(); feedback.setText(SecureVault.remainingMillis(this)>0 ? "Demasiados intentos." : "Acceso incorrecto. Inténtalo otra vez.");
                    cooldown(gate,feedback,blocked->{ for (int i=0;i<gate.getChildCount();i++) {
                        View child=gate.getChildAt(i); if (child instanceof KnockCodeView) ((KnockCodeView)child).setAccepting(!blocked);
                        else if (child instanceof Button) child.setEnabled(!blocked);
                    } });
                }
            });
        });
    }
    private void addBrandingCard() {
        LinearLayout card=card(); card.addView(text("NOMBRE E ICONO",11,MUTED,true)); gap(card,8);
        card.addView(text("Personaliza el título de la app y crea un acceso directo con tu nombre y logo en la pantalla de inicio.",13,MUTED,false)); gap(card,12);
        Button name=button("Cambiar nombre y logo",BG,TEXT); card.addView(name,new LinearLayout.LayoutParams(-1,dp(48))); name.setOnClickListener(v->brandingDialog());
        gap(card,5); card.addView(text("Android mantiene fijo el nombre del cajón de apps; el acceso directo personalizado se confirma desde el lanzador.",11,MUTED,false));
    }
    private void brandingDialog() {
        LinearLayout content=vertical(); content.setPadding(dp(8),dp(8),dp(8),0);
        EditText name=input("Nombre visible",false); name.setText(brandPrefs.getString("app_name","LumaRec")); content.addView(name); gap(content,10);
        List<String> colors=List.of("Rojo", "Rosa", "Violeta", "Blanco", "Azul", "Naranja");
        int[] swatches={Color.rgb(255,55,83),Color.rgb(255,85,145),Color.rgb(153,92,255),Color.WHITE,Color.rgb(62,155,255),Color.rgb(255,148,54)};
        Spinner color=new Spinner(this); ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,colors); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); color.setAdapter(adapter); color.setSelection(brandPrefs.getInt("logo_color",0)); content.addView(color);
        gap(content,8); Button imageButton=button("Elegir logo desde fotos",BG,TEXT); content.addView(imageButton); imageButton.setOnClickListener(v->{ try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),71); } catch (Exception error) { toast("No se pudo abrir las imágenes."); } });
        new AlertDialog.Builder(this).setTitle("Tu nombre y logo").setView(content).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(d,w)->{
            String chosen=name.getText().toString().trim(); if (chosen.isEmpty()) chosen="LumaRec";
            int choice=color.getSelectedItemPosition(); brandPrefs.edit().putString("app_name",chosen).putInt("logo_color",choice).apply(); render();
            pinShortcut(chosen,swatches[choice]);
        }).show();
    }
    private void pinShortcut(String name,int color) {
        if (Build.VERSION.SDK_INT<26) { toast("Los accesos directos personalizados requieren Android 8 o posterior."); return; }
        ShortcutManager manager=getSystemService(ShortcutManager.class);
        if (manager==null || !manager.isRequestPinShortcutSupported()) { toast("El lanzador del móvil no admite accesos directos fijados."); return; }
        Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(bitmap);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.rgb(12,8,10)); canvas.drawCircle(96,96,94,paint);
        paint.setColor(color); canvas.drawCircle(96,96,79,paint);
        paint.setColor(Color.WHITE); canvas.drawRoundRect(45,62,131,127,16,16,paint); canvas.drawCircle(88,94,22,new Paint(Paint.ANTI_ALIAS_FLAG));
        Paint lens=new Paint(Paint.ANTI_ALIAS_FLAG); lens.setColor(color); canvas.drawCircle(88,94,13,lens); canvas.drawRect(126,72,145,88,paint);
        File custom=new File(getFilesDir(),"launcher-logo.png"); if (custom.isFile()) try { Bitmap chosen=BitmapFactory.decodeFile(custom.getAbsolutePath()); if (chosen!=null) bitmap=chosen; } catch (Exception ignored) { }
        ShortcutInfo shortcut=new ShortcutInfo.Builder(this,"lumarec-custom")
                .setShortLabel(name.length()>25 ? name.substring(0,25) : name).setLongLabel(name)
                .setIcon(Icon.createWithBitmap(bitmap)).setIntent(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(this,MainActivity.class))).build();
        try { manager.requestPinShortcut(shortcut,null); toast("Confirma el acceso directo en la pantalla de inicio."); }
        catch (Exception error) { toast("No se pudo crear el acceso directo."); }
    }
    private void addNotificationCard() {
        LinearLayout card=card(); card.addView(text("NOTIFICACIÓN",11,MUTED,true)); gap(card,8);
        String title=brandPrefs.getString("notification_title","").trim();
        card.addView(text(title.isEmpty() ? "LumaRec" : title,16,TEXT,true)); gap(card,8);
        int[] modes={1,2,0};
        selector(card,"Estilo",List.of("Información y controles","Estado y detener","Compacta · solo nombre"),indexOf(modes,brandPrefs.getInt("notification_mode",1)),
                index->{ brandPrefs.edit().putInt("notification_mode",modes[index]).apply(); render(); });
        card.addView(text("La notificación detallada muestra cámara, calidad, tiempo y controles. La compacta conserva el aviso original con solo el nombre. Tocarla abre la app.",13,MUTED,false)); gap(card,12);
        Button customize=button("Cambiar nombre en la notificación",BG,TEXT); card.addView(customize,new LinearLayout.LayoutParams(-1,dp(48))); customize.setOnClickListener(v->notificationDialog());
    }
    private void notificationDialog() {
        LinearLayout content=vertical(); content.setPadding(dp(8),dp(8),dp(8),0);
        EditText title=input("Nombre visible (opcional)",false); title.setSingleLine(true); title.setText(brandPrefs.getString("notification_title","")); content.addView(title); gap(content,10);
        content.addView(text("Si lo dejas vacío, aparecerá LumaRec.",12,MUTED,false));
        new AlertDialog.Builder(this).setTitle("Nombre en la notificación").setView(content).setNegativeButton("Cancelar",null).setNeutralButton("Restablecer",(d,w)->{
            brandPrefs.edit().remove("notification_title").remove("notification_text").apply(); render();
        }).setPositiveButton("Guardar",(d,w)->{
            brandPrefs.edit().putString("notification_title",title.getText().toString().trim()).remove("notification_text").apply(); render();
        }).show();
    }
    private void addVaultCard() {
        LinearLayout vault=card(); vault.addView(text("BÓVEDA PRIVADA",11,MUTED,true)); gap(vault,8);
        boolean configured=SecureVault.configured(this);
        vault.addView(text(configured
                ? "Acceso: "+(SecureVault.knockMode(this) ? "cuatro toques" : "contraseña escrita")+". Los vídeos están cifrados en la app y se reproducen aquí. Al salir o bloquear el móvil se cierra el acceso."
                : "Protege los vídeos con cuatro toques sobre cuatro cuadros, como el Knock Code. También puedes elegir una contraseña escrita. Los archivos quedarán cifrados y fuera de la galería.",13,MUTED,false)); gap(vault,12);
        Button knock=button(configured && SecureVault.knockMode(this) ? "Cambiar código de cuatro toques" : "Usar código de cuatro toques",GREEN,BG);
        knock.setEnabled(!busy()); vault.addView(knock,new LinearLayout.LayoutParams(-1,dp(50))); knock.setOnClickListener(v->knockSetupDialog()); gap(vault,8);
        Button password=button(configured && !SecureVault.knockMode(this) ? "Cambiar contraseña escrita" : "Usar contraseña escrita",BG,TEXT);
        password.setEnabled(!busy()); vault.addView(password,new LinearLayout.LayoutParams(-1,dp(48))); password.setOnClickListener(v->passwordSetupDialog());
        gap(vault,8); vault.addView(text("El código tiene 4 toques y limita los intentos fallidos. Una contraseña larga ofrece más combinaciones.",11,MUTED,false));
        if (configured) {
            gap(vault,10); Button protect=button("Proteger vídeos anteriores de LumaRec",BG,TEXT); protect.setEnabled(!busy());
            vault.addView(protect,new LinearLayout.LayoutParams(-1,dp(48))); protect.setOnClickListener(v->protectPublicVideos());
            gap(vault,8); Button show=button("Desactivar bóveda y mostrar en galería",BG,RED); show.setEnabled(!busy());
            vault.addView(show,new LinearLayout.LayoutParams(-1,dp(48))); show.setOnClickListener(v->disableVault()); gap(vault,8);
            Button lock=button("Bloquear la app ahora",BG,TEXT); vault.addView(lock,new LinearLayout.LayoutParams(-1,dp(44)));
            lock.setOnClickListener(v->{ SecureVault.lockUi(RecordingService.state!=RecordingService.IDLE); render(); });
        }
    }
    private EditText input(String hint,boolean password) {
        EditText edit=new EditText(this); edit.setSingleLine(true); edit.setHint(hint); edit.setTextColor(TEXT); edit.setHintTextColor(MUTED);
        edit.setInputType(password ? InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (password) {
            edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); edit.setSaveEnabled(false);
            edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            edit.setFilterTouchesWhenObscured(true);
        }
        edit.setPadding(dp(14),dp(8),dp(14),dp(8)); edit.setBackground(background(CARD,12)); return edit;
    }
    private void knockSetupDialog() {
        if (busy()) return;
        LinearLayout content=vertical(); content.setPadding(dp(8),dp(8),dp(8),0);
        TextView instruction=text("Elige cuatro toques. Toca cualquiera de los cuatro cuadros en cada paso; puedes repetir un cuadro.",14,TEXT,false); content.addView(instruction); gap(content,10);
        char[][] first={null}; KnockCodeView[] pad=new KnockCodeView[1]; AlertDialog[] dialog=new AlertDialog[1];
        pad[0]=new KnockCodeView(this,sequence->{
            if (first[0]==null) {
                first[0]=sequence; instruction.setText("Ahora repite los mismos cuatro toques para confirmar."); pad[0].reset();
            } else {
                int difference=0; for (int i=0;i<4;i++) difference|=first[0][i]^sequence[i]; boolean same=difference==0;
                java.util.Arrays.fill(first[0],'\0'); first[0]=null;
                if (!same) { java.util.Arrays.fill(sequence,'\0'); instruction.setText("No coincidieron. Elige otra vez tus cuatro toques."); pad[0].reset(); }
                else { dialog[0].dismiss(); saveVaultCredential(sequence,true); }
            }
        });
        content.addView(pad[0]); gap(content,8); content.addView(text("Recuerda el orden: no hay recuperación del código. Guarda o exporta los vídeos antes de desinstalar la app.",12,MUTED,false));
        dialog[0]=new AlertDialog.Builder(this).setTitle("Código de cuatro toques").setView(content).setNegativeButton("Cancelar",null).create();
        dialog[0].setOnDismissListener(d->{ if (first[0]!=null) java.util.Arrays.fill(first[0],'\0'); pad[0].reset(); });
        showProtected(dialog[0]);
    }
    private void passwordSetupDialog() {
        if (busy()) return;
        LinearLayout content=vertical(); content.setPadding(dp(8),dp(8),dp(8),0);
        EditText first=input("Nueva contraseña · mínimo 6 caracteres",true); content.addView(first); gap(content,10);
        EditText second=input("Repetir contraseña",true); content.addView(second); gap(content,12);
        content.addView(text("No hay recuperación. Una contraseña larga protege mejor frente a intentos de adivinarla.",12,MUTED,false));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Contraseña de la bóveda").setView(content).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            char[] password=first.getText().toString().toCharArray(), confirm=second.getText().toString().toCharArray();
            boolean valid=password.length>=6 && java.util.Arrays.equals(password,confirm); java.util.Arrays.fill(confirm,'\0');
            if (!valid) { java.util.Arrays.fill(password,'\0'); second.setText(""); toast("Usa al menos 6 caracteres y repite la misma contraseña."); return; }
            first.setText(""); second.setText(""); dialog.dismiss(); saveVaultCredential(password,false);
        }));
        dialog.setOnDismissListener(d->{ first.setText(""); second.setText(""); }); showProtected(dialog);
    }
    private void saveVaultCredential(char[] credential,boolean knock) {
        boolean creating=!SecureVault.configured(this);
        vaultWorking=true; render();
        toast(creating ? "Preparando la bóveda y protegiendo tus vídeos…" : "Guardando el nuevo acceso…");
        files.execute(()->{
            String problem=null; int moved=0; byte[] key=null;
            try {
                if (creating) {
                    SecureVault.setup(this,credential,knock); key=SecureVault.key();
                    moved=VideoLibrary.hideExisting(this,key);
                    try { QuickVault.ensure(this,key); } catch (Exception ignored) { }
                } else SecureVault.setCredential(this,credential,knock);
            } catch (Exception error) {
                problem=creating && SecureVault.configured(this)
                        ? "La bóveda está activa. No se pudieron ocultar todos los vídeos anteriores; vuelve a intentarlo desde Ajustes."
                        : "No se pudo guardar el acceso. Mantén la app abierta y el móvil desbloqueado e inténtalo otra vez.";
            } finally { java.util.Arrays.fill(credential,'\0'); if (key!=null) java.util.Arrays.fill(key,(byte)0); }
            final String result=problem; final int count=moved;
            runOnUiThread(()->{
                vaultWorking=false; if (isDestroyed()) return; render();
                toast(result!=null ? result : creating ? "Bóveda activada. "+count+" vídeo(s) protegido(s)." : knock ? "Ya puedes entrar con tus cuatro toques." : "Contraseña actualizada.");
            });
        });
    }
    private void protectPublicVideos() {
        byte[] key=SecureVault.key(); if (key==null || !SecureVault.unlocked()) { if (key!=null) java.util.Arrays.fill(key,(byte)0); toast("Desbloquea la bóveda."); return; }
        vaultWorking=true; render();
        files.execute(()->{
            String result;
            try { result=VideoLibrary.hideExisting(this,key)+" vídeo(s) cifrado(s) y retirado(s) de la galería."; }
            catch (Exception error) { result="No se pudieron proteger todos los vídeos. Libera espacio y vuelve a intentarlo."; }
            finally { java.util.Arrays.fill(key,(byte)0); }
            final String message=result; runOnUiThread(()->{ vaultWorking=false; if (!isDestroyed()) { render(); toast(message); } });
        });
    }
    private void disableVault() {
        if (busy() || !SecureVault.unlocked()) return;
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("¿Desactivar la bóveda privada?")
                .setMessage("Se descifrarán los vídeos y se copiarán a Movies/LumaRec. Después quedarán visibles en la galería.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Descifrar y mostrar",(d,w)->{
                    byte[] key=SecureVault.key(); if (key==null || !SecureVault.unlocked()) { if (key!=null) java.util.Arrays.fill(key,(byte)0); render(); return; }
                    vaultWorking=true; render();
                    files.execute(()->{ String error=null; int count=0;
                    try { if (key==null) throw new SecurityException("La bóveda está bloqueada."); count=VideoLibrary.showVault(this,key); SecureVault.disable(this); }
                    catch (Exception problem) { error="No se pudieron exportar todos los vídeos. Los archivos que faltan siguen cifrados."; }
                    finally { if (key!=null) java.util.Arrays.fill(key,(byte)0); }
                    final String resultError=error; final int resultCount=count;
                    runOnUiThread(()->{ vaultWorking=false; if (isDestroyed()) return; render(); toast(resultError==null ? resultCount+" vídeo(s) devuelto(s) a la galería." : resultError); });
                }); }).create();
        showProtected(dialog);
    }
    private int indexOf(int[] values,int value) { for (int i=0;i<values.length;i++) if (values[i]==value) return i; return 0; }
    private String storageText() {
        try { long free=new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes(); return VideoLibrary.size(free)+" disponibles  ·  "+(SecureVault.configured(this) ? "Bóveda interna cifrada" : "Movies/LumaRec"); }
        catch (Exception ignored) { return SecureVault.configured(this) ? "Destino: bóveda interna cifrada" : "Destino: Movies/LumaRec"; }
    }
    private void renderLibrary() {
        body.addView(text("Tus momentos, aquí.",26,TEXT,true)); gap(body,5); body.addView(text("Reproduce, comparte y gestiona tus grabaciones.",14,MUTED,false)); gap(body,20);
        LinearLayout tools=card(); EditText search=input("Buscar por nombre o fecha",false); search.setSingleLine(true); search.setText(libraryQuery); tools.addView(search); gap(tools,8);
        selector(tools,"Ordenar",List.of("Más recientes","Más antiguos","Nombre","Mayor tamaño"),Math.max(0,Math.min(3,prefs.getInt("library_sort",0))),
                value->{ prefs.edit().putInt("library_sort",value).apply(); renderLibraryResults(); });
        Switch favorites=new Switch(this); favorites.setText("Solo favoritos"); favorites.setTextColor(TEXT); favorites.setChecked(libraryFavorites); tools.addView(favorites); gap(tools,8);
        favorites.setOnCheckedChangeListener((v,value)->{ libraryFavorites=value; renderLibraryResults(); });
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { libraryQuery=s.toString(); handler.removeCallbacks(filterLibrary); handler.postDelayed(filterLibrary,180); }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        Button folder=button("Carpeta de exportación: "+ExportFolder.name(this),BG,TEXT); tools.addView(folder); folder.setOnClickListener(v->chooseExportFolder()); gap(tools,8);
        Button batch=button("Seleccionar vídeos para borrar",BG,MUTED); tools.addView(batch); batch.setOnClickListener(v->deleteSelectionDialog());
        LinearLayout destination=body;
        TextView loading=text("Cargando vídeos…",14,MUTED,false); body.addView(loading);
        libraryResults=vertical(); body.addView(libraryResults); libraryVideos=new ArrayList<>();
        files.execute(()->{
            List<VideoLibrary.Video> videos;
            try { videos=VideoLibrary.list(this); } catch (Exception ignored) { videos=new ArrayList<>(); }
            List<VideoLibrary.Video> result=videos;
            runOnUiThread(()->{
                if (isDestroyed() || tab!=1 || body!=destination) return;
                body.removeView(loading);
                libraryVideos=result; renderLibraryResults();
                gap(body,5); body.addView(text(SecureVault.configured(MainActivity.this) ? "Los vídeos privados están cifrados en la bóveda y fuera de la galería.\n"+storageText() : "Carpeta: Movies/LumaRec\n"+storageText(),12,MUTED,false));
            });
        });
    }
    private List<VideoLibrary.Video> filteredVideos() {
        List<VideoLibrary.Video> result=new ArrayList<>(); String query=libraryQuery.trim().toLowerCase(Locale.getDefault());
        for (VideoLibrary.Video video:libraryVideos) {
            if (libraryFavorites && !VideoNotes.favorite(this,video)) continue;
            String search=VideoNotes.name(this,video)+" "+new SimpleDateFormat("d MMM yyyy HH:mm",Locale.getDefault()).format(new Date(video.date));
            if (!query.isEmpty() && !search.toLowerCase(Locale.getDefault()).contains(query)) continue;
            result.add(video);
        }
        int sort=prefs.getInt("library_sort",0);
        result.sort((a,b)->sort==1 ? Long.compare(a.date,b.date) : sort==2 ? VideoNotes.name(this,a).compareToIgnoreCase(VideoNotes.name(this,b)) : sort==3 ? Long.compare(b.bytes,a.bytes) : Long.compare(b.date,a.date));
        return result;
    }
    private void renderLibraryResults() {
        if (libraryResults==null || tab!=1 || SecureVault.configured(this) && !SecureVault.unlocked()) return;
        libraryResults.removeAllViews(); List<VideoLibrary.Video> videos=filteredVideos(); long total=0; for (VideoLibrary.Video video:videos) total+=video.bytes;
        libraryResults.addView(text(videos.size()+" de "+libraryVideos.size()+" vídeos  ·  "+VideoLibrary.size(total),12,GREEN,true)); gap(libraryResults,12);
        if (videos.isEmpty()) {
            libraryResults.addView(text(libraryVideos.isEmpty() ? "Tu primera grabación te espera." : "No hay vídeos para estos filtros.",16,TEXT,true)); gap(libraryResults,12);
            if (!libraryQuery.isEmpty() || libraryFavorites) {
                Button clear=button("Limpiar filtros",BG,TEXT); libraryResults.addView(clear); clear.setOnClickListener(v->{ libraryQuery=""; libraryFavorites=false; render(); });
            }
        } else for (VideoLibrary.Video video:videos) addVideo(libraryResults,video);
    }
    private void addVideo(LinearLayout parent,VideoLibrary.Video video) {
        LinearLayout item=vertical(); item.setPadding(dp(18),dp(18),dp(18),dp(18)); item.setBackground(background(CARD,20));
        LinearLayout.LayoutParams itemSize=new LinearLayout.LayoutParams(-1,-2); itemSize.bottomMargin=dp(14); parent.addView(item,itemSize);
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumbnail=new ImageView(this); thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP); thumbnail.setBackground(background(BG,12));
        thumbnail.setContentDescription("Reproducir vídeo"); row.addView(thumbnail,new LinearLayout.LayoutParams(dp(90),dp(72))); thumbnail.setOnClickListener(v->play(video));
        LinearLayout info=vertical(); info.setPadding(dp(12),0,0,0);
        info.addView(text(new SimpleDateFormat("d MMM yyyy · HH:mm",Locale.getDefault()).format(new Date(video.date)),14,TEXT,true)); gap(info,5);
        info.addView(text(VideoLibrary.time(video.duration)+"  /  "+VideoLibrary.size(video.bytes)+(video.hidden() ? "  ·  🔒 Privado" : ""),12,GREEN,false));
        TextView filename=text((VideoNotes.favorite(this,video) ? "★  " : "")+VideoNotes.name(this,video),11,MUTED,false); filename.setMaxLines(2); info.addView(filename); row.addView(info,new LinearLayout.LayoutParams(0,-2,1)); item.addView(row); gap(item,12);
        LinearLayout actions=new LinearLayout(this); String[] labels={"Reproducir","Compartir","Borrar"};
        for (int i=0;i<3;i++) { final int action=i; Button b=button(labels[i],BG,i==2 ? RED : TEXT); b.setTextSize(11); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1); if (i<2) p.rightMargin=dp(6); actions.addView(b,p); b.setOnClickListener(v->{ if (action==0) play(video); else if (action==1) share(video); else delete(video); }); }
        item.addView(actions);
        gap(item,6); Button more=button("Favorito · Nombre · Exportar · Detalles",BG,MUTED); more.setTextSize(11); item.addView(more,new LinearLayout.LayoutParams(-1,dp(40))); more.setOnClickListener(v->videoOptions(video));
        if (video.hidden()) {
            thumbnail.setImageResource(R.drawable.ic_lock); thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE); thumbnail.setPadding(dp(22),dp(17),dp(22),dp(17));
            return;
        }
        files.execute(()->{ try {
            android.graphics.Bitmap image;
            image=getContentResolver().loadThumbnail(video.uri,new Size(220,180),null);
            runOnUiThread(()->{ if (!isDestroyed()) thumbnail.setImageBitmap(image); });
        } catch (Exception ignored) { } });
    }
    private void videoOptions(VideoLibrary.Video video) {
        if (exportWorking) { toast("Espera a que termine la exportación."); return; }
        if (video.hidden() && !SecureVault.unlocked()) { render(); return; }
        String[] options={VideoNotes.favorite(this,video) ? "Quitar de favoritos" : "Añadir a favoritos","Cambiar nombre en la biblioteca","Exportar copia a una carpeta","Detalles del vídeo"};
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(VideoNotes.name(this,video)).setItems(options,(d,index)->{
            if (video.hidden() && !SecureVault.unlocked()) { render(); return; }
            if (index==0) { VideoNotes.favorite(this,video,!VideoNotes.favorite(this,video)); renderLibraryResults(); }
            else if (index==1) renameVideo(video);
            else if (index==2) exportVideo(video);
            else {
                AlertDialog details=new AlertDialog.Builder(this).setTitle(VideoNotes.name(this,video))
                        .setMessage(new SimpleDateFormat("d MMM yyyy · HH:mm:ss",Locale.getDefault()).format(new Date(video.date))+"\nDuración: "+VideoLibrary.time(video.duration)+"\nTamaño: "+VideoLibrary.size(video.bytes)+"\nUbicación: "+(video.hidden() ? "Bóveda cifrada" : "Galería · Movies/LumaRec"))
                        .setPositiveButton("Cerrar",null).create(); showProtected(details);
            }
        }).create(); showProtected(dialog);
    }
    private void renameVideo(VideoLibrary.Video video) {
        EditText name=input("Nombre en la biblioteca",false); name.setText(VideoNotes.name(this,video)); name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nombre del vídeo").setView(name).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try { VideoNotes.rename(this,video,name.getText().toString()); dialog.dismiss(); renderLibraryResults(); }
            catch (Exception error) { toast("Escribe un nombre y mantén la bóveda desbloqueada."); }
        })); showProtected(dialog);
    }
    private void exportVideo(VideoLibrary.Video video) {
        if (ExportFolder.tree(this)==null) { toast("Elige la carpeta y después vuelve a exportar el vídeo."); chooseExportFolder(); return; }
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("¿Exportar una copia?")
                .setMessage("Destino: "+ExportFolder.name(this)+".\n\n"+(video.hidden() ? "La copia se guardará sin cifrar, fuera de la bóveda, y otras apps podrán acceder a ella. " : "")+"El original se conservará.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Exportar copia",(d,w)->{
                    if (exportWorking) return;
                    byte[] key=video.hidden() ? SecureVault.key() : null;
                    if (video.hidden() && (key==null || !SecureVault.unlocked())) { if (key!=null) java.util.Arrays.fill(key,(byte)0); render(); return; }
                    exportWorking=true; toast("Exportando la copia autorizada…");
                    files.execute(()->{
                        String problem=null;
                        try { ExportFolder.save(this,video,key); }
                        catch (Exception error) { problem=error instanceof ExportFolder.IncompleteExportException ? "La copia quedó incompleta y no se pudo borrar. Revísala y elimínala en la carpeta de destino." : "No se pudo completar la exportación. Revisa el espacio y los permisos de la carpeta."; }
                        finally { if (key!=null) java.util.Arrays.fill(key,(byte)0); }
                        final String result=problem;
                        runOnUiThread(()->{ exportWorking=false; if (!isDestroyed()) { render(); toast(result==null ? "Copia guardada en "+ExportFolder.name(this)+"." : result); } });
                    });
                }).create(); showProtected(dialog);
    }
    private void deleteSelectionDialog() {
        if (exportWorking || libraryVideos.isEmpty()) { toast(exportWorking ? "Espera a que termine la exportación." : "No hay vídeos para seleccionar."); return; }
        List<VideoLibrary.Video> videos=filteredVideos(); String[] names=new String[videos.size()]; boolean[] selected=new boolean[videos.size()];
        for (int i=0;i<names.length;i++) names[i]=VideoNotes.name(this,videos.get(i));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Seleccionar para borrar").setMultiChoiceItems(names,selected,(d,index,value)->selected[index]=value)
                .setNegativeButton("Cancelar",null).setPositiveButton("Continuar",(d,w)->{
                    List<VideoLibrary.Video> chosen=new ArrayList<>(); for (int i=0;i<selected.length;i++) if (selected[i]) chosen.add(videos.get(i));
                    if (chosen.isEmpty()) { toast("Selecciona al menos un vídeo."); return; }
                    AlertDialog confirm=new AlertDialog.Builder(this).setTitle("¿Borrar "+chosen.size()+" vídeo(s)?").setMessage("Se eliminarán del móvil. No se pueden recuperar.")
                            .setNegativeButton("Cancelar",null).setPositiveButton("Borrar",(c,which)->files.execute(()->{
                                int count=0; for (VideoLibrary.Video video:chosen) try { if (VideoLibrary.delete(this,video)) { VideoNotes.forget(this,video); count++; } } catch (Exception ignored) { }
                                final int deleted=count; runOnUiThread(()->{ if (!isDestroyed()) { render(); toast(deleted+" de "+chosen.size()+" vídeo(s) eliminado(s)."); } });
                            })).create(); showProtected(confirm);
                }).create(); showProtected(dialog);
    }
    private void play(VideoLibrary.Video video) {
        if (!video.hidden()) { playUri(video.uri); return; }
        if (!SecureVault.unlocked()) { render(); return; }
        startActivity(new Intent(this,PrivatePlayerActivity.class).putExtra("video",video.vaultId));
    }
    private void playUri(Uri uri) {
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(intent); } catch (ActivityNotFoundException error) { toast("Instala un reproductor de vídeo para abrir el MP4."); }
    }
    private void share(VideoLibrary.Video video) {
        if (!video.hidden()) { shareUri(video.uri); return; }
        if (!SecureVault.unlocked()) { render(); return; }
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("¿Compartir una copia de este vídeo?")
                .setMessage("La app que elijas recibirá una copia sin cifrar y podrá guardarla. El acceso temporal caduca a los 15 minutos; las copias que guarde la otra app quedan bajo su control.")
                .setNegativeButton("Mantener privado",null).setPositiveButton("Compartir copia",(d,w)->{
                    byte[] key=SecureVault.key(); if (key==null || !SecureVault.unlocked()) { if (key!=null) java.util.Arrays.fill(key,(byte)0); render(); return; }
                    toast("Preparando la copia autorizada…");
                    files.execute(()->{
                        try {
                            Uri uri=VideoLibrary.exportForShare(this,video,key);
                            runOnUiThread(()->{ if (!isDestroyed() && resumed && SecureVault.unlocked()) shareUri(uri); else VideoLibrary.discardExport(this,uri); });
                        } catch (Exception error) { runOnUiThread(()->{ if (!isDestroyed()) toast("No se pudo preparar el vídeo para compartirlo. Revisa el espacio libre."); }); }
                        finally { java.util.Arrays.fill(key,(byte)0); }
                    });
                }).create();
        showProtected(dialog);
    }
    private void shareUri(Uri uri) {
        Intent intent=new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(android.content.ClipData.newRawUri("Vídeo compartido",uri));
        startActivity(Intent.createChooser(intent,"Compartir vídeo"));
    }
    private void delete(VideoLibrary.Video video) {
        if (exportWorking) { toast("Espera a que termine la exportación."); return; }
        new AlertDialog.Builder(this).setTitle("¿Borrar este vídeo?").setMessage("El archivo se eliminará del móvil. Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Borrar",(dialog,which)->files.execute(()->{
                    boolean ok=VideoLibrary.delete(this,video); if (ok) VideoNotes.forget(this,video); runOnUiThread(()->{ if (ok) render(); else toast("No se pudo borrar el vídeo."); });
                })).show();
    }
    private void updateControls() {
        if (record==null) return;
        int state=RecordingService.state;
        boolean active=state!=RecordingService.IDLE;
        record.setText(vaultWorking ? "Protegiendo la bóveda…" : state==RecordingService.SAVING ? "Guardando vídeo…" : state==RecordingService.PREPARING ? "Cancelar inicio" : active ? "■  Detener y guardar" : countdown>0 ? "Cancelar temporizador" : launching ? "Preparando…" : "●  Iniciar grabación");
        record.setBackground(background(active ? RED : GREEN,16)); record.setEnabled(!vaultWorking && selected!=null && state!=RecordingService.SAVING && (!launching || countdown>0));
        pause.setVisibility(state==RecordingService.RECORDING || state==RecordingService.PAUSED ? View.VISIBLE : View.GONE);
        pause.setText(state==RecordingService.PAUSED ? "Reanudar grabación" : "Pausar grabación");
        timer.setText(countdown>0 ? String.format(Locale.US,"Inicio en %d",countdown) : VideoLibrary.time(RecordingService.elapsed()));
        status.setText(state==RecordingService.RECORDING ? "●  GRABANDO" : state==RecordingService.PAUSED ? "●  EN PAUSA" : state==RecordingService.PREPARING ? "●  PREPARANDO" : state==RecordingService.SAVING ? "●  GUARDANDO" : "●  LISTA PARA GRABAR");
        status.setTextColor(state==RecordingService.RECORDING ? RED : GREEN);
        if (active) subtitle.setText(RecordingService.description);
    }
    private void askToRecord() {
        if (selected==null || !resumed || vaultWorking || (SecureVault.configured(this) && !SecureVault.unlocked())) return;
        List<String> permissions=new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.CAMERA);
        if (prefs.getBoolean("audio",true) && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) { permissionForRecording=true; requestPermissions(permissions.toArray(new String[0]),20); return; }
        if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            new AlertDialog.Builder(this).setTitle("Activa las notificaciones").setMessage("La grabación necesita una notificación visible de LumaRec.")
                    .setNegativeButton("Cancelar",null).setPositiveButton("Abrir ajustes",(d,w)->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))).show(); return;
        }
        launching=true; countdown=prefs.getInt("delay",0); render();
        if (countdown>0) handler.postDelayed(countdownTick,1000); else launchRecording();
    }
    private final Runnable countdownTick=new Runnable() {
        @Override public void run() {
            if (!resumed || countdown<=0) return;
            countdown--; updateControls(); if (countdown>0) handler.postDelayed(this,1000); else launchRecording();
        }
    };
    private void launchRecording() {
        handler.postDelayed(()->{
            if (!resumed || vaultWorking || (SecureVault.configured(this) && !SecureVault.unlocked())) { launching=false; return; }
            Intent intent=RecordRequest.create(this,selected,quality,fps);
            launching=false;
            try { startForegroundService(intent); } catch (Exception error) { toast("No se pudo iniciar. Revisa los permisos y vuelve a intentarlo."); render(); }
        },350);
    }
    private void command(String action) { startService(new Intent(this,RecordingService.class).setAction(action)); }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==73) {
            if (result==RESULT_OK && data!=null) {
                try { ExportFolder.remember(this,data); toast("Carpeta de exportación guardada."); }
                catch (Exception error) { toast("No se pudo usar esa carpeta. Elige una que permita guardar vídeos."); }
                render();
            }
            return;
        }
        if ((request!=71 && request!=72) || result!=RESULT_OK || data==null || data.getData()==null) return;
        final boolean widgetImage=request==72;
        Uri imageUri=data.getData();
        files.execute(()->{
            try {
                BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
                try (java.io.InputStream input=getContentResolver().openInputStream(imageUri)) { BitmapFactory.decodeStream(input,null,bounds); }
                bounds.inSampleSize=1; int largest=Math.max(bounds.outWidth,bounds.outHeight);
                while (largest/bounds.inSampleSize>512) bounds.inSampleSize*=2;
                bounds.inJustDecodeBounds=false; Bitmap original;
                try (java.io.InputStream input=getContentResolver().openInputStream(imageUri)) { original=BitmapFactory.decodeStream(input,null,bounds); }
                if (original==null) throw new java.io.IOException("La imagen no se puede abrir.");
                int crop=Math.min(original.getWidth(),original.getHeight());
                Bitmap square=Bitmap.createBitmap(original,(original.getWidth()-crop)/2,(original.getHeight()-crop)/2,crop,crop);
                Bitmap icon=Bitmap.createScaledBitmap(square,192,192,true);
                File dest=new File(getFilesDir(),widgetImage ? "widget-logo.png" : "launcher-logo.png");
                try (java.io.FileOutputStream output=new java.io.FileOutputStream(dest)) { icon.compress(Bitmap.CompressFormat.PNG,100,output); }
                if (square!=original) square.recycle(); original.recycle(); icon.recycle();
                runOnUiThread(()->{
                    if (isDestroyed()) return;
                    if (widgetImage) {
                        QuickRecordWidget.prefs(this).edit().putInt("icon",QuickRecordWidget.CUSTOM_ICON).apply(); QuickRecordWidget.updateAll(this); render(); toast("Imagen del widget guardada.");
                    } else { render(); pinShortcut(brandPrefs.getString("app_name","LumaRec"),Color.rgb(255,55,83)); toast("Logo guardado. Confirma el nuevo icono en la pantalla de inicio."); }
                });
            } catch (Exception error) { runOnUiThread(()->toast("No se pudo usar esa imagen como logo.")); }
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        boolean all=true; for (int grant:grants) if (grant!=PackageManager.PERMISSION_GRANTED) all=false;
        render();
        if (all && grants.length>0 && permissionForRecording) handler.postDelayed(this::askToRecord,250);
        else if (!all) toast("Permiso no concedido. Puedes activarlo en Ajustes de la app.");
        permissionForRecording=false;
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
}
