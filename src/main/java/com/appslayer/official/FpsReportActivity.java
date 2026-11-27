package com.appslayer.official;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// One session's report: a header card (app, date, duration,
// samples), a stats card with the average FPS big on top and the
// rest of the PureCalc figures as a grid, then the FPS chart
// (temperature overlaid on its own axis when the session recorded
// any), the temperature chart and the frame-time chart. All the
// math stays in PureCalc.summarizeFps — this class only presents
// it. A session with no valid samples shows an honest empty card
// instead of a grid of dashes, and the temperature card hides
// entirely when no temperature was recorded.
public class FpsReportActivity extends Activity {

    private static final int FPS_COLOR = Color.rgb(76, 175, 80);
    private static final int TEMP_COLOR = Color.rgb(255, 167, 38);
    private static final int CPU_TEMP_COLOR = Color.rgb(255, 193, 7);
    private static final int GPU_TEMP_COLOR = Color.rgb(239, 83, 80);
    private static final int FRAME_COLOR = Color.rgb(100, 181, 246);

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_fps_report);
        setTitle(getString(R.string.fps_report_title));
        String path = getIntent() != null
                ? getIntent().getStringExtra(FpsSessionsActivity.EXTRA_SESSION_PATH) : null;
        if (path == null) {
            finish();
            return;
        }
        final File file = new File(path);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final FpsSession session = FpsSession.load(file);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (session != null && !isFinishing()) {
                            show(session);
                        }
                    }
                });
            }
        }).start();
    }

    private void show(FpsSession session) {
        PureCalc.FpsSummary summary = PureCalc.summarizeFps(session.fpsArray());
        double avgCpuTemp = PureCalc.average(session.cpuTempArray());
        double avgGpuTemp = PureCalc.average(session.gpuTempArray());
        double avgPower = PureCalc.average(session.powerArray());

        // Header: the app that was recorded, through the same
        // formatter the sessions list uses — a plain dash when no
        // foreground app ever answered, never a sentence about an
        // unknown app.
        TextView app = (TextView) findViewById(R.id.reportApp);
        if (app != null) {
            app.setText(FpsSessionsActivity.appLabel(this, session.packageName));
        }
        TextView meta = (TextView) findViewById(R.id.reportMeta);
        if (meta != null) {
            String date = new SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault())
                    .format(new Date(session.startTimeMs));
            meta.setText(date + " · " + FpsSessionsActivity.formatDuration(session.durationMs())
                    + " · " + getString(R.string.fps_report_samples, session.samples.size()));
        }

        boolean hasData = summary.count > 0;
        setVisible(R.id.reportEmpty, !hasData);
        setVisible(R.id.statsCard, hasData);
        setVisible(R.id.reportNote, hasData);
        setVisible(R.id.fpsChartCard, hasData);
        setVisible(R.id.frameChartCard, hasData);
        if (!hasData) {
            setVisible(R.id.tempChartCard, false);
            return;
        }

        setText(R.id.statAvgValue, fpsValue(summary.avg));
        setText(R.id.statMaxValue, fpsValue(summary.max));
        setText(R.id.statMinValue, fpsValue(summary.min));
        setText(R.id.statLow1Value, fpsValue(summary.low1));
        setText(R.id.statLow5Value, fpsValue(summary.low5));
        setText(R.id.statSmoothValue, Double.isNaN(summary.smoothness)
                ? "—" : String.format(Locale.US, "%.0f%%", summary.smoothness));
        setText(R.id.statCpuTempValue, tempValue(avgCpuTemp));
        setText(R.id.statGpuTempValue, tempValue(avgGpuTemp));
        setText(R.id.statPowerValue, Double.isNaN(avgPower)
                ? "—" : String.format(Locale.US, "%.2f W", avgPower));

        long[] times = session.timeArray();
        double[] cpuTemps = session.cpuTempArray();
        double[] gpuTemps = session.gpuTempArray();
        // One temperature line for the FPS chart's overlay: the
        // mean of whichever sensors answered for that sample.
        double[] temps = new double[cpuTemps.length];
        boolean anyTemp = false;
        for (int i = 0; i < cpuTemps.length; i++) {
            double sum = 0;
            int count = 0;
            if (!Double.isNaN(cpuTemps[i])) {
                sum += cpuTemps[i];
                count++;
            }
            if (!Double.isNaN(gpuTemps[i])) {
                sum += gpuTemps[i];
                count++;
            }
            temps[i] = count > 0 ? sum / count : Double.NaN;
            anyTemp = anyTemp || count > 0;
        }

        SessionGraphView fpsGraph = (SessionGraphView) findViewById(R.id.graphFps);
        if (fpsGraph != null) {
            fpsGraph.setEmptyText(getString(R.string.fps_no_samples));
            fpsGraph.setData(times, session.fpsArray(), FPS_COLOR);
            if (anyTemp) {
                fpsGraph.setOverlay(temps, TEMP_COLOR);
                fpsGraph.setLegend("FPS", getString(R.string.chart_legend_temp));
            }
        }
        // The temperature chart only exists when the session
        // recorded temperatures; its empty text is its own (the
        // FPS one here was a 6.3.7 copy-paste bug).
        setVisible(R.id.tempChartCard, anyTemp);
        if (anyTemp) {
            SessionGraphView tempGraph = (SessionGraphView) findViewById(R.id.graphTemp);
            if (tempGraph != null) {
                tempGraph.setEmptyText(getString(R.string.fps_no_temp_samples));
                tempGraph.setZoomedAxis(true);
                tempGraph.setLegend("CPU", "GPU");
                tempGraph.setData(times, cpuTemps, CPU_TEMP_COLOR, gpuTemps, GPU_TEMP_COLOR);
            }
        }
        SessionGraphView frameGraph = (SessionGraphView) findViewById(R.id.graphFrameTime);
        if (frameGraph != null) {
            frameGraph.setEmptyText(getString(R.string.fps_no_samples));
            frameGraph.setData(times, session.frameTimeArray(), FRAME_COLOR);
        }
    }

    private void setText(int id, String text) {
        TextView view = (TextView) findViewById(id);
        if (view != null) {
            view.setText(text);
        }
    }

    private void setVisible(int id, boolean visible) {
        View view = findViewById(id);
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private static String fpsValue(double value) {
        return Double.isNaN(value) ? "—" : String.format(Locale.US, "%.1f", value);
    }

    private static String tempValue(double value) {
        return Double.isNaN(value) ? "—" : String.format(Locale.US, "%.1f°C", value);
    }
}
