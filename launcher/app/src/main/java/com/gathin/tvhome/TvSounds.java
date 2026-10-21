package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Theme sounds (theme sounds: true, files in the theme's sounds/ folder): wake.ogg when the screen
 * comes on (wake_delay_s late, 3 s by default, so a soundbar on ARC is awake; at most once per 5 minutes), sleep.ogg when it
 * goes off, launch.ogg when an app is opened. Nothing else makes a sound, the system click sounds are
 * left alone; night_quiet_sounds can silence night mode hours. A missing folder or file is skipped quietly.
 *
 * <p>A wake is whichever comes first: Home shown again after the screen went off (TCL brings Home up on
 * power-on, about 4 s before SCREEN_ON is sent), the SCREEN_ON broadcast, or Home starting with the screen
 * on. A soundbar woken over CEC connects ARC some 6 s after power-on and renegotiates it for 5 s more;
 * a sound played before that is lost, so with wake_wait_arc the delay counts from the ARC connection.
 */
final class TvSounds {
    private static final String TAG = "tvhome-sound";
    private static TvSounds instance;

    private final SoundPool pool;
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<String, Boolean> ready = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Config cfg;
    private final AudioManager audio;
    private final PowerManager power;
    private long lastWake = Long.MIN_VALUE / 2;
    /** The screen went off since the last wake. */
    private boolean off;
    private static final long WAKE_COOLDOWN_MS = 5 * 60_000L;
    /** wake_wait_arc gives up on the soundbar after this long and plays anyway. */
    private static final long ARC_MAX_WAIT_MS = 20_000L;
    private int warmStream;
    private final Runnable warmStart = () -> warmStream = play("wake", 0.001f);
    private final Runnable warmStop = this::stopWarm;
    private final Runnable playWake = () -> {
        stopWaitingForArc();
        play("wake");
    };
    private boolean waitingForArc;
    private final AudioDeviceCallback arcCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            if (!waitingForArc || !hasArc(added)) return;
            Log.i(TAG, "soundbar connected");
            stopWaitingForArc();
            scheduleWake(cfg.wakeDelayS * 1000L);
        }
    };

    private TvSounds(Context ctx, Config cfg) {
        this.cfg = cfg;
        audio = ctx.getSystemService(AudioManager.class);
        power = ctx.getSystemService(PowerManager.class);
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
        if (s == null) return;
        Log.i(TAG, "screen off");
        s.wentOff();
        s.play("sleep");
    }

    /** Home stopped with the screen already off; the screen-off broadcast may come late or not at all. */
    static void homeStopped(Context ctx) {
        TvSounds s = get(ctx);
        if (s != null && s.power != null && !s.power.isInteractive()) s.wentOff();
    }

    /** Home is shown again: a wake when the screen went off in between. */
    static void homeShown(Context ctx) {
        TvSounds s = get(ctx);
        if (s != null && s.off && s.power != null && s.power.isInteractive()) screenOn(ctx, "home shown");
    }

    static void screenOn(Context ctx, String why) {
        TvSounds s = get(ctx);
        if (s == null) return;
        long now = SystemClock.elapsedRealtime();
        Log.i(TAG, "screen on (" + why + ")" + (s.lastWake < 0 ? "" : "; since last wake " + (now - s.lastWake) / 1000 + " s"));
        if (now - s.lastWake < WAKE_COOLDOWN_MS) return;
        s.lastWake = now;
        s.off = false;
        if (s.cfg.wakeWaitArc && s.audio != null && !hasArc(s.audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))) {
            // The soundbar is still asleep: wait for ARC, then wake_delay_s for it to settle.
            Log.i(TAG, "waiting for the soundbar");
            s.waitingForArc = true;
            s.audio.registerAudioDeviceCallback(s.arcCallback, s.handler);
            s.scheduleWake(ARC_MAX_WAIT_MS);
        } else {
            s.scheduleWake(s.cfg.wakeDelayS * 1000L);
        }
    }

    private void wentOff() {
        off = true;
        stopWaitingForArc();
        handler.removeCallbacks(warmStart);
        handler.removeCallbacks(warmStop);
        handler.removeCallbacks(playWake);
    }

    /** wake.ogg after delay; with sound_preroll_ms a silent play first, so the soundbar locks on before the first syllable. */
    private void scheduleWake(long delay) {
        handler.removeCallbacks(warmStart);
        handler.removeCallbacks(warmStop);
        handler.removeCallbacks(playWake);
        if (cfg.soundPrerollMs > 0) {
            handler.postDelayed(warmStart, delay);
            delay += cfg.soundPrerollMs;
            handler.postDelayed(warmStop, delay - 20);
        }
        handler.postDelayed(playWake, delay);
    }

    private void stopWarm() {
        if (warmStream != 0) pool.stop(warmStream);
        warmStream = 0;
    }

    private void stopWaitingForArc() {
        if (!waitingForArc) return;
        waitingForArc = false;
        if (audio != null) audio.unregisterAudioDeviceCallback(arcCallback);
    }

    private static boolean hasArc(AudioDeviceInfo[] devices) {
        if (devices == null) return false;
        for (AudioDeviceInfo d : devices) {
            // TYPE_HDMI_EARC is API 31, but only a constant (29): on older TVs no device has that type.
            if (d.isSink() && (d.getType() == AudioDeviceInfo.TYPE_HDMI_ARC || d.getType() == AudioDeviceInfo.TYPE_HDMI_EARC)) return true;
        }
        return false;
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
        int stream = pool.play(id, v, v, 1, 0, 1f);
        Log.i(TAG, "play " + name + (volume >= 0 ? " (silent)" : "") + ", stream " + stream);
        return stream;
    }
}
