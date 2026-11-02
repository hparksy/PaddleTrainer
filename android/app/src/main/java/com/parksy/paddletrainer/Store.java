package com.parksy.paddletrainer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Practice and exam records, kept as the same CSV files the desktop app writes (attempts / exams / sentences.csv).
final class Store {
    static final class Rec {  // one judged character
        final long time; final char ch; final boolean ok; final int score, mode, wpm, eff, habits, kind;  // kind: 0 practice, 1 exam
        Rec(long time, char ch, boolean ok, int score, int mode, int wpm, int eff, int habits, int kind) {
            this.time = time; this.ch = ch; this.ok = ok; this.score = score; this.mode = mode; this.wpm = wpm; this.eff = eff;
            this.habits = habits; this.kind = kind;
        }
    }
    static final class ExamRec {  // one exam; `time` (exam start) links its sentences
        long time; String grade; int rate, minutes, sent, sub, del, ins, unclear, corr, shortfall, over, score, pass;
    }
    static final class SentRec {
        long time; int idx; String target, sent; int sub, del, ins, unclear, unsent, score;
    }

    final File dir;
    final List<Rec> recs = new ArrayList<>();
    final List<ExamRec> exams = new ArrayList<>();
    final List<SentRec> sents = new ArrayList<>();

    Store(File dir) { this.dir = dir; load(); }

    private List<String[]> read(String name, int minCols) {
        List<String[]> rows = new ArrayList<>();
        File f = new File(dir, name);
        if (!f.exists()) return rows;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            r.readLine();  // header
            for (String line; (line = r.readLine()) != null; ) {
                String[] c = line.trim().split(",", -1);
                if (c.length >= minCols) rows.add(c);
            }
        } catch (IOException ignored) {}
        return rows;
    }
    private static int i(String s) { try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; } }
    private static long l(String s) { try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; } }

    private void load() {
        for (String[] c : read("attempts.csv", 8))
            if (!c[1].isEmpty())
                recs.add(new Rec(l(c[0]), c[1].charAt(0), c[2].equals("1"), i(c[3]), i(c[4]), i(c[5]), i(c[6]), i(c[7]), c.length > 8 ? i(c[8]) : 0));
        for (String[] c : read("exams.csv", 14)) {
            ExamRec e = new ExamRec();
            e.time = l(c[0]); e.grade = c[1]; e.rate = i(c[2]); e.minutes = i(c[3]); e.sent = i(c[4]); e.sub = i(c[5]); e.del = i(c[6]);
            e.ins = i(c[7]); e.unclear = i(c[8]); e.corr = i(c[9]); e.shortfall = i(c[10]); e.over = i(c[11]); e.score = i(c[12]); e.pass = i(c[13]);
            exams.add(e);
        }
        for (String[] c : read("sentences.csv", 10)) {
            SentRec s = new SentRec();
            s.time = l(c[0]); s.idx = i(c[1]); s.target = c[2]; s.sent = c[3]; s.sub = i(c[4]); s.del = i(c[5]); s.ins = i(c[6]);
            s.unclear = i(c[7]); s.unsent = i(c[8]); s.score = i(c[9]);
            sents.add(s);
        }
    }

    // appends one CSV line (header first if the file is new); commas inside fields become '·'
    void append(String name, String header, Object... fields) {
        File f = new File(dir, name);
        final boolean fresh = !f.exists();
        StringBuilder sb = new StringBuilder();
        if (fresh) sb.append(header).append('\n');
        for (int k = 0; k < fields.length; ++k) sb.append(k > 0 ? "," : "").append(String.valueOf(fields[k]).replace(',', '·'));
        sb.append('\n');
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        } catch (IOException ignored) {}
    }

    void save(Rec r) {
        recs.add(r);
        append("attempts.csv", "time,char,ok,score,mode,wpm,eff,habits,kind",
                r.time, r.ch, r.ok ? 1 : 0, r.score, r.mode, r.wpm, r.eff, r.habits, r.kind);
    }
    void save(SentRec s) {
        sents.add(s);
        append("sentences.csv", "time,idx,target,sent,sub,del,ins,unclear,unsent,score",
                s.time, s.idx, s.target, s.sent, s.sub, s.del, s.ins, s.unclear, s.unsent, s.score);
    }
    void save(ExamRec e) {
        exams.add(e);
        append("exams.csv", "time,grade,rate,minutes,sent,sub,del,ins,unclear,corr,shortfall,over,score,pass",
                e.time, e.grade, e.rate, e.minutes, e.sent, e.sub, e.del, e.ins, e.unclear, e.corr, e.shortfall, e.over, e.score, e.pass);
    }
}
