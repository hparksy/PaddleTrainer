package com.parksy.paddletrainer;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

// Drawing helpers shared by the custom views. Views scale the canvas by density, so every size here is in dp.
// Paints are reused: draw on the UI thread only.
final class Ui {
    static final int INK = Color.rgb(216, 220, 240), MUTED = Color.rgb(130, 136, 160), BG = Color.rgb(14, 15, 24);
    static final int CYAN = Color.rgb(90, 210, 255), GREEN = Color.rgb(80, 230, 140), YELLOW = Color.rgb(255, 210, 70),
            RED = Color.rgb(255, 80, 90), ORANGE = Color.rgb(255, 150, 60);

    private static final TextPaint T = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    static final Paint F = new Paint(Paint.ANTI_ALIAS_FLAG);  // fills and strokes

    static TextPaint font(float size, int color, boolean bold) {
        T.setTextSize(size);
        T.setColor(color);
        T.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        T.setTextAlign(Paint.Align.LEFT);
        return T;
    }

    // One line, baseline at y; align: -1 left of x, 0 centred on x, 1 right of x. Shrinks to fit maxW (0 = no limit).
    static void text(Canvas c, String s, float x, float y, float size, int color, boolean bold, int align, float maxW) {
        TextPaint p = font(size, color, bold);
        if (maxW > 0) {
            final float w = p.measureText(s);
            if (w > maxW) p.setTextSize(size * maxW / w);
        }
        p.setTextAlign(align < 0 ? Paint.Align.LEFT : align == 0 ? Paint.Align.CENTER : Paint.Align.RIGHT);
        c.drawText(s, x, y, p);
    }
    static void text(Canvas c, String s, float x, float y, float size, int color, boolean bold) { text(c, s, x, y, size, color, bold, -1, 0); }

    // Text centred in a box.
    static void centred(Canvas c, String s, RectF r, float size, int color, boolean bold) {
        TextPaint p = font(size, color, bold);
        final float w = p.measureText(s);
        if (w > r.width()) p.setTextSize(size * r.width() / w);
        p.setTextAlign(Paint.Align.CENTER);
        final Paint.FontMetrics m = p.getFontMetrics();
        c.drawText(s, r.centerX(), r.centerY() - (m.ascent + m.descent) / 2, p);
    }

    // Wrapped paragraph with its top at y; returns its height.
    static float block(Canvas c, String s, float x, float y, float w, float size, int color, boolean bold) {
        StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), font(size, color, bold), Math.max(1, (int) w))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
        return l.getHeight();
    }

    static void round(Canvas c, RectF r, float rad, int color) {
        F.setStyle(Paint.Style.FILL);
        F.setColor(color);
        c.drawRoundRect(r, rad, rad, F);
    }
    static void outline(Canvas c, RectF r, float rad, int color, float width) {
        F.setStyle(Paint.Style.STROKE);
        F.setStrokeWidth(width);
        F.setColor(color);
        c.drawRoundRect(r, rad, rad, F);
        F.setStyle(Paint.Style.FILL);
    }

    static int alpha(int color, int a) { return (color & 0xFFFFFF) | (a << 24); }
    static int darker(int color, float f) {
        return Color.rgb((int) (Color.red(color) / f), (int) (Color.green(color) / f), (int) (Color.blue(color) / f));
    }

    static int gradeColor(int s) { return s >= 95 ? CYAN : s >= 85 ? GREEN : s >= 70 ? YELLOW : Color.rgb(255, 120, 70); }
    static String gradeName(int s) { return s >= 95 ? "PERFECT" : s >= 85 ? "GREAT" : s >= 70 ? "GOOD" : "SLOPPY"; }
    // status colours for score tiles: good / warning / critical, always shown with the number
    static int statusColor(double s) { return s >= 85 ? Color.rgb(46, 160, 90) : s >= 70 ? Color.rgb(200, 150, 30) : Color.rgb(200, 60, 70); }

    private Ui() {}
}
