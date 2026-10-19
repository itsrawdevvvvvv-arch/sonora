package com.sonora.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * A small now-playing widget. It shows the current track and a play/pause
 * button, and updates whenever the playback service reports a change.
 */
public class SonoraWidgetProvider extends AppWidgetProvider {

    private static String sTitle = null;
    private static String sArtist = null;
    private static boolean sPlaying = false;

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, build(context));
            } catch (Exception ignored) {
            }
        }
    }

    /** Called by the playback service whenever the current track changes. */
    public static void refresh(Context context, String title, String artist, boolean playing) {
        sTitle = title;
        sArtist = artist;
        sPlaying = playing;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, SonoraWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            for (int id : ids) {
                mgr.updateAppWidget(id, build(context));
            }
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent flags(Intent intent, Context context, int code, boolean service) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        if (service) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return PendingIntent.getForegroundService(context, code, intent, f);
            }
            return PendingIntent.getService(context, code, intent, f);
        }
        return PendingIntent.getActivity(context, code, intent, f);
    }

    private static RemoteViews build(Context context) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_sonora);

        boolean hasTrack = sTitle != null && !sTitle.isEmpty();
        v.setTextViewText(R.id.w_title, hasTrack ? sTitle : "Sonora");
        v.setTextViewText(R.id.w_artist, hasTrack ? (sArtist == null ? "" : sArtist) : "Nothing playing");
        v.setImageViewResource(R.id.w_play, sPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

        // Tapping the card opens the app.
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, flags(open, context, 0, false));

        // Tapping play toggles playback — but only once a track is loaded,
        // otherwise just open the app (a foreground service with no track to
        // play would have nothing to show and would be torn down immediately).
        if (hasTrack) {
            Intent toggle = new Intent(context, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_TOGGLE);
            v.setOnClickPendingIntent(R.id.w_play, flags(toggle, context, 9, true));
        } else {
            v.setOnClickPendingIntent(R.id.w_play, flags(open, context, 9, false));
        }
        return v;
    }
}
