package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.time.LocalDate;
import java.util.Random;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView wallpaperChoice;
    private final TextView[] liveChoices = new TextView[4];
    private final TextView[] effectChoices = new TextView[3];
    private int liveBackground, liveEffect;
    private boolean hasVideos;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        if (WallpaperRotation.list(Config.dir(this)).length < 2) {
            wallpaperChoice.setVisibility(View.GONE);
            findViewById(R.id.wallpaperHint).setVisibility(View.GONE);
        } else {
            wallpaperChoice.setOnClickListener(v -> pickWallpaper());
        }
        findViewById(R.id.showHiddenChoice).setOnClickListener(v -> {
            TileOrder.showHidden(this);
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.resetOrderChoice).setOnClickListener(v -> {
            TileOrder.resetOrder(this);
            setResult(RESULT_OK);
            Toast.makeText(this, R.string.order_reset_done, Toast.LENGTH_SHORT).show();
        });
        setUpLiveBackground(config);
        setUpPlex();
        loadBackground();
        refresh();
        // Open at the top: the first visible row of the first section has focus.
        View first = wallpaperChoice.getVisibility() == View.VISIBLE ? wallpaperChoice
                : findViewById(R.id.plexChoice).getVisibility() == View.VISIBLE ? findViewById(R.id.plexChoice)
                : findViewById(R.id.showHiddenChoice);
        first.requestFocus();
    }

    private static final int PICK_WALLPAPER = 41, PICK_VIDEO = 42;

    private void pickWallpaper() {
        startActivityForResult(new android.content.Intent(this, WallpaperPickerActivity.class), PICK_WALLPAPER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            refresh();
        }
        if (requestCode == PICK_WALLPAPER && resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            loadBackground();
            refresh();
        }
    }

    /** Today's wallpaper behind the settings, dimmed by the layout's scrim. */
    private void loadBackground() {
        File f = Config.currentWallpaper(this);
        if (f == null) return;
        android.widget.ImageView bg = findViewById(R.id.settingsBg);
        new Thread(() -> {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;
            android.graphics.Bitmap bmp;
            try {
                bmp = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            } catch (OutOfMemoryError e) {
                bmp = null;
            }
            final android.graphics.Bitmap b = bmp;
            if (b != null) runOnUiThread(() -> bg.setImageBitmap(b));
        }).start();
    }

    // ---- Live background (live_background, live_effect) --------------------------------------

    /** Picture / Video / either with an effect, then which effect; Video needs the theme's videos/ folder. */
    private void setUpLiveBackground(Config config) {
        liveBackground = config.liveBackground;
        liveEffect = config.liveEffect;
        hasVideos = WallpaperRotation.videos(Config.dir(this)).length > 0;
        int[] ids = {R.id.livePicture, R.id.liveVideo, R.id.livePictureEffect, R.id.liveVideoEffect};
        for (int i = 0; i < ids.length; i++) {
            final int value = i;
            TextView v = liveChoices[i] = findViewById(ids[i]);
            if (!hasVideos && (value == Config.LIVE_VIDEO || value == Config.LIVE_VIDEO_EFFECT)) {
                v.setEnabled(false);
                v.setFocusable(false);
                v.setAlpha(0.4f);
            }
            v.setOnClickListener(x -> {
                if (liveBackground == value) return;
                liveBackground = value;
                choices.edit().putInt(Config.LIVE_BACKGROUND, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] effectIds = {R.id.liveSparks, R.id.liveDust, R.id.liveBoth};
        for (int i = 0; i < effectIds.length; i++) {
            final int value = i;
            effectChoices[i] = findViewById(effectIds[i]);
            effectChoices[i].setOnClickListener(x -> {
                if (liveEffect == value) return;
                liveEffect = value;
                choices.edit().putInt(Config.LIVE_EFFECT, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        ((TextView) findViewById(R.id.liveHint)).setText(hasVideos ? R.string.live_hint : R.string.live_hint_no_video);
        findViewById(R.id.liveVideoChoice).setOnClickListener(v -> startActivityForResult(
                new Intent(this, WallpaperPickerActivity.class).putExtra(WallpaperPickerActivity.EXTRA_VIDEOS, true), PICK_VIDEO));
    }

    // ---- Plex (plex_row) ------------------------------------------------------------------------

    private PlexClient plex;

    private void setUpPlex() {
        boolean on = Config.load(this).plexRow;
        int vis = on ? View.VISIBLE : View.GONE;
        findViewById(R.id.plexHeader).setVisibility(vis);
        findViewById(R.id.plexChoice).setVisibility(vis);
        findViewById(R.id.plexHint).setVisibility(vis);
        if (!on) return;
        plex = new PlexClient(this);
        findViewById(R.id.plexChoice).setOnClickListener(v -> {
            if (plex.linked()) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.plex_disconnect_q)
                        .setPositiveButton(R.string.plex_disconnect, (d, w) -> {
                            plex.unlink();
                            setResult(RESULT_OK);
                            refresh();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                linkPlex();
            }
        });
    }

    /** plex.tv/link: show the code, poll until the owner enters it (10 minutes at most), then find the server. */
    private void linkPlex() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.section_plex)
                .setMessage(R.string.plex_link_wait)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        new Thread(() -> {
            try {
                PlexClient.Pin pin = plex.createPin();
                runOnUiThread(() -> dialog.setMessage(getString(R.string.plex_link_code, pin.code)));
                long end = System.currentTimeMillis() + 10 * 60_000L;
                while (System.currentTimeMillis() < end) {
                    Thread.sleep(2500);
                    if (isFinishing() || !dialog.isShowing()) return;
                    if (plex.checkPin(pin.id)) {
                        boolean server = plex.discover(Config.load(this).plexLanIp);
                        runOnUiThread(() -> {
                            dialog.dismiss();
                            Toast.makeText(this, server ? R.string.plex_link_done : R.string.plex_no_server,
                                    Toast.LENGTH_LONG).show();
                            setResult(RESULT_OK);
                            refresh();
                        });
                        return;
                    }
                }
                runOnUiThread(dialog::dismiss);
            } catch (Exception e) {
                runOnUiThread(() -> dialog.setMessage(getString(R.string.plex_link_error)));
            }
        }).start();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
        for (int i = 0; i < liveChoices.length; i++) liveChoices[i].setSelected(liveBackground == i);
        // The effect row only when the background has an effect: no row that does nothing.
        boolean effect = liveBackground == Config.LIVE_PICTURE_EFFECT || liveBackground == Config.LIVE_VIDEO_EFFECT;
        findViewById(R.id.liveEffectLabel).setVisibility(effect ? View.VISIBLE : View.GONE);
        findViewById(R.id.liveEffectRow).setVisibility(effect ? View.VISIBLE : View.GONE);
        for (int i = 0; i < effectChoices.length; i++) effectChoices[i].setSelected(liveEffect == i);
        // Which clip: only with a video background and clips to choose from.
        boolean video = hasVideos && (liveBackground == Config.LIVE_VIDEO || liveBackground == Config.LIVE_VIDEO_EFFECT);
        TextView videoChoice = findViewById(R.id.liveVideoChoice);
        videoChoice.setVisibility(video ? View.VISIBLE : View.GONE);
        if (video) {
            File clip = Config.currentVideo(this);
            boolean picked = choices.getLong(Config.VIDEO_PICK_DAY, Long.MIN_VALUE) == LocalDate.now().toEpochDay()
                    && clip != null && clip.getName().equals(choices.getString(Config.VIDEO_PICK, null));
            videoChoice.setText(getString(R.string.video_state, clip == null ? "-" : WallpaperRotation.label(clip),
                    getString(picked ? R.string.wallpaper_today : R.string.wallpaper_daily)));
        }
        ((TextView) findViewById(R.id.showHiddenChoice)).setText(getString(R.string.show_hidden, TileOrder.hidden(this).size()));
        if (plex != null) {
            ((TextView) findViewById(R.id.plexChoice)).setText(plex.linked()
                    ? getString(R.string.plex_connected, plex.hasServer() ? plex.serverName() : "-")
                    : getString(R.string.plex_connect));
        }
        if (wallpaperChoice != null && wallpaperChoice.getVisibility() == View.VISIBLE) {
            File now = Config.currentWallpaper(this);
            boolean picked = choices.getLong(Config.WALLPAPER_PICK_DAY, Long.MIN_VALUE) == LocalDate.now().toEpochDay()
                    && now != null && now.getName().equals(choices.getString(Config.WALLPAPER_PICK, null));
            wallpaperChoice.setText(getString(R.string.wallpaper_state_pick,
                    now == null ? "-" : WallpaperRotation.label(now),
                    getString(picked ? R.string.wallpaper_today : R.string.wallpaper_daily)));
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
