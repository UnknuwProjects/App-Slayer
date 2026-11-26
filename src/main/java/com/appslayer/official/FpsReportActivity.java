package com.appslayer.official;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// One session's report: the headline FPS figures (max / min /
// average, 5% low, 1% low, smoothness — all computed by PureCalc
// from the recorded samples), average temperatures and power, and
// the FPS / temperature / frame-time graphs.
public class FpsReportActivity extends Activity {

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
        String date = new SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date(session.startTimeMs));
        String text = getString(R.string.fps_report_message,
                FpsSessionsActivity.appLabel(this, session.packageName),
                date,
                FpsSessionsActivity.formatDuration(session.durationMs()),
                session.samples.size(),
                fpsValue(summary.max), fpsValue(summary.min), fpsValue(summary.avg),
                fpsValue(summary.low5), fpsValue(summary.low1),
                Double.isNaN(summary.smoothness)
                        ? "—" : String.format(Locale.US, "%.0f%%", summary.smoothness),
                tempValue(avgCpuTemp), tempValue(avgGpuTemp),
                Double.isNaN(avgPower)
                        ? "—" : String.format(Locale.US, "%.2f W", avgPower));
        if (summary.count == 0) {
            text += "\n\n" + getString(R.string.fps_no_samples);
        }
        TextView stats = (TextView) findViewById(R.id.reportStats);
        if (stats != null) {
            stats.setText(text);
        }
        long[] times = session.timeArray();
        SessionGraphView fpsGraph = (SessionGraphView) findViewById(R.id.graphFps);
        if (fpsGraph != null) {
            fpsGraph.setEmptyText(getString(R.string.fps_no_samples));
            fpsGraph.setData(times, session.fpsArray(), Color.rgb(76, 175, 80));
        }
        SessionGraphView tempGraph = (SessionGraphView) findViewById(R.id.graphTemp);
        if (tempGraph != null) {
            tempGraph.setEmptyText(getString(R.string.fps_no_samples));
            tempGraph.setData(times, session.cpuTempArray(), Color.rgb(255, 193, 7),
                    session.gpuTempArray(), Color.rgb(239, 83, 80));
        }
        SessionGraphView frameGraph = (SessionGraphView) findViewById(R.id.graphFrameTime);
        if (frameGraph != null) {
            frameGraph.setEmptyText(getString(R.string.fps_no_samples));
            frameGraph.setData(times, session.frameTimeArray(), Color.rgb(100, 181, 246));
        }
    }

    private static String fpsValue(double value) {
        return Double.isNaN(value) ? "—" : String.format(Locale.US, "%.1f", value);
    }

    private static String tempValue(double value) {
        return Double.isNaN(value) ? "—" : String.format(Locale.US, "%.1f°C", value);
    }
}
