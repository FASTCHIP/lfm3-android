package com.fastchip.lfm3;

// Инструменты агента: веб-поиск, чтение страниц, SSH. Без Android-типов —
// компилируется и тестируется обычным javac/java на ПК (javac -cp jsch.jar).
// Поиск: DuckDuckGo Lite (основной), Bing (фолбэк). Разбор регулярками по живой разметке.

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import com.jcraft.jsch.*;

public class Tools {

    public static String UA = "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0";
    public static int FETCH_MAX_BYTES = 600 * 1024;
    public static int TEXT_CAP = 4000;      // сколько текста страницы отдаём модели
    public static int OUT_CAP = 3000;       // вывод команд

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

    // ---------- поиск ----------

    public static String search(String q) throws IOException {
        String enc = URLEncoder.encode(q, "UTF-8");
        List<String[]> r = null;
        Exception err = null;
        try { r = ddgLite(enc); } catch (Exception e) { err = e; }
        if (r == null || r.isEmpty()) { try { r = bing(enc); } catch (Exception e) { if (err == null) err = e; } }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":").append(r != null && !r.isEmpty()).append(",\"results\":[");
        if (r != null) for (int i = 0; i < Math.min(6, r.size()); i++) {
            String[] it = r.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"t\":\"").append(jesc(it[0])).append("\",\"u\":\"").append(jesc(it[1]))
              .append("\",\"s\":\"").append(jesc(it[2] == null ? "" : it[2])).append("\"}");
        }
        sb.append('}');
        if ((r == null || r.isEmpty()) && err != null) sb.append(" /* ").append(err).append(" */");
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

    // ---------- чтение страницы ----------

    public static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        try {
            int code = c.getResponseCode();
            String ct = c.getContentType();
            if (ct != null && !ct.contains("html") && !ct.contains("text") && !ct.contains("json") && !ct.contains("xml"))
                throw new IOException("не текстовый тип: " + ct);
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0; boolean trunc = false;
            while ((n = is.read(buf)) > 0) { if (total >= FETCH_MAX_BYTES) { trunc = true; break; } bo.write(buf, 0, n); total += n; }
            String html = bo.toString("UTF-8");
            String title = "";
            Matcher mt = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
            if (mt.find()) title = cleanText(mt.group(1));
            String text = pageToText(html);
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

    // ПК-тест: java -cp .:jsch.jar com.fastchip.lfm3.Tools "запрос" [url] [host port user pass cmd]
    public static void main(String[] a) throws Exception {
        if (a.length >= 1) System.out.println(search(a[0]));
        if (a.length >= 2) System.out.println(fetch(a[1]).substring(0, Math.min(600, fetch(a[1]).length())));
        if (a.length >= 6) System.out.println(sshExec(a[2], Integer.parseInt(a[3]), a[4], a[5], a[6]));
    }
}
