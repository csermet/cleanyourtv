package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The header's status lines (theme status_nodes / status_usage_url): CPU, RAM and temperature per
 * server from node-exporter's /metrics, and the Claude usage windows from a JSON endpoint. CPU is the
 * busy share between two reads, so the first read only primes it. Network calls block.
 */
final class ServerStatus {
    private final Map<String, double[]> lastCpu = new HashMap<>();   // url -> {total, idle}

    /** "intel %12 %37 51°  ·  ryzen …" — cpu, ram, temperature; "…" where a value is not known yet. */
    String nodesLine(List<String[]> nodes) {
        StringBuilder sb = new StringBuilder();
        for (String[] n : nodes) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append(n[0]).append("  ");
            try {
                sb.append(node(n[1]));
            } catch (Exception e) {
                sb.append("—");
            }
        }
        return sb.toString();
    }

    private String node(String url) throws Exception {
        double total = 0, idle = 0, memAvail = -1, memTotal = -1, temp = -1, zone = -1;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("node_cpu_seconds_total{")) {
                    double v = value(line);
                    total += v;
                    if (line.contains("mode=\"idle\"") || line.contains("mode=\"iowait\"")) idle += v;
                } else if (line.startsWith("node_memory_MemAvailable_bytes ")) {
                    memAvail = value(line);
                } else if (line.startsWith("node_memory_MemTotal_bytes ")) {
                    memTotal = value(line);
                } else if (line.startsWith("node_hwmon_temp_celsius{") && (line.contains("coretemp")
                        || line.contains(":18_3") || line.contains("k10temp") || line.contains("thermal_zone"))) {
                    temp = Math.max(temp, value(line));
                } else if (line.startsWith("node_thermal_zone_temp{")) {
                    zone = Math.max(zone, value(line));
                }
            }
        } finally {
            c.disconnect();
        }
        if (temp < 0) temp = zone;
        double[] prev = lastCpu.put(url, new double[]{total, idle});
        String cpu = "…";
        if (prev != null && total > prev[0]) {
            cpu = "%" + Math.round(100 * (1 - (idle - prev[1]) / (total - prev[0])));
        }
        String ram = memTotal > 0 && memAvail >= 0 ? "%" + Math.round(100 * (1 - memAvail / memTotal)) : "—";
        String t = temp >= 0 ? Math.round(temp) + "°" : "";
        return cpu + "  " + ram + (t.isEmpty() ? "" : "  " + t);
    }

    private static double value(String line) {
        return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
    }

    /** "Claude  5s %17 · hafta %39" from the usage JSON's windows (session, and the all-models week). */
    static String usageLine(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            String body;
            try {
                body = PlexClient.readAll(c.getInputStream());
            } finally {
                c.disconnect();
            }
            JSONArray w = new JSONObject(body).optJSONArray("windows");
            if (w == null) return "";
            int session = -1, week = -1;
            for (int i = 0; i < w.length(); i++) {
                JSONObject o = w.getJSONObject(i);
                String kind = o.optString("kind", "");
                int pct = (int) Math.round(o.optDouble("percent", -1));
                if (kind.startsWith("session") && session < 0) session = pct;
                else if (kind.startsWith("week") && o.isNull("model")) week = Math.max(week, pct);
            }
            StringBuilder sb = new StringBuilder("Claude");
            if (session >= 0) sb.append("  5s %").append(session);
            if (week >= 0) sb.append("   ·   hafta %").append(week);
            return sb.toString();
        } catch (Exception e) {
            return "Claude  —";
        }
    }
}
