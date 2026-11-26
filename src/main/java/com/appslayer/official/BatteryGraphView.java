package com.appslayer.official;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Draws the last 24 hours of battery level as a simple filled line
// chart on a Canvas — no chart library. Discharging segments are
// green, charging segments amber. Shows an empty-state text when
// there is no data yet.
public class BatteryGraphView extends View {

    private static final long WINDOW_MS = 24L * 60 * 60 * 1000;

    private List<BatteryHistory.Sample> samples;
    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chargePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BatteryGraphView(Context context) {
        super(context);
        init();
    }

    public BatteryGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setColor(Color.rgb(16, 18, 20));
        bgPaint.setStyle(Paint.Style.FILL);
        gridPaint.setColor(Color.rgb(60, 64, 67));
        gridPaint.setStrokeWidth(1);
        labelPaint.setColor(Color.rgb(154, 160, 166));
        labelPaint.setTextSize(26);
        linePaint.setColor(Color.rgb(76, 175, 80));
        linePaint.setStrokeWidth(4);
        linePaint.setStyle(Paint.Style.STROKE);
        chargePaint.setColor(Color.rgb(255, 193, 7));
        chargePaint.setStrokeWidth(4);
        chargePaint.setStyle(Paint.Style.STROKE);
        fillPaint.setColor(Color.argb(70, 76, 175, 80));
        fillPaint.setStyle(Paint.Style.FILL);
        emptyPaint.setColor(Color.rgb(154, 160, 166));
        emptyPaint.setTextSize(30);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void ensureLoaded() {
        if (samples == null) {
            samples = BatteryHistory.load(getContext(), WINDOW_MS);
        }
    }

    public void reload() {
        samples = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensureLoaded();
        int width = getWidth();
        int height = getHeight();
        canvas.drawRect(0, 0, width, height, bgPaint);
        if (samples == null || samples.size() < 2) {
            canvas.drawText(getContext().getString(R.string.battery_history_empty),
                    width / 2f, height / 2f, emptyPaint);
            return;
        }
        float leftPad = 64;
        float rightPad = 16;
        float topPad = 16;
        float bottomPad = 40;
        float plotW = width - leftPad - rightPad;
        float plotH = height - topPad - bottomPad;
        long now = System.currentTimeMillis();
        long start = now - WINDOW_MS;

        // Gridlines at 0 / 50 / 100%
        for (int pct : new int[]{0, 50, 100}) {
            float y = topPad + plotH - (pct / 100f) * plotH;
            canvas.drawLine(leftPad, y, leftPad + plotW, y, gridPaint);
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(pct + "%", leftPad - 8, y + 9, labelPaint);
        }
        // Time labels: window start and now
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(timeFormat.format(new Date(start)), leftPad, height - 10, labelPaint);
        String nowText = timeFormat.format(new Date(now));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(nowText, leftPad + plotW, height - 10, labelPaint);

        // Filled area under the level line
        Path fill = new Path();
        boolean started = false;
        float lastX = leftPad;
        float lastY = topPad + plotH;
        for (BatteryHistory.Sample s : samples) {
            float x = leftPad + ((s.time - start) / (float) WINDOW_MS) * plotW;
            float y = topPad + plotH - (s.level / 100f) * plotH;
            if (!started) {
                fill.moveTo(x, topPad + plotH);
                fill.lineTo(x, y);
                started = true;
            } else {
                fill.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        fill.lineTo(lastX, topPad + plotH);
        fill.close();
        canvas.drawPath(fill, fillPaint);

        // Level line, colored per segment
        for (int i = 1; i < samples.size(); i++) {
            BatteryHistory.Sample a = samples.get(i - 1);
            BatteryHistory.Sample b = samples.get(i);
            float x1 = leftPad + ((a.time - start) / (float) WINDOW_MS) * plotW;
            float y1 = topPad + plotH - (a.level / 100f) * plotH;
            float x2 = leftPad + ((b.time - start) / (float) WINDOW_MS) * plotW;
            float y2 = topPad + plotH - (b.level / 100f) * plotH;
            canvas.drawLine(x1, y1, x2, y2, b.charging ? chargePaint : linePaint);
        }
    }
}
