package com.lumarec.pro;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.List;

/** Uses the same saved camera settings for the app and the quick-record widget. */
final class RecordRequest {
    static Intent fromPreferences(Activity activity) throws Exception {
        SharedPreferences p=activity.getSharedPreferences("recorder",Context.MODE_PRIVATE);
        List<CameraCatalog.Camera> cameras=CameraCatalog.scan(activity);
        if (cameras.isEmpty()) throw new IllegalStateException("No se encontró una cámara compatible.");
        CameraCatalog.Camera camera=cameras.get(0);
        for (CameraCatalog.Camera candidate:cameras) if (candidate.id.equals(p.getString("camera",camera.id))) camera=candidate;
        CameraCatalog.Quality quality=camera.qualities.get(0);
        for (CameraCatalog.Quality q:camera.qualities) if (q.width==p.getInt("width",0) && q.height==p.getInt("height",0)) quality=q;
        List<Integer> rates=CameraCatalog.frameRates(camera,quality,p.getBoolean("hevc",false));
        if (rates.isEmpty()) { p.edit().putBoolean("hevc",false).apply(); rates=CameraCatalog.frameRates(camera,quality,false); }
        int fps=p.getInt("fps",30);
        if (!rates.contains(fps)) fps=rates.contains(30) ? 30 : rates.isEmpty() ? 15 : rates.get(rates.size()-1);
        return create(activity,camera,quality,fps);
    }
    static Intent create(Activity activity,CameraCatalog.Camera camera,CameraCatalog.Quality quality,int fps) {
        SharedPreferences p=activity.getSharedPreferences("recorder",Context.MODE_PRIVATE);
        int mode=p.getInt("orientation_mode",0);
        int rotation=mode==1 ? 0 : mode==2 ? 90 : mode==3 ? 180 : mode==4 ? 270 : activity.getWindowManager().getDefaultDisplay().getRotation()*90;
        int orientation=camera.front ? (camera.sensorOrientation+rotation)%360 : (camera.sensorOrientation-rotation+360)%360;
        boolean hevc=p.getBoolean("hevc",false);
        boolean automaticHevc=fps==60 && !CameraCatalog.encoderSupports(false,quality.width,quality.height,60)
                && CameraCatalog.encoderSupports(true,quality.width,quality.height,60);
        if (automaticHevc) { hevc=true; p.edit().putBoolean("hevc",true).apply(); }
        int audioSource=p.getInt("audio_source",android.media.MediaRecorder.AudioSource.MIC);
        if (audioSource==android.media.MediaRecorder.AudioSource.UNPROCESSED && !"true".equals(activity.getSystemService(android.media.AudioManager.class).getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)))
            audioSource=android.media.MediaRecorder.AudioSource.MIC;
        return new Intent(activity,RecordingService.class).setAction(RecordingService.START)
                .putExtra("camera",camera.id).putExtra("label",camera.label).putExtra("width",quality.width).putExtra("height",quality.height).putExtra("fps",fps)
                .putExtra("orientation",orientation).putExtra("audio",p.getBoolean("audio",true)).putExtra("hevc",hevc).putExtra("automaticHevc",automaticHevc)
                .putExtra("stabilization",p.getBoolean("stabilization",false))
                .putExtra("bitrate",CameraControls.customBitrate(p,CameraCatalog.bitrate(quality,fps,hevc,p.getBoolean("high",true)),hevc,quality.width,quality.height,fps))
                .putExtra("audio_kbps",p.getInt("audio_kbps",192)).putExtra("audio_source",audioSource)
                .putExtra("duration",(long)p.getInt("duration",0)).putExtra("maxBytes",p.getInt("maxMB",0)*1_000_000L);
    }
}
