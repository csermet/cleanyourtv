package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * single_row + plex_row: the two bands, drawn behind the rows instead of inside them, so a row can
 * shrink (scale) while its band keeps the full width: only the band's height follows the tiles.
 */
final class RowBands extends View {
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RecyclerView[] rows;
    /** The rows' own width at full size (their layout width can grow while they are shrunk). */
    private int normalWidth;
    /** Per row, 0 to 1: a row coming in fades its band in with it. */
    private final float[] shown;
    private final int bandAlpha, edgeAlpha = 0x1F;

    RowBands(Context ctx, float density, int opacityPercent, RecyclerView... rows) {
        super(ctx);
        this.density = density;
        this.rows = rows;
        bandAlpha = Math.round(255 * Math.max(20, Math.min(100, opacityPercent)) / 100f);
        band.setColor((bandAlpha << 24) | 0x141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor((edgeAlpha << 24) | 0xFFFFFF);
        shown = new float[rows.length];
        java.util.Arrays.fill(shown, 1f);
        setWillNotDraw(false);
    }

    void setNormalWidth(int w) { normalWidth = w; }

    void setShown(RecyclerView row, float f) {
        for (int i = 0; i < rows.length; i++) if (rows[i] == row) shown[i] = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        for (int r = 0; r < rows.length; r++) {
            RecyclerView row = rows[r];
            if (row == null || row.getVisibility() != View.VISIBLE || shown[r] <= 0f) continue;
            View card = null;
            View child = null;
            for (int i = 0; i < row.getChildCount() && card == null; i++) {
                child = row.getChildAt(i);
                card = child.findViewById(R.id.card);
            }
            if (card == null) continue;
            float s = row.getScaleY(), py = row.getPivotY();
            float base = row.getTop() + row.getTranslationY();
            float top = child.getTop() + card.getTop();
            float bottom = top + card.getHeight();
            float vt = base + py + (top - py) * s, vb = base + py + (bottom - py) * s;
            float pad = 10 * density * s, inset = 6 * density;
            float w = normalWidth > 0 ? normalWidth : row.getWidth();
            rect.set(row.getLeft() + inset, vt - pad, row.getLeft() + w - inset, vb + pad);
            float radius = 14 * density;
            band.setAlpha(Math.round(bandAlpha * shown[r]));
            edge.setAlpha(Math.round(edgeAlpha * shown[r]));
            c.drawRoundRect(rect, radius, radius, band);
            c.drawRoundRect(rect, radius, radius, edge);
        }
    }
}
