package com.fastchip.lfm3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground-сервис с llama-server: без него Android замораживает процесс,
 * как только экран гаснет, и движок перестаёт отвечать.
 */
public class EngineService extends Service {

    public static final String TAG = "LFM3";
    public static final String EXTRA_CFG = "cfg";

    private static Process proc;
    private static volatile int port = 8080;
    private static final StringBuilder log = new StringBuilder();
    private PowerManager.WakeLock lock;

    public static boolean isRunning() { return proc != null && proc.isAlive(); }

    public static int pid() {
        try {
            return proc == null ? 0 : (int) Process.class.getMethod("pid").invoke(proc);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static boolean isListening() {
        try (Socket so = new Socket()) {
            so.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String logText() {
        synchronized (log) {
            String s = log.toString();
            return s.length() > 4000 ? s.substring(s.length() - 4000) : s;
        }
    }

    static void logLine(String s) {
        Log.i(TAG, s);
        synchronized (log) {
            log.append(s).append('\n');
            if (log.length() > 120000) log.delete(0, 60000);
        }
    }

    // ---------- публичное API для моста ----------

    public static void start(Context c, String cfgJson) {
        if (isRunning()) {
            try {
                JSONObject n = new JSONObject(cfgJson);
                JSONObject cur = Bridge.readCfg(c);
                boolean same = n.optString("model", cur.optString("model", ""))
                        .equals(cur.optString("model", ""))
                        && n.optInt("ctx", 32768) == cur.optInt("ctx", 32768)
                        && n.optInt("threads", 4) == cur.optInt("threads", 4)
                        && n.optString("kv", "q8_0").equals(cur.optString("kv", "q8_0"))
                        && n.optBoolean("fa", true) == cur.optBoolean("fa", true)
                        && n.optBoolean("lan", false) == cur.optBoolean("lan", false)
                        && n.optInt("port", 8080) == cur.optInt("port", 8080)
                        && n.optString("mmproj", "").equals(cur.optString("mmproj", ""));
                if (same) { logLine("уже запущен, конфиг не менялся"); return; }
            } catch (Exception ignored) { }
        }
        Intent i = new Intent(c, EngineService.class);
        i.setAction("restart");
        i.putExtra(EXTRA_CFG, cfgJson);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, EngineService.class);
        i.setAction("stop");
        c.startService(i);
    }

    // ---------- сервис ----------

    @Override
    public IBinder onBind(Intent i) { return null; }

    private Notification notification(String text) {
        return new Notification.Builder(this, "lfm3")
                .setContentTitle("LFM3")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationChannel ch = new NotificationChannel("lfm3", "Движок LFM3",
                NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);

        String cfgJson = intent != null ? intent.getStringExtra(EXTRA_CFG) : null;
        if (cfgJson == null) cfgJson = getSharedPreferences("cfg", 0).getString("json", "{}");

        if (intent != null && "stop".equals(intent.getAction())) {
            startForeground(1, notification("остановлен"));
            stopEngine();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(1, notification("запуск…"));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (lock == null) {
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LFM3::engine");
            lock.setReferenceCounted(false);
        }
        lock.acquire(6 * 60 * 60 * 1000L);

        if (isRunning()) {
            if ("restart".equals(intent != null ? intent.getAction() : null)) stopEngine();
            else { logLine("уже работает"); return START_STICKY; }
        }
        startEngine(cfgJson);
        return START_STICKY;
    }

    private void copyAssets() throws IOException {
        File www = new File(getFilesDir(), "www");
        www.mkdirs();
        String[] names = getAssets().list("www");
        if (names == null) return;
        for (String n : names) {
            InputStream in = getAssets().open("www/" + n);
            FileOutputStream out = new FileOutputStream(new File(www, n));
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.close();
            in.close();
        }
    }

    private void startEngine(final String cfgJson) {
        try {
            copyAssets();
        } catch (IOException e) {
            logLine("assets: " + e);
        }
        final File www = new File(getFilesDir(), "www");

        new Thread(() -> {
            try {
                JSONObject cfg = new JSONObject(cfgJson);
                String model = cfg.optString("model", "");
                String mmproj = cfg.optString("mmproj", "");
                int ctx = cfg.optInt("ctx", 32768);
                int threads = cfg.optInt("threads", 4);
                int np = cfg.optInt("np", 1);
                String kv = cfg.optString("kv", "q8_0");
                boolean fa = cfg.optBoolean("fa", true);
                boolean lan = cfg.optBoolean("lan", false);
                boolean mmp = cfg.optBoolean("mmp", true);
                String key = cfg.optString("key", "");
                port = cfg.optInt("port", 8080);

                if (model.isEmpty()) {
                    File d = Bridge.modelsDir(this);
                    File[] fs = d.listFiles();
                    File best = null;
                    if (fs != null) for (File f : fs) {
                        if (!f.getName().endsWith(".gguf") || f.getName().startsWith("mmproj")) continue;
                        if (best == null) best = f;
                    }
                    model = best == null ? "" : best.getAbsolutePath();
                    cfg.put("model", model);
                    getSharedPreferences("cfg", 0).edit().putString("json", cfg.toString()).apply();
                }
                if (model.isEmpty() || !new File(model).exists()) {
                    logLine("модель не найдена: " + model);
                    return;
                }

                File exe = new File(getApplicationInfo().nativeLibraryDir, "libllamaserver.so");
                List<String> cmd = new ArrayList<>();
                cmd.add(exe.getAbsolutePath());
                cmd.add("-m"); cmd.add(model);
                cmd.add("--host"); cmd.add(lan ? "0.0.0.0" : "127.0.0.1");
                cmd.add("--port"); cmd.add(String.valueOf(port));
                cmd.add("-c"); cmd.add(String.valueOf(ctx));
                cmd.add("-t"); cmd.add(String.valueOf(threads));
                cmd.add("-np"); cmd.add(String.valueOf(np));
                cmd.add("--jinja");
                cmd.add("-fa"); cmd.add(fa ? "on" : "off");
                cmd.add("-ctk"); cmd.add(fa ? kv : "f16");
                cmd.add("-ctv"); cmd.add(fa ? kv : "f16");
                if (!mmp) cmd.add("--no-mmap");
                if (!mmproj.isEmpty() && new File(mmproj).exists()
                        && new File(model).getName().toLowerCase().contains("vl")) {
                    cmd.add("--mmproj"); cmd.add(mmproj);
                }
                if (!key.isEmpty()) { cmd.add("--api-key"); cmd.add(key); }
                cmd.add("--path"); cmd.add(www.getAbsolutePath());

                logLine("start: " + cfg.optString("model_name", new File(model).getName())
                        + " ctx=" + ctx + " t=" + threads + " kv=" + kv + " fa=" + fa
                        + " lan=" + lan + " np=" + np + (mmproj.isEmpty() ? "" : " +mmproj"));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
                pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                pb.directory(getFilesDir());
                pb.redirectErrorStream(true);
                proc = pb.start();
                logLine("pid " + pid());
                final InputStream is = proc.getInputStream();
                new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            logLine("eng: " + line);
                            if (line.contains("listening on")) {
                                NotificationManager nm = getSystemService(NotificationManager.class);
                                nm.notify(1, notification(lan ? "доступен по сети · :" + port : "готов · " + (ctx / 1024) + "k ctx"));
                            }
                        }
                    } catch (IOException ignored) { }
                }).start();
            } catch (Throwable e) {
                logLine("start error: " + Log.getStackTraceString(e));
            }
        }, "engine-launcher").start();
    }

    private void stopEngine() {
        try {
            if (proc != null) {
                Process p = proc;
                proc = null;
                p.destroy();
                Thread t = new Thread(() -> { try { p.waitFor(); } catch (Exception ignored) { } });
                t.setDaemon(true);
                t.start();
                logLine("движок остановлен");
            }
        } catch (Exception e) {
            logLine("stop: " + e);
        }
        if (lock != null && lock.isHeld()) lock.release();
    }

    @Override
    public void onDestroy() {
        stopEngine();
        stopForeground(true);
        super.onDestroy();
    }
}