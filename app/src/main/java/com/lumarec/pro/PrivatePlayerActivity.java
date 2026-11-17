package com.lumarec.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A non-exported player; vault videos never receive a grant to an external player. */
public final class PrivatePlayerActivity extends Activity implements SurfaceHolder.Callback {
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler();
    private MediaPlayer player;
    private MediaDataSource source;
    private ParcelFileDescriptor legacy;
    private SurfaceView surface;
    private TextView status;
    private Button play;
    private Button speed,backward,forward;
    private float playbackSpeed=1f;
    private SeekBar seek;
    private boolean ready, stopped, dragging, opening;
    private int generation;
    private String id;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); PrivacyUi.protect(this,true);
        id=getIntent().getStringExtra("video");
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(8,7,9));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        Button back=new Button(this); back.setText("‹  Volver a la bóveda"); back.setAllCaps(false); back.setTextColor(Color.WHITE); back.setBackgroundColor(Color.rgb(25,17,21));
        root.addView(back,new LinearLayout.LayoutParams(-1,dp(54))); back.setOnClickListener(v->finish());
        surface=new SurfaceView(this); surface.getHolder().addCallback(this); surface.setSecure(true);
        root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this); status.setText("Abriendo vídeo protegido…"); status.setTextColor(Color.WHITE); status.setTextSize(14); status.setGravity(Gravity.CENTER); status.setPadding(dp(12),dp(12),dp(12),dp(12)); root.addView(status);
        seek=new SeekBar(this); seek.setEnabled(false); root.addView(seek,new LinearLayout.LayoutParams(-1,dp(44)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) { dragging=true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging=false; if (ready && player!=null) player.seekTo(s.getProgress()); }
            @Override public void onProgressChanged(SeekBar s,int progress,boolean user) { }
        });
        LinearLayout playback=new LinearLayout(this);
        backward=new Button(this); backward.setText("−15 s"); backward.setAllCaps(false); backward.setEnabled(false); playback.addView(backward,new LinearLayout.LayoutParams(0,dp(46),1)); backward.setOnClickListener(v->jump(-15000));
        speed=new Button(this); speed.setText("1×"); speed.setAllCaps(false); speed.setEnabled(false); playback.addView(speed,new LinearLayout.LayoutParams(0,dp(46),1)); speed.setOnClickListener(v->speedDialog());
        forward=new Button(this); forward.setText("+15 s"); forward.setAllCaps(false); forward.setEnabled(false); playback.addView(forward,new LinearLayout.LayoutParams(0,dp(46),1)); forward.setOnClickListener(v->jump(15000));
        root.addView(playback);
        play=new Button(this); play.setText("Pausar"); play.setAllCaps(false); play.setTextColor(Color.WHITE); play.setBackgroundColor(Color.rgb(255,55,83)); play.setEnabled(false);
        root.addView(play,new LinearLayout.LayoutParams(-1,dp(52)));
        play.setOnClickListener(v->{ if (!ready || player==null) return; if (player.isPlaying()) { player.pause(); play.setText("Reproducir"); } else { player.start(); play.setText("Pausar"); } });
        setContentView(root); root.requestApplyInsets();
    }
    @Override public void onResume() {
        super.onResume(); stopped=false;
        if (!SecureVault.unlocked()) {
            startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish();
        } else if (surface.getHolder().getSurface().isValid() && player==null) open(surface.getHolder());
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { if (!stopped && SecureVault.unlocked()) open(holder); }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { releasePlayer(); }
    private void open(SurfaceHolder holder) {
        if (player!=null || opening || !SecureVault.unlocked() || isFinishing()) return;
        opening=true;
        final int epoch=++generation; byte[] key=SecureVault.key();
        if (key==null) { opening=false; finish(); return; }
        status.setText("Abriendo vídeo protegido…");
        files.execute(()->{
            MediaDataSource preparedSource=null; ParcelFileDescriptor preparedLegacy=null;
            try {
                File encrypted=SecureVault.encryptedFile(this,id);
                if (VaultFile.isChunked(encrypted)) {
                    byte[] videoKey=QuickVault.videoKey(this,encrypted,key);
                    VaultFile.Reader reader;
                    try { reader=new VaultFile.Reader(encrypted,videoKey); } finally { Arrays.fill(videoKey,(byte)0); }
                    preparedSource=new MediaDataSource() {
                        @Override public int readAt(long position,byte[] buffer,int offset,int size) throws IOException { return reader.readAt(position,buffer,offset,size); }
                        @Override public long getSize() { return reader.size; }
                        @Override public void close() throws IOException { reader.close(); }
                    };
                } else preparedLegacy=SecureVault.legacyPlayback(this,id,key);
                final MediaDataSource data=preparedSource; final ParcelFileDescriptor descriptor=preparedLegacy;
                runOnUiThread(()->{
                    if (isDestroyed() || stopped || epoch!=generation || !SecureVault.unlocked()) { close(data,descriptor); return; }
                    try {
                        opening=false; source=data; legacy=descriptor; player=new MediaPlayer();
                        if (source!=null) player.setDataSource(source); else player.setDataSource(legacy.getFileDescriptor());
                        player.setDisplay(holder); player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build());
                        player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                        player.setOnPreparedListener(p->{ ready=true; seek.setEnabled(true); seek.setMax(p.getDuration()); play.setEnabled(true); speed.setEnabled(true); backward.setEnabled(true); forward.setEnabled(true); p.start(); if (playbackSpeed!=1f) setSpeed(playbackSpeed); handler.post(ticker); });
                        player.setOnCompletionListener(p->{ play.setText("Reproducir"); });
                        player.setOnErrorListener((p,what,extra)->{ fail("No se pudo reproducir el vídeo en este dispositivo."); return true; });
                        player.prepareAsync();
                    } catch (Exception error) { fail("No se pudo abrir el vídeo protegido."); }
                });
            } catch (Exception error) { close(preparedSource,preparedLegacy); runOnUiThread(()->{ if (!isDestroyed() && epoch==generation) fail("No se pudo verificar o abrir el vídeo protegido."); }); }
            finally { Arrays.fill(key,(byte)0); }
        });
    }
    private final Runnable ticker=new Runnable() {
        @Override public void run() {
            if (!ready || player==null || stopped) return;
            try { int at=player.getCurrentPosition(); if (!dragging) seek.setProgress(at); status.setText(VideoLibrary.time(at)+"  /  "+VideoLibrary.time(player.getDuration())+"  ·  Bóveda privada"); }
            catch (Exception ignored) { }
            handler.postDelayed(this,400);
        }
    };
    private void jump(int millis) {
        if (!ready || player==null) return;
        try { player.seekTo(Math.max(0,Math.min(player.getDuration(),player.getCurrentPosition()+millis))); }
        catch (Exception error) { android.widget.Toast.makeText(this,"No se pudo cambiar la posición.",android.widget.Toast.LENGTH_SHORT).show(); }
    }
    private void speedDialog() {
        if (!ready || player==null) return;
        float[] rates={0.5f,0.75f,1f,1.25f,1.5f,2f};
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Velocidad de reproducción").setItems(new String[]{"0.5×","0.75×","1×","1.25×","1.5×","2×"},(d,index)->setSpeed(rates[index])).create();
        dialog.show(); PrivacyUi.protect(dialog);
    }
    private void setSpeed(float value) {
        if (!ready || player==null) return;
        try {
            boolean playing=player.isPlaying();
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(value).setPitch(1f));
            if (!playing) player.pause();
            playbackSpeed=value; speed.setText(String.format(java.util.Locale.getDefault(),"%s×",value));
        } catch (Exception error) { android.widget.Toast.makeText(this,"El dispositivo no admite esa velocidad para este vídeo.",android.widget.Toast.LENGTH_SHORT).show(); }
    }
    private void fail(String message) { releasePlayer(); status.setText(message); }
    private static void close(MediaDataSource source,ParcelFileDescriptor descriptor) {
        if (source!=null) try { source.close(); } catch (Exception ignored) { }
        if (descriptor!=null) try { descriptor.close(); } catch (Exception ignored) { }
    }
    private void releasePlayer() {
        generation++; ready=false; opening=false; handler.removeCallbacks(ticker);
        if (player!=null) { try { player.release(); } catch (Exception ignored) { } player=null; }
        close(source,legacy); source=null; legacy=null; if (play!=null) play.setEnabled(false); if (seek!=null) seek.setEnabled(false);
        if (speed!=null) speed.setEnabled(false); if (backward!=null) backward.setEnabled(false); if (forward!=null) forward.setEnabled(false);
    }
    @Override protected void onStop() { stopped=true; releasePlayer(); super.onStop(); }
    @Override public void onDestroy() { releasePlayer(); files.shutdownNow(); super.onDestroy(); }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
}
