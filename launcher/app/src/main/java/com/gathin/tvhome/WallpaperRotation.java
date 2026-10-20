package com.gathin.tvhome;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * Which wallpaper Home shows today. A theme can push several pictures into a {@code wallpapers/}
 * folder next to config.json; Home then shows one a day, in file-name order, and moves to the next on
 * the first Home after midnight. Without that folder the single {@code wallpaper.jpg} is used, as before.
 * Plain Java (no Android types) so it can be unit tested.
 */
final class WallpaperRotation {
    static final String DIR_NAME = "wallpapers";

    private WallpaperRotation() {}

    /** Today's picture for {@code epochDay} (LocalDate.toEpochDay), or null when there is none. */
    static File pick(File themeDir, String singleName, long epochDay) {
        if (themeDir == null) return null;
        File[] list = new File(themeDir, DIR_NAME).listFiles(f -> f.isFile() && f.length() > 0
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".jpg"));
        if (list != null && list.length > 0) {
            Arrays.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
            return list[(int) Math.floorMod(epochDay, (long) list.length)];
        }
        File single = new File(themeDir, singleName);
        return single.isFile() && single.length() > 0 ? single : null;
    }
}
