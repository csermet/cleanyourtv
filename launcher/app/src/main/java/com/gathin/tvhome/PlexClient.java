package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Plex for the home screen's recommended row: plex.tv/link sign-in (the TV shows a code, the owner
 * types it at plex.tv/link), the owner's server and its local https address (plex.direct), and the
 * row's items. The token lives in this app's private preferences only and is never logged. Every
 * network call blocks: call them off the main thread.
 */
final class PlexClient {
    private static final String PREFS = "plex";
    private static final String TOKEN = "token", CLIENT = "client_id", URI = "server_uri",
            SERVER_TOKEN = "server_token", SERVER_ID = "server_id", SERVER_NAME = "server_name";
    private static final String PLEX_TV = "https://plex.tv/api/v2";
    static final String PLEX_PACKAGE = "com.plexapp.android";

    static final class Pin {
        final long id;
        final String code;
        Pin(long id, String code) { this.id = id; this.code = code; }
    }

    static final class Item {
        final String ratingKey, title, subtitle, image;
        Item(String ratingKey, String title, String subtitle, String image) {
            this.ratingKey = ratingKey; this.title = title; this.subtitle = subtitle; this.image = image;
        }
    }

    private final SharedPreferences prefs;

    PlexClient(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean linked() { return prefs.getString(TOKEN, null) != null; }
    boolean hasServer() { return prefs.getString(URI, null) != null; }
    String serverName() { return prefs.getString(SERVER_NAME, ""); }

    void unlink() {
        String client = prefs.getString(CLIENT, null);
        SharedPreferences.Editor e = prefs.edit().clear();
        if (client != null) e.putString(CLIENT, client);
        e.apply();
    }

    private String clientId() {
        String id = prefs.getString(CLIENT, null);
        if (id == null) {
            id = "tvhome-" + UUID.randomUUID();
            prefs.edit().putString(CLIENT, id).apply();
        }
        return id;
    }

    // ---- sign-in ------------------------------------------------------------------------------

    Pin createPin() throws IOException, JSONException {
        JSONObject o = new JSONObject(request("POST", PLEX_TV + "/pins?strong=false", null));
        return new Pin(o.getLong("id"), o.getString("code"));
    }

    /** True once the owner entered the code at plex.tv/link; the token is then stored. */
    boolean checkPin(long id) throws IOException, JSONException {
        JSONObject o = new JSONObject(request("GET", PLEX_TV + "/pins/" + id, null));
        String t = o.optString("authToken", "");
        if (t.isEmpty() || "null".equals(t)) return false;
        prefs.edit().putString(TOKEN, t).apply();
        return true;
    }

    /**
     * Finds the owner's server and an https address this TV can actually reach. A server in Docker
     * advertises its container address as "local", which other machines cannot reach, so every
     * candidate is probed (GET /identity, short timeout). {@code lanIp} (theme plex_lan_ip) adds the
     * server's LAN address in the certificate's plex.direct form and is tried first.
     */
    boolean discover(String lanIp) throws IOException, JSONException {
        String token = prefs.getString(TOKEN, null);
        if (token == null) return false;
        JSONArray arr = new JSONArray(request("GET", PLEX_TV + "/resources?includeHttps=1&includeRelay=0", token));
        java.util.regex.Pattern direct = java.util.regex.Pattern.compile("https://[^/.]+\\.([0-9a-f]+)\\.plex\\.direct:(\\d+)");
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optString("provides", "").contains("server")) continue;
                if (pass == 0 && !o.optBoolean("owned", false)) continue;   // the owner's own server first
                String serverToken = o.optString("accessToken", token);
                List<String> candidates = new ArrayList<>();
                JSONArray conns = o.optJSONArray("connections");
                if (conns == null) continue;
                for (int j = 0; j < conns.length(); j++) {
                    JSONObject c = conns.getJSONObject(j);
                    if (c.optBoolean("relay", false) || !"https".equals(c.optString("protocol"))) continue;
                    String uri = c.optString("uri", "");
                    if (uri.isEmpty()) continue;
                    java.util.regex.Matcher m = direct.matcher(uri);
                    if (lanIp != null && !lanIp.isEmpty() && m.find()) {
                        String lan = "https://" + lanIp.trim().replace('.', '-') + "." + m.group(1) + ".plex.direct:" + m.group(2);
                        if (!candidates.contains(lan)) candidates.add(0, lan);
                    }
                    if (c.optBoolean("local", false)) candidates.add(uri);
                    else candidates.add(candidates.size(), uri);
                }
                for (String uri : candidates) {
                    if (!reachable(uri, serverToken)) continue;
                    prefs.edit().putString(URI, uri)
                            .putString(SERVER_TOKEN, serverToken)
                            .putString(SERVER_ID, o.optString("clientIdentifier", ""))
                            .putString(SERVER_NAME, o.optString("name", "Plex")).apply();
                    return true;
                }
            }
        }
        return false;
    }

    /** Forget the server address, so the next load looks for it again. */
    void forgetServer() {
        prefs.edit().remove(URI).remove(SERVER_TOKEN).remove(SERVER_ID).remove(SERVER_NAME).apply();
    }

    private boolean reachable(String base, String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/identity").openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestProperty("Accept", "application/json");
            if (token != null) c.setRequestProperty("X-Plex-Token", token);
            return c.getResponseCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- the row --------------------------------------------------------------------------------

    /** Recently added first (one card per show), then a random pick of unwatched films and shows. */
    List<Item> recommended(int max) throws IOException, JSONException {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Film and series libraries only: personal media (camera clips, home videos) has no agent.
        JSONArray sections = directories(server("/library/sections"));
        List<JSONObject> libraries = new ArrayList<>();
        Set<String> allowed = new HashSet<>();
        for (int i = 0; i < sections.length(); i++) {
            JSONObject s = sections.getJSONObject(i);
            String type = s.optString("type");
            if (!"movie".equals(type) && !"show".equals(type)) continue;
            if (s.optString("agent", "").contains("none")) continue;
            libraries.add(s);
            allowed.add(s.optString("key"));
        }
        JSONArray recent = metadata(server("/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=60"));
        for (int i = 0; i < recent.length() && out.size() < max / 2; i++) {
            JSONObject m = recent.getJSONObject(i);
            if (allowed.contains(m.optString("librarySectionID"))) add(out, seen, m);
        }
        List<Item> random = new ArrayList<>();
        for (JSONObject s : libraries) {
            JSONArray all = metadata(server("/library/sections/" + s.optString("key")
                    + "/all?unwatched=1&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=" + max));
            for (int j = 0; j < all.length(); j++) add(random, seen, all.getJSONObject(j));
        }
        Collections.shuffle(random);
        for (Item it : random) {
            if (out.size() >= max) break;
            out.add(it);
        }
        return out;
    }

    private static void add(List<Item> out, Set<String> seen, JSONObject m) {
        String type = m.optString("type");
        String key, title, sub, image;
        switch (type) {
            case "episode":
                key = m.optString("ratingKey");
                title = m.optString("grandparentTitle", m.optString("title"));
                sub = "S" + m.optInt("parentIndex") + " · E" + m.optInt("index");
                image = first(m, "thumb", "grandparentArt", "art");
                if (!seen.add("show:" + m.optString("grandparentRatingKey", key))) return;
                break;
            case "season":
                key = m.optString("ratingKey");
                title = m.optString("parentTitle", m.optString("title"));
                sub = m.optString("title");
                image = first(m, "art", "parentArt", "thumb");
                if (!seen.add("show:" + m.optString("parentRatingKey", key))) return;
                break;
            case "movie":
            case "show":
                key = m.optString("ratingKey");
                title = m.optString("title");
                sub = m.has("year") ? String.valueOf(m.optInt("year")) : "";
                image = first(m, "art", "thumb");
                if (!seen.add(("show".equals(type) ? "show:" : "movie:") + key)) return;
                break;
            default:
                return;
        }
        if (key.isEmpty() || title.isEmpty()) return;
        out.add(new Item(key, title, sub, image));
    }

    private static String first(JSONObject m, String... keys) {
        for (String k : keys) {
            String v = m.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static JSONArray metadata(String body) throws JSONException {
        JSONArray a = new JSONObject(body).optJSONObject("MediaContainer") == null ? null
                : new JSONObject(body).getJSONObject("MediaContainer").optJSONArray("Metadata");
        return a != null ? a : new JSONArray();
    }

    private static JSONArray directories(String body) throws JSONException {
        JSONObject mc = new JSONObject(body).optJSONObject("MediaContainer");
        JSONArray a = mc == null ? null : mc.optJSONArray("Directory");
        return a != null ? a : new JSONArray();
    }

    /** A scaled picture from the server's transcoder (no token in it), or null when there is no picture. */
    String imageUrl(String path, int w, int h) {
        String base = prefs.getString(URI, null);
        if (base == null || path == null || path.isEmpty()) return null;
        return base + "/photo/:/transcode?width=" + w + "&height=" + h + "&minSize=1&upscale=1&url=" + enc(path);
    }

    /** Downloads a picture from imageUrl with the token in a header; null on any failure. */
    android.graphics.Bitmap fetchImage(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            String token = prefs.getString(SERVER_TOKEN, null);
            if (token != null) c.setRequestProperty("X-Plex-Token", token);
            if (c.getResponseCode() / 100 != 2) return null;
            try (InputStream in = c.getInputStream()) {
                return android.graphics.BitmapFactory.decodeStream(in);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Opens the item in the Plex app (Plex 2026.19 and later; older builds open Plex's home). */
    String deepLink(String ratingKey) {
        String machine = prefs.getString(SERVER_ID, "");
        return "plex://watch/video?uri=" + enc("server://" + machine
                + "/com.plexapp.plugins.library/library/metadata/" + ratingKey);
    }

    private String server(String path) throws IOException {
        String base = prefs.getString(URI, null);
        if (base == null) throw new IOException("no server");
        return request("GET", base + path, prefs.getString(SERVER_TOKEN, null));
    }

    private String request(String method, String url, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-Plex-Product", "tvhome");
            c.setRequestProperty("X-Plex-Version", "2.3");
            c.setRequestProperty("X-Plex-Client-Identifier", clientId());
            c.setRequestProperty("X-Plex-Platform", "Android");
            c.setRequestProperty("X-Plex-Device", "Android TV");
            c.setRequestProperty("X-Plex-Device-Name", "tvhome launcher");
            if (token != null) c.setRequestProperty("X-Plex-Token", token);
            if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.getOutputStream().close();
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
