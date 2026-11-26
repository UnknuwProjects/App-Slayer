package com.appslayer.official;

import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

// Battery level history, stored as CSV lines "millis,level,charging"
// in filesDir/battery_history.csv. Samples come from the sticky
// ACTION_BATTERY_CHANGED broadcast (fires when the level or charging
// state changes), so there is no polling loop. Samples older than
// 7 days are pruned and the file is capped at a few thousand lines.
public class BatteryHistory {

    private static final String FILE_NAME = "battery_history.csv";
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_LINES = 3000;
    private static final int PRUNE_EVERY = 100;

    private static int lastLevel = -1;
    private static int lastCharging = -1;
    private static int recordsSincePrune = 0;

    public static class Sample {
        public final long time;
        public final int level;
        public final boolean charging;

        Sample(long time, int level, boolean charging) {
            this.time = time;
            this.level = level;
            this.charging = charging;
        }
    }

    public static class Summary {
        public long sinceFullChargeMs;
        public double drainPerHour;
        public int estimatedHoursLeft;
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static synchronized void record(Context context, Intent batteryIntent) {
        if (batteryIntent == null) {
            return;
        }
        int level = batteryIntent.getIntExtra("level", -1);
        int scale = batteryIntent.getIntExtra("scale", 100);
        if (level < 0 || scale <= 0) {
            return;
        }
        int percent = Math.round(level * 100f / scale);
        int status = batteryIntent.getIntExtra("status", -1);
        int charging = (status == 2 || status == 5) ? 1 : 0;
        if (percent == lastLevel && charging == lastCharging) {
            return; // nothing new
        }
        lastLevel = percent;
        lastCharging = charging;
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(file(context), true));
            writer.write(System.currentTimeMillis() + "," + percent + "," + charging);
            writer.newLine();
            writer.close();
        } catch (Exception ignored) {
            return;
        }
        recordsSincePrune++;
        if (recordsSincePrune >= PRUNE_EVERY) {
            recordsSincePrune = 0;
            prune(context);
        }
    }

    public static synchronized void prune(Context context) {
        List<Sample> samples = load(context, Long.MAX_VALUE);
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        List<Sample> kept = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.time >= cutoff) {
                kept.add(sample);
            }
        }
        if (kept.size() > MAX_LINES) {
            kept = kept.subList(kept.size() - MAX_LINES, kept.size());
        }
        if (kept.size() == samples.size()) {
            return;
        }
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(file(context), false));
            for (Sample sample : kept) {
                writer.write(sample.time + "," + sample.level + "," + (sample.charging ? 1 : 0));
                writer.newLine();
            }
            writer.close();
        } catch (Exception ignored) {
        }
    }

    public static synchronized List<Sample> load(Context context, long windowMs) {
        List<Sample> samples = new ArrayList<>();
        File f = file(context);
        if (!f.exists()) {
            return samples;
        }
        long cutoff = System.currentTimeMillis() - windowMs;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(f));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 3) {
                    try {
                        long time = Long.parseLong(parts[0].trim());
                        if (time >= cutoff) {
                            samples.add(new Sample(time,
                                    Integer.parseInt(parts[1].trim()),
                                    "1".equals(parts[2].trim())));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            reader.close();
        } catch (Exception ignored) {
        }
        return samples;
    }

    // Finds the last time the battery was full and then unplugged, and
    // works out the average drain since then. Returns null when there
    // is not enough data yet.
    public static Summary summarize(List<Sample> samples) {
        if (samples.size() < 2) {
            return null;
        }
        int fullIndex = -1;
        for (int i = samples.size() - 1; i >= 0; i--) {
            Sample s = samples.get(i);
            if (s.level >= 99 && !s.charging) {
                fullIndex = i;
                break;
            }
            if (s.level >= 100 && s.charging) {
                fullIndex = i; // still plugged at 100%, count from here
                break;
            }
        }
        if (fullIndex < 0) {
            return null;
        }
        Sample start = samples.get(fullIndex);
        Sample last = samples.get(samples.size() - 1);
        long elapsedMs = last.time - start.time;
        if (elapsedMs < 30 * 60 * 1000) {
            return null; // less than 30 minutes of data
        }
        int drained = start.level - last.level;
        if (drained <= 0) {
            return null; // charged again since, rate is meaningless
        }
        Summary summary = new Summary();
        summary.sinceFullChargeMs = elapsedMs;
        summary.drainPerHour = drained / (elapsedMs / 3600000.0d);
        summary.estimatedHoursLeft = summary.drainPerHour > 0
                ? (int) Math.round(last.level / summary.drainPerHour)
                : -1;
        return summary;
    }
}
