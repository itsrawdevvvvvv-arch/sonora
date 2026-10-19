package com.sonora.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Sonora — a warm, offline local music player for Android.
 *
 * The whole interface is a single self-contained HTML/CSS/JS document served
 * from the app assets. This activity hosts it and bridges the two things a
 * WebView cannot do by itself: reading the device's audio library through
 * MediaStore, and controlling the playback service that keeps music running in
 * the background.
 *
 * Nothing is uploaded and there is no network permission — your library stays
 * on your device.
 */
public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 2001;
    private static final String BG = "#121212";

    private WebView web;
    private boolean pendingScan = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor(BG));
        w.setNavigationBarColor(Color.parseColor(BG));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(BG));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u == null ? null : u.getScheme();
                if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            request.grant(request.getResources());
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int top = 0, bottom = 0;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                        top = bars.top;
                        bottom = bars.bottom;
                    } else {
                        top = insets.getSystemWindowInsetTop();
                        bottom = insets.getSystemWindowInsetBottom();
                    }
                    setCssInsets(top, bottom);
                    return insets;
                }
            });
        }

        web.addJavascriptInterface(new Bridge(), "SonoraBridge");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        // Bring up the playback engine and route its state into the page.
        PlaybackService.setListener(new PlaybackService.Listener() {
            @Override
            public void onState(String json) {
                js("window.__player&&window.__player(" + json + ")");
            }
        });
        try {
            startService(new Intent(this, PlaybackService.class));
        } catch (Exception ignored) {
        }
    }

    private void setCssInsets(int topPx, int bottomPx) {
        final float d = getResources().getDisplayMetrics().density;
        final String jsCode = String.format(Locale.US,
                "document.documentElement.style.setProperty('--sat','%.1fpx');"
                        + "document.documentElement.style.setProperty('--sab','%.1fpx');",
                topPx / d, bottomPx / d);
        if (web != null) {
            web.post(new Runnable() {
                @Override
                public void run() {
                    web.evaluateJavascript(jsCode, null);
                }
            });
        }
    }

    private void js(final String code) {
        if (web != null) {
            web.post(new Runnable() {
                @Override
                public void run() {
                    web.evaluateJavascript(code, null);
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        PlaybackService.setListener(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ library

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String audioPermissionName() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void scanSongs() {
        final JSONArray out = new JSONArray();
        try {
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.ALBUM_ID
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + "!=0";
            Cursor c = getContentResolver().query(uri, proj, sel, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC");
            if (c != null) {
                int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int iAlbId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                while (c.moveToNext()) {
                    long id = c.getLong(iId);
                    long albId = c.getLong(iAlbId);
                    Uri songUri = ContentUris.withAppendedId(uri, id);
                    Uri artUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"), albId);
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("title", nz(c.getString(iTitle)));
                    o.put("artist", nz(c.getString(iArtist)));
                    o.put("album", nz(c.getString(iAlbum)));
                    o.put("duration", c.getLong(iDur));
                    o.put("uri", songUri.toString());
                    o.put("art", artUri.toString());
                    out.put(o);
                }
                c.close();
            }
        } catch (Exception e) {
            // ignore — return whatever we gathered
        }
        final String json = out.toString();
        js("window.__songs&&window.__songs(" + JSONObject.quote(json) + ")");
    }

    private static String nz(String s) {
        if (s == null || s.trim().isEmpty() || "<unknown>".equalsIgnoreCase(s)) {
            return "Unknown";
        }
        return s;
    }

    private void doScanAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                scanSongs();
            }
        }).start();
    }

    // ------------------------------------------------------------------ bridge

    public class Bridge {

        @JavascriptInterface
        public boolean hasAudio() {
            return hasAudioPermission();
        }

        @JavascriptInterface
        public void requestAudio() {
            if (!hasAudioPermission()) {
                pendingScan = true;
                requestPermissions(new String[]{audioPermissionName()}, REQ_AUDIO);
            }
        }

        @JavascriptInterface
        public void scan() {
            if (!hasAudioPermission()) {
                pendingScan = true;
                requestPermissions(new String[]{audioPermissionName()}, REQ_AUDIO);
                return;
            }
            doScanAsync();
        }

        // ---- playback (delegated to the foreground service) ----

        @JavascriptInterface
        public void setQueue(String json, int at) {
            PlaybackService s = PlaybackService.get();
            if (s != null) s.setQueue(json, at);
        }

        @JavascriptInterface
        public void toggle() {
            PlaybackService s = PlaybackService.get();
            if (s != null) s.toggle();
        }

        @JavascriptInterface
        public void next() {
            PlaybackService s = PlaybackService.get();
            if (s != null) s.next();
        }

        @JavascriptInterface
        public void prev() {
            PlaybackService s = PlaybackService.get();
            if (s != null) s.prev();
        }

        @JavascriptInterface
        public void seek(int ms) {
            PlaybackService s = PlaybackService.get();
            if (s != null) s.seek(ms);
        }

        @JavascriptInterface
        public void setRepeat(int mode) {
            PlaybackService s = PlaybackService.get();
            if (s != null) s.setRepeat(mode);
        }

        @JavascriptInterface
        public void setShuffle(boolean on) {
            PlaybackService s = PlaybackService.get();
            if (s != null) s.setShuffle(on);
        }

        @JavascriptInterface
        public void stop() {
            PlaybackService s = PlaybackService.get();
            if (s != null) s.stopAll();
        }

        @JavascriptInterface
        public void requestState() {
            PlaybackService s = PlaybackService.get();
            if (s != null) s.requestState();
        }

        // ---- chrome ----

        @JavascriptInterface
        public void setBars(final String color, final boolean light) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int c = Color.parseColor(color);
                        getWindow().setStatusBarColor(c);
                        getWindow().setNavigationBarColor(c);
                        if (web != null) {
                            web.setBackgroundColor(c);
                        }
                        View dv = getWindow().getDecorView();
                        int flags = dv.getSystemUiVisibility();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            flags = light ? (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                                    : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            flags = light ? (flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                                    : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                        }
                        dv.setSystemUiVisibility(flags);
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                android.os.Vibrator v = (android.os.Vibrator)
                        getSystemService(android.content.Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(
                                ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(ms);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void toastMsg(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            js("window.__perm&&window.__perm(" + granted + ")");
            if (granted && pendingScan) {
                pendingScan = false;
                doScanAsync();
            }
        }
    }
}
