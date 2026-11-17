package com.lumarec.pro;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Range;
import android.view.Surface;
import java.io.File;
import java.util.Collections;

public final class RecordingService extends Service {
    public static final String START="com.lumarec.pro.START", STOP="com.lumarec.pro.STOP", PAUSE="com.lumarec.pro.PAUSE", UPDATE="com.lumarec.pro.UPDATE", CONTROLS="com.lumarec.pro.CONTROLS";
    static final int IDLE=0, PREPARING=1, RECORDING=2, PAUSED=3, SAVING=4;
    static volatile int state=IDLE;
    static volatile long accumulated=0, segmentStart=0, revision=0;
    static volatile String message="", lastVideo="", description="";
    static volatile String controlMessage="";
    static volatile boolean highSpeedSession;
    static long elapsed() { return accumulated+(state==RECORDING ? SystemClock.elapsedRealtime()-segmentStart : 0); }
    private static final String CHANNEL="recording";
    private static final int NOTIFICATION=10;
    private HandlerThread thread;
    private Handler worker;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private File recordingFile;
    private Uri uri;
    private PowerManager.WakeLock wakeLock;
    private boolean started, finished;
    private boolean privateRecording;
    private byte[] recordingKey, sealedRecordingKey;
    private long durationLimit;
    private Intent configuration;
    private CaptureRequest.Builder recordingRequest;
    private Surface recordingSurface;
    private android.hardware.camera2.CameraCharacteristics recordingCharacteristics;
    private java.util.Map<String,Object> appliedControls;

    @Override public void onCreate() {
        super.onCreate();
        thread=new HandlerThread("LumaRecCamera"); thread.start(); worker=new Handler(thread.getLooper());
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Grabación de vídeo",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Aviso de LumaRec mientras el servicio de cámara está activo");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent==null) { stopSelf(); return START_NOT_STICKY; }
        String action=intent.getAction();
        if (STOP.equals(action)) { if (state==IDLE) { QuickRecordWidget.updateAll(this); stopSelf(); } else worker.post(()->finish(null)); return START_NOT_STICKY; }
        if (PAUSE.equals(action)) { if (state!=IDLE) worker.post(this::togglePause); else stopSelf(); return START_NOT_STICKY; }
        if (CONTROLS.equals(action)) { if (state==RECORDING || state==PAUSED) worker.post(this::updateCameraControls); else if (state==IDLE) stopSelf(); return START_NOT_STICKY; }
        if (!START.equals(action) || state!=IDLE) return START_NOT_STICKY;
        getSharedPreferences("recorder",MODE_PRIVATE).edit().putBoolean("ae_lock",false).putBoolean("awb_lock",false).apply();
        configuration=new Intent(intent); finished=false; started=false; accumulated=0;
        privateRecording=SecureVault.configured(this);
        state=PREPARING; message=""; lastVideo=""; controlMessage=""; highSpeedSession=false;
        description=intent.getStringExtra("label")+" · "+intent.getIntExtra("height",1080)+"p · "+intent.getIntExtra("fps",30)+" fps"+(intent.getBooleanExtra("hevc",false) ? " · H.265" : " · H.264");
        try {
            int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (intent.getBooleanExtra("audio",true)) type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION,notification(),type);
            PowerManager power=getSystemService(PowerManager.class);
            wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LumaRec:recording");
            wakeLock.acquire();
            broadcast(); worker.post(this::begin);
        } catch (Exception error) {
            message="No se pudo iniciar la cámara. Abre la app, revisa sus permisos y vuelve a intentarlo.";
            state=IDLE; releaseWakeLock(); broadcast(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
        return START_NOT_STICKY;
    }
    private void begin() {
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("Falta el permiso de cámara.");
            boolean audio=configuration.getBooleanExtra("audio",true);
            if (audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("Falta el permiso de micrófono.");
            File dir=new File(getFilesDir(),"recordings");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("No se pudo preparar el almacenamiento.");
            new File(dir,".nomedia").createNewFile();
            long free=new StatFs(dir.getAbsolutePath()).getAvailableBytes();
            if (free<256L*1024*1024) throw new IllegalStateException("Libera espacio: se necesitan al menos 256 MB.");
            if (privateRecording && recordingKey==null) {
                if (configuration.getBooleanExtra("quick_widget",false) && QuickVault.ready(this)) {
                    recordingKey=QuickSeal.randomKey(); sealedRecordingKey=QuickVault.seal(this,recordingKey);
                } else {
                    recordingKey=SecureVault.key();
                    if (recordingKey==null) throw new IllegalStateException("Abre LumaRec y prepara el widget desde Ajustes para guardar en la bóveda.");
                }
            }
            recordingFile=new File(dir,java.util.UUID.randomUUID().toString()+".recording.mp4");
            boolean chosenHevc=configuration.getBooleanExtra("hevc",false);
            try { prepareRecorder(chosenHevc,free); }
            catch (Exception h264Failure) {
                int width=configuration.getIntExtra("width",1920), height=configuration.getIntExtra("height",1080);
                int fps=configuration.getIntExtra("fps",30);
                boolean canRetryAsHevc=fps==60 && height>=2160 && !chosenHevc
                        && (CameraCatalog.encoderSupports(true,width,height,fps) || "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER));
                if (!canRetryAsHevc) throw h264Failure;
                if (recorder!=null) { try { recorder.reset(); recorder.release(); } catch (Exception ignored) { } recorder=null; }
                if (recordingFile.exists()) recordingFile.delete();
                recordingFile=new File(dir,java.util.UUID.randomUUID().toString()+".recording.mp4");
                configuration.putExtra("hevc",true).putExtra("automaticHevc",true)
                        .putExtra("bitrate",CameraControls.customBitrate(getSharedPreferences("recorder",MODE_PRIVATE),CameraCatalog.bitrate(new CameraCatalog.Quality(width,height,configuration.getIntExtra("bitrate",50_000_000)),fps,true,true),true,width,height,fps));
                description=description.replace("H.264","H.265");
                getSharedPreferences("recorder",MODE_PRIVATE).edit().putBoolean("hevc",true).apply();
                prepareRecorder(true,free);
            }
            openCamera(0);
        } catch (Exception error) { finish(friendly(error)); }
    }
    private void prepareRecorder(boolean hevc,long free) throws Exception {
        boolean audio=configuration.getBooleanExtra("audio",true);
        recorder=Build.VERSION.SDK_INT>=31 ? new MediaRecorder(this) : new MediaRecorder();
        if (audio) {
            int source=configuration.getIntExtra("audio_source",MediaRecorder.AudioSource.MIC);
            if (source!=MediaRecorder.AudioSource.CAMCORDER && source!=MediaRecorder.AudioSource.UNPROCESSED) source=MediaRecorder.AudioSource.MIC;
            recorder.setAudioSource(source);
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(configuration.getIntExtra("width",1920),configuration.getIntExtra("height",1080));
        recorder.setVideoFrameRate(configuration.getIntExtra("fps",30));
        recorder.setVideoEncodingBitRate(configuration.getIntExtra("bitrate",16_000_000));
        recorder.setOrientationHint(configuration.getIntExtra("orientation",90));
        if (audio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            int kbps=configuration.getIntExtra("audio_kbps",192);
            if (kbps!=64 && kbps!=128 && kbps!=192 && kbps!=256) kbps=192;
            recorder.setAudioSamplingRate(48000); recorder.setAudioEncodingBitRate(kbps*1000); recorder.setAudioChannels(1);
        }
        recorder.setOutputFile(recordingFile.getAbsolutePath());
        long cap=configuration.getLongExtra("maxBytes",0), reserve=free-192L*1024*1024;
        long safeLimit=privateRecording ? reserve/2 : reserve;
        if (safeLimit<64L*1024*1024) throw new IllegalStateException("Libera espacio para guardar el vídeo.");
        recorder.setMaxFileSize(cap>0 ? Math.min(privateRecording ? cap*2 : cap,safeLimit) : safeLimit);
        durationLimit=configuration.getLongExtra("duration",0)*1000;
        recorder.setOnInfoListener((mr,what,extra)->{
            if (what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED || what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)
                worker.post(()->finish("Se alcanzó el límite configurado o el espacio disponible."));
        });
        recorder.setOnErrorListener((mr,what,extra)->worker.post(()->finish("La cámara interrumpió la grabación. Revisa el vídeo guardado.")));
        recorder.prepare();
    }
    private void openCamera(int attempt) {
        if (finished) return;
        try {
            getSystemService(CameraManager.class).openCamera(configuration.getStringExtra("camera"),new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (finished || state!=PREPARING) { device.close(); return; }
                    camera=device; createSession();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close(); finish("La cámara se desconectó. Revisa el vídeo guardado.");
                }
                @Override public void onError(CameraDevice device,int error) {
                    device.close();
                    if (finished) return;
                    if ((error==ERROR_CAMERA_IN_USE || error==ERROR_MAX_CAMERAS_IN_USE) && attempt<2) {
                        worker.postDelayed(()->openCamera(attempt+1),700); return;
                    }
                    finish("No se pudo abrir la cámara. Cierra otras apps que la estén usando y vuelve a intentarlo.");
                }
            },worker);
        } catch (Exception error) { finish(friendly(error)); }
    }
    private void createSession() {
        try {
            Surface surface=recorder.getSurface(); recordingSurface=surface;
            CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(surface);
            Range<Integer> highSpeedRange=null;
            int width=configuration.getIntExtra("width",1920), height=configuration.getIntExtra("height",1080), selectedFps=configuration.getIntExtra("fps",30);
            CameraCatalog.Quality chosenQuality=new CameraCatalog.Quality(width,height,configuration.getIntExtra("bitrate",16_000_000));
            for (CameraCatalog.Camera item:CameraCatalog.scan(this)) if (item.id.equals(configuration.getStringExtra("camera"))) {
                recordingCharacteristics=item.characteristics;
                highSpeedRange=CameraCatalog.highSpeedRange(item,chosenQuality,selectedFps);
                Range<Integer> range=highSpeedRange!=null ? highSpeedRange : CameraCatalog.aeRange(item,selectedFps);
                if (range!=null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,range);
                int[] af=item.characteristics.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                if (af!=null) for (int mode:af) if (mode==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                if (item.stabilization) request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        configuration.getBooleanExtra("stabilization",false) ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            }
            highSpeedSession=highSpeedRange!=null;
            if (!highSpeedSession && recordingCharacteristics!=null) CameraControls.apply(request,recordingCharacteristics,getSharedPreferences("recorder",MODE_PRIVATE));
            recordingRequest=request;
            appliedControls=CameraControls.snapshot(getSharedPreferences("recorder",MODE_PRIVATE));
            final Range<Integer> selectedHighSpeedRange=highSpeedRange;
            CameraCaptureSession.StateCallback sessionCallback=new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (finished || camera==null) { configured.close(); return; }
                    session=configured;
                    try {
                        if (selectedHighSpeedRange!=null && session instanceof android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession) {
                            android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession highSpeed=(android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession)session;
                            java.util.List<CaptureRequest> requests=highSpeed.createHighSpeedRequestList(request.build());
                            highSpeed.setRepeatingBurst(requests,null,worker);
                        } else session.setRepeatingRequest(request.build(),null,worker);
                        recorder.start(); started=true; segmentStart=SystemClock.elapsedRealtime(); state=RECORDING;
                        notifyState(); worker.post(tick);
                    } catch (Exception error) { if (!retryAsHevc(error)) finish(friendly(error)); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    failed.close(); Exception error=new java.io.IOException("No se pudo crear la sesión de cámara.");
                    if (!retryAsHevc(error)) finish(friendly(error));
                }
            };
            if (selectedHighSpeedRange!=null) camera.createConstrainedHighSpeedCaptureSession(Collections.singletonList(surface),sessionCallback,worker);
            else camera.createCaptureSession(Collections.singletonList(surface),sessionCallback,worker);
        } catch (Exception error) { if (!retryAsHevc(error)) finish(friendly(error)); }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private void updateCameraControls() {
        if (finished || session==null || camera==null || recordingRequest==null || recordingCharacteristics==null) return;
        if (highSpeedSession) { controlMessage="Este perfil de alta velocidad no permite controles manuales durante la grabación."; broadcast(); return; }
        android.content.SharedPreferences settings=getSharedPreferences("recorder",MODE_PRIVATE);
        try {
            CaptureRequest previous=recordingRequest.build();
            CaptureRequest.Builder next=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD); next.addTarget(recordingSurface);
            for (CaptureRequest.Key key:previous.getKeys()) next.set(key,previous.get(key));
            CameraControls.apply(next,recordingCharacteristics,settings);
            session.setRepeatingRequest(next.build(),null,worker);
            recordingRequest=next; appliedControls=CameraControls.snapshot(settings); controlMessage="";
        } catch (Exception error) {
            if (appliedControls!=null) CameraControls.restore(settings,appliedControls);
            controlMessage="La cámara rechazó ese ajuste. Se conservaron los controles anteriores.";
            broadcast();
        }
    }
    private boolean retryAsHevc(Exception cause) {
        if (finished || configuration==null || configuration.getBooleanExtra("hevc",false)
                || configuration.getBooleanExtra("automaticHevc",false)) return false;
        int width=configuration.getIntExtra("width",1920), height=configuration.getIntExtra("height",1080);
        int fps=configuration.getIntExtra("fps",30);
        if (fps!=60 || height<2160 || !(CameraCatalog.encoderSupports(true,width,height,fps) || "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER))) return false;
        android.util.Log.w("LumaRecCamera","H.264 failed; retrying 4K60 with HEVC",cause);
        if (session!=null) { try { session.stopRepeating(); } catch (Exception ignored) { } session.close(); session=null; }
        if (camera!=null) { camera.close(); camera=null; }
        if (recorder!=null) { try { recorder.reset(); recorder.release(); } catch (Exception ignored) { } recorder=null; }
        if (recordingFile!=null) { recordingFile.delete(); recordingFile=null; }
        configuration.putExtra("hevc",true).putExtra("automaticHevc",true)
                .putExtra("bitrate",CameraControls.customBitrate(getSharedPreferences("recorder",MODE_PRIVATE),CameraCatalog.bitrate(new CameraCatalog.Quality(width,height,50_000_000),fps,true,true),true,width,height,fps));
        description=description.replace("H.264","H.265");
        getSharedPreferences("recorder",MODE_PRIVATE).edit().putBoolean("hevc",true).apply();
        worker.postDelayed(this::begin,500);
        return true;
    }
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if (finished) return;
            if (durationLimit>0 && elapsed()>=durationLimit) { finish("Se completó la duración configurada."); return; }
            getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());
            worker.postDelayed(this,1000);
        }
    };
    private void togglePause() {
        try {
            if (state==RECORDING) {
                recorder.pause(); accumulated+=SystemClock.elapsedRealtime()-segmentStart; state=PAUSED;
            } else if (state==PAUSED) {
                recorder.resume(); segmentStart=SystemClock.elapsedRealtime(); state=RECORDING;
            }
            notifyState();
        } catch (Exception error) { finish("No se pudo cambiar la pausa. Revisa el vídeo guardado."); }
    }
    private void finish(String reason) {
        if (finished) return;
        finished=true; worker.removeCallbacksAndMessages(null);
        long duration=elapsed(); accumulated=duration; state=SAVING; notifyState();
        boolean saved=false;
        if (session!=null) { try { session.stopRepeating(); } catch (Exception ignored) { } session.close(); session=null; }
        if (recorder!=null) {
            if (started) try { recorder.stop(); saved=true; } catch (RuntimeException ignored) { }
            try { recorder.reset(); recorder.release(); } catch (Exception ignored) { } recorder=null;
        }
        if (camera!=null) { camera.close(); camera=null; }
        if (saved && recordingFile!=null) {
            try {
                if (privateRecording) {
                    if (recordingKey==null) throw new SecurityException("No se pudo preparar la clave del vídeo privado.");
                    String id=sealedRecordingKey==null ? SecureVault.save(this,recordingFile,duration,recordingKey)
                            : SecureVault.saveQuickCapture(this,recordingFile,duration,recordingKey,sealedRecordingKey);
                    lastVideo="vault:"+id;
                } else {
                    Uri gallery=VideoLibrary.publishFile(this,recordingFile,duration);
                    lastVideo=gallery.toString();
                }
            } catch (Exception error) { saved=false; reason=error instanceof SecurityException ? error.getMessage() : "No se pudo finalizar o cifrar el vídeo. Libera espacio e inténtalo de nuevo."; }
        }
        if (recordingFile!=null) { recordingFile.delete(); recordingFile=null; }
        if (recordingKey!=null) { java.util.Arrays.fill(recordingKey,(byte)0); recordingKey=null; }
        if (sealedRecordingKey!=null) { java.util.Arrays.fill(sealedRecordingKey,(byte)0); sealedRecordingKey=null; }
        releaseWakeLock();
        message=reason!=null ? reason : saved ? (privateRecording ? "Vídeo guardado en la bóveda privada; no aparece en la galería." : "Vídeo guardado en Movies/LumaRec.")+(configuration.getBooleanExtra("automaticHevc",false) ? " Se utilizó H.265 automáticamente." : "") : "La grabación fue demasiado corta o no pudo iniciarse. No se guardó ningún vídeo.";
        getSharedPreferences("recorder",MODE_PRIVATE).edit().putBoolean("ae_lock",false).putBoolean("awb_lock",false).apply();
        state=IDLE; if (!LumaRecApp.isForeground()) SecureVault.lock();
        broadcast(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        android.content.SharedPreferences prefs=getSharedPreferences("recorder_brand",MODE_PRIVATE);
        String title=prefs.getString("notification_title","").trim();
        if (title.isEmpty()) title="LumaRec";
        int mode=prefs.getInt("notification_mode",1);
        String status=state==PAUSED ? "En pausa" : state==PREPARING ? "Preparando cámara" : state==SAVING ? "Guardando vídeo" : "Grabando";
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_record)
                .setContentTitle(mode==0 ? title : title+" · "+status).setShowWhen(false)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE);
        if (mode==1) builder.setContentText(description+" · "+VideoLibrary.time(elapsed()));
        if (mode==1 && (state==RECORDING || state==PAUSED)) {
            PendingIntent pause=PendingIntent.getService(this,1,new Intent(this,RecordingService.class).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,state==PAUSED ? "Reanudar" : "Pausar",pause).build());
        }
        if (mode!=0 && state!=SAVING) {
            PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,RecordingService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,"Detener",stop).build());
        }
        return builder.build();
    }
    private void notifyState() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION,notification()); broadcast();
    }
    private void broadcast() { revision++; sendBroadcast(new Intent(UPDATE).setPackage(getPackageName())); try { QuickRecordWidget.updateAll(this); } catch (Exception ignored) { } }
    private void releaseWakeLock() { if (wakeLock!=null && wakeLock.isHeld()) wakeLock.release(); wakeLock=null; }
    private String friendly(Exception error) {
        android.util.Log.e("LumaRecCamera", "Falló la sesión de grabación", error);
        if (error instanceof IllegalStateException && error.getMessage()!=null) return error.getMessage();
        if (error instanceof SecurityException) return "Revisa los permisos de cámara y micrófono en Ajustes.";
        if (configuration!=null && configuration.getIntExtra("height",0)>=2160 && configuration.getIntExtra("fps",0)==60) {
            return configuration.getBooleanExtra("hevc",false)
                    ? "El móvil rechazó su perfil 4K/60 incluso con H.265. Huawei puede reservarlo para la cámara propia; prueba 4K/30."
                    : "H.264 rechazó el perfil 4K/60. LumaRec probará H.265 automáticamente si el móvil lo admite; si vuelve a fallar, prueba 4K/30.";
        }
        return "La cámara o el codificador rechazaron esa combinación. Prueba bajar la resolución o los fps.";
    }
    @Override public void onDestroy() {
        if (!finished && state!=IDLE) worker.post(()->{ finish("Android detuvo la grabación. Revisa el vídeo guardado."); thread.quitSafely(); });
        else thread.quitSafely();
        releaseWakeLock(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
