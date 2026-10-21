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
        final String ratingKey, title, subtitle, image, guid;
        /** "movie" or "show"; for a show, its rating key and the season the card is about (0 when unknown). */
        final String type, showKey;
        final int season;
        /** watch.plex.tv slug from Plex's online metadata, or "" when unknown. */
        final String slug;
        Item(String ratingKey, String title, String subtitle, String image, String guid,
             String type, String showKey, int season, String slug) {
            this.ratingKey = ratingKey; this.title = title; this.subtitle = subtitle; this.image = image; this.guid = guid;
            this.type = type; this.showKey = showKey; this.season = season; this.slug = slug;
        }

        Item with(String newTitle, String newSlug) {
            return new Item(ratingKey, newTitle, subtitle, image, guid, type, showKey, season, newSlug);
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
        return englishTitles(out);
    }

    /**
     * Plex's online metadata for every matched item (plex:// guid): the watch.plex.tv slug for opening
     * its page, and the English title where the library shows original (e.g. Japanese) titles. Cached
     * per guid; a failed lookup is tried again on the next load.
     */
    private List<Item> englishTitles(List<Item> items) {
        String token = prefs.getString(TOKEN, null);
        List<Item> res = new ArrayList<>();
        for (Item it : items) {
            String title = it.title, slug = "";
            if (token != null && it.guid.startsWith("plex://")) {
                String en = prefs.getString("en:" + it.guid, null);
                slug = prefs.getString("slug:" + it.guid, null);
                if (en == null || slug == null) {
                    String[] meta = fetchOnlineMeta(it.guid, token);
                    if (meta != null) {
                        en = meta[0];
                        slug = meta[1];
                        prefs.edit().putString("en:" + it.guid, en).putString("slug:" + it.guid, slug).apply();
                    }
                }
                if (slug == null) slug = "";
                if (!latin(title) && en != null && !en.isEmpty() && latin(en)) title = en;
            }
            res.add(it.with(title, slug));
        }
        return res;
    }

    /** {English title, slug} from metadata.provider.plex.tv, or null on failure. */
    private String[] fetchOnlineMeta(String guid, String token) {
        try {
            String id = guid.substring(guid.lastIndexOf('/') + 1);
            JSONObject mc = new JSONObject(request("GET",
                    "https://metadata.provider.plex.tv/library/metadata/" + enc(id) + "?X-Plex-Language=en-US", token))
                    .optJSONObject("MediaContainer");
            JSONArray md = mc == null ? null : mc.optJSONArray("Metadata");
            if (md == null || md.length() == 0) return new String[]{"", ""};
            JSONObject m = md.getJSONObject(0);
            return new String[]{m.optString("title", ""), m.optString("slug", "")};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The item's page in the Plex app (watch.plex.tv opens even while Plex is still starting): a film's
     * page, or for a show the season you are in, from the server's On Deck episode, else the first
     * season with unwatched episodes, else the card's own season. Null when there is no slug, or when
     * watch.plex.tv has no page for it (then the card opens the Plex app itself).
     */
    String watchUrl(Item it) {
        if (it.slug.isEmpty()) return null;
        if ("movie".equals(it.type)) {
            String film = "https://watch.plex.tv/movie/" + it.slug;
            return pageExists(film) ? film : null;   // null: the Plex app's own Home, not an error page
        }
        int season = 0;
        if (!it.showKey.isEmpty()) {
            try {
                JSONObject mc = new JSONObject(server("/library/metadata/" + it.showKey + "?includeOnDeck=1"))
                        .optJSONObject("MediaContainer");
                JSONArray md = mc == null ? null : mc.optJSONArray("Metadata");
                JSONObject deck = md != null && md.length() > 0 ? md.getJSONObject(0).optJSONObject("OnDeck") : null;
                JSONArray deckMd = deck == null ? null : deck.optJSONArray("Metadata");
                if (deckMd != null && deckMd.length() > 0) season = deckMd.getJSONObject(0).optInt("parentIndex", 0);
                if (season == 0) {
                    JSONArray seasons = metadata(server("/library/metadata/" + it.showKey + "/children"));
                    for (int i = 0; i < seasons.length() && season == 0; i++) {
                        JSONObject s = seasons.getJSONObject(i);
                        int idx = s.optInt("index", 0);
                        if (idx > 0 && s.optInt("viewedLeafCount", 0) < s.optInt("leafCount", 0)) season = idx;
                    }
                }
            } catch (Exception ignored) {
                // fall back to the card's season
            }
        }
        if (season == 0) season = it.season;
        String show = "https://watch.plex.tv/show/" + it.slug;
        String page = pageFor(show, season, this::pageExists);
        return page.equals(show) && !pageExists(show) ? null : page;
    }

    /**
     * The season's page when watch.plex.tv has it, else the show's. Plex's online metadata can lag the
     * library by a season, or number it differently (an anime season listed as a show of its own); the
     * Plex app opens such a season page as "Something's Not Right".
     */
    static String pageFor(String show, int season, java.util.function.Predicate<String> exists) {
        if (season <= 0) return show;
        String page = show + "/season/" + season;
        return exists.test(page) ? page : show;
    }

    private static final long PAGE_FOUND = -1L, PAGE_RECHECK_MS = 24 * 60 * 60_000L;

    /**
     * A HEAD request to the public page. Cached: a page that exists for good, a missing one for a day (the
     * new season shows up online later). Offline, slow or anything but 404 counts as there.
     */
    private boolean pageExists(String url) {
        String key = "page:" + url;
        long seen = prefs.getLong(key, 0);
        if (seen == PAGE_FOUND) return true;
        if (seen > 0 && System.currentTimeMillis() - seen < PAGE_RECHECK_MS) return false;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            int code = c.getResponseCode();
            if (code / 100 == 2) {
                prefs.edit().putLong(key, PAGE_FOUND).apply();
                return true;
            }
            if (code == 404) {
                prefs.edit().putLong(key, System.currentTimeMillis()).apply();
                return false;
            }
            return true;
        } catch (IOException e) {
            return true;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** True when every letter is Latin (digits and punctuation do not count). */
    static boolean latin(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static void add(List<Item> out, Set<String> seen, JSONObject m) {
        String type = m.optString("type");
        String key, title, sub, image, guid, kind = "show", showKey;
        int season = 0;
        switch (type) {
            case "episode":
                key = m.optString("ratingKey");
                title = m.optString("grandparentTitle", m.optString("title"));
                sub = "S" + m.optInt("parentIndex") + " · E" + m.optInt("index");
                image = first(m, "thumb", "grandparentArt", "art");
                guid = m.optString("grandparentGuid", "");
                showKey = m.optString("grandparentRatingKey", "");
                season = m.optInt("parentIndex", 0);
                if (!seen.add("show:" + m.optString("grandparentRatingKey", key))) return;
                break;
            case "season":
                key = m.optString("ratingKey");
                title = m.optString("parentTitle", m.optString("title"));
                sub = m.optString("title");
                image = first(m, "art", "parentArt", "thumb");
                guid = m.optString("parentGuid", "");
                showKey = m.optString("parentRatingKey", "");
                season = m.optInt("index", 0);
                if (!seen.add("show:" + m.optString("parentRatingKey", key))) return;
                break;
            case "movie":
            case "show":
                key = m.optString("ratingKey");
                title = m.optString("title");
                sub = m.has("year") ? String.valueOf(m.optInt("year")) : "";
                image = first(m, "art", "thumb");
                guid = m.optString("guid", "");
                kind = type;
                showKey = "show".equals(type) ? key : "";
                if (!seen.add(("show".equals(type) ? "show:" : "movie:") + key)) return;
                break;
            default:
                return;
        }
        if (key.isEmpty() || title.isEmpty()) return;
        out.add(new Item(key, title, sub, image, guid, kind, showKey, season, ""));
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
            c.setRequestProperty("X-Plex-Language", "en-US");
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
