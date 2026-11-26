package com.appslayer.official;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Line chart for one FPS session, drawn on a Canvas — no chart
// library. One primary series (green FPS by default), an optional
// same-axis second series (CPU/GPU temperature share a chart), and
// an optional overlay series on its own axis (temperature over the
// FPS chart, the way Scene draws it, with a small legend).
//
// Axes come from PureCalc: FPS and frame time get a zero-based
// axis with a round ceiling — the pre-6.3.8 code padded the raw
// data range by 8% on both ends and pushed the FPS axis below
// zero. Temperature gets a zoomed axis around its narrow band.
// Lines are smoothed, the primary series carries a soft gradient
// fill, and NaN values break the line instead of dipping to zero,
// so missing readings stay visibly missing.
public class SessionGraphView extends View {

    private long[] times;
    private double[] values;
    private double[] values2;
    private int color2 = Color.rgb(255, 193, 7);
    private double[] overlayValues;
    private int overlayColor = Color.rgb(255, 167, 38);
    private String legendPrimary;
    private String legendOverlay;
    private boolean zoomedAxis;
    private String emptyText = "";

    private final float density;
    private final float scaledDensity;

    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SessionGraphView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        init();
    }

    public SessionGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        init();
    }

    private void init() {
        // Same fill as the card the chart sits in, so the plot
        // blends into the card instead of boxing itself.
        bgPaint.setColor(Color.rgb(26, 29, 31));
        bgPaint.setStyle(Paint.Style.FILL);
        gridPaint.setColor(Color.rgb(52, 56, 59));
        gridPaint.setStrokeWidth(Math.max(1f, density * 0.75f));
        gridPaint.setPathEffect(new DashPathEffect(
                new float[]{density * 4, density * 4}, 0));
        labelPaint.setColor(Color.rgb(154, 160, 166));
        labelPaint.setTextSize(10 * scaledDensity);
        overlayLabelPaint.setTextSize(10 * scaledDensity);
        linePaint.setColor(Color.rgb(76, 175, 80));
        linePaint.setStrokeWidth(density * 2);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        line2Paint.setStrokeWidth(density * 2);
        line2Paint.setStyle(Paint.Style.STROKE);
        line2Paint.setStrokeJoin(Paint.Join.ROUND);
        line2Paint.setStrokeCap(Paint.Cap.ROUND);
        overlayPaint.setStrokeWidth(density * 1.5f);
        overlayPaint.setStyle(Paint.Style.STROKE);
        overlayPaint.setStrokeJoin(Paint.Join.ROUND);
        overlayPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
        emptyPaint.setColor(Color.rgb(154, 160, 166));
        emptyPaint.setTextSize(13 * scaledDensity);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        legendPaint.setColor(Color.rgb(230, 233, 235));
        legendPaint.setTextSize(10.5f * scaledDensity);
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

    // A second series on its own axis (drawn on the right edge):
    // temperature over the FPS chart.
    public void setOverlay(double[] overlay, int color) {
        this.overlayValues = overlay;
        this.overlayColor = color;
        overlayPaint.setColor(color);
        overlayLabelPaint.setColor(color);
        invalidate();
    }

    // Legend labels for the primary and overlay series, drawn at
    // the top of the plot. Null clears the legend.
    public void setLegend(String primary, String overlay) {
        this.legendPrimary = primary;
        this.legendOverlay = overlay;
        invalidate();
    }

    // Temperature charts zoom into their band instead of starting
    // at zero; FPS and frame time always start at zero.
    public void setZoomedAxis(boolean zoomed) {
        this.zoomedAxis = zoomed;
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
        double[] primaryStats = stats(values);
        if (times == null || times.length == 0 || primaryStats == null) {
            canvas.drawText(emptyText, width / 2f, height / 2f, emptyPaint);
            return;
        }
        double dataMin = primaryStats[0];
        double dataMax = primaryStats[1];
        double[] secondStats = stats(values2);
        if (secondStats != null) {
            dataMin = Math.min(dataMin, secondStats[0]);
            dataMax = Math.max(dataMax, secondStats[1]);
        }
        double[] range = zoomedAxis
                ? PureCalc.axisRangeZoom(dataMin, dataMax)
                : PureCalc.axisRangeZero(dataMax);
        if (range == null) {
            canvas.drawText(emptyText, width / 2f, height / 2f, emptyPaint);
            return;
        }
        double[] overlayStats = stats(overlayValues);
        double[] overlayRange = overlayStats != null
                ? PureCalc.axisRangeZoom(overlayStats[0], overlayStats[1]) : null;
        boolean hasOverlay = overlayRange != null;

        float leftPad = density * 34;
        float rightPad = hasOverlay ? density * 30 : density * 8;
        float topPad = legendPrimary != null ? density * 24 : density * 10;
        float bottomPad = density * 18;
        float plotW = width - leftPad - rightPad;
        float plotH = height - topPad - bottomPad;
        double min = range[0];
        double max = range[1];
        long start = times[0];
        long span = Math.max(1, times[times.length - 1] - start);

        // Dashed gridlines: bottom, two in between, top — with the
        // axis values on the left (and the overlay's on the right).
        for (int i = 0; i < 4; i++) {
            double value = min + (max - min) * i / 3.0d;
            float y = topPad + plotH - (float) ((value - min) / (max - min)) * plotH;
            canvas.drawLine(leftPad, y, leftPad + plotW, y, gridPaint);
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(axisLabel(value, max), leftPad - density * 4,
                    y + labelPaint.getTextSize() * 0.35f, labelPaint);
            if (hasOverlay) {
                double oValue = overlayRange[0]
                        + (overlayRange[1] - overlayRange[0]) * i / 3.0d;
                overlayLabelPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(axisLabel(oValue, overlayRange[1]),
                        leftPad + plotW + density * 4,
                        y + overlayLabelPaint.getTextSize() * 0.35f, overlayLabelPaint);
            }
        }
        // Time labels at both ends.
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(timeFormat.format(new Date(start)), leftPad,
                height - density * 5, labelPaint);
        String endText = timeFormat.format(new Date(times[times.length - 1]));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(endText, leftPad + plotW, height - density * 5, labelPaint);

        if (legendPrimary != null) {
            drawLegend(canvas, leftPad + plotW, topPad - density * 8);
        }

        // The second same-axis series goes under the primary one.
        if (values2 != null) {
            drawSeries(canvas, values2, line2Paint, null, min, max,
                    start, span, leftPad, topPad, plotW, plotH, false);
        }
        drawSeries(canvas, values, linePaint, linePaint.getColor(), min, max,
                start, span, leftPad, topPad, plotW, plotH, true);
        if (hasOverlay) {
            drawSeries(canvas, overlayValues, overlayPaint, null,
                    overlayRange[0], overlayRange[1],
                    start, span, leftPad, topPad, plotW, plotH, false);
        }
    }

    private void drawLegend(Canvas canvas, float right, float y) {
        float x = right;
        if (legendOverlay != null) {
            // The second entry is the overlay series when one is
            // set, otherwise the same-axis second series.
            int secondColor = overlayValues != null ? overlayColor : color2;
            x = drawLegendItem(canvas, x, y, legendOverlay, secondColor);
            x -= density * 12;
        }
        drawLegendItem(canvas, x, y, legendPrimary, linePaint.getColor());
    }

    // One legend entry (colour swatch + label) ending at x; returns
    // the x its left edge landed on.
    private float drawLegendItem(Canvas canvas, float x, float y, String label, int color) {
        legendPaint.setTextAlign(Paint.Align.LEFT);
        float textW = legendPaint.measureText(label);
        float swatchW = density * 14;
        float left = x - textW - density * 4 - swatchW;
        Paint swatch = new Paint(Paint.ANTI_ALIAS_FLAG);
        swatch.setColor(color);
        swatch.setStrokeWidth(density * 2);
        canvas.drawLine(left, y - legendPaint.getTextSize() * 0.3f,
                left + swatchW, y - legendPaint.getTextSize() * 0.3f, swatch);
        canvas.drawText(label, left + swatchW + density * 4, y, legendPaint);
        return left;
    }

    // Draws one series as a smoothed path, optionally with the
    // gradient fill under it. NaN runs split the path; a lone point
    // becomes a dot, and sparse series get dots at every sample so
    // a five-sample session still reads point by point.
    private void drawSeries(Canvas canvas, double[] series, Paint paint, Integer fillColor,
                            double min, double max, long start, long span,
                            float leftPad, float topPad, float plotW, float plotH,
                            boolean dots) {
        if (series == null) {
            return;
        }
        int n = Math.min(series.length, times.length);
        int valid = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(series[i])) {
                valid++;
            }
        }
        if (valid == 0) {
            return;
        }
        float bottom = topPad + plotH;
        int i = 0;
        while (i < n) {
            if (Double.isNaN(series[i])) {
                i++;
                continue;
            }
            int runStart = i;
            while (i < n && !Double.isNaN(series[i])) {
                i++;
            }
            int runEnd = i; // exclusive
            Path line = new Path();
            float firstX = xFor(runStart, start, span, leftPad, plotW);
            float firstY = yFor(series[runStart], min, max, topPad, plotH);
            line.moveTo(firstX, firstY);
            if (runEnd - runStart == 1) {
                dotPaint.setColor(paint.getColor());
                canvas.drawCircle(firstX, firstY, density * 2.5f, dotPaint);
                continue;
            }
            float prevX = firstX;
            float prevY = firstY;
            for (int j = runStart + 1; j < runEnd; j++) {
                float x = xFor(j, start, span, leftPad, plotW);
                float y = yFor(series[j], min, max, topPad, plotH);
                float midX = (prevX + x) / 2f;
                float midY = (prevY + y) / 2f;
                line.quadTo(prevX, prevY, midX, midY);
                prevX = x;
                prevY = y;
            }
            line.lineTo(prevX, prevY);
            if (fillColor != null) {
                Path fill = new Path(line);
                fill.lineTo(prevX, bottom);
                fill.lineTo(firstX, bottom);
                fill.close();
                fillPaint.setShader(new LinearGradient(0, topPad, 0, bottom,
                        Color.argb(64, Color.red(fillColor), Color.green(fillColor),
                                Color.blue(fillColor)),
                        Color.argb(0, Color.red(fillColor), Color.green(fillColor),
                                Color.blue(fillColor)),
                        Shader.TileMode.CLAMP));
                canvas.drawPath(fill, fillPaint);
                fillPaint.setShader(null);
            }
            canvas.drawPath(line, paint);
            if (dots && valid <= 60) {
                dotPaint.setColor(paint.getColor());
                for (int j = runStart; j < runEnd; j++) {
                    canvas.drawCircle(xFor(j, start, span, leftPad, plotW),
                            yFor(series[j], min, max, topPad, plotH),
                            density * 2.2f, dotPaint);
                }
            }
        }
    }

    private float xFor(int index, long start, long span, float leftPad, float plotW) {
        return leftPad + ((times[index] - start) / (float) span) * plotW;
    }

    private float yFor(double value, double min, double max, float topPad, float plotH) {
        return topPad + plotH - (float) ((value - min) / (max - min)) * plotH;
    }

    // Axis tick labels: whole numbers when the axis tops out at a
    // whole step, one decimal otherwise.
    private static String axisLabel(double value, double axisMax) {
        if (axisMax >= 10 && Math.abs(value - Math.round(value)) < 0.001d) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.1f", value);
    }

    // [min, max] over the finite values of one series, or null when
    // it holds none.
    private static double[] stats(double[] series) {
        if (series == null) {
            return null;
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double value : series) {
            if (!Double.isNaN(value)) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        return min == Double.MAX_VALUE ? null : new double[]{min, max};
    }
}
