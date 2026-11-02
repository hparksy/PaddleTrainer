package com.parksy.paddletrainer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Progress over time: summary, per-character tiles, daily score trend, frequent habits, exams by sentence.
final class StatsView extends View {
    Store store;
    int days = 7;      // period length, 0 = all time
    int kind = -1;     // -1 = all, 0 = practice only, 1 = exam only
    String koch = "";  // characters currently in practice
    private final List<RectF> tipBoxes = new ArrayList<>();
    private final List<String> tipTexts = new ArrayList<>();
    private int tapped = -1;          // trend point whose tooltip is shown
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

    StatsView(Context c) { super(c); }

    private static final class Agg {
        int n, ok;
        double sum;
        double avg() { return n > 0 ? sum / n : 0; }
    }

    private static long dayIndex(long secs) {  // local calendar day number
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(secs * 1000);
        return (secs * 1000 + cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 86400000L;
    }

    // attempts made `to` (inclusive) .. `from` (exclusive) days ago; from = 0 means no lower bound.
    // habit != 0: sum counts attempts showing that habit instead of score.
    private Agg agg(char ch, int from, int to, int habit) {
        Agg a = new Agg();
        final long today = dayIndex(System.currentTimeMillis() / 1000);
        for (Store.Rec r : store.recs) {
            final long back = today - dayIndex(r.time);
            if ((from > 0 && back >= from) || back < to || (ch != 0 && r.ch != ch) || (kind >= 0 && r.kind != kind)) continue;
            ++a.n;
            if (r.ok) ++a.ok;
            a.sum += habit != 0 ? ((r.habits & habit) != 0 ? 1 : 0) : r.score;
        }
        return a;
    }

    private String chars() {
        StringBuilder s = new StringBuilder(koch);
        for (Store.Rec r : store.recs)
            if (s.indexOf(String.valueOf(r.ch)) < 0) s.append(r.ch);
        return s.toString();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        final int w = MeasureSpec.getSize(wSpec);
        final float dn = getResources().getDisplayMetrics().density;
        final Picture pic = new Picture();  // dry run of the drawing to learn the content height
        final float h = store == null ? 0 : paint(pic.beginRecording(Math.max(1, w), 1), w / dn);
        pic.endRecording();
        setMeasuredDimension(w, (int) (h * dn));
    }

    void refresh() { requestLayout(); invalidate(); }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) return false;
        final float dn = getResources().getDisplayMetrics().density;
        tapped = -1;
        for (int i = 0; i < tipBoxes.size(); ++i)
            if (tipBoxes.get(i).contains(e.getX() / dn, e.getY() / dn)) tapped = i;
        invalidate();
        return tapped >= 0;
    }

    @Override protected void onDraw(Canvas c) {
        if (store == null) return;
        final float dn = getResources().getDisplayMetrics().density;
        c.drawColor(Ui.BG);
        c.save();
        c.scale(dn, dn);
        paint(c, getWidth() / dn);
        c.restore();
    }

    // Draws everything; returns the content height (dp).
    private float paint(Canvas c, float W) {
        tipBoxes.clear();
        tipTexts.clear();
        final int grid = Color.rgb(40, 44, 60);

        // summary tiles
        final Agg now = agg((char) 0, days, 0, 0), prev = days > 0 ? agg((char) 0, 2 * days, days, 0) : new Agg();
        final String period = days == 1 ? "오늘" : days > 0 ? "최근 " + days + "일" : "전체 기간";
        final String[][] tiles = {
                {period + " 시도", String.valueOf(now.n), "글자 수"},
                {"정답률", now.n > 0 ? Math.round(100.0 * now.ok / now.n) + "%" : "-", prev.n > 0 ? "이전 " + Math.round(100.0 * prev.ok / prev.n) + "%" : ""},
                {"평균 점수", now.n > 0 ? String.valueOf(Math.round(now.avg())) : "-",
                 prev.n > 0 ? "이전 " + Math.round(prev.avg()) + " (" + (now.avg() >= prev.avg() ? "▲" : "▼") + Math.round(Math.abs(now.avg() - prev.avg())) + ")" : "틀린 글자 = 0점"}};
        final float tw = (W - 24 - 2 * 8) / 3f;
        for (int i = 0; i < 3; ++i) {
            final RectF r = new RectF(12 + i * (tw + 8), 12, 12 + i * (tw + 8) + tw, 84);
            Ui.round(c, r, 10, Color.rgb(24, 27, 44));
            Ui.text(c, tiles[i][0], r.left + 10, r.top + 18, 11, Ui.MUTED, false, -1, tw - 20);
            Ui.text(c, tiles[i][1], r.left + 10, r.top + 46, 22, Ui.INK, true);
            Ui.text(c, tiles[i][2], r.right - 8, r.bottom - 8, 10, Ui.MUTED, false, 1, tw - 16);
        }

        // per-character tiles
        float y = 104;
        Ui.text(c, "글자별 평균 점수 (" + period + ", 화살표 = 이전 같은 기간 대비)", 12, y + 14, 13, Ui.INK, true, -1, W - 24);
        y += 26;
        final String cs = chars();
        final int cols = Math.max(1, (int) ((W - 24 + 6) / 76));
        final float cw = (W - 24 - (cols - 1) * 6) / cols;
        for (int i = 0; i < cs.length(); ++i) {
            final char ch = cs.charAt(i);
            final Agg a = agg(ch, days, 0, 0), b = days > 0 ? agg(ch, 2 * days, days, 0) : new Agg();
            final float x = 12 + (i % cols) * (cw + 6), ty = y + (i / cols) * 76;
            final RectF r = new RectF(x, ty, x + cw, ty + 70);
            Ui.round(c, r, 10, a.n > 0 ? Ui.darker(Ui.statusColor(a.avg()), 1.7f) : Color.rgb(28, 30, 44));
            if (a.n > 0) Ui.round(c, new RectF(r.left, r.top, r.left + 5, r.bottom), 3, Ui.statusColor(a.avg()));
            Ui.text(c, String.valueOf(ch), r.left + 10, r.top + 24, 20, Ui.INK, true);
            Ui.text(c, a.n > 0 ? String.valueOf(Math.round(a.avg())) : "-", r.right - 8, r.top + 22, 16, a.n > 0 ? Ui.INK : Ui.MUTED, true, 1, 0);
            final String trend = a.n > 0 && b.n > 0 ? " " + (a.avg() >= b.avg() ? "▲" : "▼") + Math.round(Math.abs(a.avg() - b.avg())) : "";
            Ui.text(c, a.n > 0 ? "정답 " + Math.round(100.0 * a.ok / a.n) + "%" + trend : "기록 없음", r.left + 10, r.bottom - 22, 10, Color.rgb(200, 205, 225), false, -1, cw - 14);
            Ui.text(c, a.n > 0 ? a.n + "회" : "", r.left + 10, r.bottom - 8, 10, Color.rgb(200, 205, 225), false);
        }
        y += ((cs.length() + cols - 1) / cols) * 76 + 16;

        // daily trend: one series, fixed 0..100 axis, gaps on days without practice; tap a point for its numbers
        Ui.text(c, "일별 평균 점수 (최근 30일, 점을 누르면 자세히)", 12, y + 14, 13, Ui.INK, true, -1, W - 24);
        y += 26;
        final RectF plot = new RectF(40, y + 8, W - 16, y + 168);
        for (int v = 0; v <= 100; v += 25) {
            final float gy = plot.bottom - v / 100f * plot.height();
            Ui.F.setColor(grid);
            Ui.F.setStrokeWidth(1);
            c.drawLine(plot.left, gy, plot.right, gy, Ui.F);
            Ui.text(c, String.valueOf(v), plot.left - 6, gy + 4, 10, Ui.MUTED, false, 1, 0);
        }
        final SimpleDateFormat md = new SimpleDateFormat("M/d", Locale.KOREA), ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
        final long nowMs = System.currentTimeMillis();
        final Path path = new Path();
        boolean open = false;
        final List<PointF> dots = new ArrayList<>();
        for (int back = 29; back >= 0; --back) {
            final Agg a = agg((char) 0, back + 1, back, 0);
            final float x = plot.right - back / 29f * plot.width();
            final Date day = new Date(nowMs - back * 86400000L);
            if (back % 7 == 0) Ui.text(c, md.format(day), x, plot.bottom + 16, 10, Ui.MUTED, false, 0, 0);
            if (a.n == 0) { open = false; continue; }
            final PointF pt = new PointF(x, (float) (plot.bottom - a.avg() / 100 * plot.height()));
            if (open) path.lineTo(pt.x, pt.y); else path.moveTo(pt.x, pt.y);
            open = true;
            dots.add(pt);
            tipBoxes.add(new RectF(pt.x - 12, plot.top, pt.x + 12, plot.bottom));
            tipTexts.add(ymd.format(day) + "  평균 " + Math.round(a.avg()) + "점 · 정답 " + Math.round(100.0 * a.ok / a.n) + "% · " + a.n + "회");
        }
        final int series = Color.rgb(90, 170, 255);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2);
        line.setColor(series);
        c.drawPath(path, line);
        for (PointF d : dots) {
            Ui.F.setColor(Ui.BG);  // surface ring
            c.drawCircle(d.x, d.y, 6.5f, Ui.F);
            Ui.F.setColor(series);
            c.drawCircle(d.x, d.y, 4.5f, Ui.F);
        }
        if (dots.isEmpty()) Ui.centred(c, "아직 기록이 없습니다", plot, 12, Ui.MUTED, false);
        if (tapped >= 0 && tapped < tipBoxes.size()) {
            final RectF b = tipBoxes.get(tapped);
            final float bw = Math.min(W - 24, 300), bx = Math.max(12, Math.min(W - 12 - bw, b.centerX() - bw / 2));
            final RectF tip = new RectF(bx, plot.top - 4, bx + bw, plot.top + 22);
            Ui.round(c, tip, 6, Color.rgb(28, 31, 51));
            Ui.outline(c, tip, 6, Color.rgb(58, 64, 104), 1);
            Ui.centred(c, tipTexts.get(tapped), tip, 11, Ui.INK, false);
        }
        y = plot.bottom + 36;

        // habits: share of attempts in the period that showed each one
        Ui.text(c, "자주 나오는 버릇 (" + period + ", 시도 대비 비율)", 12, y + 14, 13, Ui.INK, true, -1, W - 24);
        y += 26;
        final List<double[]> hs = new ArrayList<>();
        for (int h = 1; h <= Morse.STRAY_PRESS; h <<= 1) {
            final Agg a = agg((char) 0, days, 0, h);
            if (a.sum > 0) hs.add(new double[]{a.sum / a.n, h});
        }
        Collections.sort(hs, (a, b) -> Double.compare(b[0], a[0]));
        if (hs.isEmpty()) { Ui.text(c, now.n > 0 ? "지적된 버릇이 없습니다" : "-", 12, y + 14, 12, Ui.MUTED, false); y += 24; }
        final float barL = W * 0.55f, barMax = W - 16 - 44 - barL;
        for (int i = 0; i < Math.min(7, hs.size()); ++i) {
            final double share = hs.get(i)[0];
            final int h = (int) hs.get(i)[1];
            Ui.text(c, Morse.habitName(h), 12, y + 14, 11, Ui.INK, false, -1, barL - 18);
            final float bw = (float) Math.max(4, barMax * share / hs.get(0)[0]);  // scaled to the most frequent
            Ui.round(c, new RectF(barL, y + 5, barL + bw, y + 15), 3, Color.rgb(255, 120, 90));
            Ui.text(c, Math.round(share * 100) + "%", barL + bw + 6, y + 14, 11, Ui.MUTED, false);
            y += 22;
        }
        if (!hs.isEmpty()) y += Ui.block(c, "→ " + Morse.habitAdvice((int) hs.get(0)[1]), 12, y + 4, W - 24, 12, Color.rgb(170, 200, 255), false) + 8;
        y += 16;

        // exams, sentence by sentence
        Ui.text(c, "검정 기록 — 문장별 (" + period + ", 최근 5회)", 12, y + 14, 13, Ui.INK, true, -1, W - 24);
        y += 28;
        final List<Store.ExamRec> ex = new ArrayList<>();
        final long today = dayIndex(nowMs / 1000);
        for (int i = store.exams.size() - 1; i >= 0 && ex.size() < 5; --i)
            if (days == 0 || today - dayIndex(store.exams.get(i).time) < days) ex.add(store.exams.get(i));
        if (ex.isEmpty()) { Ui.text(c, "아직 검정 기록이 없습니다", 12, y + 14, 12, Ui.MUTED, false); y += 24; }
        final SimpleDateFormat when = new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA);
        for (Store.ExamRec e : ex) {
            final RectF head = new RectF(12, y, W - 12, y + 62);
            Ui.round(c, head, 6, Color.rgb(24, 27, 44));
            Ui.text(c, e.score + "점 " + (e.pass != 0 ? "합격" : "불합격"), head.left + 10, y + 20, 14, e.pass != 0 ? Ui.GREEN : Color.rgb(255, 100, 100), true);
            Ui.text(c, when.format(new Date(e.time * 1000)) + "  ·  " + e.minutes + "분  ·  보냄 " + e.sent + "자", head.right - 10, y + 19, 11, Ui.INK, false, 1, head.width() - 110);
            Ui.text(c, e.grade, head.left + 10, y + 38, 11, Ui.MUTED, false, -1, head.width() - 20);
            Ui.text(c, "오자 " + e.sub + " 탈자 " + e.del + " 용자 " + e.ins + " · 불명료 " + e.unclear + " · 정정 " + e.corr +
                    " · 속도미달 " + e.shortfall + (e.over != 0 ? " · 속도초과" : ""), head.left + 10, y + 55, 11, Ui.INK, false, -1, head.width() - 20);
            y += 70;
            for (Store.SentRec sr : store.sents) {
                if (sr.time != e.time) continue;
                Ui.text(c, sr.score + "점", 20, y + 14, 12, Ui.statusColor(sr.score), true);
                Ui.text(c, (sr.idx + 1) + ". " + sr.target, 64, y + 14, 12, Ui.INK, false, -1, W - 76);
                Ui.text(c, "→ " + (sr.sent.isEmpty() ? "-" : sr.sent) + "   (오 " + sr.sub + " 탈 " + sr.del + " 용 " + sr.ins + " 불 " + sr.unclear +
                        (sr.unsent > 0 ? " · 미송신 " + sr.unsent : "") + ")", 64, y + 30, 11, Ui.MUTED, false, -1, W - 76);
                y += 38;
            }
            y += 6;
        }
        Ui.text(c, "기록 파일: " + store.dir.getAbsolutePath(), 12, y + 20, 10, Ui.MUTED, false, -1, W - 24);
        return y + 40;
    }
}
