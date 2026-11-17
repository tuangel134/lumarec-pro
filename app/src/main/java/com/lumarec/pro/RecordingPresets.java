package com.lumarec.pro;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Profiles contain recorder options only, never vault credentials, keys or file grants. */
final class RecordingPresets {
    static final String[] KEYS={"camera","width","height","fps","audio","hevc","high","stabilization","delay","duration","maxMB",
            "zoom","exposure","white_balance","focus","torch","orientation_mode","custom_mbps","audio_kbps","audio_source","file_prefix"};
    static final class Preset {
        String id,name; JSONObject values;
        @Override public String toString() { return name; }
    }
    private static SharedPreferences store(Context c) { return c.getSharedPreferences("recording_presets",Context.MODE_PRIVATE); }
    static List<Preset> list(Context c) {
        List<Preset> result=new ArrayList<>();
        for (Map.Entry<String,?> entry:store(c).getAll().entrySet()) try {
            if (!(entry.getValue() instanceof String)) continue;
            JSONObject json=new JSONObject((String)entry.getValue());
            Preset preset=new Preset(); preset.id=entry.getKey(); preset.name=json.getString("name"); preset.values=json.getJSONObject("values"); result.add(preset);
        } catch (Exception ignored) { }
        result.sort(Comparator.comparing(p->p.name.toLowerCase(java.util.Locale.ROOT))); return result;
    }
    static void save(Context c,String name) throws Exception {
        if (name.trim().isEmpty()) throw new IllegalArgumentException("Pon un nombre al perfil.");
        JSONObject values=new JSONObject(); Map<String,?> source=c.getSharedPreferences("recorder",Context.MODE_PRIVATE).getAll();
        for (String key:KEYS) if (source.containsKey(key)) values.put(key,source.get(key));
        JSONObject profile=new JSONObject(); profile.put("name",name.trim()); profile.put("values",values);
        store(c).edit().putString(java.util.UUID.randomUUID().toString(),profile.toString()).apply();
    }
    static void load(Context c,Preset preset) {
        SharedPreferences.Editor edit=c.getSharedPreferences("recorder",Context.MODE_PRIVATE).edit();
        for (String key:KEYS) {
            edit.remove(key);
            Object value=preset.values.opt(key);
            if (value instanceof Boolean) edit.putBoolean(key,(Boolean)value);
            else if ("zoom".equals(key) && value instanceof Number) edit.putFloat(key,((Number)value).floatValue());
            else if (value instanceof Number) edit.putInt(key,((Number)value).intValue());
            else if (value instanceof String) edit.putString(key,(String)value);
        }
        edit.apply();
    }
    static void delete(Context c,Preset preset) { store(c).edit().remove(preset.id).apply(); }
}
