package com.parksy.paddletrainer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

// Exam screen: target sentences coloured by how each letter was sent, a countdown, and the score sheet.
final class ExamView extends View {
    List<String> sentences = new ArrayList<>();
    Morse.ExamGrade g = new Morse.ExamGrade();
    boolean running, finished;
    double leftMs;
    int required, sentCount;
    String gradeName = "", live = "";
    Morse.Criteria crit;

    ExamView(Context c) { super(c); }

    @Override protected void onDraw(Canvas c) {
        final float dn = getResources().getDisplayMetrics().density;
        c.drawColor(Ui.BG);
        c.save();
        c.scale(dn, dn);
        final float W = getWidth() / dn, H = getHeight() / dn;

        final int secs = Math.max(0, (int) Math.ceil(leftMs / 1000));
        Ui.text(c, secs / 60 + ":" + String.format("%02d", secs % 60), W - 12, 34, 28, running && secs <= 10 ? Color.rgb(255, 120, 90) : Ui.INK, true, 1, 0);
        Ui.text(c, gradeName, 12, 22, 13, Ui.INK, true, -1, W - 110);
        Ui.text(c, "보낸 글자 " + sentCount + " / 필요 " + required + "   ·   현재 " + g.score + "점", 12, 44, 12, Ui.MUTED, false, -1, W - 110);
        Ui.text(c, "오자 " + g.sub + " · 탈자 " + g.del + " · 용자 " + g.ins + " · 불명료 " + g.unclear + " · 정정 " + g.corr +
                "   ·   치는 중: " + (live.isEmpty() ? "-" : live), 12, 62, 12, Ui.MUTED, false, -1, W - 24);

        if (sentences.isEmpty()) {
            Ui.block(c, "등급과 시간을 고르고 '시작'을 누르세요.\n\n" +
                    "옛 아마추어무선기사 전신 실기 형식: 영문 보통어를 정해진 속도(1분간 N자)로 정해진 시간 동안 송신합니다.\n\n" +
                    "틀렸으면 dit 8개(........)로 정정 후 다시 치세요 — 정정 1회마다 감점됩니다.\n" +
                    "단어 사이 간격은 채점하지 않습니다. 마지막 글자를 치면 바로 채점됩니다.", 16, 90, W - 32, 14, Ui.MUTED, false);
            c.restore();
            return;
        }

        // sentences: letter cells, colour = result; the next letter to send is outlined
        float units = 1;  // widest sentence in letter cells (a space is half a cell)
        for (String s : sentences) {
            float u = 0;
            for (char ch : s.toCharArray()) u += ch == ' ' ? 0.5f : 1;
            units = Math.max(units, u);
        }
        final int rows = sentences.size();
        final float rowH = Math.max(30, Math.min(52, (H - 84) / Math.max(1, rows)));
        final float cell = Math.min(Math.min(24, rowH / 2), (W - 24) / units);
        int li = 0;
        float y = 76;
        for (int k = 0; k < rows; ++k) {
            float x = 12;
            for (char ch : sentences.get(k).toCharArray()) {
                if (ch == ' ') { x += cell / 2; continue; }
                final Morse.LetterGrade lg = g.letters.get(li);
                final int col = !lg.reached ? Color.rgb(90, 95, 120) : !lg.ok ? Ui.RED : lg.score < crit.examUnclearBelow ? Ui.YELLOW : Ui.GREEN;
                final RectF box = new RectF(x, y, x + cell, y + cell + 4);
                if (running && li == g.end) {
                    final RectF b = new RectF(box.left - 1, box.top - 1, box.right + 1, box.bottom + 1);
                    Ui.round(c, b, 3, Ui.alpha(Ui.CYAN, 40));
                    Ui.outline(c, b, 3, Ui.CYAN, 2);
                }
                Ui.centred(c, String.valueOf(ch), box, cell * 0.75f, col, true);
                x += cell;
                ++li;
            }
            final Morse.SentenceGrade sg = g.sentences.get(k);
            if (sg.reached) Ui.text(c, "→ " + sg.sent, 12, y + cell + 4 + Math.max(10, cell / 2) + 2, Math.max(9, cell / 2), Ui.MUTED, false, -1, W - 24);
            y += rowH;
        }

        if (finished) {  // score sheet, like the old exam's sending record
            final float bw = Math.min(W - 24, 340), bx = (W - bw) / 2;
            final RectF box = new RectF(bx, 80, bx + bw, 80 + 250);
            Ui.round(c, box, 12, Color.argb(245, 20, 22, 38));
            Ui.outline(c, box, 12, Color.rgb(60, 66, 100), 1);
            final boolean pass = g.score >= crit.examPass;
            Ui.text(c, g.score + "점  " + (pass ? "합격" : "불합격"), box.left + 16, box.top + 34, 20, pass ? Ui.GREEN : Color.rgb(255, 100, 100), true);
            Ui.text(c, "합격 " + GameView.fmt(crit.examPass) + "점 이상", box.right - 16, box.top + 30, 10, Ui.MUTED, false, 1, 0);
            final String[] names = {"오자 · 탈자 · 용자", "자호불명료", "정정", "기준속도미달 (글자)", "표준속도초과"};
            final int[] n = {g.sub + g.del + g.ins, g.unclear, g.corr, g.shortfall, g.over ? 1 : 0};
            final double[] each = {crit.examErr, crit.examUnclear, crit.examCorr, crit.examShort, crit.examOver};
            float ry = box.top + 66;
            Ui.text(c, "항목", box.left + 16, ry, 11, Ui.MUTED, true);
            Ui.text(c, "횟수", box.left + bw * 0.68f, ry, 11, Ui.MUTED, true, 1, 0);
            Ui.text(c, "감점", box.right - 16, ry, 11, Ui.MUTED, true, 1, 0);
            for (int i = 0; i < names.length; ++i) {
                ry += 26;
                Ui.text(c, names[i], box.left + 16, ry, 12, n[i] > 0 ? Ui.INK : Ui.MUTED, false);
                Ui.text(c, String.valueOf(n[i]), box.left + bw * 0.68f, ry, 12, n[i] > 0 ? Ui.INK : Ui.MUTED, false, 1, 0);
                Ui.text(c, n[i] > 0 ? "−" + GameView.fmt(n[i] * each[i]) : "0", box.right - 16, ry, 12, n[i] > 0 ? Color.rgb(255, 140, 120) : Ui.MUTED, false, 1, 0);
            }
            Ui.text(c, "기록: 통계 탭 → 검정 기록 (문장별) · 글자별 타일", box.left + 16, box.bottom - 12, 10, Ui.MUTED, false, -1, bw - 32);
        }
        c.restore();
    }
}
