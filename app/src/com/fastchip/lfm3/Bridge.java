package com.fastchip.lfm3;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Мост между WebView-интерфейсом и нативным слоем. */
public class Bridge {

    static final int REQ_DOC = 11;
    static final int REQ_IMG = 12;
    static final int REQ_GGUF = 13;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    Bridge(Activity a) { this.act = a; }

    // ---------- настройки ----------

    static JSONObject readCfg(Context c) {
        String s = c.getSharedPreferences("cfg", 0).getString("json", null);
        JSONObject o;
        try {
            o = s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            o = new JSONObject();
        }
        defaultIf(o, "ctx", 32768);
        defaultIf(o, "threads", 4);
        defaultIf(o, "kv", "q8_0");
        defaultIf(o, "fa", true);
        defaultIf(o, "np", 1);
        defaultIf(o, "lan", false);
        defaultIf(o, "port", 8080);
        defaultIf(o, "key", "");
        defaultIf(o, "mmp", true);
        defaultIf(o, "nm", "QAD-Q4_0");
        return o;
    }

    private static void defaultIf(JSONObject o, String k, Object v) {
        if (!o.has(k) || o.isNull(k)) {
            try { o.put(k, v); } catch (Exception ignored) { }
        }
    }

    @JavascriptInterface
    public String cfg() { return readCfg(act).toString(); }

    @JavascriptInterface
    public synchronized boolean setCfg(String json) {
        try {
            JSONObject cur = readCfg(act);
            JSONObject n = new JSONObject(json);
            for (String k : new String[]{"ctx", "threads", "kv", "fa", "np", "lan", "port", "key", "mmp",
                    "model", "mmproj", "temp", "think", "preserve", "showReas", "maxTok", "thinkBudget"}) {
                if (n.has(k)) cur.put(k, n.get(k));
            }
            act.getSharedPreferences("cfg", 0).edit().putString("json", cur.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- модели ----------

    static File modelsDir(Context c) {
        File d = new File(c.getFilesDir(), "models");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    @JavascriptInterface
    public String models() {
        try {
            JSONObject out = new JSONObject();
            File dir = modelsDir(act);
            out.put("dir", dir.getAbsolutePath());
            JSONArray arr = new JSONArray();
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    String n = f.getName();
                    if (!n.endsWith(".gguf")) continue;
                    JSONObject m = new JSONObject();
                    m.put("name", n);
                    m.put("size", f.length());
                    m.put("mb", Math.round(f.length() / 1e6));
                    m.put("path", f.getAbsolutePath());
                    m.put("vl", n.toLowerCase().contains("vl"));
                    m.put("proj", n.toLowerCase().startsWith("mmproj"));
                    arr.put(m);
                }
            }
            out.put("items", arr);
            return out.toString();
        } catch (Exception e) {
            return "{\"items\":[]}";
        }
    }

    @JavascriptInterface
    public void importModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        act.startActivityForResult(i, REQ_GGUF);
    }

    @JavascriptInterface
    public String deleteModel(String path) {
        try {
            File f = new File(path);
            if (f.getParentFile() != null && f.getParentFile().getAbsolutePath().equals(modelsDir(act).getAbsolutePath())) {
                return f.delete() ? "deleted" : "fail";
            }
            return "outside";
        } catch (Exception e) {
            return "err";
        }
    }

    // ---------- движок ----------

    @JavascriptInterface
    public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", EngineService.isRunning());
            o.put("listening", EngineService.isListening());
            o.put("log", EngineService.logText());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void start() {
        ui.post(() -> EngineService.start(act, readCfg(act).toString()));
    }

    @JavascriptInterface
    public void stop() {
        ui.post(() -> EngineService.stop(act));
    }

    @JavascriptInterface
    public void backConsumed() {
        ui.post(() -> { if (act instanceof MainActivity) ((MainActivity) act).keepBack(); });
    }

    // ---------- сеть ----------

    @JavascriptInterface
    public String netInfo() {
        try {
            JSONObject cfg = readCfg(act);
            JSONObject o = new JSONObject();
            String ip = null, vpn = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String nm = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String s = a.getHostAddress();
                    boolean priv = s.startsWith("192.168.") || s.startsWith("10.")
                            || s.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
                    if (nm.startsWith("wlan") && priv) ip = s;
                    else if (ip == null && priv) ip = s;
                    else if (vpn == null) vpn = s;
                }
            }
            if (ip == null) ip = vpn;
            o.put("ip", ip == null ? "" : ip);
            o.put("port", cfg.optInt("port", 8080));
            o.put("lan", cfg.optBoolean("lan", false));
            o.put("key", cfg.optString("key", ""));
            o.put("url", ip == null ? "" : "http://" + ip + ":" + cfg.optInt("port", 8080));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------- мониторинг ----------

    private static String rd(String p) {
        try {
            byte[] b = new byte[64];
            try (FileInputStream in = new FileInputStream(p)) {
                int n = in.read(b);
                if (n <= 0) return "";
                return new String(b, 0, n).trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static double tC(String path) {
        String s = rd(path + "/temp");
        if (s.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(s);
            if (Math.abs(v) > 1000) v /= 1000.0;
            if (v < -40 || v > 150) return 0;
            return Math.round(v * 10) / 10.0;
        } catch (Exception e) {
            return 0;
        }
    }

    @JavascriptInterface
    public String sys() {
        try {
            JSONObject o = new JSONObject();
            File base = new File("/sys/class/thermal");
            File[] zones = base.listFiles();
            double cpu = 0, soc = 0, ddr = 0, batt = 0;
            if (zones != null) {
                for (File z : zones) {
                    String t = rd(z.getAbsolutePath() + "/type");
                    double v = tC(z.getAbsolutePath());
                    if (v <= 0) continue;
                    if (t.startsWith("cpu-1-")) cpu = Math.max(cpu, v);
                    else if (t.startsWith("cpuss-")) soc = Math.max(soc, v);
                    else if (t.equals("ddr")) ddr = v;
                    else if (t.equals("battery")) batt = v;
                }
            }
            JSONObject temps = new JSONObject();
            temps.put("cpu", cpu);
            temps.put("soc", soc);
            temps.put("ddr", ddr);
            temps.put("batt", batt);
            o.put("temps", temps);

            JSONArray fr = new JSONArray();
            int[] cores = {0, 4, 7};
            String[] nm = {"LITTLE", "BIG", "PRIME"};
            for (int i = 0; i < cores.length; i++) {
                String p = "/sys/devices/system/cpu/cpu" + cores[i] + "/cpufreq/";
                long cur = num(rd(p + "scaling_cur_freq"));
                long maxNow = num(rd(p + "scaling_max_freq"));
                long maxHw = num(rd(p + "cpuinfo_max_freq"));
                JSONObject c = new JSONObject();
                c.put("name", nm[i]);
                c.put("cur", Math.round(cur / 1000.0));
                c.put("maxNow", Math.round(maxNow / 1000.0));
                c.put("maxHw", Math.round(maxHw / 1000.0));
                fr.put(c);
            }
            o.put("freq", fr);

            long mt = 0, ma = 0;
            for (String line : read("/proc/meminfo").split("\n")) {
                if (line.startsWith("MemTotal:")) mt = num(line.replaceAll("\\D", ""));
                if (line.startsWith("MemAvailable:")) ma = num(line.replaceAll("\\D", ""));
            }
            JSONObject ram = new JSONObject();
            ram.put("total", Math.round(mt / 1024.0));
            ram.put("free", Math.round(ma / 1024.0));
            o.put("ram", ram);
            o.put("load", read("/proc/loadavg").split(" ")[0]);
            o.put("listening", EngineService.isListening());
            o.put("running", EngineService.isRunning());
            o.put("pid", EngineService.pid());
            return o.toString();
        } catch (Exception e) {
            Log.e("LFM3", "sys: " + e);
            return "{}";
        }
    }

    private static long num(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String read(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] b = new byte[8192];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- вложения ----------

    @JavascriptInterface
    public void pickDoc() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        act.startActivityForResult(i, REQ_DOC);
    }

    @JavascriptInterface
    public void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        act.startActivityForResult(i, REQ_IMG);
    }

    void onResult(int req, int res, Intent data) {
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = displayName(uri);
        if (req == REQ_DOC) {
            new Thread(() -> {
                String text = null, err = null;
                try {
                    text = readText(uri, 300000);
                    if (text == null || text.isEmpty()) err = "не текст: " + name;
                } catch (Exception e) {
                    err = "ошибка чтения: " + e.getMessage();
                }
                callJs(json("doc", name, text, null, err));
            }).start();
        } else if (req == REQ_IMG) {
            new Thread(() -> {
                String url = null, err = null;
                try {
                    url = imageDataUrl(uri, 1280, 82);
                    if (url == null) err = "не картинка: " + name;
                } catch (Exception e) {
                    err = "ошибка чтения: " + e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = "слишком большая картинка";
                }
                callJs(json("img", name, null, url, err));
            }).start();
        } else if (req == REQ_GGUF) {
            toast("Копирую модель…");
            new Thread(() -> {
                try {
                    File dst = new File(modelsDir(act), name);
                    copy(uri, dst);
                    toast("Готово: " + name + " (" + (dst.length() / 1000000) + " МБ)");
                    callJs("{\"event\":\"models\"}");
                } catch (Exception e) {
                    toast("Не удалось скопировать: " + e.getMessage());
                }
            }).start();
        }
    }

    private static String json(String kind, String name, String text, String url, String err) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "attach");
            o.put("kind", kind);
            o.put("name", name == null ? "" : name);
            if (text != null) o.put("text", text);
            if (url != null) o.put("dataUrl", url);
            if (err != null) o.put("error", err);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void callJs(String payload) {
        ui.post(() -> {
            if (act instanceof MainActivity) ((MainActivity) act).evalJs(
                    "window.__native&&window.__native(" + JSONObject.quote(payload) + ")");
        });
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(act, s, Toast.LENGTH_SHORT).show());
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = act.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "file";
        return name.replace('/', '_');
    }

    private String readText(Uri uri, int limit) throws Exception {
        ContentResolver cr = act.getContentResolver();
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n, total = 0;
            while ((n = in.read(b)) > 0 && total < limit) {
                bo.write(b, 0, n);
                total += n;
            }
            byte[] data = bo.toByteArray();
            for (byte x : data) if (x == 0) return null; // бинарь
            String s = new String(data, "UTF-8");
            return s.replace("\u0000", "");
        }
    }

    private String imageDataUrl(Uri uri, int maxSide, int quality) throws Exception {
        ContentResolver cr = act.getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while ((o.outWidth / sample) > maxSide || (o.outHeight / sample) > maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = cr.openInputStream(uri)) {
            bm = BitmapFactory.decodeStream(in, null, o2);
        }
        if (bm == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, quality, out);
        bm.recycle();
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private void copy(Uri uri, File dst) throws Exception {
        try (InputStream in = act.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new Exception("нет доступа");
            byte[] b = new byte[262144];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }
}