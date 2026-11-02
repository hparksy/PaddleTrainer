package com.parksy.paddletrainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.parksy.paddletrainer.Morse.*;

// Self-check for Morse.java, same cases as ../../../../../../../../test_morse.cpp. Run by android/build_apk.bat.
public class MorseTest {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAIL: " + what); ++fails; } }

    // Runs a copy of the keyer; paddles are released at `releaseAt`. Returns the elements keyed and the first tone length.
    static int len;
    static String keyed(Keyer k0, int samples, int releaseAt) {
        Keyer k = k0.copy();
        StringBuilder out = new StringBuilder();
        boolean was = false;
        len = 0;
        for (int i = 0; i < samples; ++i) {
            if (i == releaseAt) k.ditIn = k.dahIn = false;
            final boolean on = k.step();
            if (on && !was) out.append(k.sym);
            if (on && out.length() == 1) ++len;
            was = on;
        }
        return out.toString();
    }
    static double[] d(double... x) { return x; }
    static List<ExamChar> ex(String s, int score) {
        List<ExamChar> out = new ArrayList<>();
        for (char ch : s.toCharArray()) out.add(new ExamChar(ch, score, 0));
        return out;
    }

    public static void main(String[] args) {
        Keyer k = new Keyer();
        k.dit = 10;
        k.ditIn = true;
        check(keyed(k, 100, 1000).equals(".....") && len == 10, "held dit");
        k.ditIn = false; k.dahIn = true;
        check(keyed(k, 80, 1000).equals("--") && len == 30, "held dah");
        k.ditIn = k.dahIn = true;
        check(keyed(k, 200, 5).equals(".-"), "mode B memory");
        k.mode = Keyer.IAMBIC_A;
        check(keyed(k, 200, 5).equals("."), "mode A");
        check(keyed(k, 200, 85).equals(".-.-"), "squeeze alternates");

        Decoder dec = new Decoder();
        dec.ditMs = 60; dec.spaceMs = 300;
        dec.element(60, '.'); dec.element(180, '-');
        check(dec.gap(100) == 0, "inside char");
        check(dec.gap(200) == 'A', "A");
        dec.gap(400);
        dec.element(50, (char) 0); dec.element(200, (char) 0); dec.element(40, (char) 0);
        check(dec.gap(200) == 'R', "straight R");
        check(dec.text.toString().equals("A R"), "text");
        check(patternOf('Q').equals("--.-"), "Q");

        final Timing t = farnsworth(20, 20);
        check(t.ditMs == 60 && t.charGapMs == 180, "timing");
        final Timing f = farnsworth(18, 10);
        check(lround(f.charGapMs) == 621 && lround(f.ditMs) == 67, "farnsworth");

        final int S = Keyer.STRAIGHT, B = Keyer.IAMBIC_B;
        final Criteria c = new Criteria();
        Verdict v = judge(".-", ".-", d(60, 180), d(60), 180, -1, S, false, t, c);
        check(v.score == 100 && v.habits == 0 && v.worst == 0, "perfect");
        v = judge(".-", ".-", d(90, 180), d(60), 180, -1, S, false, t, c);
        check(v.score == 94 && v.worst == DIT_LONG, "dit long " + v.score);
        v = judge(".-", ".-", d(70, 200), d(66), 200, -1, S, false, t, c);
        check(v.score == 100 && v.habits == 0, "hand variation");
        v = judge(".-", ".-", d(60, 180), d(60), 180, -1, S, true, t, c);
        check(v.score == 80 && v.worst == HINT_USED, "hint");
        v = judge(".-", ".-", d(60, 180), d(60), -1, 30, B, false, t, c);
        check(v.score == 100 && v.habits == 0, "release in time");
        v = judge(".-", ".-", d(60, 180), d(60), -1, 60, B, false, t, c);
        check(v.score == 67 && v.worst == LATE_RELEASE, "late release " + v.score);
        v = judge(".-", ".-", d(60, 180), d(120), -1, -5, B, false, t, c);
        check(v.score == 33 && v.worst == ELEM_HESITATE, "hesitate " + v.score);
        v = judge(".-", ".-", d(60, 180), d(60), 400, -5, B, false, t, c);
        check(v.score == 71 && v.worst == GAP_LONG, "gap long " + v.score);
        v = judge(".-", ".-", d(60, 180), d(60), 90, -5, B, false, t, c);
        check(v.score == 71 && v.worst == GAP_SHORT && v.notes.size() == 1, "gap short " + v.score);
        v = judge(".-", ".-", d(67, 200), d(67), 350, -5, B, false, f, c);
        check(v.score == 100 && v.habits == 0, "farnsworth gap ok");
        v = judge(".-", ".-", d(67, 200), d(67), 120, -5, B, false, f, c);
        check(v.worst == GAP_SHORT && v.score < 85, "farnsworth gap short");
        Criteria lax = new Criteria();
        lax.shortZero = 100;
        check(judge(".-", ".-", d(60, 180), d(60), 90, -5, B, false, t, lax).score > 71, "criteria honoured");
        v = judge(".-", ".", d(), d(), -1, -1, B, false, t, c);
        check(v.score == 0 && v.worst == MISSING_ELEM, "missing");
        v = judge(".-", ".-.", d(), d(), -1, -1, B, false, t, c);
        check(v.worst == SQUEEZE_LATE, "squeeze late");
        v = judge("..", "...", d(), d(), -1, -1, B, false, t, c);
        check(v.worst == EXTRA_ELEM, "extra");

        final List<String> text = Arrays.asList("THE SUN", "IS UP");
        ExamGrade eg = gradeExam(text, ex("THESUNISUP", 100), 0, 10, 2, 5, c);
        check(eg.score == 100 && eg.end == 10 && eg.sentences.get(1).sent.equals("IS UP") && eg.letters.get(9).ok, "exam perfect");
        eg = gradeExam(text, ex("TXESNISUUP", 100), 1, 10, 2, 5, c);
        check(eg.sub == 1 && eg.del == 1 && eg.ins == 1 && eg.corr == 1, "exam errors");
        check(eg.score == 100 - 2 * 3 - 1, "exam score");
        check(eg.sentences.get(0).sub == 1 && eg.sentences.get(0).del == 1 && eg.sentences.get(1).ins == 1, "exam per sentence");
        eg = gradeExam(text, ex("THESU", 40), 0, 10, 1, 5, c);
        check(eg.end == 5 && eg.unclear == 5 && eg.shortfall == 5 && !eg.sentences.get(1).reached && !eg.letters.get(5).reached, "exam early");
        check(eg.score == 100 - 5 - 2 * 5, "exam early score");
        eg = gradeExam(text, ex("THESUNISUP", 100), 0, 10, 1, 5, c);
        check(eg.over && eg.score == 95, "exam over");

        System.out.println(fails > 0 ? fails + " failed" : "ok");
        System.exit(fails > 0 ? 1 : 0);
    }
}
