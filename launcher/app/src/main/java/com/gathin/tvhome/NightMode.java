package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Night mode (theme night_start / night_end): a darker home screen and an earlier screensaver. The
 * screensaver delay is the system's screen_off_timeout: at night it is set to night_screensaver_min,
 * and the owner's own value is put back in the morning. That needs "modify system settings"
 * (adb: appops set com.gathin.tvhome WRITE_SETTINGS allow); without it only the dimming applies.
 * An alarm at each boundary does the switch even while another app is in front.
 */
final class NightMode {
    private static final String PREFS = "night";
    private static final String DAY_TIMEOUT = "day_screen_off_timeout";

    private NightMode() {}

    /** True inside [start, end) minutes of the day; the window may cross midnight. Plain Java, tested. */
    static boolean isNight(int startMin, int endMin, int nowMin) {
        if (startMin < 0 || endMin < 0 || startMin == endMin) return false;
        return startMin < endMin ? nowMin >= startMin && nowMin < endMin
                : nowMin >= startMin || nowMin < endMin;
    }

    static boolean isNight(Config cfg) {
        LocalTime t = LocalTime.now();
        return isNight(cfg.nightStart, cfg.nightEnd, t.getHour() * 60 + t.getMinute());
    }

    /** Sets or restores the screensaver delay for the current time, then arms the next boundary. */
    static void apply(Context ctx, Config cfg) {
        if (cfg.nightStart < 0 || cfg.nightEnd < 0) return;
        boolean night = isNight(cfg);
        if (cfg.nightSaverMin > 0 && Settings.System.canWrite(ctx)) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            try {
                int now = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
                int nightMs = cfg.nightSaverMin * 60_000;
                if (night) {
                    if (!p.contains(DAY_TIMEOUT) && now != nightMs) p.edit().putInt(DAY_TIMEOUT, now).apply();
                    if (now != nightMs) Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, nightMs);
                } else if (p.contains(DAY_TIMEOUT)) {
                    Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, p.getInt(DAY_TIMEOUT, now));
                    p.edit().remove(DAY_TIMEOUT).apply();
                }
            } catch (Settings.SettingNotFoundException | SecurityException ignored) {
            }
        }
        schedule(ctx, cfg, night);
    }

    private static void schedule(Context ctx, Config cfg, boolean night) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        int target = night ? cfg.nightEnd : cfg.nightStart;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime at = now.toLocalDate().atTime(target / 60, target % 60);
        if (!at.isAfter(now)) at = at.plusDays(1);
        long when = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 5_000;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, new Intent(ctx, Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC, when, pi);
    }

    /** The boundary alarm (and boot): applies the right delay and arms the next one. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            apply(ctx, Config.load(ctx));
        }
    }
}
