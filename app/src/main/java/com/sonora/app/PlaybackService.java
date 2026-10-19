package com.sonora.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Sonora's playback engine.
 *
 * The player lives here, not in the activity, so music keeps playing when the
 * app is backgrounded. The service runs in the foreground while a track is
 * loaded (showing a media notification), exposes a {@link MediaSession} for the
 * lock screen and Bluetooth controls, and reports state back to the WebView
 * through {@link Listener}.
 */
public class PlaybackService extends Service {

    public static final String ACTION_TOGGLE = "com.sonora.app.action.TOGGLE";
    public static final String ACTION_NEXT = "com.sonora.app.action.NEXT";
    public static final String ACTION_PREV = "com.sonora.app.action.PREV";
    public static final String ACTION_STOP = "com.sonora.app.action.STOP";

    private static final String CHANNEL = "sonora.playback";
    private static final int NOTIF_ID = 41;

    private static PlaybackService sInstance;

    public static PlaybackService get() {
        return sInstance;
    }

    public interface Listener {
        void onState(String json);
    }

    private static Listener sListener;

    public static void setListener(Listener l) {
        sListener = l;
    }

    private MediaPlayer player;
    private final List<JSONObject> queue = new ArrayList<>();
    private int index = -1;
    private boolean prepared = false;
    private boolean pendingPlay = false;
    private boolean ticking = false;
    private int repeatMode = 0;   // 0 off, 1 all, 2 one
    private boolean shuffle = false;
    private final Random rng = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSession session;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createChannel();
        session = new MediaSession(this, "Sonora");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (!isPlaying()) toggle(); }
            @Override public void onPause() { if (isPlaying()) toggle(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seek((int) pos); }
            @Override public void onStop() { stopAll(); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(a)) toggle();
        else if (ACTION_NEXT.equals(a)) next();
        else if (ACTION_PREV.equals(a)) prev();
        else if (ACTION_STOP.equals(a)) stopAll();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        stopTicking();
        releasePlayer();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel c = new NotificationChannel(CHANNEL, "Playback",
                        NotificationManager.IMPORTANCE_LOW);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
        }
    }

    // ------------------------------------------------------------------ public api

    public void setQueue(final String json, final int at) {
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    queue.clear();
                    JSONArray arr = new JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) queue.add(arr.getJSONObject(i));
                } catch (Exception ignored) {
                }
                playAt(at);
            }
        });
    }

    public void toggle() {
        handler.post(new Runnable() {
            @Override public void run() {
                if (player == null) {
                    if (index >= 0) playAt(index);
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
                updateNotification();
                pushState();
            }
        });
    }

    public void next() {
        handler.post(new Runnable() {
            @Override public void run() { advance(true); }
        });
    }

    public void prev() {
        handler.post(new Runnable() {
            @Override public void run() {
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
        });
    }

    public void seek(final int ms) {
        handler.post(new Runnable() {
            @Override public void run() {
                if (player != null && prepared) {
                    try { player.seekTo(ms); } catch (Exception ignored) {
                    }
                    pushState();
                }
            }
        });
    }

    public void setRepeat(final int mode) {
        handler.post(new Runnable() {
            @Override public void run() { repeatMode = mode; pushState(); }
        });
    }

    public void setShuffle(final boolean on) {
        handler.post(new Runnable() {
            @Override public void run() { shuffle = on; pushState(); }
        });
    }

    public void requestState() {
        handler.post(new Runnable() {
            @Override public void run() { pushState(); }
        });
    }

    public void stopAll() {
        handler.post(new Runnable() {
            @Override public void run() {
                stopTicking();
                releasePlayer();
                index = -1;
                queue.clear();
                if (session != null) session.setActive(false);
                stopForeground(true);
                pushState();
                stopSelf();
            }
        });
    }

    // ------------------------------------------------------------------ engine

    private boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
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
        if (i < 0 || i >= queue.size()) return;
        index = i;
        JSONObject song = queue.get(i);
        releasePlayer();
        pendingPlay = true;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(this, Uri.parse(song.optString("uri")));
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    if (pendingPlay) {
                        mp.start();
                        pendingPlay = false;
                        startTicking();
                    }
                    updateSession();
                    updateNotification();
                    pushState();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { advance(true); }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    prepared = false;
                    stopTicking();
                    pushState();
                    return true;
                }
            });
            player.prepareAsync();
            updateSession();
            updateNotification();
            pushState();
        } catch (Exception e) {
            prepared = false;
            pushState();
        }
    }

    private void advance(boolean forward) {
        if (queue.isEmpty()) return;
        if (repeatMode == 2) { playAt(index); return; }
        int next;
        if (shuffle && queue.size() > 1) {
            do { next = rng.nextInt(queue.size()); } while (next == index);
        } else {
            next = forward ? index + 1 : index - 1;
            if (next >= queue.size()) {
                if (repeatMode == 1) next = 0;
                else { stopTicking(); pushState(); return; }
            }
            if (next < 0) next = queue.size() - 1;
        }
        playAt(next);
    }

    private void startTicking() {
        if (ticking) return;
        ticking = true;
        handler.postDelayed(tick, 300);
    }

    private void stopTicking() {
        ticking = false;
        handler.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            pushState();
            if (ticking) handler.postDelayed(this, 300);
        }
    };

    private int position() {
        try {
            return player == null ? 0 : player.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    private int duration() {
        try {
            int d = player == null ? 0 : player.getDuration();
            return d < 0 ? 0 : d;
        } catch (Exception e) {
            return 0;
        }
    }

    private void pushState() {
        try {
            JSONObject o = new JSONObject();
            String state = "stopped";
            if (player != null) {
                if (isPlaying()) state = "playing";
                else if (prepared || pendingPlay) state = "paused";
            }
            o.put("state", state);
            o.put("position", position());
            o.put("duration", duration());
            o.put("index", index);
            o.put("total", queue.size());
            o.put("repeat", repeatMode);
            o.put("shuffle", shuffle);
            if (index >= 0 && index < queue.size()) o.put("song", queue.get(index));
            String json = o.toString();
            if (sListener != null) sListener.onState(json);
            if (index >= 0 && index < queue.size()) {
                JSONObject s = queue.get(index);
                SonoraWidgetProvider.refresh(this, s.optString("title"),
                        s.optString("artist"), isPlaying());
            } else {
                SonoraWidgetProvider.refresh(this, null, null, false);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ media session + notification

    private void updateSession() {
        if (session == null || index < 0 || index >= queue.size()) return;
        JSONObject s = queue.get(index);
        try {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.optString("title"))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.optString("artist"))
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, s.optString("album"))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                    .build());
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                            | PlaybackState.ACTION_STOP)
                    .setState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            position(), 1f)
                    .build());
        } catch (Exception ignored) {
        }
    }

    private PendingIntent actionPI(int code, String act) {
        Intent i = new Intent(this, PlaybackService.class).setAction(act);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, code, i, flags);
    }

    private void updateNotification() {
        if (index < 0 || index >= queue.size()) return;
        JSONObject s = queue.get(index);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int of = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) of |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPI = PendingIntent.getActivity(this, 0, open, of);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_note)
                .setContentTitle(s.optString("title"))
                .setContentText(s.optString("artist"))
                .setContentIntent(openPI)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_prev), "Previous",
                        actionPI(1, ACTION_PREV)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this,
                                isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play),
                        isPlaying() ? "Pause" : "Play", actionPI(2, ACTION_TOGGLE)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_next), "Next",
                        actionPI(3, ACTION_NEXT)).build());

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);
        b.setStyle(style);

        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception ignored) {
        }
    }
}
