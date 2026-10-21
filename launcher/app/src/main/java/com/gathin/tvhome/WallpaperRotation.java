package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Which wallpaper Home shows today. A theme can push several pictures into a {@code wallpapers/}
 * folder next to config.json; Home then shuffles them like a playlist: every picture once per cycle of
 * N days, a new random order each cycle, and never the same picture two days running. The owner can
 * also pick one for today in Launcher settings (or shuffle now); the daily shuffle resumes the next
 * day. Without that folder the single {@code wallpaper.jpg} is used, as before.
 * Plain Java (no Android types) so it can be unit tested.
 */
final class WallpaperRotation {
    static final String DIR_NAME = "wallpapers";

    private WallpaperRotation() {}

    /** The rotating pictures, sorted by file name; empty when there are none. */
    static File[] list(File themeDir) {
        if (themeDir == null) return new File[0];
        File[] list = new File(themeDir, DIR_NAME).listFiles(f -> f.isFile() && f.length() > 0
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".jpg"));
        if (list == null) return new File[0];
        Arrays.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    static final String VIDEO_DIR_NAME = "videos";

    /** The theme's videos/*.mp4 for live_background, sorted by file name; empty when there are none. */
    static File[] videos(File themeDir) {
        if (themeDir == null) return new File[0];
        File[] list = new File(themeDir, VIDEO_DIR_NAME).listFiles(f -> f.isFile() && f.length() > 0
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".mp4"));
        if (list == null) return new File[0];
        Arrays.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    /** Today's picture for {@code epochDay} (LocalDate.toEpochDay), or null when there is none. */
    static File pick(File themeDir, String singleName, long epochDay) {
        return pick(themeDir, singleName, epochDay, null);
    }

    /** As above; {@code override} is a file name in wallpapers/ the owner picked for today, or null. */
    static File pick(File themeDir, String singleName, long epochDay, String override) {
        if (themeDir == null) return null;
        File[] list = list(themeDir);
        if (list.length > 0) {
            if (override != null) {
                for (File f : list) if (f.getName().equals(override)) return f;
            }
            return list[index(epochDay, list.length)];
        }
        File single = new File(themeDir, singleName);
        return single.isFile() && single.length() > 0 ? single : null;
    }

    /** Position in the shuffled playlist for a day: a permutation per cycle of n days. */
    static int index(long epochDay, int n) {
        if (n <= 1) return 0;
        // Two pictures can only alternate; the swap below would move the last one too.
        if (n == 2) return (int) Math.floorMod(epochDay, 2L);
        long cycle = Math.floorDiv(epochDay, (long) n);
        int pos = (int) Math.floorMod(epochDay, (long) n);
        return order(cycle, n)[pos];
    }

    /** The cycle's order; its first picture is never the previous cycle's last. */
    static int[] order(long cycle, int n) {
        int[] p = permutation(cycle, n);
        if (n > 1 && p[0] == permutation(cycle - 1, n)[n - 1]) {
            int t = p[0]; p[0] = p[1]; p[1] = t;
        }
        return p;
    }

    private static int[] permutation(long cycle, int n) {
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = i;
        long s = mix(cycle * 0x9E3779B97F4A7C15L + n);
        for (int i = n - 1; i > 0; i--) {
            s = mix(s);
            int j = (int) Math.floorMod(s, (long) (i + 1));
            int t = p[i]; p[i] = p[j]; p[j] = t;
        }
        return p;
    }

    /** SplitMix64 finaliser: a cheap, well-spread hash of a long. */
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** "misaka-kuroko.jpg" -> "Misaka kuroko": a readable label for the picker. */
    static String label(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceFirst("^[0-9]+[-_ ]", "").replace('-', ' ').replace('_', ' ').trim();
        if (name.isEmpty()) return f.getName();
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    /** Playback orders persisted in config.json (wallpaper_order). */
    static final String ORDER_SEQUENTIAL = "sequential";
    static final String ORDER_SHUFFLE = "shuffle";

    static boolean validOrder(String order) {
        return ORDER_SEQUENTIAL.equals(order) || ORDER_SHUFFLE.equals(order);
    }

    static List<File> images(File directory) {
        List<File> result = new ArrayList<>();
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (entry.isFile() && entry.length() > 0 && entry.length() <= 10 * 1024 * 1024
                    && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                result.add(entry);
                if (result.size() == 100) break;
            }
        }
        return result;
    }

    static File find(List<File> images, String name) {
        for (File image : images) if (image.getName().equals(name)) return image;
        return null;
    }

    static File pick(List<File> images, String previous, Random random) {
        List<File> candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * The next picture in the sorted collection, wrapping to the first at the end. The list from
     * {@link #images(File)} is sorted by filename, so this is a stable order across restarts. A
     * previous name that is no longer in the collection starts again at the first picture.
     */
    static File next(List<File> images, String previous) {
        if (images.isEmpty()) return null;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(previous)) {
                return images.get((i + 1) % images.size());
            }
        }
        return images.get(0);
    }

    /** Picks the next picture for {@code order}; an unknown order keeps the historical shuffle. */
    static File pick(List<File> images, String previous, String order, Random random) {
        return ORDER_SEQUENTIAL.equals(order) ? next(images, previous) : pick(images, previous, random);
    }

    static boolean due(long previous, long now, long interval) {
        return interval > 0 && (previous == 0 || now < previous || now - previous >= interval);
    }
}
