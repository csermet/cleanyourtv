package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * single_row: one band behind the whole row, fixed to the screen while the tiles scroll under it, in
 * the same colours as the dock band (DockDecoration).
 */
public class RowBandDecoration extends RecyclerView.ItemDecoration {

    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    RowBandDecoration(float density, int opacityPercent) {
        this.density = density;
        int alpha = Math.round(255 * Math.max(20, Math.min(100, opacityPercent)) / 100f);
        band.setColor((alpha << 24) | 0x141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x1FFFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        outRect.top = (int) (6 * density);
        outRect.bottom = (int) (12 * density);
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float b = t + card.getHeight();
            // The full width of the row, a little inside the list's edges; the tiles sit inset by its padding.
            float inset = 14 * density, pad = 10 * density;
            rect.set(inset, t - pad, parent.getWidth() - inset, b + pad);
            float radius = 14 * density;
            c.drawRoundRect(rect, radius, radius, band);
            c.drawRoundRect(rect, radius, radius, edge);
            return;
        }
    }
}
