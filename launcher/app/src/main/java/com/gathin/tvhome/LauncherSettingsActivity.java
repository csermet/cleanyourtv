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
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView wallpaperChoice;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
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
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            LinearLayout row = (LinearLayout) columnChoices[0].getParent();
            row.removeAllViews();
            for (int i = columnChoices.length - 1; i >= 0; i--) row.addView(columnChoices[i]);
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
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = current.columns;
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
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    /** The picture list: "Shuffle now" first, then every wallpaper; the choice holds for today. */
    private void pickWallpaper() {
        File[] list = WallpaperRotation.list(Config.dir(this));
        if (list.length < 2) return;
        String[] labels = new String[list.length + 1];
        labels[0] = getString(R.string.wallpaper_shuffle_now);
        for (int i = 0; i < list.length; i++) labels[i + 1] = WallpaperRotation.label(list[i]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper)
                .setItems(labels, (dialog, which) -> {
                    File chosen;
                    if (which == 0) {
                        File now = Config.currentWallpaper(this);
                        do { chosen = list[new Random().nextInt(list.length)]; } while (chosen.equals(now));
                    } else {
                        chosen = list[which - 1];
                    }
                    choices.edit().putString(Config.WALLPAPER_PICK, chosen.getName())
                            .putLong(Config.WALLPAPER_PICK_DAY, LocalDate.now().toEpochDay()).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .show();
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
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
        if (wallpaperChoice != null && wallpaperChoice.getVisibility() == View.VISIBLE) {
            File now = Config.currentWallpaper(this);
            boolean picked = choices.getLong(Config.WALLPAPER_PICK_DAY, Long.MIN_VALUE) == LocalDate.now().toEpochDay()
                    && now != null && now.getName().equals(choices.getString(Config.WALLPAPER_PICK, null));
            wallpaperChoice.setText(getString(R.string.wallpaper_state,
                    now == null ? "-" : WallpaperRotation.label(now),
                    getString(picked ? R.string.wallpaper_today : R.string.wallpaper_daily)));
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
