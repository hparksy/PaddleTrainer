package com.parksy.paddletrainer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

// Practice screen: target word, reference-vs-you timing bars, last result line, score/combo.
final class GameView extends View {
    String word = "";
    int pos;
    final ArrayList<Integer> doneColor = new ArrayList<>();
    String cmpRef = "";                         // pattern the comparison bars are drawn against
    List<Tone.Elem> elems = new ArrayList<>();  // user's elements for that pattern
    double liveStart = -1, now, releaseAt = -1, charGap = -1;
    Morse.Timing timing = new Morse.Timing(80, 240, 560);
    boolean hint = true, straight;
    int owe;                                    // correct repeats still needed before the current letter is done
    Morse.Criteria crit;
    int score, combo, bestCombo, attempts, hits;
    double scoreSum;
    private String popText = "";
    private int popColor;
    private double popAt = -1e9;
    private final Paint bgPaint = new Paint();
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);

    GameView(Context c) {
        super(c);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(1);
        dash.setColor(Ui.alpha(Ui.CYAN, 70));
        dash.setPathEffect(new DashPathEffect(new float[]{4, 4}, 0));
    }

    void pop(String text, int color) { popText = text; popColor = color; popAt = now; }

    private static String signedPct(double d) { return (d >= 0 ? "+" : "") + Math.round(d * 100) + "%"; }
    private static int devColor(double d, double ok, double zero) {
        d = Math.abs(d) * 100;
        return d <= ok ? Ui.GREEN : d < zero ? Ui.YELLOW : Ui.RED;
    }

    @Override protected void onDraw(Canvas c) {
        final float dn = getResources().getDisplayMetrics().density;
        final float W = getWidth() / dn, H = getHeight() / dn;
        bgPaint.setShader(new LinearGradient(0, 0, 0, getHeight(), Color.rgb(22, 20, 48), Color.rgb(8, 8, 16), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        c.save();
        c.scale(dn, dn);
        final double dit = timing.ditMs;

        // HUD
        Ui.text(c, "SCORE " + score, 12, 22, 16, Color.rgb(255, 215, 90), true);
        Ui.text(c, "COMBO x" + combo + "  (최고 " + bestCombo + ")", W - 12, 22, 13, combo >= 5 ? Color.rgb(255, 90, 210) : Color.rgb(170, 175, 210), true, 1, 0);
        Ui.text(c, "평균 " + (attempts > 0 ? Math.round(scoreSum / attempts) : 0) + "점   ✔" + hits + "  ✘" + (attempts - hits), 12, 42, 12, Color.rgb(120, 220, 255), true);

        // target word, typing-tutor style
        final int len = Math.max(1, word.length());
        final float cell = Math.min(72, (W - 16) / len), y0 = 54, x0 = (W - cell * word.length()) / 2;
        for (int i = 0; i < word.length(); ++i) {
            final RectF r = new RectF(x0 + i * cell + 4, y0, x0 + (i + 1) * cell - 4, y0 + cell);
            Ui.round(c, r, 10, i == pos ? Color.argb(170, 40, 80, 130) : Color.rgb(30, 32, 50));
            if (i == pos) Ui.outline(c, r, 10, owe > 0 ? Ui.ORANGE : Ui.CYAN, 3);
            Ui.centred(c, word.substring(i, i + 1), r, cell * 0.55f,
                    i < pos ? (i < doneColor.size() ? doneColor.get(i) : Ui.GREEN) : i == pos ? Color.WHITE : Color.rgb(85, 90, 110), true);
        }
        final float cx = x0 + pos * cell + cell / 2;
        if (hint && pos < word.length()) {  // pattern under the current letter
            final String pat = Morse.patternOf(word.charAt(pos));
            float w = 0;
            for (char ch : pat.toCharArray()) w += (ch == '.' ? 8 : 24) + 5;
            float x = cx - (w - 5) / 2;
            for (char ch : pat.toCharArray()) {
                final float bw = ch == '.' ? 8 : 24;
                Ui.round(c, new RectF(x, y0 + cell + 6, x + bw, y0 + cell + 13), 3, Ui.CYAN);
                x += bw + 5;
            }
        }
        if (owe > 0 && pos < word.length()) {  // this letter must be repeated before moving on
            final float bx = Math.max(4, Math.min(W - 164, cx - 80));
            final RectF badge = new RectF(bx, y0 + cell + 18, bx + 160, y0 + cell + 40);
            Ui.round(c, badge, 11, Ui.ORANGE);
            Ui.centred(c, "↻ 같은 글자 " + owe + "회 더", badge, 12, Color.rgb(20, 16, 10), true);
        }

        // reference vs you
        final RectF panel = new RectF(8, y0 + cell + 48, W - 8, H - 8);
        Ui.round(c, panel, 12, Color.argb(220, 16, 18, 34));
        Ui.outline(c, panel, 12, Color.rgb(55, 65, 100), 1);
        final ArrayList<double[]> ref = new ArrayList<>();
        double t = 0;
        for (char ch : cmpRef.toCharArray()) { ref.add(new double[]{t, t + (ch == '.' ? 1 : 3) * dit}); t = ref.get(ref.size() - 1)[1] + dit; }
        final double base = elems.isEmpty() ? liveStart : elems.get(0).t0;
        double userEnd = elems.isEmpty() ? 0 : elems.get(elems.size() - 1).t1 - base;
        if (liveStart >= 0 && base >= 0) userEnd = now - base;
        if (releaseAt >= 0 && base >= 0) userEnd = Math.max(userEnd, releaseAt - base);
        final double total = Math.max(Math.max(t, userEnd) + dit, 8 * dit);
        final float L = panel.left + 40, R = panel.right - 12;
        final double px = (R - L) / total;
        final float yRef = panel.top + 66, yYou = panel.top + 128, h = 28;
        final float tw = panel.width() - 20;

        final Character ch = cmpRef.isEmpty() ? null : Morse.TABLE.get(cmpRef);
        Ui.text(c, "글자 '" + (ch == null ? "" : ch) + "'   dit " + Math.round(dit) + " · dah " + Math.round(3 * dit) + " · 글자 간격 " +
                Math.round(timing.charGapMs) + " ms", panel.left + 10, panel.top + 18, 11, Color.rgb(150, 160, 200), false, -1, tw);
        if (charGap >= 0)
            Ui.text(c, "내 글자 간격 " + Math.round(charGap) + " ms (" + signedPct(charGap / timing.charGapMs - 1) + ")",
                    panel.left + 10, panel.top + 34, 11, Color.rgb(150, 160, 200), false, -1, tw);
        Ui.text(c, straight ? "채점: dit:dah ±" + fmt(crit.elemOk) + "% · 요소 간격 ±" + fmt(crit.elemGapOk) + "% · 속도 ±" + fmt(crit.speedOk) +
                        "% · 글자 간격 −" + fmt(crit.shortOk) + "%/+" + fmt(crit.longOk) + "% 이내 만점"
                        : "채점: 머뭇거림 " + fmt(crit.hesitateOk) + "% · 패들 뗌 dit의 " + fmt(crit.releaseOk) + "% · 글자 간격 −" +
                        fmt(crit.shortOk) + "%/+" + fmt(crit.longOk) + "% 이내 만점",
                panel.left + 10, panel.top + 50, 10, Ui.MUTED, false, -1, tw);
        Ui.text(c, "기준", panel.left + 8, yRef + 19, 12, Color.rgb(150, 160, 200), true);
        Ui.text(c, "나", panel.left + 8, yYou + 19, 12, Color.rgb(150, 160, 200), true);

        for (double[] ab : ref) {
            final RectF r = new RectF((float) (L + ab[0] * px), yRef, (float) (L + ab[1] * px), yRef + h);
            Ui.round(c, r, 5, Ui.alpha(Ui.CYAN, 50));
            Ui.outline(c, r, 5, Ui.CYAN, 2);
            c.drawLine(r.left, yRef + h, r.left, yYou + h, dash);  // guides down to your row
            c.drawLine(r.right, yRef + h, r.right, yYou + h, dash);
        }
        for (int i = 0; i < elems.size(); ++i) {
            final Tone.Elem e = elems.get(i);
            final float x = (float) (L + (e.t0 - base) * px);
            final RectF r = new RectF(x, yYou, (float) (x + e.ms * px), yYou + h);
            final char sym = e.sym != 0 ? e.sym : e.ms < 2 * dit ? '.' : '-';
            final boolean known = i < ref.size() && cmpRef.charAt(i) == sym;
            final double dev = known ? e.ms / (ref.get(i)[1] - ref.get(i)[0]) - 1 : 1;
            final int col = straight || !known ? devColor(dev, crit.elemOk, crit.elemZero) : Color.rgb(150, 160, 200);  // keyer-made lengths are not yours
            Ui.round(c, r, 5, col);
            Ui.text(c, !known ? "틀림" : straight ? signedPct(dev) : Math.round(e.ms) + "ms", r.centerX(), yYou + h + 14, 10, col, true, 0, 0);
            if (straight && i > 0) {  // gap before this element
                final Tone.Elem p = elems.get(i - 1);
                final double gdev = (e.t0 - p.t1) / dit - 1;
                Ui.text(c, signedPct(gdev), (float) (L + ((p.t1 + e.t0) / 2 - base) * px), yYou - 6, 9,
                        devColor(gdev, crit.elemGapOk, crit.elemGapZero), false, 0, 0);
            }
        }
        if (liveStart >= 0 && base >= 0)
            Ui.round(c, new RectF((float) (L + (liveStart - base) * px), yYou, (float) (L + (now - base) * px), yYou + h), 5, Color.argb(200, 255, 255, 255));
        if (!straight && releaseAt >= 0 && base >= 0 && !elems.isEmpty()) {  // when the hand let go
            final double lateMs = releaseAt - elems.get(elems.size() - 1).t1;
            final boolean late = lateMs / dit * 100 > crit.releaseOk;
            final int col = late ? Ui.RED : Color.rgb(240, 240, 255);
            final float x = (float) (L + (releaseAt - base) * px);
            Ui.F.setColor(col);
            Ui.F.setStrokeWidth(2);
            c.drawLine(x, yYou - 6, x, yYou + h + 6, Ui.F);
            Ui.text(c, late ? "뗌 +" + Math.round(lateMs) + "ms 늦음" : "뗌", Math.max(L, Math.min(R - 40, x)), yYou - 10, 10, col, true, 0, 0);
        }

        // last result: one steady line under the bars, shown up to 5 s (details are in the list below)
        final double age = now - popAt;
        if (age >= 0 && age < 5000) {
            final int a = (int) (255 * Math.min(1.0, (5000 - age) / 500));  // fade out over the last half second
            Ui.text(c, popText, panel.left + 10, yYou + h + 44, 18, Ui.alpha(popColor, a), true, -1, tw);
        }
        Ui.text(c, "초록 만점 · 노랑 감점 · 빨강 0점  ·  통과 " + fmt(crit.passScore) + "점  ·  ⚙ 설정에서 기준 변경",
                panel.left + 10, panel.bottom - 8, 10, Color.rgb(120, 125, 150), false, -1, tw);
        c.restore();
    }

    static String fmt(double x) { return x == Math.rint(x) ? Long.toString((long) x) : Double.toString(x); }
}
