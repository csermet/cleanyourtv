package com.gathin.tvhome;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.io.File;

/**
 * live_background video: a muted loop from the theme's videos/ folder, in a TextureView over the wallpaper
 * picture, which stays underneath as its poster. It plays only while Home is on screen: the player is
 * released when Home is left (the app opened next gets the TV's video decoder) and carries on from the same
 * point on the way back. Any player error leaves the picture alone on screen.
 */
final class LiveVideo implements TextureView.SurfaceTextureListener {
    private static final String TAG = "tvhome-video";
    private final TextureView view;
    final File file;
    private final boolean fade;
    private MediaPlayer player;
    private Surface surface;
    /** Home is on screen; shown: a frame of ours is on the view. */
    private boolean wanted, shown;
    private int position;

    LiveVideo(Context ctx, File file, boolean fade) {
        this.file = file;
        this.fade = fade;
        view = new TextureView(ctx);
        view.setAlpha(0f);
        view.setSurfaceTextureListener(this);
    }

    View view() {
        return view;
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
            p.setVolume(0f, 0f);
            p.setOnPreparedListener(mp -> {
                if (mp != player) return;
                if (position > 0) mp.seekTo(position);
                mp.start();
            });
            p.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && mp == player && !shown) {
                    shown = true;
                    Log.i(TAG, "playing " + file.getName());
                    if (fade) view.animate().alpha(1f).setDuration(600).start();
                    else view.setAlpha(1f);
                }
                return false;
            });
            p.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "player error " + what + "/" + extra + ": the picture stays");
                if (mp == player) release();
                view.animate().cancel();
                view.setAlpha(0f);
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
        view.animate().cancel();
        view.setAlpha(0f);
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
    }
}
