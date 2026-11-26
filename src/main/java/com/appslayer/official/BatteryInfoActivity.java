package com.appslayer.official;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Battery Information as its own screen. Up to 6.3.6 this was a
// dialog over the Floating Monitor screen; the user asked for a
// real tab instead — same header and cards, same sources, same
// ~2 s in-place refresh, but a normal screen with the title and
// Back in the action bar.
//
// Spam-proofing is two layers: the manifest declares this activity
// singleTop, and a static open flag (set in onCreate, cleared in
// onDestroy) lets the Floating Monitor button bring the existing
// screen forward instead of starting another copy — rapid repeated
// taps can never stack it.
public class BatteryInfoActivity extends Activity {

    private static volatile boolean open = false;

    public static boolean isOpen() {
        return open;
    }

    private SharedPreferences prefs;
    private volatile boolean refreshing = false;
    private Thread refreshThread;

    private TextView battLevelText;
    private ProgressBar battLevelBar;
    private TextView battStatusText;
    private TextView battHealthText;
    private TextView battHealthBig;
    private TextView battHealthBasis;
    private ProgressBar battHealthBar;
    private TextView battDesignValue;
    private TextView battFullValue;
    private TextView battSysHealthValue;
    private ProgressBar battSysHealthBar;
    private TextView battLiveStatusValue;
    private TextView battCurrentValue;
    private TextView battPowerValue;
    private TextView battVoltageValue;
    private TextView battEstFullValue;
    private TextView battEstLeftValue;
    private TextView battRealCapValue;
    private TextView battLastCalcValue;
    private TextView battSessionState;
    private View battSessionBody;
    private TextView battSessionLevelsValue;
    private TextView battSessionDurationValue;
    private TextView battSessionAvgCurrentValue;
    private TextView battSessionAvgPowerValue;
    private TextView battSessionMahValue;
    private android.widget.LinearLayout battSessionsList;
    private TextView battSessionEmpty;

    // Last capacity outcome written to the log (the screen
    // re-gathers its snapshot every couple of seconds; the log only
    // hears about changes). Also read by the Floating Monitor
    // screen's run-checks snapshot, which uses the same chain.
    private static volatile String lastCapacityOutcome = null;
    public static volatile String lastCapacitySource = null;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        open = true;
        setContentView(R.layout.activity_battery_info);
        setTitle(getString(R.string.dialog_battery_title));
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        this.prefs = PreferenceManager.getDefaultSharedPreferences(this);
        battLevelText = (TextView) findViewById(R.id.battLevelText);
        battLevelBar = (ProgressBar) findViewById(R.id.battLevelBar);
        battStatusText = (TextView) findViewById(R.id.battStatusText);
        battHealthText = (TextView) findViewById(R.id.battHealthText);
        battHealthBig = (TextView) findViewById(R.id.battHealthBig);
        battHealthBasis = (TextView) findViewById(R.id.battHealthBasis);
        battHealthBar = (ProgressBar) findViewById(R.id.battHealthBar);
        battDesignValue = (TextView) findViewById(R.id.battDesignValue);
        battFullValue = (TextView) findViewById(R.id.battFullValue);
        battSysHealthValue = (TextView) findViewById(R.id.battSysHealthValue);
        battSysHealthBar = (ProgressBar) findViewById(R.id.battSysHealthBar);
        battLiveStatusValue = (TextView) findViewById(R.id.battLiveStatusValue);
        battCurrentValue = (TextView) findViewById(R.id.battCurrentValue);
        battPowerValue = (TextView) findViewById(R.id.battPowerValue);
        battVoltageValue = (TextView) findViewById(R.id.battVoltageValue);
        battEstFullValue = (TextView) findViewById(R.id.battEstFullValue);
        battEstLeftValue = (TextView) findViewById(R.id.battEstLeftValue);
        battRealCapValue = (TextView) findViewById(R.id.battRealCapValue);
        battLastCalcValue = (TextView) findViewById(R.id.battLastCalcValue);
        battSessionState = (TextView) findViewById(R.id.battSessionState);
        battSessionBody = findViewById(R.id.battSessionBody);
        battSessionLevelsValue = (TextView) findViewById(R.id.battSessionLevelsValue);
        battSessionDurationValue = (TextView) findViewById(R.id.battSessionDurationValue);
        battSessionAvgCurrentValue = (TextView) findViewById(R.id.battSessionAvgCurrentValue);
        battSessionAvgPowerValue = (TextView) findViewById(R.id.battSessionAvgPowerValue);
        battSessionMahValue = (TextView) findViewById(R.id.battSessionMahValue);
        battSessionsList = (android.widget.LinearLayout) findViewById(R.id.battSessionsList);
        battSessionEmpty = (TextView) findViewById(R.id.battSessionEmpty);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startRefresh();
    }

    @Override
    protected void onPause() {
        stopRefresh();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopRefresh();
        open = false;
        super.onDestroy();
    }

    // Live refresh, the same cadence the dialog had: one snapshot
    // about every 2 s, applied to the views in place. The first
    // gather averages more current samples so the estimates open
    // steady; the loop stops the moment the screen pauses.
    private void startRefresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        refreshThread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean first = true;
                int cycle = 0;
                while (refreshing) {
                    final BatteryInfo info = first
                            ? gather(BatteryInfoActivity.this, prefs, 5, 400)
                            : gather(BatteryInfoActivity.this, prefs, 3, 300);
                    first = false;
                    // The live session comes from the tracker's
                    // in-memory state (cheap, every cycle); the
                    // finished-session files are re-read about once
                    // a minute, they cannot change faster than a
                    // charge session ends.
                    final ChargeSessions.Record liveSession =
                            ChargeSessions.current(BatteryInfoActivity.this);
                    final java.util.List<ChargeSessions.Record> recentSessions =
                            cycle % 30 == 0
                                    ? ChargeSessions.list(BatteryInfoActivity.this) : null;
                    cycle++;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (refreshing && !isFinishing()) {
                                applyBatteryInfo(info);
                                applyChargeSessions(liveSession, recentSessions);
                            }
                        }
                    });
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        refreshThread.start();
    }

    private void stopRefresh() {
        refreshing = false;
        if (refreshThread != null) {
            refreshThread.interrupt();
            refreshThread = null;
        }
    }

    // Pushes one snapshot into the screen's views. UI thread only.
    private void applyBatteryInfo(BatteryInfo info) {
        if (battLevelText == null) {
            return;
        }
        battLevelText.setText(info.level + "%");
        battLevelBar.setProgress(Math.max(0, Math.min(100, info.level)));
        battStatusText.setText(info.statusText);
        battHealthText.setText(info.healthText);
        // The health card's big number: the estimated real health
        // when the estimate exists, the system health otherwise,
        // a dash when neither does — the basis line says which.
        if (info.healthPercent >= 0) {
            battHealthBig.setText(info.healthPercent + "%");
            battHealthBar.setVisibility(View.VISIBLE);
            battHealthBar.setProgress(Math.min(100, info.healthPercent));
        } else {
            battHealthBig.setText("—");
            battHealthBar.setVisibility(View.GONE);
        }
        battHealthBasis.setText(info.healthBasis);
        battDesignValue.setText(info.designText);
        battFullValue.setText(info.fullText);
        battSysHealthValue.setText(info.sysHealthText);
        if (info.sysHealthPercent >= 0) {
            battSysHealthBar.setVisibility(View.VISIBLE);
            battSysHealthBar.setProgress(Math.min(100, info.sysHealthPercent));
        } else {
            battSysHealthBar.setVisibility(View.GONE);
        }
        battLiveStatusValue.setText(info.statusText);
        battCurrentValue.setText(info.currentText);
        battPowerValue.setText(info.powerText);
        battVoltageValue.setText(info.voltageText);
        battEstFullValue.setText(info.estFull);
        battEstLeftValue.setText(info.estEmpty);
        battRealCapValue.setText(info.realCapText);
        battLastCalcValue.setText(info.lastCalcText);
    }

    // Pushes the charge-session state into its card. UI thread
    // only. recentSessions is null on cycles that did not re-read
    // the finished files — the rows then stay as they were.
    private void applyChargeSessions(ChargeSessions.Record live,
                                     java.util.List<ChargeSessions.Record> recentSessions) {
        if (battSessionState == null) {
            return;
        }
        if (recentSessions != null) {
            lastRecentSessions = recentSessions;
        }
        java.util.List<ChargeSessions.Record> recent = lastRecentSessions;
        ChargeSessions.Record main = live != null ? live
                : (recent != null && !recent.isEmpty() ? recent.get(0) : null);
        if (main == null) {
            battSessionState.setVisibility(View.GONE);
            battSessionBody.setVisibility(View.GONE);
            battSessionsList.setVisibility(View.GONE);
            battSessionEmpty.setVisibility(View.VISIBLE);
            return;
        }
        battSessionEmpty.setVisibility(View.GONE);
        battSessionState.setVisibility(View.VISIBLE);
        battSessionBody.setVisibility(View.VISIBLE);
        battSessionState.setText(main.inProgress
                ? getString(R.string.batt_session_live)
                : getString(R.string.batt_session_last));
        battSessionLevelsValue.setText(main.startLevel + "% → " + main.endLevel + "%");
        battSessionDurationValue.setText(formatDuration(this, main.durationMs() / 3600000.0d));
        battSessionAvgCurrentValue.setText(Double.isNaN(main.avgCurrentMa)
                ? "N/A" : Math.round(main.avgCurrentMa) + " mA");
        battSessionAvgPowerValue.setText(Double.isNaN(main.avgPowerW)
                ? "N/A" : String.format(Locale.US, "%.2f W", main.avgPowerW));
        battSessionMahValue.setText(main.mahAdded >= 0 ? main.mahAdded + " mAh" : "—");
        // Recent rows: the finished sessions behind the main one
        // (when the main block is the last finished session, it is
        // not repeated as a row), at most five.
        if (recentSessions != null && battSessionsList != null) {
            battSessionsList.removeAllViews();
            int skip = live != null ? 0 : 1;
            int shown = 0;
            for (int i = skip; i < recent.size() && shown < 5; i++) {
                ChargeSessions.Record record = recent.get(i);
                TextView row = new TextView(this);
                row.setTextSize(13);
                row.setTextColor(android.graphics.Color.rgb(154, 160, 166));
                String date = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                        .format(new Date(record.startMs));
                row.setText(getString(R.string.batt_session_row, date,
                        record.startLevel, record.endLevel,
                        formatDuration(this, record.durationMs() / 3600000.0d),
                        Double.isNaN(record.avgCurrentMa)
                                ? "—" : Math.round(record.avgCurrentMa) + " mA"));
                android.widget.LinearLayout.LayoutParams params =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                if (shown > 0) {
                    params.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
                }
                battSessionsList.addView(row, params);
                shown++;
            }
            battSessionsList.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private java.util.List<ChargeSessions.Record> lastRecentSessions;

    // One Battery Information snapshot: every value the screen
    // shows, already formatted. sysHealthPercent is -1 when the
    // system health is not numeric (its bar hides then).
    static class BatteryInfo {
        int level;
        String healthText;
        String designText;
        String fullText;
        String sysHealthText;
        int sysHealthPercent = -1;
        String statusText;
        String currentText;
        String powerText;
        String voltageText;
        String estFull;
        String estEmpty;
        String realCapText;
        String realHealthText;
        String lastCalcText;
        // The health card's big number and its basis line: the
        // estimated real health when the estimate exists, the
        // system health otherwise; -1 when neither does.
        int healthPercent = -1;
        String healthBasis;
    }

    // Gathers a fresh Battery Information snapshot from the same
    // sources, in the same order, as the old dialog version. Runs
    // on a background thread (capacity chain, current sampling and
    // a possible root-shell read can take a moment).
    static BatteryInfo gather(Context context, SharedPreferences prefs,
                              int currentSamples, long sampleGapMs) {
        Intent batteryIntent = context.registerReceiver(null,
                new IntentFilter("android.intent.action.BATTERY_CHANGED"));
        int rawLevel = batteryIntent != null ? batteryIntent.getIntExtra("level", 0) : 0;
        int scale = batteryIntent != null ? batteryIntent.getIntExtra("scale", 100) : 100;
        final int level = scale > 0 ? (rawLevel * 100) / scale : rawLevel;
        int healthCode = batteryIntent != null ? batteryIntent.getIntExtra("health", 0) : 0;
        final int statusCode = batteryIntent != null ? batteryIntent.getIntExtra("status", 1) : 1;
        String healthText;
        switch (healthCode) {
            case 2:
                healthText = "Good";
                break;
            case 3:
                healthText = "Overheat";
                break;
            case 4:
                healthText = "Dead";
                break;
            case 5:
                healthText = "Over Voltage";
                break;
            case 6:
                healthText = "Failure";
                break;
            default:
                healthText = "Unknown";
                break;
        }

        final int designMah = readCapacityMah("charge_full_design", "energy_full_design",
                "POWER_SUPPLY_CHARGE_FULL_DESIGN=");
        final String designSource = lastCapacitySource;
        final int fullMah = readCapacityMah("charge_full", "energy_full",
                "POWER_SUPPLY_CHARGE_FULL=");
        final String fullSource = lastCapacitySource;
        // The screen re-gathers this snapshot every couple of
        // seconds; the log only hears when the outcome changes.
        String capacityOutcome = "design=" + designMah + " via " + designSource
                + ", full=" + fullMah + " via " + fullSource;
        if (!capacityOutcome.equals(lastCapacityOutcome)) {
            lastCapacityOutcome = capacityOutcome;
            DiagLog.log(context, "BATT", "capacity chain: " + capacityOutcome);
        }
        String sysHealthStr = "N/A";
        int sysHealthPercent = -1;
        if (designMah > 0 && fullMah > 0) {
            sysHealthPercent = Math.round((fullMah * 100.0f) / designMah);
            sysHealthStr = sysHealthPercent + "%";
        }
        Integer chargeCounter = SystemStats.readChargeCounterMah();
        final int remainingMah = chargeCounter != null ? chargeCounter
                : (fullMah > 0 ? (fullMah * level) / 100 : -1);
        // Smooth the instantaneous current over a few samples so the
        // estimates do not jump around.
        int sum = 0;
        int count = 0;
        for (int i = 0; i < currentSamples; i++) {
            Integer sample = SystemStats.readBatteryCurrentMa();
            if (sample != null && sample > 0) {
                sum += sample;
                count++;
            }
            if (i < currentSamples - 1) {
                try {
                    Thread.sleep(sampleGapMs);
                } catch (InterruptedException ignored) {
                }
            }
        }
        final int currentMa = count > 0 ? sum / count : -1;

        String statusText;
        switch (statusCode) {
            case 2:
                statusText = context.getString(R.string.batt_charging);
                break;
            case 3:
                statusText = context.getString(R.string.batt_discharging);
                break;
            case 4:
                statusText = context.getString(R.string.batt_not_charging);
                break;
            case 5:
                statusText = context.getString(R.string.batt_full);
                break;
            default:
                statusText = context.getString(R.string.batt_status_unknown);
                break;
        }
        String currentText = "N/A";
        if (currentMa >= 0) {
            String sign = statusCode == 2 ? "+" : statusCode == 3 ? "-" : "";
            currentText = sign + currentMa + " mA";
        }
        // Instantaneous power from the same current and the battery
        // voltage, signed like the current. N/A when either input is
        // missing — never a fake 0. The voltage itself is shown in
        // the Live card whenever it can be read.
        Integer voltageMv = SystemStats.readBatteryVoltageMv();
        String voltageText = voltageMv != null && voltageMv > 0
                ? PureCalc.formatVolts(voltageMv) : "N/A";
        String powerText = "N/A";
        if (currentMa >= 0 && voltageMv != null && voltageMv > 0) {
            int signedMa = statusCode == 3 ? -currentMa : currentMa;
            powerText = (signedMa < 0 ? "-" : "+")
                    + String.format(Locale.US, "%.2f W",
                            Math.abs(PureCalc.powerWatts(signedMa, voltageMv)));
        }
        String estFull = "—";
        String estEmpty = "—";
        if (statusCode == 5) {
            estFull = context.getString(R.string.batt_full);
        } else if (statusCode == 2) {
            if (currentMa >= 20 && fullMah > 0 && remainingMah >= 0 && fullMah > remainingMah) {
                estFull = formatDuration(context, (fullMah - remainingMah) / (double) currentMa);
            } else {
                // No capacity data on this ROM: estimate from how
                // fast the level itself is climbing.
                Double slope = levelSlopePerMinute(context);
                Double minutes = slope != null ? PureCalc.minutesToFull(level, slope) : null;
                estFull = minutes != null ? formatDuration(context, minutes / 60.0d)
                        : context.getString(R.string.batt_calculating);
            }
        } else if (statusCode == 3) {
            if (currentMa >= 20 && remainingMah > 0) {
                estEmpty = formatDuration(context, remainingMah / (double) currentMa);
            } else {
                Double slope = levelSlopePerMinute(context);
                Double minutes = slope != null ? PureCalc.minutesToEmpty(level, slope) : null;
                if (minutes != null) {
                    estEmpty = formatDuration(context, minutes / 60.0d);
                } else {
                    // Final fallback: the drain rate learned from the
                    // full history log.
                    BatteryHistory.Summary summary = BatteryHistory.summarize(
                            BatteryHistory.load(context, 7L * 24 * 60 * 60 * 1000));
                    if (summary != null && summary.estimatedHoursLeft >= 0) {
                        estEmpty = context.getString(R.string.hours_left, summary.estimatedHoursLeft);
                    } else {
                        estEmpty = context.getString(R.string.batt_calculating);
                    }
                }
            }
        }

        int realCap = prefs.getInt("real_capacity_mah", 0);
        int realHealth = prefs.getInt("real_health_percent", 0);
        long lastTime = prefs.getLong("real_last_calc", 0);
        String lastCalc = lastTime > 0
                ? new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(new Date(lastTime))
                : context.getString(R.string.not_available_yet);
        BatteryInfo info = new BatteryInfo();
        info.level = level;
        info.healthText = healthText;
        info.designText = designMah > 0 ? designMah + " mAh" : "N/A";
        info.fullText = fullMah > 0 ? fullMah + " mAh" : "N/A";
        info.sysHealthText = sysHealthStr;
        info.sysHealthPercent = sysHealthPercent;
        info.statusText = statusText;
        info.currentText = currentText;
        info.powerText = powerText;
        info.voltageText = voltageText;
        info.estFull = estFull;
        info.estEmpty = estEmpty;
        info.realCapText = realCap > 0 ? realCap + " mAh" : context.getString(R.string.waiting_data);
        info.realHealthText = realCap > 0 ? realHealth + "%" : context.getString(R.string.waiting_data);
        info.lastCalcText = lastCalc;
        if (realCap > 0 && realHealth > 0) {
            info.healthPercent = realHealth;
            info.healthBasis = context.getString(R.string.batt_label_real_health);
        } else if (sysHealthPercent >= 0) {
            info.healthPercent = sysHealthPercent;
            info.healthBasis = context.getString(R.string.batt_label_sys_health);
        } else {
            info.healthBasis = context.getString(R.string.batt_label_real_health);
        }
        return info;
    }

    private static String formatDuration(Context context, double hours) {
        long h = (long) hours;
        long m = Math.round((hours - h) * 60);
        if (m == 60) {
            h++;
            m = 0;
        }
        return context.getString(R.string.duration_hm, h, m);
    }

    // Battery level slope (%/min) from the history log, used for the
    // time estimates when the mAh-based path has no data. Samples
    // from the last ~10 minutes; PureCalc rejects windows shorter
    // than ~2 minutes.
    private static Double levelSlopePerMinute(Context context) {
        java.util.List<BatteryHistory.Sample> samples =
                BatteryHistory.load(context, 10L * 60 * 1000);
        if (samples.size() < 2) {
            return null;
        }
        long[] times = new long[samples.size()];
        int[] levels = new int[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            times[i] = samples.get(i).time;
            levels[i] = samples.get(i).level;
        }
        return PureCalc.levelSlopePerMinute(times, levels);
    }

    // Every power_supply directory on the device, the battery folder
    // first. Some ROMs put the fuel gauge under a different node
    // ("bms", the charger chip, ...), and the 6.0.1 chain only ever
    // looked inside battery/ — on those devices every source missed
    // and the screen showed N/A.
    private static java.util.List<String> powerSupplyDirs() {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        dirs.add("/sys/class/power_supply/battery/");
        java.io.File[] subs = new java.io.File("/sys/class/power_supply/").listFiles();
        if (subs != null) {
            java.util.Arrays.sort(subs, new java.util.Comparator<java.io.File>() {
                @Override
                public int compare(java.io.File a, java.io.File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (java.io.File sub : subs) {
                if (sub.isDirectory() && !"battery".equals(sub.getName())) {
                    dirs.add(sub.getAbsolutePath() + "/");
                }
            }
        }
        return dirs;
    }

    // One capacity value (mAh) with every source we have, in order —
    // non-root sources first, the root shell last, per the standing
    // rule:
    // 1. the plain charge file (what 5.2.0 read),
    // 2. the same value inside the directory's uevent dump,
    // 3. the energy counters converted through the voltage,
    // 4. the root shell, for nodes a plain read cannot open.
    // Values pass through PureCalc.capacityToMah, which converts
    // µAh-scale readings and rejects implausible ones instead of
    // treating them as data. -1 = no source answered.
    public static int readCapacityMah(String chargeFile, String energyFile, String ueventKey) {
        java.util.List<String> dirs = powerSupplyDirs();
        for (String dir : dirs) {
            Integer mah = PureCalc.capacityFromRaw(readPlainFile(dir + chargeFile));
            if (mah != null) {
                lastCapacitySource = "plain file " + dir + chargeFile;
                return mah;
            }
        }
        for (String dir : dirs) {
            Integer mah = PureCalc.capacityFromRaw(readUeventValue(dir, ueventKey));
            if (mah != null) {
                lastCapacitySource = "uevent " + dir;
                return mah;
            }
        }
        for (String dir : dirs) {
            Integer microWh = parseIntOrNull(readPlainFile(dir + energyFile));
            Integer microV = parseIntOrNull(readPlainFile(dir + "voltage_now"));
            if (microWh != null && microWh > 0 && microV != null && microV > 0) {
                int mah = (int) (microWh * 1000L / microV);
                if (mah >= 500 && mah <= 30000) {
                    lastCapacitySource = "energy counters " + dir;
                    return mah;
                }
            }
        }
        for (String dir : dirs) {
            Integer mah = PureCalc.capacityFromRaw(SystemStats.readFileWithRoot(dir + chargeFile));
            if (mah != null) {
                lastCapacitySource = "root shell " + dir + chargeFile;
                return mah;
            }
        }
        lastCapacitySource = "none";
        return -1;
    }

    private static String readUeventValue(String dir, String key) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(dir + "uevent"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key)) {
                    String[] parts = line.split("=");
                    if (parts.length >= 2) {
                        return parts[1];
                    }
                    return null;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static String readPlainFile(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            return reader.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
