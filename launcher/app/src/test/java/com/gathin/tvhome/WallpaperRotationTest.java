package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WallpaperRotationTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static File write(File f, int bytes) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(new byte[bytes]); }
        return f;
    }

    @Test public void noFolderUsesSingleWallpaper() throws IOException {
        File dir = tmp.getRoot();
        File single = write(new File(dir, "wallpaper.jpg"), 10);
        assertEquals(single, WallpaperRotation.pick(dir, "wallpaper.jpg", 20000));
    }

    @Test public void videosAreMp4sInNameOrder() throws IOException {
        File dir = tmp.getRoot();
        write(new File(dir, "videos/b.mp4"), 10);
        write(new File(dir, "videos/a.MP4"), 10);
        write(new File(dir, "videos/empty.mp4"), 0);
        write(new File(dir, "videos/notes.txt"), 10);
        write(new File(dir, "wallpapers/c.mp4"), 10);
        File[] v = WallpaperRotation.videos(dir);
        assertEquals(2, v.length);
        assertEquals("a.MP4", v[0].getName());
        assertEquals("b.mp4", v[1].getName());
        assertEquals(0, WallpaperRotation.videos(null).length);
        assertEquals(0, WallpaperRotation.videos(tmp.newFolder("bare")).length);
    }

    @Test public void liveBackgroundNames() {
        assertEquals(Config.LIVE_PICTURE, Config.liveBackgroundNamed("picture"));
        assertEquals(Config.LIVE_VIDEO, Config.liveBackgroundNamed("video"));
        assertEquals(Config.LIVE_PICTURE_EFFECT, Config.liveBackgroundNamed("picture_effect"));
        assertEquals(Config.LIVE_VIDEO_EFFECT, Config.liveBackgroundNamed("video_effect"));
        assertEquals(Config.LIVE_PICTURE, Config.liveBackgroundNamed("VIDEO"));
        assertEquals(Config.LIVE_PICTURE, Config.liveBackgroundNamed(""));
        assertEquals(Config.EFFECT_DUST, Config.liveEffectNamed("dust"));
        assertEquals(Config.EFFECT_BOTH, Config.liveEffectNamed("both"));
        assertEquals(Config.EFFECT_SPARKS, Config.liveEffectNamed("glitter"));
    }

    @Test public void rotationMovesOnAfterAnHour() {
        long t = 1_800_000_000_000L;
        assertTrue(!Config.rotationDue(t, t + Config.ROTATION_HOLD_MS - 1));
        assertTrue(Config.rotationDue(t, t + Config.ROTATION_HOLD_MS));
        assertTrue(Config.rotationDue(t, t + 5 * Config.ROTATION_HOLD_MS));
        // The clock went back (set again from the network): due, so the step never sticks.
        assertTrue(Config.rotationDue(t, t - 1000));
    }

    @Test public void nothingAtAllIsNull() {
        assertNull(WallpaperRotation.pick(tmp.getRoot(), "wallpaper.jpg", 20000));
        assertNull(WallpaperRotation.pick(null, "wallpaper.jpg", 20000));
    }

    @Test public void everyPictureOncePerCycle() {
        for (int n = 2; n <= 9; n++) {
            for (long cycle = -3; cycle < 40; cycle++) {
                Set<Integer> seen = new HashSet<>();
                for (int pos = 0; pos < n; pos++) seen.add(WallpaperRotation.index(cycle * n + pos, n));
                assertEquals("n=" + n + " cycle=" + cycle, n, seen.size());
            }
        }
    }

    @Test public void neverTheSamePictureTwoDaysRunning() {
        for (int n = 2; n <= 9; n++) {
            for (long day = 19000; day < 21000; day++) {
                assertNotEquals("n=" + n + " day=" + day,
                        WallpaperRotation.index(day - 1, n), WallpaperRotation.index(day, n));
            }
        }
    }

    @Test public void sameDaySamePicture() {
        assertEquals(WallpaperRotation.index(20500, 8), WallpaperRotation.index(20500, 8));
        assertEquals(0, WallpaperRotation.index(20500, 1));
    }

    @Test public void overrideWinsAndMissingOverrideFallsBack() throws IOException {
        File dir = tmp.getRoot();
        write(new File(dir, "wallpapers/a.jpg"), 10);
        File b = write(new File(dir, "wallpapers/b.jpg"), 10);
        write(new File(dir, "wallpapers/c.jpg"), 10);
        assertEquals(b, WallpaperRotation.pick(dir, "wallpaper.jpg", 7, "b.jpg"));
        File daily = WallpaperRotation.pick(dir, "wallpaper.jpg", 7, null);
        assertEquals(daily, WallpaperRotation.pick(dir, "wallpaper.jpg", 7, "gone.jpg"));
    }

    @Test public void emptyAndNonJpgFilesAreSkipped() throws IOException {
        File dir = tmp.getRoot();
        write(new File(dir, "wallpapers/01.jpg"), 0);
        write(new File(dir, "wallpapers/02.png"), 10);
        File only = write(new File(dir, "wallpapers/03.JPG"), 10);
        assertEquals(only, WallpaperRotation.pick(dir, "wallpaper.jpg", 7));
    }

    @Test public void emptyFolderFallsBackToSingle() throws IOException {
        File dir = tmp.getRoot();
        new File(dir, "wallpapers").mkdirs();
        File single = write(new File(dir, "wallpaper.jpg"), 10);
        assertEquals(single, WallpaperRotation.pick(dir, "wallpaper.jpg", 5));
    }

    @Test public void readableLabels() {
        assertEquals("Misaka kuroko", WallpaperRotation.label(new File("misaka-kuroko.jpg")));
        assertEquals("Tokyo", WallpaperRotation.label(new File("01-tokyo.jpg")));
        assertTrue(WallpaperRotation.label(new File("x.jpg")).length() > 0);
    }
}
