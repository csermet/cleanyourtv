package com.gathin.tvhome;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;

/**
 * live_background video: a loop from the theme's videos/ folder, in a TextureView over the wallpaper
 * picture, which stays underneath as its poster. Clips keep their own brightness: a black layer on top
 * (live_video_dim) darkens them behind the UI and comes off in full screen; it fades in with the video, so
 * the already dark poster is never darkened twice. It plays only while Home is on screen: the player is
 * released when Home is left (the app opened next gets the TV's video decoder) and carries on from the same
 * point on the way back. Sound starts off. Any player error leaves the picture alone on screen.
 */
final class LiveVideo implements TextureView.SurfaceTextureListener {
    private static final String TAG = "tvhome-video";
    private final FrameLayout box;
    private final TextureView view;
    private final View dim;
    private final float dimAlpha;
    private boolean sound;
    File file;
    private final boolean fade;
    private MediaPlayer player;
    private Surface surface;
    /** Home is on screen; shown: a frame of ours is on the view. */
    private boolean wanted, shown;
    private int position;
    /** Called for each new video frame (LiveEffectView draws along with it), or null. */
    Runnable onFrame;

    LiveVideo(Context ctx, File file, boolean fade, int dimPercent) {
        this.file = file;
        this.fade = fade;
        box = new FrameLayout(ctx);
        box.setAlpha(0f);
        view = new TextureView(ctx);
        view.setSurfaceTextureListener(this);
        box.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        dim = new View(ctx);
        dim.setBackgroundColor(0xFF000000);
        dimAlpha = Math.max(0, Math.min(90, dimPercent)) / 100f;
        dim.setAlpha(dimAlpha);
        box.addView(dim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    View view() {
        return box;
    }

    /** Full screen: the video at its own brightness; back to live_video_dim behind the UI. */
    void setDimmed(boolean dimmed) {
        dim.animate().cancel();
        dim.animate().alpha(dimmed ? dimAlpha : 0f).setDuration(fade ? 400 : 0).start();
    }

    boolean sound() {
        return sound;
    }

    void setSound(boolean on) {
        sound = on;
        if (player != null) player.setVolume(on ? 1f : 0f, on ? 1f : 0f);
    }
    /** Another clip in place (the Next button): this one fades out, the new one fades in from its start. */
    void switchTo(File next) {
        file = next;
        box.animate().cancel();
        box.animate().alpha(0f).setDuration(fade ? 250 : 0).withEndAction(() -> {
            release();
            position = 0;
            shown = false;
            if (wanted && surface != null) open();
        }).start();
    }

    void start() {
        wanted = true;
        if (surface != null && player == null) open();
    }

    void stop() {
        wanted = false;
        release();
    }

    private void release() {
        if (player == null) return;
        try {
            position = player.getCurrentPosition();
        } catch (IllegalStateException ignored) {
        }
        player.release();
        player = null;
        Log.i(TAG, "released at " + position + " ms");
    }

    private void open() {
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setDataSource(file.getPath());
            p.setSurface(surface);
            p.setLooping(true);
            p.setVolume(sound ? 1f : 0f, sound ? 1f : 0f);
            p.setOnPreparedListener(mp -> {
                if (mp != player) return;
                if (position > 0) mp.seekTo(position);
                mp.start();
            });
            p.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && mp == player && !shown) {
                    shown = true;
                    Log.i(TAG, "playing " + file.getName());
                    if (fade) box.animate().alpha(1f).setDuration(600).start();
                    else box.setAlpha(1f);
                }
                return false;
            });
            p.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "player error " + what + "/" + extra + ": the picture stays");
                if (mp == player) release();
                box.animate().cancel();
                box.setAlpha(0f);
                shown = false;
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "cannot play " + file.getName() + ": " + e.getClass().getSimpleName());
            p.release();
            player = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        if (wanted && player == null) open();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        // The last frame goes with the surface: hidden, so the next first frame fades in again.
        release();
        box.animate().cancel();
        box.setAlpha(0f);
        shown = false;
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
        if (onFrame != null) onFrame.run();
    }
}
