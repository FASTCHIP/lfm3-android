package com.fastchip.lfm3;

// Инструменты агента: поиск, curl/чтение страниц, ping, ls, cat, shell (SSH).
// Без Android-типов — компилируется обычным javac на ПК (javac -cp jsch.jar);
// локальные команды идут через ProcessBuilder("/system/bin/sh") — на телефоне это
// shell от uid приложения.

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import com.jcraft.jsch.*;

public class Tools {

    public static String UA = "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0";
    public static int FETCH_MAX_BYTES = 600 * 1024;
    public static int TEXT_CAP = 4000;      // сколько текста страницы отдаём модели
    public static int OUT_CAP = 3000;       // вывод команд

    // ---------- единая точка входа ----------
    public interface HostResolver { String[] resolve(String alias); } // -> {host,port,user,pass} | null

    public static String run(String name, Map<String, Object> args, HostResolver resolver) throws Exception {
        switch (name) {
            case "web_search": return search(String.valueOf(args.getOrDefault("query", "")));
            case "curl":
            case "net_fetch":  return fetch(String.valueOf(args.getOrDefault("url", "")), truthy(args.get("raw")));
            case "ping":       return ping(String.valueOf(args.getOrDefault("host", "")), clampCount(args.get("count")));
            case "ls":  return remoteOrLocal(String.valueOf(args.getOrDefault("alias", "")),
                        "ls -la -- " + sq(String.valueOf(args.getOrDefault("path", "."))), resolver);
            case "cat": return remoteOrLocal(String.valueOf(args.getOrDefault("alias", "")),
                        "head -c 32768 -- " + sq(String.valueOf(args.getOrDefault("path", ""))), resolver);
            case "shell":
            case "ssh_exec": return remoteOrLocal(String.valueOf(args.getOrDefault("alias", "")),
                        String.valueOf(args.getOrDefault("cmd", "echo ok")), resolver);
            default: throw new IllegalArgumentException("нет инструмента " + name);
        }
    }
    static boolean truthy(Object o) { return Boolean.TRUE.equals(o) || "true".equals(String.valueOf(o)); }
    static int clampCount(Object o) { try { int n = Integer.parseInt(String.valueOf(o).trim()); return Math.max(1, Math.min(8, n)); } catch (Exception e) { return 4; } }

    // без alias — локальная команда (на телефоне — sh от приложения), с alias — SSH
    static String remoteOrLocal(String alias, String cmd, HostResolver resolver) throws Exception {
        if (alias == null || alias.isEmpty() || "null".equals(alias)) return localExec(cmd);
        if (resolver == null) throw new IOException("SSH-серверы не настроены");
        String[] h = resolver.resolve(alias);
        if (h == null) throw new IOException("нет сохранённого хоста «" + alias + "»");
        return sshExec(h[0], Integer.parseInt(h[1]), h[2], h[3], cmd);
    }

    /** одинарная shell-котировка аргумента */
    public static String sq(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    // ---------- локальный shell (на телефоне — /system/bin/sh) ----------

    public static String localExec(String cmd) throws Exception {
        Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        InputStream is = p.getInputStream();
        long deadline = System.currentTimeMillis() + 15000;
        while ((n = is.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 64 * 1024) break;
            if (System.currentTimeMillis() > deadline) break; }
        boolean finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        int code = finished ? p.exitValue() : -1;
        if (!finished) p.destroyForcibly();
        return "{\"ok\":" + (finished && code == 0) + ",\"exit\":" + code +
               ",\"local\":true,\"out\":\"" + jesc(cut(cleanCtl(bo.toString("UTF-8")), OUT_CAP)) + "\"}";
    }

    // ---------- ping ----------

    public static String ping(String host, int count) throws Exception {
        if (host == null || host.isEmpty()) throw new IOException("нужен host");
        String o = extractOut(localExec("ping -w 10 -W 2 -c " + count + " -- " + sq(host)));
        String rtt = "", received = "0", ip = "";
        Matcher m = Pattern.compile("(\\d+)\\s+packets? received[^\n]*", Pattern.DOTALL).matcher(o);
        if (m.find()) received = m.group(1);
        Matcher mr = Pattern.compile("round-trip[^\n]*?= ?([\\d./]+) ?ms").matcher(o);
        if (mr.find()) rtt = mr.group(1);
        Matcher mi = Pattern.compile("\\((\\d+\\.\\d+\\.\\d+\\.\\d+)\\)").matcher(o);
        if (mi.find()) ip = mi.group(1);
        boolean ok = !received.equals("0") && !o.contains("100% packet loss");
        return "{\"ok\":" + ok + ",\"host\":\"" + jesc(host) + "\",\"ip\":\"" + jesc(ip)
             + "\",\"received\":\"" + jesc(received) + "\",\"sent\":" + count
             + ",\"rtt_ms\":\"" + jesc(rtt) + "\"}";
    }

    /** вынуть поле "out" из JSON-конверта localExec (без org.json — чтобы класс жил и на ПК) */
    static String extractOut(String json) {
        Matcher m = Pattern.compile("\"out\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) return json == null ? "" : json;
        return m.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // ---------- HTTP ----------

    static String httpGet(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "ru,en;q=0.8");
        try {
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = is.read(buf)) > 0 && total < FETCH_MAX_BYTES) { bo.write(buf, 0, n); total += n; }
            return bo.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    // ---------- поиск: DDG Lite → DDG HTML → Bing ----------

    public static String search(String q) throws IOException {
        String enc = URLEncoder.encode(q, "UTF-8");
        List<String[]> r = null;
        try { r = ddgLite(enc); } catch (Exception ignored) {}
        if (r == null || r.isEmpty()) { try { r = ddgHtml(enc); } catch (Exception ignored) {} }
        if (r == null || r.isEmpty()) { try { r = bing(enc); } catch (Exception ignored) {} }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":").append(r != null && !r.isEmpty()).append(",\"results\":[");
        if (r != null) for (int i = 0; i < Math.min(6, r.size()); i++) {
            String[] it = r.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"t\":\"").append(jesc(it[0])).append("\",\"u\":\"").append(jesc(it[1]))
              .append("\",\"s\":\"").append(jesc(it[2] == null ? "" : it[2])).append("\"}");
        }
        sb.append('}');
        return sb.toString();
    }

    // lite.duckduckgo.com: ссылки вида href="//duckduckgo.com/l/?uddg=<enc>&rut=..."
    static List<String[]> ddgLite(String encQ) throws IOException {
        String h = httpGet("https://lite.duckduckgo.com/lite/?q=" + encQ, 15000);
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("<a[^>]*href=\"(?:https?:)?//duckduckgo\\.com/l/\\?uddg=([^&\"]+)[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(h);
        List<String> snips = new ArrayList<>();
        Matcher ms = Pattern.compile("class=[\"']result-snippet[\"'][^>]*>(.*?)</(td|span|div)>", Pattern.DOTALL).matcher(h);
        while (ms.find()) snips.add(cleanText(ms.group(1)));
        int i = 0;
        Set<String> seen = new HashSet<>();
        while (m.find() && out.size() < 8) {
            String u;
            try { u = URLDecoder.decode(m.group(1), "UTF-8"); } catch (Exception e) { continue; }
            if (!u.startsWith("http") || !seen.add(u)) continue;
            String t = cleanText(m.group(2));
            if (t.isEmpty()) continue;
            out.add(new String[]{t, u, i < snips.size() ? cut(snips.get(i), 300) : ""});
            i++;
        }
        return out;
    }

    // html.duckduckgo.com/html: <a class="result__a" href="..."> + result__snippet
    static List<String[]> ddgHtml(String encQ) throws IOException {
        String h = httpGet("https://html.duckduckgo.com/html/?q=" + encQ, 15000);
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(h);
        List<String> snips = new ArrayList<>();
        Matcher ms = Pattern.compile("class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(h);
        while (ms.find()) snips.add(cleanText(ms.group(1)));
        int i = 0; Set<String> seen = new HashSet<>();
        while (m.find() && out.size() < 8) {
            String u = unesc(m.group(1));
            Matcher mu = Pattern.compile("[?&]uddg=([^&]+)").matcher(u);
            if (mu.find()) { try { u = URLDecoder.decode(mu.group(1), "UTF-8"); } catch (Exception ignored) {} }
            if (!u.startsWith("http") || !seen.add(u)) continue;
            String t = cleanText(m.group(2));
            if (t.isEmpty()) continue;
            out.add(new String[]{t, u, i < snips.size() ? cut(snips.get(i), 300) : ""});
            i++;
        }
        return out;
    }

    static List<String[]> bing(String encQ) throws IOException {
        String h = httpGet("https://www.bing.com/search?q=" + encQ, 15000);
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("<li[^>]*class=[\"']b_algo[\"'][^>]*>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>(.*?)</li>", Pattern.DOTALL).matcher(h);
        Set<String> seen = new HashSet<>();
        while (m.find() && out.size() < 6) {
            String u = unesc(m.group(1));
            if (!u.startsWith("http") || !seen.add(u)) continue;
            out.add(new String[]{cleanText(m.group(2)), u, cut(cleanText(m.group(3)), 300)});
        }
        return out;
    }

    // ---------- curl / чтение страницы ----------

    public static String fetch(String url) throws IOException { return fetch(url, false); }

    public static String fetch(String url, boolean raw) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        try {
            int code = c.getResponseCode();
            String ct = c.getContentType();
            if (ct != null && !raw &&
                !ct.contains("html") && !ct.contains("text") && !ct.contains("json") && !ct.contains("xml"))
                throw new IOException("не текстовый тип: " + ct + " (попробуй raw:true)");
            if (ct != null && raw &&
                (ct.contains("image") || ct.contains("octet-stream") || ct.contains("pdf") || ct.contains("zip")))
                throw new IOException("бинарный контент: " + ct + ", " + c.getContentLength() + " байт — текста тут нет");
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0; boolean trunc = false;
            while ((n = is.read(buf)) > 0) { if (total >= FETCH_MAX_BYTES) { trunc = true; break; } bo.write(buf, 0, n); total += n; }
            String body = bo.toString("UTF-8");
            String title = "", text;
            if (raw) {
                text = cleanCtl(body);
            } else {
                Matcher mt = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(body);
                if (mt.find()) title = cleanText(mt.group(1));
                text = pageToText(body);
            }
            boolean cutFlag = trunc || text.length() > TEXT_CAP;
            return "{\"ok\":true,\"url\":\"" + jesc(c.getURL().toString()) + "\",\"title\":\"" + jesc(title)
                 + "\",\"truncated\":" + cutFlag + ",\"text\":\"" + jesc(cut(text, TEXT_CAP)) + "\"}";
        } finally { c.disconnect(); }
    }

    static String pageToText(String h) {
        String s = h.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<(br\\s*/?>|</(p|div|li|h[1-6]|tr|table|ul|ol|section|article|blockquote|pre)>)", "\n");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = unesc(s);
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll("(?m)^ +| +$", "");
        s = s.replaceAll("\\n{2,}", "\n");
        return s.trim();
    }

    // ---------- SSH (JSch) ----------

    public static String sshExec(String host, int port, String user, String pass, String cmd) throws Exception {
        JSch jsch = new JSch();
        Session s = null;
        try {
            s = jsch.getSession(user, host, port > 0 ? port : 22);
            if (pass != null && !pass.isEmpty()) s.setPassword(pass);
            s.setConfig("StrictHostKeyChecking", "no");
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            s.connect(15000);
            ChannelExec ch = (ChannelExec) s.openChannel("exec");
            ch.setCommand(cmd);
            ByteArrayOutputStream out = new ByteArrayOutputStream(), errb = new ByteArrayOutputStream();
            ch.setOutputStream(out);
            ch.setErrStream(errb);
            ch.connect(15000);
            long deadline = System.currentTimeMillis() + 20000;
            while (!ch.isClosed() && System.currentTimeMillis() < deadline) Thread.sleep(120);
            Integer ex = ch.getExitStatus();
            ch.disconnect();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"exit\":").append(ex == null ? -1 : ex);
            String o = cleanCtl(new String(out.toByteArray(), "UTF-8"));
            String e = cleanCtl(new String(errb.toByteArray(), "UTF-8"));
            sb.append(",\"out\":\"").append(jesc(cut(o, OUT_CAP))).append("\"");
            if (!e.isEmpty()) sb.append(",\"stderr\":\"").append(jesc(cut(e, 600))).append("\"");
            sb.append('}');
            return sb.toString();
        } finally { if (s != null) s.disconnect(); }
    }

    // ---------- утилиты ----------

    static String cleanText(String s) {
        String t = s.replaceAll("(?s)<[^>]+>", "");
        t = unesc(t);
        return t.replaceAll("\\s+", " ").trim();
    }
    static String cleanCtl(String s) { return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "").trim(); }
    static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
    static String unesc(String s) {
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&#39;", "'").replace("&quot;", "\"").replace("&apos;", "'");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(m.group(1))))));
        return m.appendTail(sb).toString();
    }
    public static String jesc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) { case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break; case '\r': b.append(""); break; case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
        }
        return b.toString();
    }

    // упрощённый парсер плоских {"k":"v","b":true,"n":5} — без org.json, чтобы класс тестировался на ПК
    public static Map<String, Object> parseArgs(String json) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (json == null) return m;
        Matcher mm = Pattern.compile("\"(\\w+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(true|false|null|-?\\d+(?:\\.\\d+)?))").matcher(json);
        while (mm.find()) {
            String v = mm.group(2);
            if (v != null) m.put(mm.group(1), v.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\"));
            else if ("true".equals(mm.group(3))) m.put(mm.group(1), Boolean.TRUE);
            else if (!"null".equals(mm.group(3))) m.put(mm.group(1), mm.group(3));
        }
        return m;
    }

    // ПК-тест: java -cp .;jsch.jar com.fastchip.lfm3.Tools "запрос" [url]
    public static void main(String[] a) throws Exception {
        if (a.length >= 1) System.out.println(cut(search(a[0]), 900));
        if (a.length >= 2) System.out.println(cut(fetch(a[1]).toString(), 600));
    }
}
