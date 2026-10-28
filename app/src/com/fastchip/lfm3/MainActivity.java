package com.fastchip.lfm3;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;

public class MainActivity extends Activity {

    private WebView web;
    private Bridge bridge;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean uiReady = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSupportZoom(false);
        web.setWebViewClient(new PlainClient());
        web.setWebChromeClient(new ConsoleClient());
        web.setBackgroundColor(0xFF0E1013);

        bridge = new Bridge(this);
        web.addJavascriptInterface(bridge, "native");
        setContentView(web);

        web.loadUrl("file:///android_asset/www/index.html");
        startEngine();
    }

    private void startEngine() {
        JSONObject cfg = Bridge.readCfg(this);
        try {
            JSONObject out = new JSONObject(cfg.toString());
            if (!out.has("model") || out.optString("model").isEmpty()) {
                File dir = Bridge.modelsDir(this);
                File[] fs = dir.listFiles();
                File best = null;
                if (fs != null) for (File f : fs) {
                    String n = f.getName();
                    if (!n.endsWith(".gguf") || n.toLowerCase().startsWith("mmproj")) continue;
                    if (best == null) best = f;
                }
                if (best != null) out.put("model", best.getAbsolutePath());
            }
            EngineService.start(this, out.toString());
        } catch (Exception e) {
            EngineService.logLine("cfg: " + e);
        }
    }

    void evalJs(String js) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        bridge.onResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        // отдаём «назад» интерфейсу: он сам решает — закрыть настройки или выйти
        evalJs("window.__back&&window.__back()");
        ui.postDelayed(() -> {
            if (!consumedBack) super.onBackPressed();
            consumedBack = false;
        }, 120);
    }

    private boolean consumedBack = false;

    @SuppressWarnings("unused")
    void keepBack() { consumedBack = true; }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("native");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    static class PlainClient extends WebViewClient { }

    static class ConsoleClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            EngineService.logLine("js: " + m.message());
            return true;
        }
    }
}