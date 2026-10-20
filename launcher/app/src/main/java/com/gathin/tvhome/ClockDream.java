package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
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
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow.
 * Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * No network, no images: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    /** With a theme's wallpapers/: each picture stays this long, then cross-fades to the next. */
    private static final long SLIDE_MS = 180_000L;
    private static final long FADE_MS = 2_500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
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

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
        setUpSlides(ambient);
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
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
        if (!pictures.isEmpty()) {
            showNextSlide();
            handler.postDelayed(slideTick, SLIDE_MS);
        }
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(slideTick);
        for (android.animation.Animator z : zooms) if (z != null) z.cancel();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(slideTick);
        super.onDetachedFromWindow();
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
