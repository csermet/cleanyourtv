package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

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

    @Test public void nothingAtAllIsNull() {
        assertNull(WallpaperRotation.pick(tmp.getRoot(), "wallpaper.jpg", 20000));
        assertNull(WallpaperRotation.pick(null, "wallpaper.jpg", 20000));
    }

    @Test public void folderRotatesDailyInNameOrder() throws IOException {
        File dir = tmp.getRoot();
        write(new File(dir, "wallpaper.jpg"), 10);
        File a = write(new File(dir, "wallpapers/01.jpg"), 10);
        File b = write(new File(dir, "wallpapers/02.jpg"), 10);
        File c = write(new File(dir, "wallpapers/03.jpg"), 10);
        long day = 20001;   // 20001 % 3 == 0
        assertEquals(a, WallpaperRotation.pick(dir, "wallpaper.jpg", day));
        assertEquals(b, WallpaperRotation.pick(dir, "wallpaper.jpg", day + 1));
        assertEquals(c, WallpaperRotation.pick(dir, "wallpaper.jpg", day + 2));
        assertEquals(a, WallpaperRotation.pick(dir, "wallpaper.jpg", day + 3));
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
}
