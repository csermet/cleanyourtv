package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import java.util.Random;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow (ClockScene).
 * With a theme's wallpapers/ the glow gives way to a slideshow of them under the drifting clock.
 * No network: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    /** With a theme's wallpapers/: each picture stays this long, then cross-fades to the next. */
    private static final long SLIDE_MS = 180_000L;
    private static final long FADE_MS = 2_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ClockScene scene;
    private View content;
    /** Slideshow of the theme's wallpapers (empty: the original glow screensaver). */
    private final List<File> pictures = new ArrayList<>();
    private final ImageView[] slides = new ImageView[2];
    private final android.animation.Animator[] zooms = new android.animation.Animator[2];
    private int front = -1;
    private int next;
    private final Runnable slideTick = new Runnable() {
        @Override
        public void run() {
            showNextSlide();
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        scene = new ClockScene(getWindow().getDecorView());
        content = findViewById(R.id.dreamContent);
        setUpSlides(findViewById(R.id.dreamAmbient));
    }

    /** Today's wallpaper first, then the others in a random order; pictures sit under a dim layer. */
    private void setUpSlides(View ambient) {
        File dir = Config.dir(this);
        File[] list = WallpaperRotation.list(dir);
        if (list.length == 0) return;
        File today = Config.currentWallpaper(this);
        List<File> rest = new ArrayList<>();
        for (File f : list) if (!f.equals(today)) rest.add(f);
        Collections.shuffle(rest, random);
        if (today != null) pictures.add(today);
        pictures.addAll(rest);
        ambient.setVisibility(View.GONE);
        ViewGroup root = (ViewGroup) content.getParent();
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            root.addView(iv, i, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            slides[i] = iv;
        }
        View dim = new View(this);
        dim.setBackgroundColor(0x73000000);
        root.addView(dim, 2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showNextSlide() {
        if (pictures.isEmpty()) return;
        File f = pictures.get(next % pictures.size());
        next++;
        Bitmap bmp;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            bmp = null;
        }
        if (bmp == null) return;
        int back = front < 0 ? 0 : 1 - front;
        ImageView in = slides[back];
        in.animate().cancel();
        if (zooms[back] != null) zooms[back].cancel();
        in.setImageBitmap(bmp);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(FADE_MS).start();
        // Slow push-in over the picture's whole time on screen: nothing stays still long enough to burn in.
        android.animation.AnimatorSet zoom = new android.animation.AnimatorSet();
        zoom.playTogether(android.animation.ObjectAnimator.ofFloat(in, View.SCALE_X, 1f, 1.08f),
                android.animation.ObjectAnimator.ofFloat(in, View.SCALE_Y, 1f, 1.08f));
        zoom.setDuration(SLIDE_MS + FADE_MS);
        zoom.setInterpolator(new android.view.animation.LinearInterpolator());
        zoom.start();
        zooms[back] = zoom;
        if (front >= 0) {
            ImageView out = slides[front];
            out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> out.setImageDrawable(null)).start();
        }
        front = back;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
        if (!pictures.isEmpty()) {
            showNextSlide();
            handler.postDelayed(slideTick, SLIDE_MS);
        }
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        handler.removeCallbacks(slideTick);
        for (android.animation.Animator z : zooms) if (z != null) z.cancel();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        handler.removeCallbacks(slideTick);
        super.onDetachedFromWindow();
    }
}
