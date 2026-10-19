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
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Sonora — a warm, tactile local music player for Android.
 *
 * The whole interface is a single self-contained HTML/CSS/JS document served
 * from the app assets. This activity hosts it and bridges the two things a
 * WebView cannot do by itself: reading the device's audio library through
 * MediaStore, and playing it through a native MediaPlayer queue.
 *
 * Nothing is uploaded and there is no network permission — your library stays
 * on your device.
 */
public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 2001;
    private static final String BG = "#121212";

    private WebView web;
    private MediaPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<JSONObject> queue = new ArrayList<>();
    private int index = -1;
    private boolean prepared = false;
    private boolean pendingPlay = false;
    private int repeatMode = 0;      // 0 = off, 1 = all, 2 = one
    private boolean shuffle = false;
    private final Random rng = new Random();
    private boolean pendingScan = false;
    private boolean ticking = false;

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
    }

    private void setCssInsets(int topPx, int bottomPx) {
        final float d = getResources().getDisplayMetrics().density;
        final String js = String.format(Locale.US,
                "document.documentElement.style.setProperty('--sat','%.1fpx');"
                        + "document.documentElement.style.setProperty('--sab','%.1fpx');",
                topPx / d, bottomPx / d);
        if (web != null) {
            web.post(new Runnable() {
                @Override
                public void run() {
                    web.evaluateJavascript(js, null);
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
        stopTicking();
        releasePlayer();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ audio

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

    private void releasePlayer() {
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        prepared = false;
    }

    private void playAt(int i) {
        if (i < 0 || i >= queue.size()) {
            return;
        }
        index = i;
        JSONObject song = queue.get(i);
        releasePlayer();
        prepared = false;
        pendingPlay = true;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(this, Uri.parse(song.optString("uri")));
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    if (pendingPlay) {
                        mp.start();
                        pendingPlay = false;
                        startTicking();
                    }
                    pushState();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    advance(true);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    prepared = false;
                    stopTicking();
                    pushState();
                    return true;
                }
            });
            player.prepareAsync();
            pushState();
        } catch (Exception e) {
            prepared = false;
            pushState();
        }
    }

    private void advance(boolean forward) {
        if (queue.isEmpty()) {
            return;
        }
        if (repeatMode == 2) {
            playAt(index);
            return;
        }
        int next;
        if (shuffle && queue.size() > 1) {
            do {
                next = rng.nextInt(queue.size());
            } while (next == index);
        } else {
            next = forward ? index + 1 : index - 1;
            if (next >= queue.size()) {
                if (repeatMode == 1) {
                    next = 0;
                } else {
                    stopTicking();
                    pushState();
                    return;
                }
            }
            if (next < 0) {
                next = queue.size() - 1;
            }
        }
        playAt(next);
    }

    private void startTicking() {
        if (ticking) {
            return;
        }
        ticking = true;
        handler.postDelayed(tick, 300);
    }

    private void stopTicking() {
        ticking = false;
        handler.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            pushState();
            if (ticking) {
                handler.postDelayed(this, 300);
            }
        }
    };

    private void pushState() {
        try {
            JSONObject o = new JSONObject();
            String state = "stopped";
            int pos = 0, dur = 0;
            if (player != null) {
                try {
                    if (player.isPlaying()) {
                        state = "playing";
                    } else if (prepared || pendingPlay) {
                        state = "paused";
                    }
                    pos = player.getCurrentPosition();
                    dur = player.getDuration();
                } catch (Exception ignored) {
                }
            }
            o.put("state", state);
            o.put("position", pos);
            o.put("duration", dur < 0 ? 0 : dur);
            o.put("index", index);
            o.put("total", queue.size());
            o.put("repeat", repeatMode);
            o.put("shuffle", shuffle);
            if (index >= 0 && index < queue.size()) {
                o.put("song", queue.get(index));
            }
            js("window.__player&&window.__player(" + o.toString() + ")");
        } catch (Exception ignored) {
        }
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
            new Thread(new Runnable() {
                @Override
                public void run() {
                    scanSongs();
                }
            }).start();
        }

        /** Replaces the queue with [json] (a JSON array of songs) and plays [at]. */
        @JavascriptInterface
        public void setQueue(String json, int at) {
            try {
                queue.clear();
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    queue.add(arr.getJSONObject(i));
                }
            } catch (Exception ignored) {
            }
            playAt(at);
        }

        @JavascriptInterface
        public void playAt(int i) {
            MainActivity.this.playAt(i);
        }

        @JavascriptInterface
        public void toggle() {
            if (player == null) {
                if (index >= 0) {
                    playAt(index);
                }
                return;
            }
            try {
                if (player.isPlaying()) {
                    player.pause();
                    stopTicking();
                } else if (prepared) {
                    player.start();
                    startTicking();
                } else {
                    pendingPlay = true;
                }
            } catch (Exception ignored) {
            }
            pushState();
        }

        @JavascriptInterface
        public void next() {
            advance(true);
        }

        @JavascriptInterface
        public void prev() {
            if (player != null) {
                try {
                    if (player.getCurrentPosition() > 4000) {
                        player.seekTo(0);
                        pushState();
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            advance(false);
        }

        @JavascriptInterface
        public void seek(int ms) {
            if (player != null && prepared) {
                try {
                    player.seekTo(ms);
                } catch (Exception ignored) {
                }
                pushState();
            }
        }

        @JavascriptInterface
        public void setRepeat(int mode) {
            repeatMode = mode;
            pushState();
        }

        @JavascriptInterface
        public void setShuffle(boolean on) {
            shuffle = on;
            pushState();
        }

        @JavascriptInterface
        public void stop() {
            stopTicking();
            releasePlayer();
            index = -1;
            pushState();
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
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        scanSongs();
                    }
                }).start();
            }
        }
    }
}
