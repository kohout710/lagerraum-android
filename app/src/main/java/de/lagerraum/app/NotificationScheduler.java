package de.lagerraum.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

public final class NotificationScheduler {
  public static final String PREFS = "lagerraum_notifications";
  public static final String KEY_ENABLED = "enabled";
  public static final String KEY_TIME = "time";
  public static final String KEY_DATA = "data";

  private static final int REQUEST_CODE = 4401;

  private NotificationScheduler() {}

  public static void scheduleNext(Context context) {
    boolean enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, false);

    AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarm == null) return;

    Intent intent = new Intent(context, NotificationReceiver.class);
    PendingIntent pending = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    alarm.cancel(pending);
    if (!enabled) return;

    String time = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_TIME, "09:00");

    int hour = 9;
    int minute = 0;
    try {
      String[] parts = time.split(":");
      hour = Integer.parseInt(parts[0]);
      minute = Integer.parseInt(parts[1]);
    } catch (Throwable ignored) {}

    Calendar now = Calendar.getInstance();
    Calendar next = Calendar.getInstance();
    next.set(Calendar.HOUR_OF_DAY, hour);
    next.set(Calendar.MINUTE, minute);
    next.set(Calendar.SECOND, 0);
    next.set(Calendar.MILLISECOND, 0);
    if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1);

    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pending);
  }
}
