package de.lagerraum.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

public class NotificationReceiver extends BroadcastReceiver {
  private static final String CHANNEL_ID = "lagerraum_warnungen";
  private static final int NOTIFICATION_ID = 4402;

  @Override public void onReceive(Context context, Intent intent) {
    showNotification(context, false);
    NotificationScheduler.scheduleNext(context);
  }

  public static void showNotification(Context context, boolean forceTest) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      return;
    }

    String json = context.getSharedPreferences(NotificationScheduler.PREFS, Context.MODE_PRIVATE)
        .getString(NotificationScheduler.KEY_DATA, "{}");

    int soon = 0;
    int expired = 0;
    int low = 0;

    try {
      JSONObject db = new JSONObject(json);
      JSONObject settings = db.optJSONObject("settings");
      int warnDays = settings == null ? 3 : settings.optInt("warnDays", 3);
      JSONArray items = db.optJSONArray("items");
      JSONArray batches = db.optJSONArray("batches");

      if (items == null) items = new JSONArray();
      if (batches == null) batches = new JSONArray();

      Map<String, Double> totals = new HashMap<>();
      Map<String, String> earliest = new HashMap<>();

      for (int i = 0; i < batches.length(); i++) {
        JSONObject b = batches.optJSONObject(i);
        if (b == null) continue;
        String itemId = b.optString("itemId", "");
        double qty = b.optDouble("qty", 0);
        totals.put(itemId, totals.getOrDefault(itemId, 0.0) + qty);
        if (qty <= 0) continue;

        String d = firstNonEmpty(
            b.optString("openUntil", ""),
            b.optString("useBy", ""),
            b.optString("bestBefore", ""));
        if (d.isEmpty()) continue;

        String old = earliest.get(itemId);
        if (old == null || d.compareTo(old) < 0) earliest.put(itemId, d);
      }

      LocalDate today = LocalDate.now();

      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item == null) continue;
        String id = item.optString("id", "");
        double min = item.optDouble("min", 0);
        double total = totals.getOrDefault(id, 0.0);

        if (total < min) low++;

        String d = earliest.get(id);
        if (d != null) {
          try {
            long days = ChronoUnit.DAYS.between(today, LocalDate.parse(d));
            if (days < 0) expired++;
            else if (days <= warnDays) soon++;
          } catch (Throwable ignored) {}
        }
      }
    } catch (Throwable ignored) {}

    if (!forceTest && soon == 0 && expired == 0 && low == 0) return;

    String title = forceTest ? "Lagerraum – Test" : "Lagerraum – Vorrat prüfen";
    String text;
    if (forceTest && soon == 0 && expired == 0 && low == 0) {
      text = "Benachrichtigungen funktionieren.";
    } else {
      StringBuilder s = new StringBuilder();
      if (expired > 0) s.append(expired).append(" abgelaufen");
      if (soon > 0) {
        if (s.length() > 0) s.append(" · ");
        s.append(soon).append(" bald fällig");
      }
      if (low > 0) {
        if (s.length() > 0) s.append(" · ");
        s.append(low).append(" unter Mindestbestand");
      }
      text = s.toString();
    }

    NotificationManager nm =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null) return;

    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel channel = new NotificationChannel(
          CHANNEL_ID,
          "Lagerraum Warnungen",
          NotificationManager.IMPORTANCE_DEFAULT);
      channel.setDescription("Warnungen zu MHD und Mindestbestand");
      nm.createNotificationChannel(channel);
    }

    Intent open = new Intent(context, MainActivity.class);
    open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pi = PendingIntent.getActivity(
        context,
        0,
        open,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    android.app.Notification.Builder builder =
        Build.VERSION.SDK_INT >= 26
            ? new android.app.Notification.Builder(context, CHANNEL_ID)
            : new android.app.Notification.Builder(context);

    builder.setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setContentIntent(pi);

    nm.notify(NOTIFICATION_ID, builder.build());
  }

  private static String firstNonEmpty(String... values) {
    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) return value;
    }
    return "";
  }
}
