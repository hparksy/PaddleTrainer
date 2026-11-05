package com.parksy.paddletrainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Java port of core/morse.h (keyer, decoder, scoring, exam grading). Keep the two in step.
final class Morse {
    static final String KOCH = "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X";

    // Iambic keyer, advanced one audio sample at a time so element timing is sample-exact.
    static final class Keyer {
        static final int IAMBIC_B = 0, IAMBIC_A = 1, STRAIGHT = 2;
        int mode = IAMBIC_B;
        int dit = 3840;                 // samples per dit = rate * 1.2 / wpm
        boolean ditIn, dahIn;
        char sym;                       // element being sent: '.', '-' or 0 (idle / straight key)
        // Silent presses: a paddle pressed and released without the keyer ever sending its element
        int ditStrays, dahStrays;
        private int n;
        private boolean ditMem, dahMem, ditWas, dahWas, ditPending, dahPending;

        boolean step() {
            if (mode == STRAIGHT) return ditIn || dahIn;
            ditPending |= ditIn && !ditWas;  // a new press owes an element
            dahPending |= dahIn && !dahWas;
            ditWas = ditIn;
            dahWas = dahIn;
            if (n > 0) {
                if (mode == IAMBIC_B) {  // remember the opposite paddle -> it still gets sent after release
                    dahMem |= sym == '.' && dahIn;
                    ditMem |= sym == '-' && ditIn;
                }
                if (--n > 0) return n > dit;  // the last dit of each slot is the inter-element gap
            }
            final char next = sym == '.' ? (dahMem || dahIn ? '-' : ditIn ? '.' : 0)
                            : sym == '-' ? (ditMem || ditIn ? '.' : dahIn ? '-' : 0)
                            : (ditIn ? '.' : dahIn ? '-' : 0);
            ditMem = dahMem = false;
            // the element a press owed is sent now -> paid; released before it could be sent -> silent press
            if (next == '.') ditPending = false;
            if (next == '-') dahPending = false;
            if (ditPending && !ditIn) { ditPending = false; ++ditStrays; }
            if (dahPending && !dahIn) { dahPending = false; ++dahStrays; }
            sym = next;
            n = next == '.' ? 2 * dit : next == '-' ? 4 * dit : 0;
            return n > dit;
        }

        Keyer copy() {
            Keyer k = new Keyer();
            k.mode = mode; k.dit = dit; k.ditIn = ditIn; k.dahIn = dahIn; k.sym = sym;
            k.n = n; k.ditMem = ditMem; k.dahMem = dahMem; k.ditWas = ditWas; k.dahWas = dahWas;
            k.ditPending = ditPending; k.dahPending = dahPending; k.ditStrays = ditStrays; k.dahStrays = dahStrays;
            return k;
        }
    }

    static final Map<String, Character> TABLE = new HashMap<>();
    static {
        String[] t = {".-A", "-...B", "-.-.C", "-..D", ".E", "..-.F", "--.G", "....H", "..I", ".---J", "-.-K", ".-..L",
                      "--M", "-.N", "---O", ".--.P", "--.-Q", ".-.R", "...S", "-T", "..-U", "...-V", ".--W", "-..-X",
                      "-.--Y", "--..Z", "-----0", ".----1", "..---2", "...--3", "....-4", ".....5", "-....6", "--...7",
                      "---..8", "----.9", ".-.-.-.", "--..--,", "..--..?", "-..-./", "-...-="};
        for (String s : t) TABLE.put(s.substring(0, s.length() - 1), s.charAt(s.length() - 1));
    }

    static String patternOf(char c) {
        for (Map.Entry<String, Character> e : TABLE.entrySet())
            if (e.getValue() == c) return e.getKey();
        return "";
    }

    // Farnsworth: characters keyed at charWpm, spacing stretched so the overall speed is effWpm (ARRL formula).
    static final class Timing {
        final double ditMs, charGapMs, wordGapMs;
        Timing(double d, double c, double w) { ditMs = d; charGapMs = c; wordGapMs = w; }
    }
    static Timing farnsworth(double charWpm, double effWpm) {
        final double dit = 1200 / charWpm;
        if (effWpm >= charWpm) return new Timing(dit, 3 * dit, 7 * dit);
        final double ta = (60 * charWpm - 37.2 * effWpm) / (effWpm * charWpm) * 1000;  // total spacing delay per word, ms
        return new Timing(dit, 3 * ta / 19, 7 * ta / 19);
    }

    // Bad habits detected per attempt (bit flags, stored in the attempt log).
    static final int WRONG_CHAR = 1, EXTRA_ELEM = 2, MISSING_ELEM = 4, LATE_RELEASE = 8, GAP_SHORT = 16, GAP_LONG = 32,
            DIT_LONG = 64, DIT_SHORT = 128, DAH_LONG = 256, DAH_SHORT = 512, ELEM_GAP_OFF = 1024, HINT_USED = 2048,
            ELEM_HESITATE = 4096, SPEED_OFF = 8192, SQUEEZE_LATE = 16384, STRAY_PRESS = 32768;

    static String habitName(int h) {
        switch (h) {
        case WRONG_CHAR: return "틀린 글자";
        case EXTRA_ELEM: return "요소가 더 나감 (패들을 너무 오래 누름)";
        case MISSING_ELEM: return "요소가 빠짐 (글자가 중간에 끊김)";
        case LATE_RELEASE: return "패들을 늦게 뗌";
        case GAP_SHORT: return "글자 간격이 짧음 (글자가 붙음)";
        case GAP_LONG: return "글자 간격이 김 (망설임)";
        case DIT_LONG: return "dit가 김";
        case DIT_SHORT: return "dit가 짧음";
        case DAH_LONG: return "dah를 끎";
        case DAH_SHORT: return "dah가 짧음";
        case ELEM_GAP_OFF: return "요소 사이 간격이 고르지 않음";
        case HINT_USED: return "힌트를 보고 침";
        case ELEM_HESITATE: return "요소 사이에서 머뭇거림";
        case SPEED_OFF: return "속도가 기준과 다름";
        case SQUEEZE_LATE: return "스퀴즈 후 반대쪽 패들을 늦게 뗌";
        case STRAY_PRESS: return "소리 안 난 패들 누름 (헛손질)";
        }
        return "";
    }
    // Corrections, after Pierpont "The Art & Skill of Radio-Telegraphy" and W8RIT "Iambic Sending".
    static String habitAdvice(int h) {
        switch (h) {
        case WRONG_CHAR: return "기준 소리(▶ 글자)를 먼저 듣고, 들은 소리를 그대로 흉내 내세요.";
        case EXTRA_ELEM: return "마지막 요소가 소리 나기 시작하면 바로 손을 떼세요.";
        case MISSING_ELEM: return "다음 패들을 소리가 끝나기 전에 미리 대고 있으세요. 쉬면 글자가 끊깁니다.";
        case LATE_RELEASE: return "마지막 요소가 소리 나는 동안 손을 떼세요. 늦으면 키어가 요소를 하나 더 보냅니다.";
        case GAP_SHORT: return "글자 끝에 dah 하나만큼 쉬세요. 간격이 리듬의 핵심이고, 붙으면 다른 글자로 들립니다.";
        case GAP_LONG: return "점·선을 세지 말고 글자를 소리 덩어리로 바로 떠올리세요.";
        case DIT_LONG: return "dit는 톡 치듯 짧게. 기준 막대 끝에서 떼세요.";
        case DIT_SHORT: return "dit도 끝까지 눌러 길이를 채우세요.";
        case DAH_LONG: return "dah를 끌지 마세요. dah는 정확히 dit 3개입니다.";
        case DAH_SHORT: return "dah는 dit 3개 길이까지 채우세요. dit와 확실히 구별돼야 합니다.";
        case ELEM_GAP_OFF: return "요소 사이는 항상 dit 하나 길이만큼만 쉬세요.";
        case HINT_USED: return "힌트를 끄고 소리로 익혀야 속도가 늘어납니다.";
        case ELEM_HESITATE: return "요소 사이는 dit 하나를 넘기지 마세요. 손가락을 양쪽 패들에 항상 대고 있으세요.";
        case SPEED_OFF: return "기준 소리(▶ 글자)를 듣고 그 빠르기에 손을 맞추세요.";
        case SQUEEZE_LATE: return "마지막 요소가 시작되면 그 절반 전에 반대쪽 패들을 떼세요 (예: A가 R로 나가면 dit 패들을 더 빨리).";
        case STRAY_PRESS: return "필요한 패들만 누르세요. 다음 패들은 지금 요소가 끝날 때까지 누른 채로 두고, 툭 건드렸다 떼지 마세요.";
        }
        return "";
    }

    // Pass/scoring criteria; same names and defaults as the desktop app (fields are read by name in Settings).
    static final class Criteria {
        public double passScore = 85, maxRepeat = 3, levelAvg = 90, levelCount = 20, hintFactor = 80;
        public double gapWeight = 2;
        public double elemOk = 20, elemZero = 60;
        public double speedOk = 25, speedZero = 60;
        public double elemGapOk = 30, elemGapZero = 80;
        public double hesitateOk = 30, hesitateZero = 100;
        public double releaseOk = 50, releaseZero = 100;
        public double shortOk = 25, shortZero = 60;
        public double longOk = 100, longZero = 300;
        public double alertWindow = 10, alertCount = 4;  // warn when one habit shows up alertCount times in the last alertWindow tries
        public double examPass = 60;
        public double examErr = 2, examUnclear = 1, examCorr = 1, examShort = 2, examOver = 5;
        public double examUnclearBelow = 60;
        public double examOverPct = 130;

        // ok/zero pairs: zero must lie beyond ok or the score slope divides by zero
        void sanitize() {
            shortZero = Math.max(shortZero, shortOk + 5); longZero = Math.max(longZero, longOk + 5);
            releaseZero = Math.max(releaseZero, releaseOk + 5); hesitateZero = Math.max(hesitateZero, hesitateOk + 5);
            elemZero = Math.max(elemZero, elemOk + 5); elemGapZero = Math.max(elemGapZero, elemGapOk + 5);
            speedZero = Math.max(speedZero, speedOk + 5);
        }
    }

    static final class Verdict {
        int score, habits, worst;
        final List<String> notes = new ArrayList<>();  // worst first: "<habit> — <measured vs reference>"
    }

    static long lround(double x) { return x < 0 ? -Math.round(-x) : Math.round(x); }  // C++ std::lround

    static double partScore(double relErr, double ok, double zero) {
        return Math.max(0, Math.min(1, 1 - (Math.abs(relErr) - ok) / (zero - ok)));
    }

    private static String num(double x) { return Long.toString(lround(x)); }
    private static String pct(double x) { return (x > 0 ? "+" : "") + num(x * 100) + "%"; }
    private static String one(double x) { return String.format(Locale.US, "%.1f", x); }

    private static final class Part {
        final double s, w; final int h; final String what;
        Part(double s, double w, int h, String what) { this.s = s; this.w = w; this.h = h; this.what = what; }
    }

    // Judge one keyed character; see morse.h for the meaning of every argument.
    static Verdict judge(String want, String got, double[] on, double[] gaps, double charGap, double releaseLate,
                         int mode, boolean hint, Timing t, Criteria c) {
        Verdict v = new Verdict();
        if (!got.equals(want)) {
            v.habits = v.worst = WRONG_CHAR;
            v.notes.add(habitName(WRONG_CHAR) + " — " + got + " ≠ " + want);
            if (got.length() > want.length() && got.startsWith(want)) {
                final boolean latch = mode == Keyer.IAMBIC_B && got.charAt(want.length()) != want.charAt(want.length() - 1);
                v.worst = latch ? SQUEEZE_LATE : EXTRA_ELEM;
            } else if (got.length() < want.length() && want.startsWith(got)) {
                v.worst = MISSING_ELEM;
            }
            if (v.worst != WRONG_CHAR) { v.habits |= v.worst; v.notes.add(habitName(v.worst)); }
            return v;
        }

        List<Part> parts = new ArrayList<>();
        if (mode == Keyer.STRAIGHT) {  // the hand makes everything: judge proportions, then speed
            double units = 0, total = 0;
            for (int i = 0; i < on.length; ++i) { units += want.charAt(i) == '.' ? 1 : 3; total += on[i]; }
            for (double g : gaps) { units += 1; total += g; }
            final double u = total / units;  // your own dit length in this character
            for (int i = 0; i < on.length; ++i) {
                final boolean dit = want.charAt(i) == '.';
                final double dev = on[i] / ((dit ? 1 : 3) * u) - 1;
                parts.add(new Part(partScore(dev, c.elemOk / 100, c.elemZero / 100), 1,
                        dit ? (dev > 0 ? DIT_LONG : DIT_SHORT) : (dev > 0 ? DAH_LONG : DAH_SHORT),
                        (i + 1) + "번째 " + (dit ? "dit " : "dah ") + num(on[i]) + "ms = 내 dit의 " + one(on[i] / u) +
                                "배 (기준 " + (dit ? "1" : "3") + "배)"));
            }
            for (int i = 0; i < gaps.length; ++i) {
                final double dev = gaps[i] / u - 1;
                parts.add(new Part(partScore(dev, c.elemGapOk / 100, c.elemGapZero / 100), c.gapWeight, ELEM_GAP_OFF,
                        (i + 1) + "번째 요소 사이 " + num(gaps[i]) + "ms (기준 dit 1개, " + pct(dev) + ")"));
            }
            final double dev = u / t.ditMs - 1;
            parts.add(new Part(partScore(dev, c.speedOk / 100, c.speedZero / 100), 1, SPEED_OFF,
                    "내 dit " + num(u) + "ms / 기준 " + num(t.ditMs) + "ms (" + pct(dev) + ")"));
        } else {  // the keyer makes the elements: judge what the hand controls
            for (int i = 0; i < gaps.length; ++i) {
                final double extra = Math.max(0, gaps[i] / t.ditMs - 1);
                parts.add(new Part(partScore(extra, c.hesitateOk / 100, c.hesitateZero / 100), c.gapWeight, ELEM_HESITATE,
                        (i + 1) + "번째 요소 사이 +" + num(gaps[i] - t.ditMs) + "ms 늦게 다음 패들"));
            }
            if (!Double.isNaN(releaseLate)) {
                final double late = Math.max(0, releaseLate / t.ditMs);
                parts.add(new Part(partScore(late, c.releaseOk / 100, c.releaseZero / 100), 1, LATE_RELEASE,
                        "소리 끝난 뒤 +" + num(Math.max(0, releaseLate)) + "ms에 뗌 (dit의 " + num(late * 100) +
                                "%, 기준 " + num(c.releaseOk) + "% 이내)"));
            }
        }
        if (charGap >= 0) {
            // 3 dits .. the Farnsworth gap is fine; short is measured against 3 dits, long against Farnsworth
            final double stdGap = 3 * t.ditMs;
            if (charGap < stdGap) {
                final double dev = charGap / stdGap - 1;
                parts.add(new Part(partScore(dev, c.shortOk / 100, c.shortZero / 100), c.gapWeight, GAP_SHORT,
                        "글자 간격 " + num(charGap) + "ms / 표준 최소 " + num(stdGap) + "ms = dit 3개 (" + pct(dev) + ")"));
            } else if (charGap > t.charGapMs) {
                final double dev = charGap / t.charGapMs - 1;
                parts.add(new Part(partScore(dev, c.longOk / 100, c.longZero / 100), c.gapWeight, GAP_LONG,
                        "글자 간격 " + num(charGap) + "ms / Farnsworth " + num(t.charGapMs) + "ms (" + pct(dev) + ")"));
            } else {
                parts.add(new Part(1, c.gapWeight, GAP_SHORT, ""));
            }
        }

        double sum = 0, wsum = 0;
        for (Part p : parts) { sum += p.s * p.w; wsum += p.w; }
        double mean = wsum > 0 ? sum / wsum : 1;
        parts.sort((a, b) -> Double.compare((1 - b.s) * b.w, (1 - a.s) * a.w));  // stable, worst first
        for (Part p : parts)
            if (p.s < 1) {
                if (v.worst == 0) v.worst = p.h;
                v.habits |= p.h;
                v.notes.add(habitName(p.h) + " — " + p.what);
            }
        if (hint) {
            mean *= c.hintFactor / 100;
            v.habits |= HINT_USED;
            if (v.worst == 0) v.worst = HINT_USED;
            v.notes.add(habitName(HINT_USED) + " — 점수 ×" + one(c.hintFactor / 100));
        }
        v.score = (int) lround(100 * mean);
        return v;
    }

    // Silent presses made while keying this character: the letter may be right, but the hand motion failed.
    static void markStrays(Verdict v, int ditStrays, int dahStrays) {
        if (ditStrays + dahStrays == 0) return;
        v.habits |= STRAY_PRESS;
        if ((v.habits & WRONG_CHAR) == 0) v.worst = STRAY_PRESS;
        v.notes.add(0, habitName(STRAY_PRESS) + " — dit 패들 " + ditStrays + "회, dah 패들 " + dahStrays + "회 (소리는 안 났지만 손동작 실패)");
    }

    // How often a letter comes up, from its last tries; fails = wrong, under the pass mark, or a stray press.
    // Average 95 -> 1.0, 85 -> 1.3, 70 -> 2.1, 50 -> 4, 0 -> 13 (squared so weak ones stand out), plus 2 per recent fail (5 at most).
    static double letterWeight(int tries, double scoreSum, int fails) {
        if (tries == 0) return 4;  // never practised: extra weight
        final double d = 1 - scoreSum / tries / 100;
        return 1 + 12 * d * d + 2 * Math.min(fails, 5);
    }

    // The habit that keeps coming back: most frequent in the last alertWindow attempts (newest last), if it showed up
    // at least alertCount times; 0 = nothing repeating. Specific habits win ties over "wrong letter"; the hint is ignored.
    static int repeatedHabit(List<Integer> recent, Criteria c, int[] count) {
        final int from = recent.size() - Math.min(recent.size(), (int) Math.max(1, c.alertWindow));
        int best = 0, bestN = 0;
        for (int h = STRAY_PRESS; h >= WRONG_CHAR; h >>= 1) {
            if (h == HINT_USED) continue;
            int k = 0;
            for (int i = from; i < recent.size(); ++i) if ((recent.get(i) & h) != 0) ++k;
            if (k > bestN) { best = h; bestN = k; }
        }
        if (bestN < c.alertCount) return 0;
        if (count != null) count[0] = bestN;
        return best;
    }

    // ---- Exam grading ----
    static final class ExamChar {
        final char c; final int score, habits;
        ExamChar(char c, int score, int habits) { this.c = c; this.score = score; this.habits = habits; }
    }
    static final class SentenceGrade {
        int sub, del, ins, unclear, unsent, score;
        String sent = "";
        boolean reached;
    }
    static final class LetterGrade {
        char want; boolean reached, ok; int score, habits;
        LetterGrade(char want, boolean reached, boolean ok, int score, int habits) {
            this.want = want; this.reached = reached; this.ok = ok; this.score = score; this.habits = habits;
        }
    }
    static final class ExamGrade {
        int sub, del, ins, unclear, corr, shortfall, score;
        boolean over;
        int end;
        final List<SentenceGrade> sentences = new ArrayList<>();
        final List<LetterGrade> letters = new ArrayList<>();
    }

    // Aligns what was sent to the target text (edit distance, free tail on the target) and grades it like the old exam sheet.
    static ExamGrade gradeExam(List<String> sentences, List<ExamChar> sent, int corrections, int required,
                               double elapsedMin, double ratePerMin, Criteria c) {
        StringBuilder tb = new StringBuilder();
        List<Integer> sentOf = new ArrayList<>();
        List<Boolean> wordStart = new ArrayList<>();
        for (int k = 0; k < sentences.size(); ++k) {
            boolean start = true;
            for (char ch : sentences.get(k).toCharArray()) {
                if (ch == ' ') { start = true; continue; }
                wordStart.add(start && !sentOf.isEmpty() && sentOf.get(sentOf.size() - 1) == k);
                tb.append(ch);
                sentOf.add(k);
                start = false;
            }
        }
        final String T = tb.toString();
        final int n = T.length(), m = sent.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; ++i) dp[i][0] = i;
        for (int j = 0; j <= m; ++j) dp[0][j] = j;
        for (int i = 1; i <= n; ++i)
            for (int j = 1; j <= m; ++j)
                dp[i][j] = Math.min(dp[i - 1][j - 1] + (T.charAt(i - 1) != sent.get(j - 1).c ? 1 : 0),
                                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
        int end = 0;
        for (int i = 0; i <= n; ++i)
            if (dp[i][m] <= dp[end][m]) end = i;  // ties: the furthest point reached

        ExamGrade g = new ExamGrade();
        g.end = end;
        for (int k = 0; k < sentences.size(); ++k) g.sentences.add(new SentenceGrade());
        for (int i = 0; i < n; ++i) g.letters.add(new LetterGrade(T.charAt(i), i < end, false, 0, 0));
        StringBuilder[] pieces = new StringBuilder[sentences.size()];
        for (int k = 0; k < pieces.length; ++k) pieces[k] = new StringBuilder();
        int i = end, j = m;
        while (i > 0 || j > 0) {  // backtrace; prefer match/substitution, then deletion, then insertion
            if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + (T.charAt(i - 1) != sent.get(j - 1).c ? 1 : 0)) {
                --i; --j;
                SentenceGrade s = g.sentences.get(sentOf.get(i));
                final ExamChar e = sent.get(j);
                final boolean ok = T.charAt(i) == e.c;
                pieces[sentOf.get(i)].insert(0, (wordStart.get(i) ? " " : "") + e.c);
                if (ok) {
                    final int unclear = e.score < c.examUnclearBelow ? 1 : 0;
                    g.letters.set(i, new LetterGrade(T.charAt(i), true, true, e.score, e.habits));
                    g.unclear += unclear; s.unclear += unclear;
                } else {
                    g.letters.set(i, new LetterGrade(T.charAt(i), true, false, 0, WRONG_CHAR));
                    ++g.sub; ++s.sub;
                }
            } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                --i;
                g.letters.set(i, new LetterGrade(T.charAt(i), true, false, 0, MISSING_ELEM));
                ++g.del; ++g.sentences.get(sentOf.get(i)).del;
                pieces[sentOf.get(i)].insert(0, wordStart.get(i) ? " _" : "_");
            } else {
                --j;
                final int k = i > 0 ? sentOf.get(i - 1) : 0;
                ++g.ins; ++g.sentences.get(k).ins;
                pieces[k].insert(0, sent.get(j).c);
            }
        }
        for (int k = end; k < n; ++k) ++g.sentences.get(sentOf.get(k)).unsent;
        for (int k = 0; k < sentences.size(); ++k) {
            SentenceGrade s = g.sentences.get(k);
            s.sent = pieces[k].toString();
            s.reached = !s.sent.isEmpty();
            s.score = Math.max(0, (int) lround(100 - c.examErr * (s.sub + s.del + s.ins) - c.examUnclear * s.unclear - c.examShort * s.unsent));
        }
        g.corr = corrections;
        g.shortfall = Math.max(0, required - m);
        g.over = elapsedMin > 0 && m / elapsedMin > ratePerMin * c.examOverPct / 100;
        g.score = Math.max(0, (int) lround(100 - c.examErr * (g.sub + g.del + g.ins) - c.examUnclear * g.unclear -
                c.examCorr * g.corr - c.examShort * g.shortfall - (g.over ? c.examOver : 0)));
        return g;
    }

    // Turns keyed elements + silences into text. Straight-key elements (sym 0) are classified by length.
    static final class Decoder {
        double ditMs = 80, spaceMs = 560;  // spaceMs: silence that counts as a word space
        final StringBuilder pattern = new StringBuilder(), text = new StringBuilder();
        private boolean spaced = true;

        void element(double ms, char sym) {
            pattern.append(sym != 0 ? sym : ms < 2 * ditMs ? '.' : '-');
            spaced = false;
        }
        // ms = silence since the last element ended; returns the finished character or 0
        char gap(double ms) {
            char c = 0;
            if (pattern.length() > 0 && ms > 2 * ditMs) {
                Character ch = TABLE.get(pattern.toString());
                c = ch == null ? '*' : ch;
                text.append(c);
                pattern.setLength(0);
            }
            if (!spaced && pattern.length() == 0 && ms > spaceMs) {
                text.append(' ');
                spaced = true;
            }
            return c;
        }
    }

    private Morse() {}
}
