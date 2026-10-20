package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The owner's own tile order and hidden apps, chosen on the TV (hold OK on a tile). Stored in the
 * launcher settings, so a theme push keeps them; Launcher settings can reset both.
 */
final class TileOrder {
    static final String ORDER_KEY = "tile_order";
    static final String HIDDEN_KEY = "tile_hidden";

    private TileOrder() {}

    static List<String> order(Context ctx) {
        return split(prefs(ctx).getString(ORDER_KEY, ""));
    }

    static Set<String> hidden(Context ctx) {
        return new LinkedHashSet<>(split(prefs(ctx).getString(HIDDEN_KEY, "")));
    }

    static void saveOrder(Context ctx, List<String> order) {
        prefs(ctx).edit().putString(ORDER_KEY, String.join(",", order)).apply();
    }

    static void hide(Context ctx, String pkg) {
        Set<String> h = hidden(ctx);
        h.add(pkg);
        prefs(ctx).edit().putString(HIDDEN_KEY, String.join(",", h)).apply();
    }

    static void resetOrder(Context ctx) {
        prefs(ctx).edit().remove(ORDER_KEY).apply();
    }

    static void showHidden(Context ctx) {
        prefs(ctx).edit().remove(HIDDEN_KEY).apply();
    }

    /** {@code order} with {@code pkg} moved {@code delta} places (clamped at the ends); plain Java, tested. */
    static List<String> move(List<String> order, String pkg, int delta) {
        List<String> out = new ArrayList<>(order);
        int from = out.indexOf(pkg);
        if (from < 0) return out;
        int to = Math.max(0, Math.min(out.size() - 1, from + delta));
        if (to == from) return out;
        out.remove(from);
        out.add(to, pkg);
        return out;
    }

    static List<String> split(String s) {
        if (s == null || s.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String p : Arrays.asList(s.split(","))) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
