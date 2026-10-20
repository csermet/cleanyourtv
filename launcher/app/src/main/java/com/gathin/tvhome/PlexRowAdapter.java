package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The Plex recommended row: small landscape cards with the artwork and a title, loaded off the main thread. */
final class PlexRowAdapter extends RecyclerView.Adapter<PlexRowAdapter.Holder> {

    interface Listener {
        void onPlexClick(PlexClient.Item item);
        void onPlexFocused(int position);
        /** D-pad on a card; return true when handled. */
        boolean onPlexKey(int position, int keyCode);
    }

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private final PlexClient plex;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<PlexClient.Item> items = new ArrayList<>();
    private int cellWidth, cardWidth, cardHeight;

    PlexRowAdapter(PlexClient plex, Listener listener) {
        this.plex = plex;
        this.listener = listener;
        setHasStableIds(true);
    }

    /** Card size from the row's inner width and the number of cards per screen. */
    void setSize(int innerWidth, int perScreen, float density) {
        cellWidth = innerWidth / perScreen;
        cardWidth = cellWidth - Math.round(14 * density);
        cardHeight = Math.round(cardWidth * 9f / 16f);
    }

    void setItems(List<PlexClient.Item> fresh) {
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    int cardHeight() { return cardHeight; }

    @Override
    public long getItemId(int position) { return items.get(position).ratingKey.hashCode(); }

    @Override
    public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_plex, parent, false);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = cellWidth;
        v.setLayoutParams(lp);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams clp = card.getLayoutParams();
        clp.width = cardWidth;
        clp.height = cardHeight;
        card.setLayoutParams(clp);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        PlexClient.Item it = items.get(position);
        h.title.setText(it.title);
        h.subtitle.setText(it.subtitle);
        h.subtitle.setVisibility(it.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        h.card.setOnClickListener(v -> listener.onPlexClick(it));
        h.card.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(150).start();
            int pos = h.getBindingAdapterPosition();
            if (focused && pos != RecyclerView.NO_POSITION) listener.onPlexFocused(pos);
        });
        h.card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = h.getBindingAdapterPosition();
            return pos != RecyclerView.NO_POSITION && listener.onPlexKey(pos, keyCode);
        });
        String url = plex.imageUrl(it.image, Math.max(320, cardWidth), Math.max(180, cardHeight));
        h.art.setTag(url);
        Bitmap cached = url == null ? null : CACHE.get(url);
        h.art.setImageBitmap(cached);
        if (url != null && cached == null) {
            LOADER.execute(() -> {
                Bitmap bmp = plex.fetchImage(url);
                if (bmp == null) return;
                CACHE.put(url, bmp);
                main.post(() -> {
                    if (url.equals(h.art.getTag())) h.art.setImageBitmap(bmp);
                });
            });
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView art;
        final TextView title, subtitle;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            art = v.findViewById(R.id.art);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }
}
