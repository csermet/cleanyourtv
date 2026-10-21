package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.gathin.tvhome/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the dock and the look without a rebuild:
 * {@code tools/theme.sh themes/<name>} pushes a theme folder there and restarts Home.
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";
    /** Launcher settings: the wallpapers/ file the owner picked, and the day (LocalDate.toEpochDay) it is for. */
    static final String WALLPAPER_PICK = "wallpaper_pick";
    static final String WALLPAPER_PICK_DAY = "wallpaper_pick_day";
    static final String VIDEO_PICK = "video_pick", VIDEO_PICK_DAY = "video_pick_day";
    /**
     * Pictures and videos move on together, one step at a time: when the TV is turned on and the same ones
     * have been up for an hour or more (advanceRotation). The step starts at today's date, so an upgrade
     * keeps today's picture.
     */
    static final String ROTATION_STEP = "rotation_step", ROTATION_SINCE = "rotation_since";
    static final long ROTATION_HOLD_MS = 60 * 60_000L;
    static final String SETTINGS_NAME = "launcher_settings";
    /** live_background: the picture alone, a video from the theme's videos/ folder, or either with an effect over it. */
    static final int LIVE_PICTURE = 0, LIVE_VIDEO = 1, LIVE_PICTURE_EFFECT = 2, LIVE_VIDEO_EFFECT = 3;
    /** live_effect: what the effect draws (purple electric sparks, drifting light dust, or both). */
    static final int EFFECT_SPARKS = 0, EFFECT_DUST = 1, EFFECT_BOTH = 2;
    /** The owner's picks in Launcher settings; like the wallpaper pick, a theme push leaves them alone. */
    static final String LIVE_BACKGROUND = "live_background", LIVE_EFFECT = "live_effect";
    private static final String[] LIVE_NAMES = {"picture", "video", "picture_effect", "video_effect"};
    private static final String[] EFFECT_NAMES = {"sparks", "dust", "both"};
    private static final String THEME_REVISION_NAME = "theme_revision";
    private static final String THEME_SIGNATURE_KEY = "theme_signature";

    final List<String> dockApps;
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock); on by default, false leaves the slots empty. */
    final boolean fillDock;
    final List<String> hiddenApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String sourcePanelService;
    final String sourcePanelAction;
    final String inputAction;
    final int columns;
    final boolean animations;
    final boolean advancedEffects;
    final int rowsBelowDock;
    final boolean shamsiDate;
    final boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    final boolean reducedMotion;
    final String vpnApp;
    final boolean showHomeSettingsShortcut;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Time-of-day backdrop, 0 to 100 (config.xml, sky_intensity); 0 keeps the plain background. */
    final int skyIntensity;
    /** Remote keycodes that open Settings through HomeKeyService (config.xml, settings_key_keycodes). */
    final int[] settingsKeycodes;
    /** The Settings button opens the quick settings panel instead of the TV's Settings (settings_key_panel). */
    final boolean settingsKeyPanel;
    /** Remote app buttons by keycode, each opening a package through HomeKeyService (config.xml, app_keys). */
    final Map<Integer, String> appKeys;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Header text colour (clock, greeting, date, focused app name) from a theme's text_color, or 0 for the built-in white. */
    final int textColor;
    /** single_row: every app in one horizontal row that scrolls; no grid below (theme only). */
    final boolean singleRow;
    /** network_pill: false hides the Wi-Fi/Ethernet pill in the top bar (theme only). */
    final boolean networkPill;
    /** top_buttons_size: the top bar pills' size in percent, 60 to 100 (theme only). */
    final int topButtonsSize;
    /** band_opacity: single_row band opacity in percent, 20 to 100 (theme only). */
    final int bandOpacity;
    /** ambient_glow: strength of the focused app's colour glow in percent, 0 turns it off (theme only). */
    final int ambientGlow;
    /** plex_row: a smaller second row of Plex recommendations under single_row (theme only). */
    final boolean plexRow;
    /** plex_lan_ip: the Plex server's LAN address, for servers whose advertised local address is a container's. */
    final String plexLanIp;
    /** row_tiles: single_row tiles per screen width, fractions allowed (3 to 10); 0 follows columns. */
    final float rowTiles;
    /** tile_gap: single_row space between tiles in dp (4 to 40); 0 keeps room for the full focus zoom. */
    final int tileGap;
    /** title_size: the top shelf title in sp (20 to 60); 0 keeps the layout's size. */
    final int titleSize;
    /** parallax: the wallpaper drifts a little (bounded) with the focus (theme only). */
    final boolean parallax;
    /** open_from_tile: apps open growing out of their tile (ActivityOptions scale-up) (theme only). */
    final boolean openFromTile;
    /** inputs_pill: false hides the Inputs pill (the remote's Input key still works) (theme only). */
    final boolean inputsPill;
    /** night_start / night_end ("HH:MM"): night mode window in minutes of the day, -1 when off (theme only). */
    final int nightStart, nightEnd;
    /** night_dim: extra darkening of the wallpaper at night in percent; night_screensaver_min: screensaver delay at night. */
    final int nightDim, nightSaverMin;
    /** status_nodes: [{name, url}] node-exporter /metrics per server; status_usage_url: usage JSON (theme only). */
    final List<String[]> statusNodes;
    final String statusUsageUrl;
    /** sounds: theme sounds/ (wake, sleep, launch); sound_volume 0-100 (theme only). */
    final boolean sounds;
    final int soundVolume;
    /** wake_delay_s: seconds before wake.ogg, so a soundbar on ARC is listening (0-10, default 3). */
    final int wakeDelayS;
    /** wake_wait_arc: on wake, count wake_delay_s from the moment the ARC/eARC soundbar connects (at most 20 s), not from the wake. */
    final boolean wakeWaitArc;
    /** night_quiet_sounds: no theme sounds in night mode hours (default false: sounds play at night too). */
    final boolean nightQuietSounds;
    /** sound_preroll_ms: a silent play this long before wake.ogg opens the audio path (a soundbar on ARC drops the first syllable otherwise). */
    final int soundPrerollMs;
    /** live_background / live_effect (theme default, then the owner's pick); see LIVE_* and EFFECT_*. */
    final int liveBackground, liveEffect;
    /** live_video_dim: black over the video behind the UI, in percent (0-90, default 45); none in full screen. */
    final int liveVideoDim;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;
    /** Festive skin from a theme (festival, festival_until, greeting, accent_color); {@link Festival#NONE} if none. */
    final Festival festival;

    private Config(List<String> dockApps, boolean fillDock, List<String> hiddenApps, String liveTvPackage,
                   String liveTvAction, String sourcePanelService, String sourcePanelAction, String inputAction,
                   int columns, int rowsBelowDock, boolean animations, boolean advancedEffects,
                   boolean shamsiDate, boolean moduleCards, boolean reducedMotion, String vpnApp,
                   boolean showHomeSettingsShortcut, int backgroundColor, int skyIntensity,
                   int[] settingsKeycodes, boolean settingsKeyPanel, Map<Integer, String> appKeys,
                   File wallpaper, boolean overlayApplied, Festival festival, int textColor, boolean singleRow,
                   boolean networkPill, int topButtonsSize, int bandOpacity,
                   int ambientGlow, boolean plexRow, String plexLanIp, float rowTiles,
                   int tileGap, int titleSize, boolean parallax, boolean openFromTile,
                   boolean inputsPill, int nightStart, int nightEnd, int nightDim, int nightSaverMin,
                   List<String[]> statusNodes, String statusUsageUrl, boolean sounds, int soundVolume,
                   int wakeDelayS, boolean wakeWaitArc, boolean nightQuietSounds, int soundPrerollMs,
                   int liveBackground, int liveEffect, int liveVideoDim) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.fillDock = fillDock;
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.columns = columns;
        this.rowsBelowDock = rowsBelowDock;
        this.animations = animations;
        this.advancedEffects = advancedEffects;
        this.shamsiDate = shamsiDate;
        this.moduleCards = moduleCards;
        this.reducedMotion = reducedMotion;
        this.vpnApp = vpnApp;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.backgroundColor = backgroundColor;
        this.skyIntensity = skyIntensity;
        this.settingsKeycodes = settingsKeycodes;
        this.settingsKeyPanel = settingsKeyPanel;
        this.appKeys = Collections.unmodifiableMap(appKeys);
        this.wallpaper = wallpaper;
        this.overlayApplied = overlayApplied;
        this.festival = festival;
        this.textColor = textColor;
        this.singleRow = singleRow;
        this.networkPill = networkPill;
        this.topButtonsSize = topButtonsSize;
        this.bandOpacity = bandOpacity;
        this.ambientGlow = ambientGlow;
        this.plexRow = plexRow;
        this.plexLanIp = plexLanIp;
        this.rowTiles = rowTiles;
        this.tileGap = tileGap;
        this.titleSize = titleSize;
        this.parallax = parallax;
        this.openFromTile = openFromTile;
        this.inputsPill = inputsPill;
        this.nightStart = nightStart;
        this.nightEnd = nightEnd;
        this.nightDim = nightDim;
        this.nightSaverMin = nightSaverMin;
        this.statusNodes = statusNodes;
        this.statusUsageUrl = statusUsageUrl;
        this.sounds = sounds;
        this.soundVolume = soundVolume;
        this.wakeDelayS = wakeDelayS;
        this.wakeWaitArc = wakeWaitArc;
        this.nightQuietSounds = nightQuietSounds;
        this.soundPrerollMs = soundPrerollMs;
        this.liveBackground = liveBackground;
        this.liveEffect = liveEffect;
        this.liveVideoDim = liveVideoDim;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        boolean fillDock = res.getBoolean(R.bool.fill_dock);
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String inputAction = res.getString(R.string.input_action).trim();
        int columns = res.getInteger(R.integer.columns);
        int rowsBelow = res.getInteger(R.integer.rows_below_dock);
        boolean animations = res.getBoolean(R.bool.animations);
        boolean advancedEffects = res.getBoolean(R.bool.advanced_effects);
        boolean shamsi = res.getBoolean(R.bool.shamsi_date);
        boolean moduleCards = res.getBoolean(R.bool.module_cards);
        boolean reducedMotion = res.getBoolean(R.bool.reduced_motion);
        String vpn = res.getString(R.string.vpn_app).trim();
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        int background = 0xFF000000;
        int skyIntensity = res.getInteger(R.integer.sky_intensity);
        int[] settingsKeycodes = res.getIntArray(R.array.settings_key_keycodes);
        boolean settingsKeyPanel = res.getBoolean(R.bool.settings_key_panel);
        Map<Integer, String> appKeys = appKeys(res.getStringArray(R.array.app_keys));
        boolean applied = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        JSONObject json = read(file);
        if (json != null) {
            applied = true;
            dock = stringList(json, "dock_apps", dock);
            fillDock = bool(json, "fill_dock", fillDock);
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            panelService = string(json, "source_panel_service", panelService);
            panelAction = string(json, "source_panel_action", panelAction);
            inputAction = string(json, "input_action", inputAction);
            columns = integer(json, "columns", columns, 1, 8);
            rowsBelow = integer(json, "rows_below_dock", rowsBelow, 0, 3);
            animations = bool(json, "animations", animations);
            advancedEffects = bool(json, "advanced_effects", advancedEffects);
            shamsi = bool(json, "shamsi_date", shamsi);
            moduleCards = bool(json, "module_cards", moduleCards);
            reducedMotion = bool(json, "reduced_motion", reducedMotion);
            vpn = string(json, "vpn_app", vpn);
            homeSettings = bool(json, "show_home_settings_shortcut", homeSettings);
            background = color(json, "background_color", background);
            skyIntensity = integer(json, "sky_intensity", skyIntensity, 0, 100);
            settingsKeycodes = intArray(json, "settings_key_keycodes", settingsKeycodes);
            settingsKeyPanel = bool(json, "settings_key_panel", settingsKeyPanel);
            appKeys = appKeys(json, "app_keys", appKeys);
        }
        // A theme push or reset invalidates choices made on the TV. On first use, remember the
        // current theme without clearing choices from an older launcher version.
        SharedPreferences choices = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        syncThemeChoices(choices, dir, file);
        int chosenColumns = choices.getInt("columns", columns);
        if (chosenColumns >= 4 && chosenColumns <= 7) columns = chosenColumns;
        animations = choices.getBoolean("animations", animations);
        advancedEffects = choices.getBoolean("advanced_effects", advancedEffects);
        // wallpapers/*.jpg rotate once a day; otherwise the single wallpaper.jpg (WallpaperRotation).
        File wallpaper = currentWallpaper(ctx, dir);
        // A theme can also switch the wallpaper off while the file is still there.
        if (json != null && json.has("wallpaper") && !bool(json, "wallpaper", true)) wallpaper = null;
        // A festive skin comes only from a theme; config.xml has no festival.
        Festival festival = json == null ? Festival.NONE : Festival.parse(string(json, "festival", ""),
                string(json, "festival_until", ""), string(json, "greeting", ""), string(json, "accent_color", ""));

        return new Config(dock, fillDock, hidden, liveTv, liveTvAction, panelService, panelAction, inputAction,
                Math.max(1, columns), Math.max(0, rowsBelow), animations, advancedEffects,
                shamsi, moduleCards, reducedMotion, vpn, homeSettings, background,
                Math.max(0, Math.min(100, skyIntensity)), settingsKeycodes, settingsKeyPanel, appKeys, wallpaper, applied,
                festival, json == null ? 0 : color(json, "text_color", 0),
                json != null && bool(json, "single_row", false),
                json == null || bool(json, "network_pill", true),
                json == null ? 100 : integer(json, "top_buttons_size", 100, 60, 100),
                json == null ? 100 : integer(json, "band_opacity", 100, 20, 100),
                json == null ? 100 : integer(json, "ambient_glow", 100, 0, 100),
                json != null && bool(json, "plex_row", false),
                json == null ? "" : string(json, "plex_lan_ip", ""),
                json == null ? 0f : (float) Math.max(0, Math.min(10, json.optDouble("row_tiles", 0))),
                json == null ? 0 : integer(json, "tile_gap", 0, 0, 40),
                json == null ? 0 : integer(json, "title_size", 0, 0, 60),
                json != null && bool(json, "parallax", false),
                json != null && bool(json, "open_from_tile", false),
                json == null || bool(json, "inputs_pill", true),
                json == null ? -1 : clock(string(json, "night_start", "")),
                json == null ? -1 : clock(string(json, "night_end", "")),
                json == null ? 35 : integer(json, "night_dim", 35, 0, 90),
                json == null ? 0 : integer(json, "night_screensaver_min", 0, 0, 60),
                nodes(json), json == null ? "" : string(json, "status_usage_url", ""),
                json != null && bool(json, "sounds", false),
                json == null ? 100 : integer(json, "sound_volume", 100, 0, 100),
                json == null ? 3 : integer(json, "wake_delay_s", 3, 0, 10),
                json != null && bool(json, "wake_wait_arc", false),
                json != null && bool(json, "night_quiet_sounds", false),
                json == null ? 600 : integer(json, "sound_preroll_ms", 600, 0, 3000),
                pick(choices, LIVE_BACKGROUND, named(json == null ? "" : string(json, LIVE_BACKGROUND, ""), LIVE_NAMES), LIVE_NAMES.length),
                pick(choices, LIVE_EFFECT, named(json == null ? "" : string(json, LIVE_EFFECT, ""), EFFECT_NAMES), EFFECT_NAMES.length),
                json == null ? 45 : integer(json, "live_video_dim", 45, 0, 90));
    }

    /** A theme value's position in names ("video" -> 1); 0, the first, for anything else. */
    static int named(String value, String[] names) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(value)) return i;
        return 0;
    }

    static int liveBackgroundNamed(String value) { return named(value, LIVE_NAMES); }

    static int liveEffectNamed(String value) { return named(value, EFFECT_NAMES); }

    private static int pick(SharedPreferences choices, String key, int fallback, int count) {
        int v = choices.getInt(key, fallback);
        return v >= 0 && v < count ? v : fallback;
    }

    boolean liveVideo() { return liveBackground == LIVE_VIDEO || liveBackground == LIVE_VIDEO_EFFECT; }

    boolean liveEffectOn() { return liveBackground == LIVE_PICTURE_EFFECT || liveBackground == LIVE_VIDEO_EFFECT; }

    /** The video from the theme's videos/ folder: the owner's pick for today, else the shuffle; null when there is none. */
    static File currentVideo(Context ctx) {
        File[] list = WallpaperRotation.videos(dir(ctx));
        if (list.length == 0) return null;
        SharedPreferences p = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        if (p.getLong(VIDEO_PICK_DAY, Long.MIN_VALUE) == java.time.LocalDate.now().toEpochDay()) {
            String pick = p.getString(VIDEO_PICK, null);
            for (File f : list) if (f.getName().equals(pick)) return f;
        }
        return list[WallpaperRotation.index(rotationStep(p), list.length)];
    }

    /** Today's wallpaper: the owner's pick for today (Launcher settings), else the daily shuffle. */
    static File currentWallpaper(Context ctx) {
        return currentWallpaper(ctx, dir(ctx));
    }

    static File currentWallpaper(Context ctx, File dir) {
        SharedPreferences p = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        long today = java.time.LocalDate.now().toEpochDay();
        String pick = p.getLong(WALLPAPER_PICK_DAY, Long.MIN_VALUE) == today ? p.getString(WALLPAPER_PICK, null) : null;
        return WallpaperRotation.pick(dir, WALLPAPER_NAME, rotationStep(p), pick);
    }

    static long rotationStep(SharedPreferences p) {
        return p.getLong(ROTATION_STEP, java.time.LocalDate.now().toEpochDay());
    }

    /** Power-on: one step on when the same picture and video have been up for ROTATION_HOLD_MS; true if it moved. */
    static boolean advanceRotation(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), since = p.getLong(ROTATION_SINCE, 0);
        if (since == 0) {
            p.edit().putLong(ROTATION_STEP, rotationStep(p)).putLong(ROTATION_SINCE, now).apply();
            return false;
        }
        if (!rotationDue(since, now)) return false;
        p.edit().putLong(ROTATION_STEP, rotationStep(p) + 1).putLong(ROTATION_SINCE, now).apply();
        return true;
    }

    /** Due after the hold; also when the clock went back past the last step (set again from the network). */
    static boolean rotationDue(long since, long now) {
        return now < since || now - since >= ROTATION_HOLD_MS;
    }

    private static void syncThemeChoices(SharedPreferences choices, File dir, File config) {
        File revision = dir == null ? null : new File(dir, THEME_REVISION_NAME);
        String token = revisionToken(revision);
        String signature = fileStamp(config) + ":" + token;
        String seen = choices.getString(THEME_SIGNATURE_KEY, null);
        if (seen == null) {
            // Existing preferences survive an app upgrade, unless the first launch follows an
            // explicit theme push or reset by the updated tools.
            SharedPreferences.Editor editor = choices.edit();
            if (!"absent".equals(token)) editor.remove("columns").remove("animations")
                    .remove("advanced_effects");
            editor.putString(THEME_SIGNATURE_KEY, signature).commit();
        } else if (!seen.equals(signature)) {
            choices.edit().remove("columns").remove("animations").remove("advanced_effects")
                    .putString(THEME_SIGNATURE_KEY, signature).commit();
        }
    }

    private static String fileStamp(File file) {
        return file != null && file.isFile() ? file.length() + "@" + file.lastModified() : "absent";
    }

    private static String revisionToken(File file) {
        if (file == null || !file.isFile() || file.length() > 64) return "absent";
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = in.read(bytes);
            return n > 0 ? new String(bytes, 0, n, StandardCharsets.US_ASCII) : "absent";
        } catch (Exception ignored) {
            return "absent";
        }
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static List<String[]> nodes(JSONObject json) {
        List<String[]> out = new ArrayList<>();
        JSONArray a = json == null ? null : json.optJSONArray("status_nodes");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", ""), url = o.optString("url", "");
            if (!name.isEmpty() && url.startsWith("http")) out.add(new String[]{name, url});
        }
        return out;
    }

    /** "HH:MM" as minutes of the day, or -1 when it does not parse. */
    static int clock(String s) {
        if (s == null || !s.matches("\\d{1,2}:\\d{2}")) return -1;
        String[] p = s.split(":");
        int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
        return h < 24 && m < 60 ? h * 60 + m : -1;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            return c | 0xFF000000;   // always opaque: the window behind is black anyway
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** config.xml items "keycode=package"; a malformed item is skipped. */
    private static Map<Integer, String> appKeys(String[] items) {
        Map<Integer, String> out = new HashMap<>();
        for (String item : items) {
            String[] parts = item.split("=", 2);
            if (parts.length != 2) continue;
            try {
                int code = Integer.parseInt(parts[0].trim());
                String pkg = parts[1].trim();
                if (code > 0 && !pkg.isEmpty()) out.put(code, pkg);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** A theme's {"keycode": "package"} object; anything that is not a positive keycode and a string is ignored. */
    private static Map<Integer, String> appKeys(JSONObject o, String key, Map<Integer, String> fallback) {
        JSONObject map = o.optJSONObject(key);
        if (map == null) return fallback;
        Map<Integer, String> out = new HashMap<>();
        for (Iterator<String> it = map.keys(); it.hasNext(); ) {
            String code = it.next();
            Object pkg = map.opt(code);
            if (!(pkg instanceof String) || ((String) pkg).trim().isEmpty()) continue;
            try {
                int c = Integer.parseInt(code.trim());
                if (c > 0) out.put(c, ((String) pkg).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Positive keycodes only (vendor remotes use codes in the thousands); a list with anything else in it is ignored as a whole. */
    private static int[] intArray(JSONObject o, String key, int[] fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        int[] out = new int[a.length()];
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (!(item instanceof Number)) return fallback;
            out[i] = ((Number) item).intValue();
            if (out[i] < 1) return fallback;
        }
        return out;
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }
}
