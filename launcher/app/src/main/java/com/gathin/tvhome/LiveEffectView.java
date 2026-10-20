package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * live_effect, over the wallpaper (picture or video) and under the night shade: dark purple electric sparks
 * now and then, slowly drifting light dust, or both. Drawn at about 30 frames a second while Home is on
 * screen; sparks alone draw nothing between bursts. The sparks keep to the upper right, away from the
 * header and the rows.
 */
final class LiveEffectView extends View {
    private final boolean sparks, dust;
    private final float density;
    private final Random rnd = new Random();
    private boolean running;
    private int vsyncs;
    private long lastDraw, lastVideoFrame = Long.MIN_VALUE / 2;
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            // Every other vsync: 30 frames a second is plenty for slow dust and short flashes. Over a video,
            // its frames drive the drawing instead (videoFrame), so the screen is not redrawn twice as often.
            boolean videoDriving = SystemClock.uptimeMillis() - lastVideoFrame < 120;
            if (!videoDriving && (++vsyncs & 1) == 0) invalidate();
            if (dust || burstActive(SystemClock.uptimeMillis())) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                invalidate();   // a clean frame: nothing of the last burst stays on screen
                long wait = Math.max(16, nextBurst - SystemClock.uptimeMillis());
                postDelayed(resume, wait);
            }
        }
    };
    private final Runnable resume = () -> {
        if (running) Choreographer.getInstance().postFrameCallback(frame);
    };

    // ---- dust ----------------------------------------------------------------------------------
    private static final int DUST = 36;
    private final float[] px = new float[DUST], py = new float[DUST], vy = new float[DUST],
            sway = new float[DUST], size = new float[DUST], phase = new float[DUST], twinkle = new float[DUST];
    private final Bitmap dot;
    private final Paint dotPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private boolean dustPlaced;

    // ---- sparks --------------------------------------------------------------------------------
    /** A burst: three flashes over BURST_MS, each a freshly broken path between the same two points. */
    private static final long BURST_MS = 360;
    private static final long[] FLASH_ON = {0, 130, 260}, FLASH_OFF = {80, 210, 360};
    private final Paint glow = stroke(0xFF4B1FA8, 9f, 70), mid = stroke(0xFF6E3BD8, 3.5f, 150), core = stroke(0xFFA27BFF, 1.3f, 220);
    private final Path bolt = new Path();
    private float ax, ay, bx, by;
    private long burstStart = Long.MIN_VALUE / 2, nextBurst;
    private int flashDrawn = -1;
    private static final int CHIPS = 9;
    private final float[] cx = new float[CHIPS], cy = new float[CHIPS], cvx = new float[CHIPS], cvy = new float[CHIPS];
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    LiveEffectView(Context ctx, int effect, float density) {
        super(ctx);
        this.density = density;
        sparks = effect == Config.EFFECT_SPARKS || effect == Config.EFFECT_BOTH;
        dust = effect == Config.EFFECT_DUST || effect == Config.EFFECT_BOTH;
        setFocusable(false);
        setWillNotDraw(false);
        int d = 48;
        dot = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new RadialGradient(d / 2f, d / 2f, d / 2f, new int[]{0xFFFFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(dot).drawCircle(d / 2f, d / 2f, d / 2f, g);
        dotPaint.setColorFilter(new PorterDuffColorFilter(0xFFD9C8FF, PorterDuff.Mode.SRC_IN));
        chipPaint.setColor(0xFFB793FF);
        nextBurst = SystemClock.uptimeMillis() + 1500;
    }

    private Paint stroke(int color, float widthDp, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        p.setAlpha(alpha);
        p.setStrokeWidth(widthDp * getResources().getDisplayMetrics().density);
        return p;
    }

    void start() {
        if (running) return;
        running = true;
        lastDraw = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        removeCallbacks(resume);
    }

    /** A new frame of the video underneath: draw with it. */
    void videoFrame() {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        lastVideoFrame = now;
        if (dust || burstActive(now)) invalidate();
    }

    private boolean burstActive(long now) {
        return sparks && (now - burstStart < BURST_MS + 450 || now >= nextBurst);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastDraw == 0 ? 0f : Math.min(0.1f, (now - lastDraw) / 1000f);
        lastDraw = now;
        if (dust) drawDust(c, w, h, now, dt);
        if (sparks) drawSparks(c, w, h, now, dt);
    }

    private void drawDust(Canvas c, int w, int h, long now, float dt) {
        if (!dustPlaced) {
            for (int i = 0; i < DUST; i++) place(i, w, rnd.nextFloat() * h);
            dustPlaced = true;
        }
        float t = now / 1000f;
        for (int i = 0; i < DUST; i++) {
            py[i] -= vy[i] * dt;
            if (py[i] < -20 * density) place(i, w, h + 20 * density);
            float x = px[i] + (float) Math.sin(t * 0.25f + phase[i]) * sway[i];
            float a = 0.18f + 0.42f * (0.5f + 0.5f * (float) Math.sin(t * twinkle[i] + phase[i]));
            float r = size[i] * 2.2f;
            dst.set(x - r, py[i] - r, x + r, py[i] + r);
            dotPaint.setAlpha(Math.round(255 * a));
            c.drawBitmap(dot, null, dst, dotPaint);
        }
    }

    private void place(int i, int w, float y) {
        px[i] = rnd.nextFloat() * w;
        py[i] = y;
        vy[i] = (6 + rnd.nextFloat() * 14) * density;
        sway[i] = (6 + rnd.nextFloat() * 18) * density;
        size[i] = (1.2f + rnd.nextFloat() * 2.6f) * density;
        phase[i] = rnd.nextFloat() * 6.283f;
        twinkle[i] = 0.4f + rnd.nextFloat() * 1.2f;
    }

    private void drawSparks(Canvas c, int w, int h, long now, float dt) {
        if (now >= nextBurst && now - burstStart > BURST_MS) newBurst(w, h, now);
        long age = now - burstStart;
        if (age < BURST_MS) {
            for (int f = 0; f < FLASH_ON.length; f++) {
                if (age >= FLASH_ON[f] && age < FLASH_OFF[f]) {
                    if (flashDrawn != f) {
                        flashDrawn = f;
                        breakBolt();
                    }
                    // The last flash dies away.
                    float fade = f == FLASH_ON.length - 1 ? 1f - (age - FLASH_ON[f]) / (float) (FLASH_OFF[f] - FLASH_ON[f]) : 1f;
                    glow.setAlpha(Math.round(70 * fade));
                    mid.setAlpha(Math.round(150 * fade));
                    core.setAlpha(Math.round(220 * fade));
                    c.drawPath(bolt, glow);
                    c.drawPath(bolt, mid);
                    c.drawPath(bolt, core);
                }
            }
        }
        // Chips fly off the ends and fade over the 450 ms after the burst starts.
        float chipLife = (age) / (BURST_MS + 450f);
        if (chipLife < 1f) {
            chipPaint.setAlpha(Math.round(200 * (1f - chipLife)));
            float r = 1.6f * density;
            for (int i = 0; i < CHIPS; i++) {
                cx[i] += cvx[i] * dt;
                cy[i] += cvy[i] * dt;
                cvy[i] += 140 * density * dt;   // a little gravity
                c.drawCircle(cx[i], cy[i], r, chipPaint);
            }
        }
    }

    private void newBurst(int w, int h, long now) {
        burstStart = now;
        flashDrawn = -1;
        nextBurst = now + 3000 + rnd.nextInt(4000);
        float len = (120 + rnd.nextFloat() * 180) * density;
        double angle = rnd.nextDouble() * Math.PI * 2;
        ax = w * (0.45f + rnd.nextFloat() * 0.45f);
        ay = h * (0.08f + rnd.nextFloat() * 0.40f);
        bx = Math.max(w * 0.40f, Math.min(w * 0.97f, ax + (float) Math.cos(angle) * len));
        by = Math.max(h * 0.05f, Math.min(h * 0.55f, ay + (float) Math.sin(angle) * len));
        for (int i = 0; i < CHIPS; i++) {
            boolean fromA = i % 2 == 0;
            cx[i] = fromA ? ax : bx;
            cy[i] = fromA ? ay : by;
            double a = rnd.nextDouble() * Math.PI * 2;
            float v = (40 + rnd.nextFloat() * 110) * density;
            cvx[i] = (float) Math.cos(a) * v;
            cvy[i] = (float) Math.sin(a) * v - 30 * density;
        }
    }

    /** Midpoint displacement between the two ends, plus a short fork: a new crackle each flash. */
    private void breakBolt() {
        bolt.reset();
        bolt.moveTo(ax, ay);
        jag(ax, ay, bx, by, 5);
        // One fork from somewhere along the bolt, shorter and off to the side.
        float t = 0.3f + rnd.nextFloat() * 0.4f;
        float fx = ax + (bx - ax) * t, fy = ay + (by - ay) * t;
        float len = (float) Math.hypot(bx - ax, by - ay) * (0.25f + rnd.nextFloat() * 0.2f);
        double a = Math.atan2(by - ay, bx - ax) + (rnd.nextBoolean() ? 0.7 : -0.7);
        bolt.moveTo(fx, fy);
        jag(fx, fy, fx + (float) Math.cos(a) * len, fy + (float) Math.sin(a) * len, 3);
    }

    private void jag(float x0, float y0, float x1, float y1, int depth) {
        if (depth == 0) {
            bolt.lineTo(x1, y1);
            return;
        }
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        float off = (rnd.nextFloat() - 0.5f) * len * 0.35f;
        float mx = (x0 + x1) / 2 - (y1 - y0) / Math.max(1f, len) * off;
        float my = (y0 + y1) / 2 + (x1 - x0) / Math.max(1f, len) * off;
        jag(x0, y0, mx, my, depth - 1);
        jag(mx, my, x1, y1, depth - 1);
    }
}
