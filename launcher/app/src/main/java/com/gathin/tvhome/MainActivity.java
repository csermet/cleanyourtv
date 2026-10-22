package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, PlexRowAdapter.Listener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    private final Runnable showIdleClock = () -> startActivity(new Intent(this, ClockActivity.class));
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** The top shelf: heroName, plus the cinematic shelf's "Last opened" line and dock position dots. */
    private View hero;
    private TextView heroMeta;
    private LinearLayout heroDots;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    private boolean cinematic;
    /** config.xml ambient_glow, overridable by a theme. Off: the glow view is gone and no fade or colour animator
     *  is started for it, so a focus move costs nothing extra on a launcher that runs all day. */
    private boolean ambientGlow;
    private ImageView heroArt;
    private static final float HERO_ART_ALPHA = 0.5f;
    /** Softened artwork per app (HeroArt, about 5 KB each), made the first time its dock tile is focused. */
    private final Map<String, Bitmap> heroArtCache = new HashMap<>();
    /** The app whose artwork heroArt shows, so focusing it again only fades it back in. */
    private String heroArtKey;
    /** The focus glow's colour while the cinematic shelf tints it, 0 for plain white glass. */
    private int glassAccent;
    private int ambientColor = -1;
    /** Config.ambientGlow as a drawable alpha, 0 to 255. */
    private int ambientAlpha = 255;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    private TextView btnWifi;
    /** live_background video controls next to Settings; fullScreen: only the video, at its own brightness. */
    private TextView btnSound;
    private View btnFullScreen, btnNext;
    private static final java.util.concurrent.ExecutorService WALL_EXEC = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean fullScreen;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView shamsiDate;
    private TextView stats;
    private List<RaceWeekend.Race> raceCalendar;
    private View racePanel;
    private TextView raceKicker, raceHeadline, raceSessions;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    /** Horizontal counterpart of glassScrollShift, for single_row. */
    private float glassScrollShiftX;
    /** Config.singleRow: one horizontal row, no grid. */
    private boolean singleRow;
    /** Where the top shelf title last sat, so a Plex card can show its title in the same place. */
    private float heroY = Float.NaN;
    /** heroName's own text size in px, restored for app titles after a long Plex title shrank it. */
    private float heroTextPx;
    /** single_row: the title's bottom edge, just above the band; its top follows its height. */
    private float heroBottom = Float.NaN;
    /** single_row: the title box has been lined up with the first tile's left edge. */
    private boolean heroAligned;
    /** single_row + plex_row: the focused row is full size, the other ROW_SMALL; the gap between the bands stays. */
    private static final float ROW_SMALL = 0.70f;
    /** single_row: the bands reach this far past the column's padding, towards the screen edges; the tiles stay put. */
    private static final float BAND_OUTSET_DP = 16f;
    /** single_row: the rows' side padding; the band is drawn 6dp from the row's edge, so tiles sit ~20dp inside it. */
    private static final float ROW_SIDE_DP = 22f;
    private static final float ROW_GAP_DP = 12f, PLEX_INNER_TOP_DP = 10f, APPS_INNER_BOTTOM_DP = 80f;
    private boolean plexFocused;
    /** 0 = app row focused, 1 = Plex row focused; a spring moves it between the two. */
    private final androidx.dynamicanimation.animation.FloatValueHolder rowProgress =
            new androidx.dynamicanimation.animation.FloatValueHolder(0f);
    private SpringAnimation rowSpring;
    /** The row without focus is dimmed to this opacity. */
    private static final float ROW_DIM = 0.65f;
    private boolean parallax, openFromTile;
    private boolean inputsPill = true;
    /** Night mode: the theme's config, and a black layer over the wallpaper at night. */
    private Config nightCfg;
    private View nightShade;
    /** Screen on/off for the theme's wake and sleep sounds (TvSounds). */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) TvSounds.screenOn(c, "broadcast");
            else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) TvSounds.screenOff(c);
        }
    };
    /** Header status lines (status_nodes / status_usage_url), refreshed every minute while Home is shown. */
    private TextView statusNodes, statusUsage;
    /** The line over the notes and the bar between them: shown only with something to separate. */
    private View statusRule, statusBar;
    private final ServerStatus serverStatus = new ServerStatus();
    private static final java.util.concurrent.ExecutorService STATUS_EXEC = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Every five minutes while Home is shown; a return to Home within that keeps the last values. */
    private static final long STATUS_EVERY_MS = 5 * 60_000L;
    private long statusAt = Long.MIN_VALUE / 2;
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            long age = android.os.SystemClock.elapsedRealtime() - statusAt;
            if (age >= STATUS_EVERY_MS) {
                statusAt = android.os.SystemClock.elapsedRealtime();
                refreshStatus();
                age = 0;
            }
            handler.postDelayed(this, STATUS_EVERY_MS - age);
        }
    };
    private View wallView;
    private static final float PARALLAX_X_DP = 20f, PARALLAX_Y_DP = 10f, WALL_ZOOM = 1.06f;
    private RowBandDecoration appsBand;
    private RowBands bands;
    /** The rows' normal width (the wrapper's); a shrunk row is laid out wider so its tiles still reach the band's end. */
    private int rowWidth;
    /** The focused app tile's layout top in root coordinates, for the title above the (scaled) row. */
    private float appsCardTop = Float.NaN;
    /** Config.plexRow: the Plex recommended row under the single row; null when off. */
    private RecyclerView plexRow;
    private PlexRowAdapter plexAdapter;
    private PlexClient plex;
    private long plexLoadedAt = Long.MIN_VALUE / 2;
    private String plexLanIp = "";
    private boolean plexLoading;
    private android.animation.ValueAnimator plexEntrance;
    private int plexLast;
    private static final java.util.concurrent.ExecutorService PLEX_EXEC = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final long PLEX_REFRESH_MS = 15 * 60_000L;
    /**
     * The Plex row or a status line came back empty: on power-on the TV drops Ethernet and gets it back
     * some 5 s later, after Home has already asked. Tried again 3 s after each network change, 10 s after
     * a failure and then every 30 s while Home is shown, six times at most; a success or a network change
     * starts the count again.
     */
    private boolean plexFailed, statusFailed;
    private int failRetries;
    private static final long NET_SETTLE_MS = 3_000L, FIRST_RETRY_MS = 10_000L, RETRY_MS = 30_000L;
    private static final int MAX_RETRIES = 6;
    private boolean resumed;
    /** Home was stopped by the screen going off: back on, the Plex row and status lines load again (once the network is up). */
    private boolean stoppedAsleep;
    private final Runnable retryFailed = new Runnable() {
        @Override
        public void run() {
            if (!resumed || (!plexFailed && !statusFailed) || failRetries >= MAX_RETRIES) return;
            failRetries++;
            android.util.Log.i("tvhome-net", "retry " + failRetries + (plexFailed ? " plex" : "") + (statusFailed ? " status" : ""));
            if (plexFailed) loadPlex();
            if (statusFailed) restartStatus();
        }
    };
    private int glassCardW;   // width of the tile the glass was last fitted to
    private boolean glassShown;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    /** The wallpaper file on screen (null for the plain colour); see WallpaperRotation. */
    private java.io.File wallpaperShown;
    /** live_background video over the wallpaper, or null (see LiveVideo). */
    private LiveVideo liveVideo;
    /** live_effect over the wallpaper or video, or null (see LiveEffectView). */
    private LiveEffectView liveEffect;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild the tiles on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    private AppLibraryDialog appLibrary;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
            handler.post(() -> retrySoon(true));
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateRace();
            updateWifiLabel();
            sky.update();
            updateNight(true);
            if (wallpaperPlayer != null) wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        // Before the wallpaper is made: parallax needs it zoomed for room to drift.
        parallax = cfg.parallax;
        openFromTile = cfg.openFromTile;
        inputsPill = cfg.inputsPill;
        nightCfg = cfg;
        if (cfg.sounds) {
            IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_ON);
            sf.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenReceiver, sf);
            TvSounds.get(this);   // load now, so the first event is ready
            // Started with the screen already on (the TV may kill Home in standby and start it again on
            // wake): the screen-on broadcast came before this receiver, so count this start as the wake.
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (savedInstanceState == null && pm != null && pm.isInteractive()
                    && android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() < 30_000L) {
                TvSounds.screenOn(this, "start");
            }
        }
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        if (cfg.wallpaperRotationMinutes > 0) {
            // wallpaper_rotation_minutes: the timed rotation draws the pictures itself (a second picture
            // otherwise); the power-on rotation, the video and the effects below need wallView, so they stay off.
            wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        } else {
            wallpaperShown = cfg.wallpaper;
            if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
        }
        // live_background video: over the picture (its poster), under the night shade. Not on low-RAM TVs.
        java.io.File video = cfg.liveVideo() && wallView != null && !isLowRam() ? Config.currentVideo(this) : null;
        if (video != null) {
            liveVideo = new LiveVideo(this, video, cfg.animations && !cfg.reducedMotion, cfg.liveVideoDim);
            root.addView(liveVideo.view(), root.indexOfChild(wallView) + 1,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // live_effect: under the night shade too; it is motion, so not with animations off.
        if (cfg.liveEffectOn() && wallView != null && nightShade != null && cfg.animations && !cfg.reducedMotion) {
            liveEffect = new LiveEffectView(this, cfg.liveEffect, density);
            root.addView(liveEffect, root.indexOfChild(nightShade),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (liveVideo != null) liveVideo.onFrame = liveEffect::videoFrame;
        }
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        if (festive) glass.setAccent(festival.accentColor());
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        ambientAlpha = Math.round(255 * cfg.ambientGlow / 100f);
        ambientGlow = cfg.ambientGlow > 0;
        if (!ambientGlow) ambient.setVisibility(View.GONE);
        if (cfg.textColor != 0) tintHeader(cfg.textColor);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // The hero is now a group (name, and the cinematic lines): without this every fade of the default
        // shelf would render it through an offscreen buffer first, as the glow above notes.
        hero.forceHasOverlappingRendering(false);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        heroArt = findViewById(R.id.heroArt);
        cinematic = cfg.cinematicHome;
        if (cinematic) setUpCinematicShelf();
        // dock_title false: the name above the dock (and the cinematic lines under it) never shows; the glow
        // still follows the dock.
        if (!cfg.dockTitle) hero.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        greetingColor = greeting.getCurrentTextColor();
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        raceCalendar = cfg.raceCalendar;
        racePanel = findViewById(R.id.racePanel);
        raceKicker = findViewById(R.id.raceKicker);
        raceHeadline = findViewById(R.id.raceHeadline);
        raceSessions = findViewById(R.id.raceSessions);
        showStats = cfg.showStats;
        shamsi = cfg.shamsiDate;
        greetingName = cfg.greetingName;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        singleRow = cfg.singleRow;
        pendingHeaderCfg = cfg;
        if (singleRow) styleHeroForRow(cfg.titleSize);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        if (singleRow) {
            // Every app in one row that scrolls sideways; the wrapper clips the tiles at the row's ends.
            grid.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, rtlAppGrid));
            // Sit on the bottom edge at the row's own height. The grid's trick of pushing the dock down
            // with top padding re-lays only some children of a horizontal list, leaving the rest stranded.
            FrameLayout.LayoutParams glp = (FrameLayout.LayoutParams) grid.getLayoutParams();
            glp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            glp.gravity = android.view.Gravity.BOTTOM;
            grid.setLayoutParams(glp);
            // Inset the tiles from the band's ends so the first and last focus have room, and clip them
            // there as they scroll; the band itself is drawn before the children, into the padding.
            int out = (int) (BAND_OUTSET_DP * density);
            ViewGroup.MarginLayoutParams wlp = (ViewGroup.MarginLayoutParams) ((View) grid.getParent()).getLayoutParams();
            wlp.setMarginStart(-out);
            wlp.setMarginEnd(-out);
            ((View) grid.getParent()).setLayoutParams(wlp);
            int side = (int) (ROW_SIDE_DP * density);   // tiles about 20dp inside the band's ends
            grid.setPadding(side, grid.getPaddingTop(), side, grid.getPaddingBottom());
            grid.setClipToPadding(true);
        } else grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (dockRow && getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                // A row already fully on screen stays put, even inside the bottom padding (the last row
                // rows_below_dock leaves visible ends there; nudging it up was the bug). A row that has to scroll
                // in still stops at the padding line, clear of the screen edge, as before.
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom <= getHeight()) dy = 0;
                else dy = Math.min(top, bottom - limitBottom);
                if (dy == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        // single_row lays the apps out with a LinearLayoutManager: no spans there.
        if (grid.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override public int getSpanSize(int position) { return adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
            });
        }
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (singleRow) {
            appsBand = new RowBandDecoration(density, cfg.bandOpacity);
            grid.addItemDecoration(appsBand);
        } else if (dockRow) grid.addItemDecoration(new DockDecoration(COLUMNS, density, cinematic, cfg.alwaysShowNames));
        if (animations) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        adapter.setSavedOrder(Order.ranks(Order.load(Order.file(this)), dockApps));
        grid.setAdapter(adapter);
        plexLanIp = cfg.plexLanIp;
        if (singleRow && cfg.plexRow) setUpPlexRow(cfg.bandOpacity);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = singleRow ? 0 : cfg.rowsBelowDock;
        if (!singleRow) grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when the theme requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? hero.getMeasuredHeight() + (int) (40 * density) : 0;
            int want = Math.max(shelfClearance, h - dockRow - gridRows - (int) (AppAdapter.BOTTOM_ROOM_DP * density));
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            // Cold start or column count > 4: the first focus can run before the grid has its final width or
            // padding, leaving the glass at the XML design size until a key is pressed.
            grid.post(this::refitGlass);
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glassTargetX -= dx;
                glassScrollShiftX -= dx;
                glass.setTranslationX(glass.getTranslationX() - dx);
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentX") {
            @Override
            public float getValue(View v) {
                return v.getTranslationX() - glassScrollShiftX;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationX(value + glassScrollShiftX);
            }
        });
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                // Stiff and almost critically damped: settles in a few frames instead of oscillating,
                // and stops once the highlight is within 1.5 px of its tile.
                s.setSpring(new SpringForce().setStiffness(1400f).setDampingRatio(0.95f));
                s.setMinimumVisibleChange(1.5f);
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                        .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnSound = findViewById(R.id.btnSound);
        btnFullScreen = findViewById(R.id.btnFullScreen);
        btnNext = findViewById(R.id.btnNext);
        // Next: with two or more clips (video) or pictures to go through.
        int nextCount = liveVideo != null ? WallpaperRotation.videos(Config.dir(this)).length
                : wallView != null ? WallpaperRotation.list(Config.dir(this)).length : 0;
        if (nextCount > 1) {
            btnNext.setVisibility(View.VISIBLE);
            btnNext.setOnClickListener(v -> nextBackground());
        }
        if (liveVideo != null) {
            btnSound.setVisibility(View.VISIBLE);
            btnFullScreen.setVisibility(View.VISIBLE);
            btnSound.setOnClickListener(v -> setVideoSound(!liveVideo.sound()));
            btnFullScreen.setOnClickListener(v -> setFullScreen(true));
        }
        if (cfg.topButtonsSize < 100) {
            // Smaller pills, anchored at the top bar's end so they keep their place in the corner.
            View bar = findViewById(R.id.topButtons);
            float s = cfg.topButtonsSize / 100f;
            bar.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
                v.setPivotX(v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 0 : r - l);
                v.setPivotY(singleRow ? 0 : b - tp);   // single_row: pinned to the top-right corner
            });
            bar.setScaleX(s);
            bar.setScaleY(s);
        }
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> open(systemIntent(Settings.ACTION_SETTINGS)));
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(cfg.reorderButton ? View.VISIBLE : View.GONE);
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        btnLibraryTop = findViewById(R.id.btnLibraryTop);
        btnLibraryTop.setOnClickListener(v -> {
            onLibraryRequested();
            libraryFromTopBar = true;   // closing it goes back to this button, not to the pill after the last row
        });
        btnLibraryTop.setVisibility(cfg.libraryButton && cfg.appLibrary ? View.VISIBLE : View.GONE);
        iconOnlyButtons = cfg.iconOnlyButtons;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(singleRow ? lastFocused : lastFocused % COLUMNS);
                return true;
            }
            int firstButton = btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : firstTopButton().getId();
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnSound.setOnKeyListener(pillKeys);
        btnFullScreen.setOnKeyListener(pillKeys);
        btnNext.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        btnLibraryTop.setOnKeyListener(pillKeys);
        if (advancedEffects || singleRow) {
            for (View button : new View[]{btnInputs, btnWifi, btnSound, btnFullScreen, btnNext, btnReorder, btnLibraryTop, btnSettings}) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(160).start());
            }
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null) openLauncherSettingsIfAsked(getIntent());
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once at the size CENTER_CROP shows, so it
     * costs a screen-sized bitmap (about 4 MB at 1080p), which is the price of a wallpaper on a 1 GB TV.
     * A picture over 4K is refused rather than decoded (#166); the plain colour stays.
     */
    /** A theme's text_color on the header texts, keeping each one's own transparency. */
    private void tintHeader(int color) {
        int[] ids = {R.id.heroName, R.id.greeting, R.id.clock, R.id.ampm, R.id.date, R.id.shamsiDate};
        for (int id : ids) {
            View v = findViewById(id);
            if (!(v instanceof TextView)) continue;
            TextView tv = (TextView) v;
            int alpha = tv.getCurrentTextColor() >>> 24;
            tv.setTextColor((alpha << 24) | (color & 0x00FFFFFF));
        }
    }

    /** Android's low-RAM flag, or under 2 GB of memory: those sets keep the half-size RGB_565 wallpaper. */
    private boolean isLowRam() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return true;
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return am.isLowRamDevice() || mi.totalMem < 2_000_000_000L;
    }

    private void showWallpaper(File file) {
        try {
            android.graphics.Bitmap bmp = decodeWallpaper(file);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            wallView = wall;
            nightShade = new View(this);
            nightShade.setBackgroundColor(0xFF000000);
            nightShade.setAlpha(0f);
            root.addView(nightShade, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (parallax) {
                // A little larger than the screen: the drift stays inside this margin, so no edge ever shows.
                wall.setScaleX(WALL_ZOOM);
                wall.setScaleY(WALL_ZOOM);
            }
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    /** The wallpaper decoded at the size CENTER_CROP shows (null when unreadable or over 4K); any thread. */
    private android.graphics.Bitmap decodeWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int w = opts.outWidth, h = opts.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = WallpaperSize.sampleSize(w, h, dm.widthPixels, dm.heightPixels);
            // The limit applies to what is decoded, so a 12 MP photo still works once it is sampled down.
            if (!WallpaperSize.accepted(w / sample, h / sample)) return null;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(w / sample, h / sample, dm.widthPixels, dm.heightPixels);
            if (scale != null) {
                opts.inScaled = true;
                opts.inDensity = scale[0];
                opts.inTargetDensity = scale[1];
            }
            // Full colour on TVs with RAM to spare (no banding in skies and gradients); RGB_565 halves the
            // bitmap on low-RAM sets, where it was the upstream default (#166).
            opts.inPreferredConfig = isLowRam() ? android.graphics.Bitmap.Config.RGB_565
                    : android.graphics.Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setFullScreen(false);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        if (stopped) {
            rebuildOnResume = true;
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            grid.post(() -> focusTile(0));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        started = true;
        // TCL brings Home up at power-on, seconds before SCREEN_ON: the earliest sign of a wake.
        if (nightCfg != null && nightCfg.sounds) TvSounds.homeShown(this);
        if (stoppedAsleep) {
            stoppedAsleep = false;
            plexLoadedAt = Long.MIN_VALUE / 2;
            statusAt = Long.MIN_VALUE / 2;
            // Every power-on starts without the video's sound.
            setVideoSound(false);
            // Turned on after an hour or more with the same picture and video: the next ones (onResume rebuilds).
            if (Config.advanceRotation(this)) android.util.Log.i("tvhome-wall", "power-on: next picture and video");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        // A rotating wallpaper whose day has passed while Home was in the background: show the next one.
        // Only when a picture is showing, so a theme with the wallpaper switched off never loops here.
        if (wallpaperShown != null && !wallpaperShown.equals(Config.currentWallpaper(this))) {
            recreate();
            return;
        }
        // The same for the day's video.
        if (liveVideo != null && !liveVideo.file.equals(Config.currentVideo(this))) {
            recreate();
            return;
        }
        restartIdleClock();
        promptsPaused = false;
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        resumed = true;
        if (liveVideo != null) liveVideo.start();
        if (liveEffect != null) liveEffect.start();
        if (plex != null && (plexFailed || android.os.SystemClock.elapsedRealtime() - plexLoadedAt > PLEX_REFRESH_MS)) loadPlex();
        if (statusFailed) statusAt = Long.MIN_VALUE / 2;
        updateGreeting();
        updateRace();
        // The network and Inputs pills keep their state while Home is in the background, and callbacks update them
        // when the network or the inputs change, so this refresh only catches anything missed. Its binder calls
        // (about 7 ms on a TCL C755, most of it WifiManager.getConnectionInfo) would hold up the first frame back
        // on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        if (showStats) startStats();
        updateNight(false);
        NightMode.apply(this, nightCfg);
        sky.update();
        if (wallpaperPlayer != null) wallpaperPlayer.start();
        handler.postDelayed(minuteTick, MINUTE_MS);
        handler.removeCallbacks(statusTick);
        handler.post(statusTick);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
        }
        reloadTiles();
        if (animate && animations && !reducedMotion) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // An updated app may bring new artwork; the backdrop is cheap to make again.
        heroArtCache.clear();
        heroArtKey = null;
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps,
                () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback);
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        root.removeCallbacks(showIdleClock);
        endReorder();   // Home or another app while reordering keeps the order so far
        if (wallpaperPlayer != null) wallpaperPlayer.stop();
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(statusTick);
        handler.removeCallbacks(retryFailed);
        resumed = false;
        setFullScreen(false);
        if (liveVideo != null) liveVideo.stop();
        if (liveEffect != null) liveEffect.stop();
        stopStats();
        if (diya != null) diya.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        unregisterReceiver(packageReceiver);
        unregisterReceiver(timeReceiver);
        handler.removeCallbacks(clockCheck);
        stopped = true;
        rebuildOnResume = true;
        if (nightCfg != null && nightCfg.sounds) TvSounds.homeStopped(this);
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        stoppedAsleep = pm != null && !pm.isInteractive();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        boolean due = level >= TRIM_MEMORY_BACKGROUND
                || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL;
        if (!due || started) return;
        adapter.clear();
        // Detach the adapter too: with Home stopped no layout pass runs, so the tile views (and the bitmaps
        // they hold) would stay attached until Home comes back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        heroArtCache.clear();
        heroArtKey = null;
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            showCardPrompt();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (wallpaperPlayer != null) wallpaperPlayer.close();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /** The next video and picture of the shuffle, in place: Home is not rebuilt. */
    private void nextBackground() {
        Config.advanceRotationNow(this);
        java.io.File pic = Config.currentWallpaper(this);
        if (pic != null && wallView instanceof android.widget.ImageView && !pic.equals(wallpaperShown)) {
            wallpaperShown = pic;   // before onResume can see a mismatch and rebuild
            WALL_EXEC.execute(() -> {
                android.graphics.Bitmap bmp = decodeWallpaper(pic);
                runOnUiThread(() -> {
                    if (bmp != null && pic.equals(wallpaperShown)) crossfadeWallpaper((android.widget.ImageView) wallView, bmp);
                });
            });
        }
        if (liveVideo != null) {
            java.io.File clip = Config.currentVideo(this);
            if (clip != null && !clip.equals(liveVideo.file)) liveVideo.switchTo(clip);
        }
    }

    private void crossfadeWallpaper(android.widget.ImageView wall, android.graphics.Bitmap bmp) {
        android.graphics.drawable.Drawable old = wall.getDrawable();
        if (old == null || !animations) {
            wall.setImageBitmap(bmp);
            return;
        }
        android.graphics.drawable.TransitionDrawable t = new android.graphics.drawable.TransitionDrawable(
                new android.graphics.drawable.Drawable[]{old, new android.graphics.drawable.BitmapDrawable(getResources(), bmp)});
        t.setCrossFadeEnabled(true);
        wall.setImageDrawable(t);
        t.startTransition(500);
        // Then the new picture alone, so the old bitmap can go.
        handler.postDelayed(() -> {
            if (wall.getDrawable() == t) wall.setImageBitmap(bmp);
        }, 600);
    }

    private void setVideoSound(boolean on) {
        if (liveVideo == null || btnSound == null) return;
        liveVideo.setSound(on);
        btnSound.setCompoundDrawablesRelativeWithIntrinsicBounds(on ? R.drawable.ic_volume_up : R.drawable.ic_volume_off, 0, 0, 0);
        keepScreenOn();
    }

    /**
     * Full screen: the video is lifted over everything (the UI, the effects and the night shade stay where
     * they are, underneath) and its dim layer comes off. Any key but volume brings Home back.
     */
    private void setFullScreen(boolean on) {
        if (liveVideo == null || fullScreen == on) return;
        fullScreen = on;
        liveVideo.view().setTranslationZ(on ? 1000f : 0f);
        liveVideo.setDimmed(!on);
        if (liveEffect != null) {
            if (on) liveEffect.stop();
            else if (resumed) liveEffect.start();
        }
        keepScreenOn();
    }

    /** No screen saver while the video is watched full screen or heard. */
    private void keepScreenOn() {
        boolean keep = fullScreen || (liveVideo != null && liveVideo.sound());
        if (keep) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (fullScreen) {
            int k = event.getKeyCode();
            if (k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN && k != KeyEvent.KEYCODE_VOLUME_MUTE) {
                // On release, so the press that ends full screen does nothing else.
                if (event.getAction() == KeyEvent.ACTION_UP) setFullScreen(false);
                return true;
            }
        }
        if (handleSettingsKey(event)) return true;
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
        }
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        dimOthers();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        if (dockEditSlot >= 0 && pos >= 0 && pos < COLUMNS && pos != dockEditSlot) return 0.45f;
        return 1f;
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        dockEditing = false;
        dockEditSlot = -1;
        drop();
        reordering = false;
        adapter.setReordering(false);
        glass.setAccent(festive ? festival.accentColor() : 0);
        btnReorder.setActivated(false);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        if (!keys.equals(before)) {
            Order order = new Order(Order.dockKey(dockApps), keys);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        LinearLayoutManager lm = (LinearLayoutManager) grid.getLayoutManager();   // a GridLayoutManager but in single_row
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView != null ? lm.getDecoratedTop(firstView) - grid.getPaddingTop() : 0;
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onLibraryRequested() {
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        libraryFromTopBar = false;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            if (libraryFromTopBar) {
                libraryFromTopBar = false;
                btnLibraryTop.requestFocus();
                return;
            }
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        });
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        setRowFocus(false);
        updateParallax();
        lastFocusedKey = adapter.keyAt(position);
        // Focus restored before the first layout (cold start): the tile has no size yet, and the glass
        // would be sized to 2*pad. Run again once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);
        if (singleRow && grid.getTranslationY() != 0f) y += grid.getTranslationY();   // the row is shifted (plex_row)

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassCardW = card.getWidth();
        glassTargetX = x;
        glassTargetY = y;
        // Cinematic shelf: the highlight glows in the dock app's colour, plain white in the grid.
        int accent = cinematic && !festive && position < COLUMNS ? adapter.ambientColorAt(position) : 0;
        if (cinematic && !festive && accent != glassAccent) {
            glassAccent = accent;
            glass.setAccent(accent);
        }
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations) glass.animate().alpha(1f).setDuration(reducedMotion ? 100 : 180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x - glassScrollShiftX);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (adapter.inDock(position)) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            // Below the dock (or in a plain grid) the glow follows the tile without the big name; letting it
            // fade out left the grid on plain black.
            showGlowOnly(position);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            revealRect.set(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
        }
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        float pad = 6f * density;
        float scale = adapter.focusScale();
        int w = (int) (card.getWidth() * scale + 2 * pad);
        int h = (int) (card.getHeight() * scale + 2 * pad);
        if (!glassShown || card.getWidth() != glassCardW || glass.getWidth() != w || glass.getHeight() != h) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        if (singleRow) {
            appsCardTop = dockTopInRoot;
            heroBottom = appsRowTopVisual() - 12 * density;
            alignHeroWithFirstTile();
        }
        float y = singleRow ? heroBottom - hero.getHeight()
                : dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        heroY = y;
        if (heroTextPx > 0 && heroName.getTextSize() != heroTextPx) heroName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, heroTextPx);
        boolean changed = !label.contentEquals(heroName.getText());
        if (cinematic) showHeroArt(position);

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            if (ambientGlow) {
                setAmbient(color);
                if (!animations) {
                    ambient.setAlpha(1f);
                } else if (!heroShown) {
                    ambient.setAlpha(0f);
                    ambient.animate().alpha(1f).setDuration(160).start();
                }
            }
            heroShown = true;
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
            if (ambientGlow) {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(600).start();
            }
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) hero.animate().cancel();
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                fillHero(position, label);
                hero.setY(y);
                hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (ambientGlow && advancedEffects) {
                transitionAmbient(color);
            } else if (ambientGlow && color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            hero.setY(y);
        }
    }

    /** The shelf's text for this dock app: its name and, on the cinematic shelf, "Last opened" and the dots. */
    private void fillHero(int position, String label) {
        heroName.setText(label);
        if (!cinematic) return;
        updateHeroMeta(position);
        updateHeroDots(position);
    }

    // ---- cinematic shelf -------------------------------------------------------------------------

    /** The parts that never change: a larger name, the meta line and the dots, the artwork and the scrim. */
    private void setUpCinematicShelf() {
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f);
        heroName.setLetterSpacing(-0.025f);
        heroMeta.setVisibility(View.INVISIBLE);   // keeps its height, so the name does not jump when it fills in
        heroDots.setVisibility(View.VISIBLE);
        heroArt.setVisibility(View.VISIBLE);
        // One stretched bitmap: apply its alpha while drawing instead of through an offscreen buffer.
        heroArt.forceHasOverlappingRendering(false);
        View scrim = findViewById(R.id.heroScrim);
        scrim.setBackground(scrimDrawable());
        scrim.setVisibility(View.VISIBLE);
    }

    /**
     * Darkens the side the shelf's text is on and the lower part of the screen, so the name, the dock and
     * the grid stay readable over the artwork. One small bitmap stretched over the screen, like the glow.
     */
    private Drawable scrimDrawable() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(rtl ? w : 0, 0, rtl ? 0 : w, 0,
                new int[]{0xD1060406, 0x59060406, 0x00060406}, new float[]{0f, 0.42f, 0.7f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00060406, 0x00060406, 0xDB060406, 0xFF060406}, new float[]{0f, 0.28f, 0.58f, 0.76f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable scrim = new BitmapDrawable(getResources(), bmp);
        scrim.setGravity(Gravity.FILL);
        return scrim;
    }

    /** Swaps the backdrop to this app's artwork: fade out, swap, fade in, the way the shelf's name changes. */
    private void showHeroArt(int position) {
        String key = adapter.keyAt(position);
        if (key == null) return;
        heroArt.animate().cancel();
        if (key.equals(heroArtKey)) {
            fadeHeroArt(420);
            return;
        }
        Bitmap art = heroArtCache.get(key);
        if (art == null) {
            art = HeroArt.from(adapter.artworkAt(position));
            if (art != null) heroArtCache.put(key, art);
        }
        heroArtKey = key;
        final Bitmap next = art;
        if (!animations || reducedMotion || heroArt.getAlpha() == 0f) {
            heroArt.setImageBitmap(next);
            fadeHeroArt(600);
            return;
        }
        heroArt.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            heroArt.setImageBitmap(next);
            heroArt.animate().alpha(HERO_ART_ALPHA).setDuration(420).start();
        }).start();
    }

    private void fadeHeroArt(int ms) {
        if (!animations) heroArt.setAlpha(HERO_ART_ALPHA);
        else heroArt.animate().alpha(HERO_ART_ALPHA).setDuration(reducedMotion ? Math.min(ms, 160) : ms).start();
    }

    /** "Last opened yesterday, 9:40 PM", the time in white; nothing (but the same height) when it was never seen. */
    private void updateHeroMeta(int position) {
        long when = LastOpened.get(this, adapter.packageAt(position));
        if (when <= 0) {
            heroMeta.setVisibility(View.INVISIBLE);
            return;
        }
        String rel = LastOpened.describe(this, when);
        String text = getString(R.string.last_opened, rel);
        SpannableString line = new SpannableString(text);
        int at = text.indexOf(rel);
        if (at >= 0) line.setSpan(new ForegroundColorSpan(0xFFFFFFFF), at, at + rel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heroMeta.setText(line);
        heroMeta.setVisibility(View.VISIBLE);
    }

    /** One dot per dock app, the focused one drawn long and white. Hebrew docks start on the right, so do the dots. */
    private void updateHeroDots(int position) {
        int count = Math.min(adapter.dockFilled(), COLUMNS);
        heroDots.setVisibility(count > 1 ? View.VISIBLE : View.INVISIBLE);
        if (heroDots.getChildCount() != count) {
            heroDots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View dot = new View(this);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(3 * density);
                dot.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (6 * density), (int) (6 * density));
                if (i > 0) lp.leftMargin = (int) (6 * density);
                heroDots.addView(dot, lp);
            }
        }
        for (int i = 0; i < count; i++) {
            View dot = heroDots.getChildAt(rtlAppGrid ? count - 1 - i : i);
            boolean on = i == position;
            ((GradientDrawable) dot.getBackground()).setColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            int w = (int) ((on ? 20 : 6) * density);
            if (lp.width != w) {
                lp.width = w;
                dot.setLayoutParams(lp);
            }
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (cinematic) {
                // Higher and stronger, from the corner away from the shelf's text: the app's colour floods
                // the top of the screen over its artwork, and the scrim keeps the text side dark.
                boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
                paint.setShader(new RadialGradient(
                        w * (rtl ? 0.20f : 0.80f), h * 0.06f, w * 0.78f,
                        new int[]{0xA6FFFFFF, 0x47FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new RadialGradient(
                        w * 0.32f, h * 0.42f, w * 0.62f,
                        new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
        glow.setAlpha(ambientAlpha);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    /**
     * Grid tiles (every tile with dock_row false): the glow behind the grid takes the focused tile's colour; the big
     * name above the dock does not show (every grid tile has its name underneath).
     */
    private void showGlowOnly(int position) {
        int color = adapter.ambientColorAt(position);
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        hero.setAlpha(0f);
        heroArt.animate().cancel();
        heroArt.setAlpha(0f);
        heroShown = false;
        if (!ambientGlow) return;
        if (!animations || reducedMotion) {
            setAmbient(color);
            ambient.setAlpha(1f);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            ambient.animate().alpha(1f).setDuration(300).start();
            return;
        }
        // The same dip-and-swap as the dock's top shelf.
        ambient.animate().alpha(0.35f).setDuration(ambient.getAlpha() > 0.35f ? 200 : 0).withEndAction(() -> {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(500).start();
        }).start();
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        heroShown = false;
        if (animations) {
            hero.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            if (ambientGlow) ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
            heroArt.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused (the quick panel or an app on top): keep the last line on screen. Taking it away moved the grid up
        // under a glass highlight that stayed put.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long mem = memAvailableMb();
        if (mem >= 0) appendStat(line, getString(R.string.stats_memory, mem));
        String free = formatGb(Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return line.toString();
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    private long memAvailableMb() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] t = line.split("\\s+");
                    return t.length >= 2 ? Long.parseLong(t[1]) / 1024 : -1;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return -1;
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? String.format(Locale.US, "%.0f GB", gb)
                : String.format(Locale.US, "%.1f GB", gb);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        if (singleRow) {
            // One row: Left/Right stop at its ends, Up goes to the top bar, Down goes nowhere.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return GridNavigation.atHorizontalEdge(position, count, Math.max(1, count),
                        rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && plexRow != null && plexRow.getVisibility() == View.VISIBLE) {
                focusPlex();
                return true;
            }
            return keyCode != KeyEvent.KEYCODE_DPAD_UP;
        }
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                focusLibrary();
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    @Override
    public boolean onTileMenu(int position) {
        String pkg = adapter.pkgAt(position);
        if (pkg == null) return false;
        String[] items = {getString(R.string.move_left), getString(R.string.move_right),
                getString(R.string.hide_app), getString(R.string.app_info)};
        new AlertDialog.Builder(this)
                .setTitle(adapter.labelAt(position))
                .setItems(items, (dialog, which) -> {
                    if (which <= 1) {
                        int delta = (which == 0) != rtlAppGrid ? -1 : 1;
                        TileOrder.saveOrder(this, TileOrder.move(adapter.currentOrder(), pkg, delta));
                        reloadApps(false);   // focus follows the app (lastFocusedKey)
                    } else if (which == 2) {
                        TileOrder.hide(this, pkg);
                        reloadApps(false);
                    } else {
                        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                    }
                })
                .show();
        return true;
    }

    // ---- Plex recommended row (single_row + plex_row) --------------------------------------------

    /**
     * single_row: the title gets a soft black edge and a faint dark box that hugs it, so it reads over
     * any wallpaper; title_size sets its size. Two lines are allowed for a Plex card's season line.
     */
    private void styleHeroForRow(int titleSizeSp) {
        if (titleSizeSp >= 20) heroName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSizeSp);
        heroTextPx = heroName.getTextSize();
        heroName.setShadowLayer(3f * density, 0f, 0f, 0xE6000000);
        android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
        panel.setColor(0x40000000);
        panel.setCornerRadius(8 * density);
        // The box sits close round the title, as much room above the capitals as below the baseline (~0.3 em):
        // Outfit's line box has a third of an em above the capitals, so a little of it is cut off.
        android.graphics.Rect cap = new android.graphics.Rect();
        heroName.getPaint().getTextBounds("H", 0, 1, cap);   // cap.top is minus the cap height
        int over = Math.max(0, Math.round(-heroName.getPaint().getFontMetrics().ascent + cap.top - 0.29f * heroTextPx));
        heroName.setBackground(new android.graphics.drawable.InsetDrawable(panel, 0, over, 0, 0));
        int ph = (int) (9 * density);
        heroName.setPadding(ph, 0, ph, (int) (2 * density));
        heroName.setSingleLine(false);
        heroName.setMaxLines(2);
        heroName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) hero.getLayoutParams();
        lp.setMarginStart(Math.max(0, lp.getMarginStart() - ph));
        hero.setLayoutParams(lp);
        // Clock and greeting closer to the top-left corner, the pills as close to the top-right one.
        View header = (View) findViewById(R.id.leftBlock).getParent();
        ViewGroup.MarginLayoutParams hp = (ViewGroup.MarginLayoutParams) header.getLayoutParams();
        hp.setMarginStart((int) (-24 * density));
        hp.setMarginEnd((int) (-24 * density));
        hp.topMargin = (int) (-12 * density);
        // The clock, date and notes on the same soft box as the title, as wide as their widest line; the header
        // moves out by the box's padding so the texts keep their place.
        View leftBlock = findViewById(R.id.leftBlock);
        android.graphics.drawable.GradientDrawable leftPanel = new android.graphics.drawable.GradientDrawable();
        leftPanel.setColor(0x40000000);
        leftPanel.setCornerRadius(8 * density);
        leftBlock.setBackground(leftPanel);
        int bx = (int) (12 * density), by = (int) (8 * density);
        leftBlock.setPadding(bx, by, bx, by);
        // RelativeLayout makes a view tied to both sides exactly that wide, wrap_content or not: let go of the pills.
        android.widget.RelativeLayout.LayoutParams llp = (android.widget.RelativeLayout.LayoutParams) leftBlock.getLayoutParams();
        llp.removeRule(android.widget.RelativeLayout.START_OF);
        llp.removeRule(android.widget.RelativeLayout.LEFT_OF);
        llp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        leftBlock.setLayoutParams(llp);
        hp.setMarginStart(hp.getMarginStart() - bx);
        hp.topMargin -= by;
        header.setLayoutParams(hp);
        rebuildHeader(nightCfgOrNull());
        // A wrap_content LinearLayout leaves match_parent children out of its width (the date was cut short):
        // the greeting, the clock row, the date column (re-added by rebuildHeader) and its lines wrap their text.
        for (int id : new int[]{R.id.greeting, R.id.clock, R.id.date, R.id.shamsiDate}) {
            View v = findViewById(id);
            if (id != R.id.clock) v.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (id != R.id.greeting) ((View) v.getParent()).getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        // The pills sit at the top-right corner instead of on the date line.
        View bar = findViewById(R.id.topButtons);
        android.widget.RelativeLayout.LayoutParams blp = (android.widget.RelativeLayout.LayoutParams) bar.getLayoutParams();
        blp.removeRule(android.widget.RelativeLayout.ALIGN_BOTTOM);
        blp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
        blp.topMargin = (int) (4 * density) + by;   // the header moved up by the left box's padding
        blp.bottomMargin = 0;
        blp.setMarginEnd((int) (-10 * density));
        bar.setLayoutParams(blp);
        // The column around it would clip the header at its padding otherwise.
        ViewGroup column = (ViewGroup) header.getParent();
        column.setClipToPadding(false);
        column.setClipChildren(false);
        // One line for an app, two for a Plex card: keep the bottom edge on the band either way.
        hero.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            if (!Float.isNaN(heroBottom) && (b - tp) != (ob - ot)) v.setY(heroBottom - (b - tp));
        });
    }

    /** Once, while the row is at its start: the title box's left edge on the first tile's left edge. */
    private void alignHeroWithFirstTile() {
        if (heroAligned || grid.computeHorizontalScrollOffset() != 0) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
        if (vh == null) return;
        View card = vh.itemView.findViewById(R.id.card);
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        if (r.left <= 0) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) hero.getLayoutParams();
        int start = Math.max(0, r.left - heroName.getPaddingLeft());   // the text, not the box, on the tile's edge
        if (lp.getMarginStart() != start) {
            lp.setMarginStart(start);
            hero.setLayoutParams(lp);
        }
        heroAligned = true;
    }

    private Config pendingHeaderCfg;
    private Config nightCfgOrNull() { return pendingHeaderCfg; }

    /**
     * single_row: the date moves under the clock ("20 October Tuesday"), then a thin line, then the
     * status lines when the theme names servers or a usage endpoint.
     */
    private void rebuildHeader(Config cfg) {
        TextView clock = findViewById(R.id.clock);
        ViewGroup row = (ViewGroup) clock.getParent();
        ViewGroup left = (ViewGroup) row.getParent();
        TextView date = findViewById(R.id.date);
        View dateCol = (View) date.getParent();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v != clock && v.getId() != R.id.ampm && v != dateCol) v.setVisibility(View.GONE);   // the vertical rule
        }
        row.removeView(dateCol);
        int at = left.indexOfChild(row) + 1;
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMarginStart((int) (2 * density));
        left.addView(dateCol, at++, dlp);
        dateCol.setPadding(0, 0, 0, 0);
        if (date instanceof android.widget.TextClock) {
            ((android.widget.TextClock) date).setFormat12Hour("d MMMM EEEE");
            ((android.widget.TextClock) date).setFormat24Hour("d MMMM EEEE");
        }
        date.setAllCaps(false);
        date.setTextSize(16f);
        boolean status = cfg != null && (!cfg.statusNodes.isEmpty() || !cfg.statusUsageUrl.isEmpty());
        if (!status) return;
        int accent = cfg.textColor != 0 ? cfg.textColor : 0xFFFFFFFF;
        View rule = new View(this);
        statusRule = rule;
        rule.setVisibility(View.GONE);
        rule.setBackgroundColor((0x55 << 24) | (accent & 0x00FFFFFF));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams((int) (300 * density), Math.max(1, (int) density));
        rlp.topMargin = (int) (8 * density);
        rlp.bottomMargin = (int) (6 * density);
        rlp.setMarginStart((int) (2 * density));
        left.addView(rule, at++, rlp);
        // Two small columns, like notes: "AI" (usage windows) | "Tech" (servers).
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        statusUsage = statusLine(accent);
        statusNodes = statusLine(accent);
        // Nothing until the first answer: no placeholder text, no empty line.
        statusUsage.setVisibility(View.GONE);
        statusNodes.setVisibility(View.GONE);
        View bar = new View(this);
        statusBar = bar;
        bar.setVisibility(View.GONE);
        bar.setBackgroundColor((0x55 << 24) | (accent & 0x00FFFFFF));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Math.max(1, (int) density), ViewGroup.LayoutParams.MATCH_PARENT);
        blp.setMarginStart((int) (14 * density));
        blp.setMarginEnd((int) (14 * density));
        box.addView(statusUsage);
        box.addView(bar, blp);
        box.addView(statusNodes);
        left.addView(box, at, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The line is as long as the notes under it.
        box.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            if (w > 0 && rule.getLayoutParams().width != w) {
                rule.getLayoutParams().width = w;
                rule.requestLayout();
            }
        });
    }

    private TextView statusLine(int accent) {
        TextView v = new TextView(this);
        v.setTextSize(11f);
        v.setTextColor((0xB3 << 24) | (accent & 0x00FFFFFF));
        v.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.outfit_light));
        v.setLineSpacing(0f, 1.1f);
        v.setPadding((int) (2 * density), 0, 0, 0);
        v.setShadowLayer(2f * density, 0f, 0f, 0x99000000);
        return v;
    }

    /** Reads the servers and the usage endpoint off the main thread; CPU needs a second read 3 s later at first. */
    private void refreshStatus() {
        Config cfg = nightCfg;
        if (cfg == null || (statusNodes == null && statusUsage == null)) return;
        STATUS_EXEC.execute(() -> {
            boolean primed = cfg.statusNodes.isEmpty();
            String nodes = cfg.statusNodes.isEmpty() ? "" : serverStatus.nodesLine(cfg.statusNodes);
            if (nodes.contains("…")) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                nodes = serverStatus.nodesLine(cfg.statusNodes);
            }
            final boolean nodesFailed = !cfg.statusNodes.isEmpty() && serverStatus.unreachable > 0;
            String usage = cfg.statusUsageUrl.isEmpty() ? "" : ServerStatus.usageLine(cfg.statusUsageUrl);
            final boolean usageFailed = usage == null;
            final String n = nodes, u = usage == null ? "" : usage;
            runOnUiThread(() -> {
                statusFailed = nodesFailed || usageFailed;
                retrySoon(false);
                // Not one answer (no network yet after power-on): the last values stay.
                if (!(n.isEmpty() && nodesFailed)) setStatus(statusNodes, n.isEmpty() ? "" : "Tech  (cpu · ram · temp)\n" + n);
                if (!usageFailed) setStatus(statusUsage, u.isEmpty() ? "" : "AI\n" + u);
            });
        });
    }

    /** A note column: hidden while it has nothing to say, faded in when it first does. */
    private void setStatus(TextView v, String text) {
        if (v == null) return;
        v.setText(text);
        fadeVisible(v, !text.isEmpty());
        boolean usage = statusUsage != null && statusUsage.getVisibility() == View.VISIBLE;
        boolean nodes = statusNodes != null && statusNodes.getVisibility() == View.VISIBLE;
        if (statusRule != null) fadeVisible(statusRule, usage || nodes);
        if (statusBar != null) fadeVisible(statusBar, usage && nodes);
    }

    private void fadeVisible(View v, boolean show) {
        if (!show) {
            v.animate().cancel();
            v.setVisibility(View.GONE);
            return;
        }
        if (v.getVisibility() == View.VISIBLE) return;
        v.setVisibility(View.VISIBLE);
        if (!animations) return;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(reducedMotion ? 150 : 350).start();
    }

    /** The status lines now, then every five minutes again. */
    private void restartStatus() {
        statusAt = Long.MIN_VALUE / 2;
        handler.removeCallbacks(statusTick);
        if (resumed) handler.post(statusTick);
    }

    /** Another try at whatever failed; after a network change the retry count starts over. */
    private void retrySoon(boolean networkChanged) {
        if (!plexFailed && !statusFailed) {
            failRetries = 0;
            return;
        }
        if (networkChanged) failRetries = 0;
        handler.removeCallbacks(retryFailed);
        long delay = networkChanged ? NET_SETTLE_MS : failRetries == 0 ? FIRST_RETRY_MS : RETRY_MS;
        if (resumed) handler.postDelayed(retryFailed, delay);
    }

    /** Long Plex titles shrink (down to 24sp) to fit the screen instead of ending in an ellipsis. */
    private void fitHeroText(String label) {
        if (heroTextPx <= 0) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) hero.getLayoutParams();
        float room = root.getWidth() - lp.getMarginStart() - heroName.getPaddingLeft() - heroName.getPaddingRight()
                - 48 * density;
        float size = heroTextPx, min = 24 * getResources().getDisplayMetrics().scaledDensity;
        android.text.TextPaint paint = new android.text.TextPaint(heroName.getPaint());
        paint.setTextSize(size);
        String first = label.split("\n", 2)[0];
        while (size > min && paint.measureText(first) > room) {
            size -= 2 * density;
            paint.setTextSize(size);
        }
    }

    /** The top shelf title for a Plex card, where the app titles sit; the season or episode on a smaller second line. */
    private void showHeroText(String title, String subtitle) {
        if (Float.isNaN(heroBottom)) return;
        String plain = subtitle.isEmpty() ? title : title + "\n" + subtitle;
        fitHeroText(plain);
        CharSequence label = plain;
        if (!subtitle.isEmpty()) {
            android.text.SpannableString s = new android.text.SpannableString(plain);
            int from = title.length() + 1;
            s.setSpan(new android.text.style.RelativeSizeSpan(0.5f), from, plain.length(), 0);
            int c = heroName.getCurrentTextColor();
            s.setSpan(new android.text.style.ForegroundColorSpan((0xCC << 24) | (c & 0x00FFFFFF)), from, plain.length(), 0);
            label = s;
        }
        final CharSequence text = label;
        hero.animate().cancel();
        if (!heroShown || !animations || reducedMotion) {
            heroName.setText(text);
            hero.setY(heroBottom - hero.getHeight());
            hero.setTranslationX(0f);
            if (animations) hero.animate().alpha(1f).setDuration(200).start();
            else hero.setAlpha(1f);
        } else if (!plain.contentEquals(heroName.getText().toString())) {
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(text);
                hero.setY(heroBottom - hero.getHeight());
                hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
        }
        heroShown = true;
    }

    private void setUpPlexRow(int bandOpacity) {
        plex = new PlexClient(this);
        plexAdapter = new PlexRowAdapter(plex, this);
        plexRow = new RecyclerView(this);
        plexRow.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, rtlAppGrid));
        plexRow.setAdapter(plexAdapter);
        plexRow.setItemAnimator(null);
        plexRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        RowBandDecoration plexBand = new RowBandDecoration(density, bandOpacity);
        plexBand.draw = false;
        plexRow.addItemDecoration(plexBand);
        if (appsBand != null) appsBand.draw = false;
        int side = (int) (ROW_SIDE_DP * density);
        plexRow.setPadding(side, (int) (8 * density), side, (int) (16 * density));
        plexRow.setClipToPadding(true);
        plexRow.setVisibility(View.GONE);
        ViewGroup wrap = (ViewGroup) grid.getParent();
        wrap.addView(plexRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        bands = new RowBands(this, density, bandOpacity, grid, plexRow);
        wrap.addView(bands, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View.OnLayoutChangeListener redraw = (v, l, tp, r, b, ol, ot, or, ob) -> bands.invalidate();
        grid.addOnLayoutChangeListener(redraw);
        plexRow.addOnLayoutChangeListener(redraw);
        wrap.post(() -> {
            rowWidth = wrap.getWidth();
            bands.setNormalWidth(rowWidth);
            // About six cards on screen: the cut-off one says the row scrolls on.
            plexAdapter.setSize(wrap.getWidth() - 2 * side, 5.6f, density);
            loadPlex();
        });
    }

    private void loadPlex() {
        if (plex == null || plexLoading || !plex.linked()) return;
        plexLoading = true;
        PLEX_EXEC.execute(() -> {
            List<PlexClient.Item> got = null;
            for (int attempt = 0; attempt < 2 && got == null; attempt++) {
                try {
                    if (!plex.hasServer() && !plex.discover(plexLanIp)) {
                        android.util.Log.w("tvhome-plex", "no reachable server");
                        break;
                    }
                    got = plex.recommended(20);
                    android.util.Log.i("tvhome-plex", "loaded " + got.size() + " items from " + plex.serverName());
                } catch (Exception e) {
                    // Never the token: request() errors carry only the HTTP status or the host. A stored
                    // address that stopped answering is forgotten and looked up once more right away.
                    android.util.Log.w("tvhome-plex", "load failed: " + e.getClass().getSimpleName());
                    plex.forgetServer();
                }
            }
            final List<PlexClient.Item> items = got;
            runOnUiThread(() -> {
                plexLoading = false;
                plexLoadedAt = android.os.SystemClock.elapsedRealtime();
                plexFailed = items == null;
                retrySoon(false);
                showPlexItems(items);
            });
        });
    }

    private void showPlexItems(List<PlexClient.Item> items) {
        if (plexRow == null) return;
        // A failed refresh keeps the cards: on power-on the network is back only seconds after Home asks.
        if (items == null && plexRow.getVisibility() == View.VISIBLE) return;
        FrameLayout.LayoutParams glp = (FrameLayout.LayoutParams) grid.getLayoutParams();
        if (items == null || items.isEmpty()) {
            if (plexRow.hasFocus()) focusTile(lastFocused);
            plexRow.setVisibility(View.GONE);
            setRowWide(grid, false);
            applyRows(1f, 1f);
            grid.setAlpha(1f);
            glp.bottomMargin = 0;
            grid.setLayoutParams(glp);
            return;
        }
        final boolean slideIn = plexRow.getVisibility() != View.VISIBLE && animations && !reducedMotion
                && grid.getHeight() > 0;
        final float appsBottom = grid.getBottom() + grid.getTranslationY();
        plexAdapter.setItems(items);
        if (slideIn) {
            plexRow.setAlpha(0f);
            bands.setShown(plexRow, 0f);
            // Off at once, not faded: the row is about to move under them. Both come back on its tile.
            glass.animate().cancel();
            glass.setAlpha(0f);
            glassShown = false;
            hideHero();
            hero.animate().cancel();
            hero.setAlpha(0f);
        }
        plexRow.setVisibility(View.VISIBLE);
        plexRow.post(() -> {
            // The app row sits just above the Plex band; the tiles' hidden label space is taken back.
            glp.bottomMargin = Math.max(0, plexRow.getHeight() - (int) (78 * density));
            grid.setLayoutParams(glp);
            if (slideIn) {
                // In the frame of the new layout, before it is drawn: the app row never shows in its new place early.
                grid.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        grid.getViewTreeObserver().removeOnPreDrawListener(this);
                        placeRows();
                        slidePlexIn(appsBottom);
                        return true;
                    }
                });
                return;
            }
            // The app row moved up: once it is laid out there, put the highlight and title back on its tile.
            grid.post(() -> grid.post(() -> {
                placeRows();
                if (grid.hasFocus()) focusTile(lastFocused);
            }));
        });
    }

    /** Widths, scales and shifts for the focused row, with both rows laid out. */
    private void placeRows() {
        setRowWide(plexFocused ? grid : plexRow, true);
        setRowWide(plexFocused ? plexRow : grid, false);
        rowProgress.setValue(plexFocused ? 1f : 0f);
        applyProgress(plexFocused ? 1f : 0f);
    }

    /** The first Plex cards: the app row slides up from where it stood alone and the Plex row rises in under it. */
    private void slidePlexIn(float appsBottomBefore) {
        final float appsTy = grid.getTranslationY();
        final float appsFrom = appsBottomBefore - grid.getBottom() - appsTy;
        final float plexAlpha = plexRow.getAlpha(), rise = 40 * density;
        grid.setTranslationY(appsTy + appsFrom);
        plexRow.setTranslationY(rise);
        plexRow.setAlpha(0f);
        bands.setShown(plexRow, 0f);
        plexEntrance = android.animation.ValueAnimator.ofFloat(1f, 0f);
        plexEntrance.setDuration(520);
        plexEntrance.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
        plexEntrance.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            grid.setTranslationY(appsTy + appsFrom * f);
            plexRow.setTranslationY(rise * f);
            plexRow.setAlpha(plexAlpha * (1f - f));
            bands.setShown(plexRow, 1f - f);
        });
        plexEntrance.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                // Also after a cancel (focus moved to a row): everything goes to where the rows really are.
                plexEntrance = null;
                plexRow.setTranslationY(0f);
                bands.setShown(plexRow, 1f);
                applyProgress(rowProgress.getValue());
                if (grid.hasFocus()) focusTile(lastFocused);
            }
        });
        plexEntrance.start();
    }

    /** A shrunk row is laid out 1/ROW_SMALL wider, so at its small scale it still spans the band. */
    private void setRowWide(RecyclerView row, boolean wide) {
        if (row == null || rowWidth <= 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) row.getLayoutParams();
        int want = wide ? (int) Math.ceil(rowWidth / ROW_SMALL) + 2 : ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp.width != want) {
            lp.width = want;
            row.setLayoutParams(lp);
        }
    }

    /** Tiles end at the band's inner edge (the normal right padding), at any scale and layout width. */
    private void clipRow(RecyclerView row, float s) {
        if (row == null || rowWidth <= 0 || row.getHeight() <= 0) return;
        float px = row.getPaddingLeft();
        int right = Math.round(px + (rowWidth - row.getPaddingRight() - px) / s);
        row.setClipBounds(new Rect(row.getPaddingLeft(), 0, right, row.getHeight()));
    }

    /** The focused app tile's top on screen, with the app row's current scale and shift. */
    private float appsRowTopVisual() {
        if (Float.isNaN(appsCardTop)) return Float.NaN;
        float bottom = gridBottomInRoot() + grid.getTranslationY();
        return bottom - grid.getScaleY() * (gridBottomInRoot() - appsCardTop);
    }

    private float gridBottomInRoot() {
        Rect r = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, r);
        return r.bottom;
    }

    private void setRowFocus(boolean plex) {
        if (plexRow == null || plexRow.getVisibility() != View.VISIBLE || plex == plexFocused) return;
        if (plexEntrance != null) plexEntrance.cancel();
        plexFocused = plex;
        setRowWide(plex ? grid : plexRow, true);
        float target = plex ? 1f : 0f;
        if (!animations || reducedMotion) {
            if (rowSpring != null) rowSpring.cancel();
            rowProgress.setValue(target);
            applyProgress(target);
            setRowWide(plex ? plexRow : grid, false);
            if (!plex) focusTile(lastFocused);
            return;
        }
        if (rowSpring == null) {
            // A soft spring like the highlight's: the rows overshoot a touch and settle.
            rowSpring = new SpringAnimation(rowProgress);
            rowSpring.setSpring(new SpringForce().setStiffness(420f).setDampingRatio(0.78f));
            rowSpring.setMinimumVisibleChange(0.002f);
            rowSpring.addUpdateListener((anim, value, velocity) -> applyProgress(value));
            rowSpring.addEndListener((anim, canceled, value, velocity) -> {
                if (canceled) return;
                setRowWide(plexFocused ? plexRow : grid, false);
                // Snap the highlight onto the full-size tile once the row has settled.
                if (!plexFocused && grid.hasFocus()) focusTile(lastFocused);
            });
        }
        rowSpring.animateToFinalPosition(target);
    }

    /** Both rows' scale and opacity for a progress between app row (0) and Plex row (1). */
    private void applyProgress(float p) {
        float sa = 1f - (1f - ROW_SMALL) * p, sp = ROW_SMALL + (1f - ROW_SMALL) * p;
        applyRows(Math.max(ROW_SMALL * 0.97f, sa), Math.max(ROW_SMALL * 0.97f, sp));
        float q = Math.max(0f, Math.min(1f, p));
        grid.setAlpha(1f - (1f - ROW_DIM) * q);
        if (plexRow != null) plexRow.setAlpha(ROW_DIM + (1f - ROW_DIM) * q);
    }

    /** The wallpaper drifts against the focus, bounded by the zoom margin: never past an edge. */
    private void updateParallax() {
        if (!parallax || wallView == null) return;
        int pos, n;
        if (plexFocused && plexAdapter != null) {
            pos = plexLast;
            n = plexAdapter.getItemCount();
        } else {
            pos = lastFocused;
            n = adapter.getItemCount();
        }
        float frac = n > 1 ? Math.max(0f, Math.min(1f, pos / (float) (n - 1))) : 0.5f;
        float tx = (0.5f - frac) * 2f * PARALLAX_X_DP * density;
        float ty = plexFocused ? -PARALLAX_Y_DP * density : 0f;
        if (!animations || reducedMotion) {
            wallView.setTranslationX(tx);
            wallView.setTranslationY(ty);
            return;
        }
        wallView.animate().translationX(tx).translationY(ty).setDuration(700)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /**
     * Scales both rows about their first tile's left edge and their bottom, then shifts the app row so
     * its band ends ROW_GAP_DP above the Plex band's top, whatever the two scales are: the rows never
     * touch, and a small Plex row lets the app row sit lower.
     */
    private void applyRows(float sa, float sp) {
        if (plexRow == null) return;
        grid.setPivotX(grid.getPaddingLeft());
        grid.setPivotY(grid.getHeight());
        plexRow.setPivotX(plexRow.getPaddingLeft());
        plexRow.setPivotY(plexRow.getHeight());
        grid.setScaleX(sa);
        grid.setScaleY(sa);
        plexRow.setScaleX(sp);
        plexRow.setScaleY(sp);
        clipRow(grid, sa);
        clipRow(plexRow, sp);
        if (bands != null) bands.invalidate();
        float plexTop = plexRow.getBottom() - sp * plexRow.getHeight();
        float plexBandTop = plexTop + sp * PLEX_INNER_TOP_DP * density;
        float ty = plexBandTop - ROW_GAP_DP * density + sa * APPS_INNER_BOTTOM_DP * density - grid.getBottom();
        if (plexRow.getVisibility() != View.VISIBLE) ty = 0f;
        float dy = ty - grid.getTranslationY();
        grid.setTranslationY(ty);
        if (glassShown && dy != 0f) {
            glassTargetY += dy;
            glassScrollShift += dy;
            glass.setTranslationY(glass.getTranslationY() + dy);
        }
        float top = appsRowTopVisual();
        if (!Float.isNaN(top) && heroShown) {
            heroBottom = top - 12 * density;
            hero.setY(heroBottom - hero.getHeight());
        }
    }

    private void focusPlex() {
        int pos = Math.max(0, Math.min(plexLast, plexAdapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = plexRow.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        plexRow.scrollToPosition(pos);
        plexRow.post(() -> {
            RecyclerView.ViewHolder h = plexRow.findViewHolderForAdapterPosition(pos);
            View target = h != null ? h.itemView.findViewById(R.id.card)
                    : plexRow.getChildCount() > 0 ? plexRow.getChildAt(0).findViewById(R.id.card) : null;
            if (target != null) target.requestFocus();
        });
    }

    @Override
    public void onPlexClick(PlexClient.Item item, View from) {
        TvSounds.launch(this);
        // The season you are in (or the film's page), looked up on the server first: a quick LAN call.
        PLEX_EXEC.execute(() -> {
            android.util.Log.i("tvhome-plex", plex.describe(item));
            String url = plex.watchUrl(item);
            runOnUiThread(() -> {
                if (url != null) {
                    Intent page = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .setPackage(PlexClient.PLEX_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        if (openFromTile && from.isAttachedToWindow() && from.getWidth() > 0) {
                            startActivity(page, android.app.ActivityOptions.makeScaleUpAnimation(
                                    from, 0, 0, from.getWidth(), from.getHeight()).toBundle());
                        } else {
                            startActivity(page);
                        }
                        return;
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
                Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(PlexClient.PLEX_PACKAGE);
                if (app != null) open(app);
            });
        });
    }

    @Override
    public void onPlexFocused(int position) {
        plexLast = position;
        setRowFocus(true);
        updateParallax();
        hideGlass();
        PlexClient.Item it = plexAdapter.itemAt(position);
        if (it == null) {
            hideHero();
            return;
        }
        showHeroText(it.title, it.subtitle);
    }

    @Override
    public boolean onPlexKey(int position, int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                focusTile(lastFocused);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return position == 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return position == plexAdapter.getItemCount() - 1;
            default:
                return false;
        }
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    /** The first top-bar button after Inputs that is shown: the network pill, the video buttons, Reorder, then Settings (always there). */
    private View firstTopButton() {
        for (View b : new View[]{btnWifi, btnSound, btnFullScreen, btnNext}) {
            if (b.getVisibility() == View.VISIBLE) return b;
        }
        if (btnReorder.getVisibility() == View.VISIBLE) return btnReorder;
        return findViewById(R.id.btnSettings);
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstTopButton().requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstTopButton().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        String festiveGreeting = festive ? festival.greetingText() : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    // ---- race-weekend panel (a theme's race_calendar) -----------------------------------------------

    private static final int RACE_RED = 0xFFFF3B2F;

    /**
     * Shows or hides the race-weekend panel for this minute. Text only, set once a minute with the clock:
     * nothing animates, so the panel costs the GPU nothing between ticks.
     */
    private void updateRace() {
        if (raceCalendar.isEmpty()) return;
        RaceWeekend.State s = RaceWeekend.at(raceCalendar, java.time.Instant.now());
        if (s == null) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        raceKicker.setText(race.place.isEmpty()
                ? getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("\u25CF  " + getString(R.string.race_live, s.live.title()));
            live.setSpan(new android.text.style.ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            raceHeadline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(s.untilNext);
            raceHeadline.setText(s.next.isRace() ? getString(R.string.race_lights_out, in)
                    : getString(R.string.race_next, s.next.title(), in));
        } else {
            raceHeadline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(this) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        android.text.SpannableStringBuilder row = new android.text.SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName()).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new android.text.style.ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        raceSessions.setText(row);
        racePanel.setVisibility(View.VISIBLE);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        // The diya's flame: 0 is out (offline), 1 to 4 follow the Wi-Fi signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long badge ("VPN отключён") can leave room for a stub like "MT…", which says nothing and, after
            // rounding, can push the badge itself out of the pill. Show the badge alone then.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            text = name.length() < text.length() && name.length() < 5 ? "" : name;
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(btnWifi.getText(), label)) btnWifi.setText(label);
        } else if (!TextUtils.equals(btnWifi.getText(), text)) {
            btnWifi.setText(text);
        }
        if (wifiIconOnly) {
            btnWifi.setContentDescription(btnWifi.getText());
            btnWifi.setText(null);
        }
        if (festive && diya != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            diya.setSteady(steady);
            diya.setFlame(flame);
        } else {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
        }
    }

    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    private boolean wifiIconOnly;
    /** The app library button in the top bar (library_button). */
    private View btnLibraryTop;
    /** The open library came from that button: closing it returns there instead of scrolling to the grid's end. */
    private boolean libraryFromTopBar;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    /** Night mode: darken the wallpaper inside the theme's night window, fading when it starts or ends. */
    private void updateNight(boolean fade) {
        if (nightShade == null || nightCfg == null || nightCfg.nightStart < 0) return;
        float want = NightMode.isNight(nightCfg) ? nightCfg.nightDim / 100f : 0f;
        if (Math.abs(nightShade.getAlpha() - want) < 0.01f) return;
        nightShade.animate().cancel();
        if (fade && animations) nightShade.animate().alpha(want).setDuration(1500).start();
        else nightShade.setAlpha(want);
    }

    private void updateInputsButton() {
        if (!inputsPill) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9): a
     *  service, or an activity on sets whose panel is a dialog (BYINTEK U4's QuickSourceActivity). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        // A configured source panel and no Live TV row to offer next to it: the panel is the whole menu.
        // The BYINTEK U4 needs this, as its TvInputManager lists three HDMI ports and an AV input it doesn't have.
        if (!sourcePanelService.isEmpty() && liveTvIntent() == null) {
            openSourcePanel();
            return;
        }
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(this);
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = device.loadCustomLabel(this);
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : tim.getInputState(deviceId);
                        addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId),
                            "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        if (moduleCards) addSettingsMenuRow(rows, popup, R.string.module_cards, this::showModuleCards);
        // single_row has no dock, and hold OK on a tile already moves or hides it (TileOrder).
        if (dockRow && !singleRow) addSettingsMenuRow(rows, popup, R.string.dock_edit, this::startDockEdit);
        if (!singleRow) addSettingsMenuRow(rows, popup, R.string.reorder, this::toggleReorder);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
