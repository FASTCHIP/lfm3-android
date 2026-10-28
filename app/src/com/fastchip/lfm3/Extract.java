package com.fastchip.lfm3;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Извлечение текста из документов. Ничего не обрезает: либо весь текст,
 * либо отказ с понятной причиной в Res.error.
 *
 * Логика живёт в extractFile(File, name) и не трогает Android — её можно прогнать
 * обычным javac/java на ПК. extract(Context, Uri, ...) только копирует поток SAF.
 */
class Extract {

    static final long MAX_FILE = 32L * 1024 * 1024;
    static final int MAX_CHARS = 4_000_000;

    static class Res {
        String name, kind, text, error, detail;
        long bytes;
        int chars;
    }

    static Res extract(Context ctx, Uri uri, String name, long size) {
        Res r = new Res();
        r.name = name;
        r.bytes = size;
        File tmp = null;
        try {
            tmp = File.createTempFile("pick", ".bin", ctx.getCacheDir());
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) return fail(r, "нет доступа к файлу");
                copy(in, out);
            }
            return extractFile(tmp, name);
        } catch (TooBig t) {
            return fail(r, t.getMessage());
        } catch (Exception e) {
            return fail(r, "ошибка чтения: " + describe(e));
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /** Чистая логика: файл на диске → текст или причина отказа. */
    static Res extractFile(File f, String name) {
        Res r = new Res();
        r.name = name;
        r.bytes = f.length();
        if (f.length() > MAX_FILE)
            return fail(r, "файл " + (f.length() / 1048576) + " МБ больше предела "
                    + (MAX_FILE / 1048576) + " МБ");
        String ext = ext(name);
        try {
            switch (ext) {
                case "docx": case "docm": return zipDocx(f, r);
                case "xlsx": case "xlsm": return zipXlsx(f, r);
                case "pptx": case "pptm": return zipPptx(f, r);
                case "odt": case "ods": case "odp": return zipOdt(f, r);
                case "epub": return zipEpub(f, r);
                case "pdf":
                    return fail(r, "PDF разбирается в интерфейсе через pdf.js — нативный путь его не читает");
                case "doc": case "xls": case "ppt":
                    return fail(r, "старый формат Office (." + ext + ") — пересохрани как ." + ext + "x");
                case "rtf":
                    return fail(r, "RTF не поддерживается — сохрани как .docx или .txt");
                default: return plain(f, r, ext);
            }
        } catch (TooBig t) {
            return fail(r, t.getMessage());
        } catch (Exception e) {
            return fail(r, "не удалось прочитать: " + describe(e));
        }
    }

    private static String ext(String name) {
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    private static Res fail(Res r, String why) {
        r.error = why;
        return r;
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static class TooBig extends Exception {
        TooBig(String m) { super(m); }
    }

    // ---------- текст ----------

    private static Res plain(File f, Res r, String ext) throws Exception {
        byte[] data = readFile(f);
        String text = decode(data);
        String enc = encName;
        if (text.indexOf('\u0000') >= 0)
            return fail(r, "двоичный файл (." + (ext.isEmpty() ? "без расширения" : ext)
                    + ") — это не текст, извлечь нечего");
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') bad++;
        }
        if (text.length() > 0 && bad * 100L > text.length())
            return fail(r, "похоже на двоичный файл (." + (ext.isEmpty() ? "без расширения" : ext)
                    + "): " + bad + " служебных байт из " + text.length());
        r.kind = "text";
        return done(r, text, "текст, " + enc + ", символов " + text.length());
    }

    // ---------- docx ----------

    private static Res zipDocx(File f, Res r) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            String xml = entry(z, "word/document.xml");
            if (xml == null) return fail(r, "файл повреждён: нет word/document.xml");
            String body = xml
                    .replaceAll("(?i)</w:p>", "\n")
                    .replaceAll("(?i)<w:br[^>]*/>", "\n")
                    .replaceAll("(?i)<w:tab[^>]*/>", "\t")
                    .replaceAll("(?i)</w:tc>", "\t")
                    .replaceAll("(?i)</w:tr>", "\n");
            String text = clean(stripTags(body));
            r.kind = "docx";
            return done(r, text, "docx, абзацев " + lines(text) + ", символов " + text.length());
        } finally { z.close(); }
    }

    // ---------- xlsx ----------

    private static Res zipXlsx(File f, Res r) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            List<String> shared = new ArrayList<>();
            String ss = entry(z, "xl/sharedStrings.xml");
            if (ss != null) {
                Matcher m = Pattern.compile("(?is)<si>(.*?)</si>").matcher(ss);
                while (m.find()) shared.add(texts(m.group(1)));
            }
            List<String> sheets = entries(z, "xl/worksheets/sheet");
            if (sheets.isEmpty()) return fail(r, "файл повреждён: нет листов");
            sheets.sort((a, b) -> num(a) - num(b));
            StringBuilder sb = new StringBuilder();
            int rows = 0;
            for (String path : sheets) {
                String xml = entry(z, path);
                if (xml == null) continue;
                sb.append("[лист ").append(num(path)).append("]\n");
                Matcher row = Pattern.compile("(?is)<row[^>]*>(.*?)</row>").matcher(xml);
                while (row.find()) {
                    rows++;
                    List<String> cells = new ArrayList<>();
                    Matcher c = Pattern.compile("(?is)<c([^>]*)>(.*?)</c>").matcher(row.group(1));
                    while (c.find()) {
                        String attrs = c.group(1), inner = c.group(2), val;
                        if (attrs.contains("t=\"s\"")) {
                            Matcher v = Pattern.compile("(?is)<v>(.*?)</v>").matcher(inner);
                            int idx = v.find() ? num(v.group(1)) : -1;
                            val = idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
                        } else if (attrs.contains("t=\"inlineStr\"")) {
                            val = texts(inner);
                        } else {
                            Matcher v = Pattern.compile("(?is)<v>(.*?)</v>").matcher(inner);
                            val = v.find() ? v.group(1) : "";
                        }
                        cells.add(val);
                    }
                    sb.append(String.join("\t", cells)).append('\n');
                }
            }
            String text = clean(sb.toString());
            r.kind = "xlsx";
            return done(r, text, "xlsx, листов " + sheets.size() + ", строк " + rows
                    + ", символов " + text.length());
        } finally { z.close(); }
    }

    // ---------- pptx ----------

    private static Res zipPptx(File f, Res r) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            List<String> slides = entries(z, "ppt/slides/slide");
            if (slides.isEmpty()) return fail(r, "файл повреждён: нет слайдов");
            slides.sort((a, b) -> num(a) - num(b));
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String path : slides) {
                String xml = entry(z, path);
                if (xml == null) continue;
                sb.append("[слайд ").append(++n).append("]\n");
                Matcher p = Pattern.compile("(?is)<a:p>(.*?)</a:p>").matcher(xml);
                while (p.find()) {
                    String line = texts(p.group(1)).trim();
                    if (!line.isEmpty()) sb.append(line).append('\n');
                }
            }
            String text = clean(sb.toString());
            r.kind = "pptx";
            return done(r, text, "pptx, слайдов " + n + ", символов " + text.length());
        } finally { z.close(); }
    }

    // ---------- odt ----------

    private static Res zipOdt(File f, Res r) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            String xml = entry(z, "content.xml");
            if (xml == null) return fail(r, "файл повреждён: нет content.xml");
            String body = xml
                    .replaceAll("(?i)</text:p>", "\n")
                    .replaceAll("(?i)</text:h>", "\n")
                    .replaceAll("(?i)<text:tab[^>]*/>", "\t")
                    .replaceAll("(?i)<text:s[^>]*/>", " ")
                    .replaceAll("(?i)</table:table-cell>", "\t")
                    .replaceAll("(?i)</table:table-row>", "\n");
            String text = clean(stripTags(body));
            r.kind = "odf";
            return done(r, text, "odt, символов " + text.length());
        } finally { z.close(); }
    }

    // ---------- epub ----------

    private static Res zipEpub(File f, Res r) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            List<String> docs = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String nm = en.nextElement().getName();
                String low = nm.toLowerCase();
                if (low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm")) docs.add(nm);
            }
            if (docs.isEmpty()) return fail(r, "файл повреждён: не найдено глав");
            docs.sort(String::compareToIgnoreCase);
            StringBuilder sb = new StringBuilder();
            for (String d : docs) {
                String xml = entry(z, d);
                if (xml == null) continue;
                String body = xml.replaceAll("(?i)</p>", "\n")
                        .replaceAll("(?i)<br[^>]*>", "\n")
                        .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
                sb.append(clean(stripTags(body))).append("\n\n");
            }
            String text = clean(sb.toString());
            r.kind = "epub";
            return done(r, text, "epub, файлов " + docs.size() + ", символов " + text.length());
        } finally { z.close(); }
    }

    // ---------- общее ----------

    private static Res done(Res r, String text, String detail) {
        r.text = text;
        r.chars = text.length();
        r.detail = detail;
        if (text.trim().isEmpty()) {
            r.text = null;
            r.error = "в документе нет текста (пусто или только картинки)";
        }
        return r;
    }

    private static String entry(ZipFile z, String name) throws Exception {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) {
            return new String(readStream(in), StandardCharsets.UTF_8);
        }
    }

    private static List<String> entries(ZipFile z, String prefix) {
        List<String> out = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            String nm = en.nextElement().getName();
            if (nm.startsWith(prefix) && nm.endsWith(".xml")) out.add(nm);
        }
        return out;
    }

    private static byte[] readFile(File f) throws Exception {
        if (f.length() > MAX_FILE) throw new TooBig("файл больше " + (MAX_FILE / 1048576) + " МБ");
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in);
        }
    }

    private static byte[] readStream(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_FILE) throw new TooBig("файл больше " + (MAX_FILE / 1048576) + " МБ");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private static void copy(InputStream in, FileOutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_FILE) throw new TooBig("файл больше " + (MAX_FILE / 1048576) + " МБ");
            out.write(buf, 0, n);
        }
    }

    private static String encName = "";

    static String decode(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) {
            encName = "UTF-8 (BOM)";
            return new String(d, 3, d.length - 3, StandardCharsets.UTF_8);
        }
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) {
            encName = "UTF-16LE";
            return new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE);
        }
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) {
            encName = "UTF-16BE";
            return new String(d, 2, d.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(d)).toString();
            encName = "UTF-8";
            return s;
        } catch (CharacterCodingException e) {
            try {
                String s = Charset.forName("windows-1251").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(d)).toString();
                encName = "windows-1251";
                return s;
            } catch (Exception e2) {
                encName = "latin-1";
                return new String(d, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private static String stripTags(String s) {
        return s.replaceAll("(?s)<[^>]+>", " ");
    }

    private static String texts(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("(?is)<(?:a|w)?:?t(?: [^>]*)?>(.*?)</(?:a|w)?:?t>").matcher(xml);
        while (m.find()) sb.append(m.group(1));
        return unescape(sb.toString());
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&")
                .replaceAll("&#\\d+;", " ");
    }

    private static String clean(String s) {
        return unescape(s).replace('\u00A0', ' ')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static int lines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }
}