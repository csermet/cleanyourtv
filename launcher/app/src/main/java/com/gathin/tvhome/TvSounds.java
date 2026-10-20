package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Theme sounds (theme sounds: true, files in the theme's sounds/ folder): wake.ogg when the screen
 * comes on (wake_delay_s late, 3 s by default, so a soundbar on ARC is awake; at most once per 5 minutes), sleep.ogg when it
 * goes off, launch.ogg when an app is opened. Nothing else makes a sound, the system click sounds are
 * left alone; night_quiet_sounds can silence night mode hours. A missing folder or file is skipped quietly.
 */
final class TvSounds {
    private static TvSounds instance;

    private final SoundPool pool;
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<String, Boolean> ready = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Config cfg;
    private long lastWake = Long.MIN_VALUE / 2;
    private static final long WAKE_COOLDOWN_MS = 5 * 60_000L;

    private TvSounds(Context ctx, Config cfg) {
        this.cfg = cfg;
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p, id, status) -> {
            for (Map.Entry<String, Integer> e : ids.entrySet()) if (e.getValue() == id) ready.put(e.getKey(), status == 0);
        });
        File dir = Config.dir(ctx) == null ? null : new File(Config.dir(ctx), "sounds");
        for (String name : new String[]{"wake", "sleep", "launch"}) {
            File f = dir == null ? null : new File(dir, name + ".ogg");
            if (f != null && f.isFile() && f.length() > 0) {
                try {
                    ids.put(name, pool.load(f.getPath(), 1));
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Loads the theme's sounds once per launcher start (null when sounds are off). */
    static synchronized TvSounds get(Context ctx) {
        if (instance == null) {
            Config cfg = Config.load(ctx);
            if (!cfg.sounds) return null;
            instance = new TvSounds(ctx.getApplicationContext(), cfg);
        }
        return instance;
    }

    static void launch(Context ctx) {
        TvSounds s = get(ctx);
        if (s != null) s.play("launch");
    }

    static void screenOff(Context ctx) {
        TvSounds s = get(ctx);
        if (s != null) s.play("sleep");
    }

    static void screenOn(Context ctx) {
        TvSounds s = get(ctx);
        if (s == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - s.lastWake < WAKE_COOLDOWN_MS) return;
        s.lastWake = now;
        long delay = s.cfg.wakeDelayS * 1000L;
        if (s.cfg.soundPrerollMs > 0) {
            // Silent first: the TV sends no audio to the soundbar while quiet, and it needs a moment to lock on.
            final int[] warm = new int[1];
            s.handler.postDelayed(() -> warm[0] = s.play("wake", 0.001f), delay);
            delay += s.cfg.soundPrerollMs;
            s.handler.postDelayed(() -> { if (warm[0] != 0) s.pool.stop(warm[0]); }, delay - 20);
        }
        s.handler.postDelayed(() -> s.play("wake"), delay);
    }

    private void play(String name) {
        play(name, -1f);
    }

    /** volume < 0: the theme's sound_volume. */
    private int play(String name, float volume) {
        Integer id = ids.get(name);
        if (id == null || !Boolean.TRUE.equals(ready.get(name))
                || (cfg.nightQuietSounds && NightMode.isNight(cfg))) return 0;
        float v = volume >= 0 ? volume : Math.max(0, Math.min(100, cfg.soundVolume)) / 100f;
        return pool.play(id, v, v, 1, 0, 1f);
    }
}
