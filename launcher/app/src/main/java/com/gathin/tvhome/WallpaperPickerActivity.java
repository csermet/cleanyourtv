package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.time.LocalDate;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pick today's wallpaper with a live preview: the focused card's picture fills the screen. OK picks it
 * for today (the daily shuffle carries on tomorrow); the first card shuffles to another picture.
 */
public final class WallpaperPickerActivity extends Activity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private File[] pictures;
    private File current;
    private ImageView preview;
    private File previewing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallpaper_picker);
        pictures = WallpaperRotation.list(Config.dir(this));
        current = Config.currentWallpaper(this);
        preview = findViewById(R.id.preview);
        showPreview(current);
        RecyclerView thumbs = findViewById(R.id.thumbs);
        thumbs.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        thumbs.setItemAnimator(null);
        thumbs.setAdapter(new Thumbs());
        int start = 0;
        for (int i = 0; i < pictures.length; i++) if (pictures[i].equals(current)) start = i + 1;
        final int focus = start;
        thumbs.scrollToPosition(focus);
        thumbs.post(() -> {
            RecyclerView.ViewHolder vh = thumbs.findViewHolderForAdapterPosition(focus);
            if (vh != null) vh.itemView.findViewById(R.id.card).requestFocus();
        });
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void pick(File f) {
        SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        choices.edit().putString(Config.WALLPAPER_PICK, f.getName())
                .putLong(Config.WALLPAPER_PICK_DAY, LocalDate.now().toEpochDay()).apply();
        setResult(RESULT_OK);
        finish();
    }

    private void shuffle() {
        if (pictures.length < 2) return;
        File chosen;
        do { chosen = pictures[new Random().nextInt(pictures.length)]; } while (chosen.equals(current));
        pick(chosen);
    }

    /** Full-screen preview, decoded off the main thread; a later focus wins over an earlier decode. */
    private void showPreview(File f) {
        if (f == null || f.equals(previewing)) return;
        previewing = f;
        loader.execute(() -> {
            Bitmap bmp = decode(f, 1920);
            main.post(() -> {
                if (bmp != null && f.equals(previewing)) preview.setImageBitmap(bmp);
            });
        });
    }

    private static Bitmap decode(File f, int maxWidth) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private final class Thumbs extends RecyclerView.Adapter<Thumbs.Holder> {
        @Override
        public int getItemCount() { return pictures.length + 1; }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_wallpaper, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            boolean shuffle = position == 0;
            File f = shuffle ? null : pictures[position - 1];
            h.shuffle.setVisibility(shuffle ? View.VISIBLE : View.GONE);
            h.label.setText(shuffle ? getString(R.string.wallpaper_shuffle_now) : WallpaperRotation.label(f)
                    + (f.equals(current) ? "  •" : ""));
            h.thumb.setImageBitmap(null);
            h.thumb.setTag(f);
            if (f != null) {
                loader.execute(() -> {
                    Bitmap bmp = decode(f, 360);
                    main.post(() -> {
                        if (f.equals(h.thumb.getTag())) h.thumb.setImageBitmap(bmp);
                    });
                });
            }
            h.card.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(150).start();
                if (focused) showPreview(shuffle ? current : f);
            });
            h.card.setOnClickListener(v -> {
                if (shuffle) shuffle();
                else pick(f);
            });
        }

        final class Holder extends RecyclerView.ViewHolder {
            final View card;
            final ImageView thumb;
            final TextView label, shuffle;

            Holder(View v) {
                super(v);
                card = v.findViewById(R.id.card);
                thumb = v.findViewById(R.id.thumb);
                label = v.findViewById(R.id.label);
                shuffle = v.findViewById(R.id.shuffleIcon);
            }
        }
    }
}
