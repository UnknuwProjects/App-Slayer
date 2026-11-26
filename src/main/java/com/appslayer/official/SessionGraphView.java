package com.appslayer.official;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Line chart for one FPS session, drawn on a Canvas in the same
// style as BatteryGraphView — no chart library. Holds one series
// plus an optional second one (CPU and GPU temperature share a
// chart). NaN values break the line instead of dipping to zero, so
// missing readings stay visibly missing.
public class SessionGraphView extends View {

    private long[] times;
    private double[] values;
    private double[] values2;
    private int color2 = Color.rgb(255, 193, 7);
    private String emptyText = "";

    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SessionGraphView(Context context) {
        super(context);
        init();
    }

    public SessionGraphView(Context context, AttributeSet attrs) {
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
        line2Paint.setStrokeWidth(4);
        line2Paint.setStyle(Paint.Style.STROKE);
        emptyPaint.setColor(Color.rgb(154, 160, 166));
        emptyPaint.setTextSize(30);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(long[] times, double[] values, int color) {
        setData(times, values, color, null, 0);
    }

    public void setData(long[] times, double[] values, int color,
                        double[] secondValues, int secondColor) {
        this.times = times;
        this.values = values;
        this.values2 = secondValues;
        this.color2 = secondColor;
        linePaint.setColor(color);
        line2Paint.setColor(secondColor);
        invalidate();
    }

    public void setEmptyText(String text) {
        this.emptyText = text != null ? text : "";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        canvas.drawRect(0, 0, width, height, bgPaint);
        double[] range = valueRange();
        if (times == null || times.length == 0 || range == null) {
            canvas.drawText(emptyText, width / 2f, height / 2f, emptyPaint);
            return;
        }
        float leftPad = 76;
        float rightPad = 16;
        float topPad = 16;
        float bottomPad = 40;
        float plotW = width - leftPad - rightPad;
        float plotH = height - topPad - bottomPad;
        double min = range[0];
        double max = range[1];
        long start = times[0];
        long span = Math.max(1, times[times.length - 1] - start);

        // Gridlines at min / mid / max with value labels.
        for (int i = 0; i < 3; i++) {
            double value = min + (max - min) * i / 2.0d;
            float y = topPad + plotH - (float) ((value - min) / (max - min)) * plotH;
            canvas.drawLine(leftPad, y, leftPad + plotW, y, gridPaint);
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(Locale.US, "%.1f", value), leftPad - 8, y + 9, labelPaint);
        }
        // Time labels at both ends.
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(timeFormat.format(new Date(start)), leftPad, height - 10, labelPaint);
        String endText = timeFormat.format(new Date(times[times.length - 1]));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(endText, leftPad + plotW, height - 10, labelPaint);

        drawSeries(canvas, values, linePaint, min, max, start, span, leftPad, topPad, plotW, plotH);
        if (values2 != null) {
            drawSeries(canvas, values2, line2Paint, min, max, start, span, leftPad, topPad, plotW, plotH);
        }
    }

    private void drawSeries(Canvas canvas, double[] series, Paint paint,
                            double min, double max, long start, long span,
                            float leftPad, float topPad, float plotW, float plotH) {
        if (series == null) {
            return;
        }
        boolean pen = false;
        float prevX = 0;
        float prevY = 0;
        int n = Math.min(series.length, times.length);
        for (int i = 0; i < n; i++) {
            double value = series[i];
            if (Double.isNaN(value)) {
                pen = false;
                continue;
            }
            float x = leftPad + ((times[i] - start) / (float) span) * plotW;
            float y = topPad + plotH - (float) ((value - min) / (max - min)) * plotH;
            if (pen) {
                canvas.drawLine(prevX, prevY, x, y, paint);
            }
            prevX = x;
            prevY = y;
            pen = true;
        }
    }

    // Padded [min, max] across both series, or null when no finite
    // value exists. A flat series gets a ±10% band so the line does
    // not hug the edge.
    private double[] valueRange() {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        min = scanMin(values, min);
        max = scanMax(values, max);
        min = scanMin(values2, min);
        max = scanMax(values2, max);
        if (min == Double.MAX_VALUE || max == -Double.MAX_VALUE) {
            return null;
        }
        if (max - min < 0.001d) {
            double pad = Math.max(1.0d, Math.abs(max) * 0.1d);
            min -= pad;
            max += pad;
        } else {
            double pad = (max - min) * 0.08d;
            min -= pad;
            max += pad;
        }
        return new double[]{min, max};
    }

    private static double scanMin(double[] series, double current) {
        if (series == null) {
            return current;
        }
        for (double value : series) {
            if (!Double.isNaN(value) && value < current) {
                current = value;
            }
        }
        return current;
    }

    private static double scanMax(double[] series, double current) {
        if (series == null) {
            return current;
        }
        for (double value : series) {
            if (!Double.isNaN(value) && value > current) {
                current = value;
            }
        }
        return current;
    }
}
