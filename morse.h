#pragma once
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <map>
#include <string>
#include <vector>

// Iambic keyer, advanced one audio sample at a time so element timing is sample-exact.
struct Keyer {
    enum Mode { IambicB, IambicA, Straight };
    Mode mode = IambicB;
    int dit = 3840;                 // samples per dit = rate * 1.2 / wpm
    bool ditIn = false, dahIn = false;
    char sym = 0;                   // element being sent: '.', '-' or 0 (idle / straight key)

    bool step() {
        if (mode == Straight) return ditIn || dahIn;
        if (n > 0) {
            if (mode == IambicB) {  // remember the opposite paddle -> it still gets sent after release
                dahMem |= sym == '.' && dahIn;
                ditMem |= sym == '-' && ditIn;
            }
            if (--n > 0) return n > dit;  // the last dit of each slot is the inter-element gap
        }
        const char next = sym == '.' ? (dahMem || dahIn ? '-' : ditIn ? '.' : 0)
                        : sym == '-' ? (ditMem || ditIn ? '.' : dahIn ? '-' : 0)
                        : (ditIn ? '.' : dahIn ? '-' : 0);
        ditMem = dahMem = false;
        sym = next;
        n = next == '.' ? 2 * dit : next == '-' ? 4 * dit : 0;
        return n > dit;
    }

private:
    int n = 0;
    bool ditMem = false, dahMem = false;
};

inline const std::map<std::string, char> &morseTable() {
    static const std::map<std::string, char> t = {
        {".-", 'A'}, {"-...", 'B'}, {"-.-.", 'C'}, {"-..", 'D'}, {".", 'E'}, {"..-.", 'F'}, {"--.", 'G'},
        {"....", 'H'}, {"..", 'I'}, {".---", 'J'}, {"-.-", 'K'}, {".-..", 'L'}, {"--", 'M'}, {"-.", 'N'},
        {"---", 'O'}, {".--.", 'P'}, {"--.-", 'Q'}, {".-.", 'R'}, {"...", 'S'}, {"-", 'T'}, {"..-", 'U'},
        {"...-", 'V'}, {".--", 'W'}, {"-..-", 'X'}, {"-.--", 'Y'}, {"--..", 'Z'},
        {"-----", '0'}, {".----", '1'}, {"..---", '2'}, {"...--", '3'}, {"....-", '4'},
        {".....", '5'}, {"-....", '6'}, {"--...", '7'}, {"---..", '8'}, {"----.", '9'},
        {".-.-.-", '.'}, {"--..--", ','}, {"..--..", '?'}, {"-..-.", '/'}, {"-...-", '='}};
    return t;
}

inline std::string patternOf(char c) {
    for (auto &[p, ch] : morseTable())
        if (ch == c) return p;
    return {};
}

// Farnsworth: characters keyed at charWpm, spacing stretched so the overall speed is effWpm (ARRL formula).
struct Timing {
    double ditMs, charGapMs, wordGapMs;
};
inline Timing farnsworth(double charWpm, double effWpm) {
    const double dit = 1200 / charWpm;
    if (effWpm >= charWpm) return {dit, 3 * dit, 7 * dit};
    const double ta = (60 * charWpm - 37.2 * effWpm) / (effWpm * charWpm) * 1000;  // total spacing delay per word, ms
    return {dit, 3 * ta / 19, 7 * ta / 19};
}

// Bad habits detected per attempt (bit flags, stored in the attempt log).
enum Habit : unsigned {
    WrongChar = 1, ExtraElem = 2, MissingElem = 4, LateRelease = 8, GapShort = 16, GapLong = 32,
    DitLong = 64, DitShort = 128, DahLong = 256, DahShort = 512, ElemGapOff = 1024, HintUsed = 2048,
    ElemHesitate = 4096, SpeedOff = 8192, SqueezeLate = 16384,
};
inline const char *habitName(unsigned h) {
    switch (h) {
    case WrongChar: return "틀린 글자";
    case ExtraElem: return "요소가 더 나감 (패들을 너무 오래 누름)";
    case MissingElem: return "요소가 빠짐 (글자가 중간에 끊김)";
    case LateRelease: return "패들을 늦게 뗌";
    case GapShort: return "글자 간격이 짧음 (글자가 붙음)";
    case GapLong: return "글자 간격이 김 (망설임)";
    case DitLong: return "dit가 김";
    case DitShort: return "dit가 짧음";
    case DahLong: return "dah를 끎";
    case DahShort: return "dah가 짧음";
    case ElemGapOff: return "요소 사이 간격이 고르지 않음";
    case HintUsed: return "힌트를 보고 침";
    case ElemHesitate: return "요소 사이에서 머뭇거림";
    case SpeedOff: return "속도가 기준과 다름";
    case SqueezeLate: return "스퀴즈 후 반대쪽 패들을 늦게 뗌";
    }
    return "";
}
// Corrections, after Pierpont "The Art & Skill of Radio-Telegraphy" (spacing first, sound not counting)
// and W8RIT "Iambic Sending" (always touching, no gap over one dit, release before Mode B latches).
inline const char *habitAdvice(unsigned h) {
    switch (h) {
    case WrongChar: return "기준 소리(Space)를 먼저 듣고, 들은 소리를 그대로 흉내 내세요.";
    case ExtraElem: return "마지막 요소가 소리 나기 시작하면 바로 손을 떼세요.";
    case MissingElem: return "다음 패들을 소리가 끝나기 전에 미리 대고 있으세요. 쉬면 글자가 끊깁니다.";
    case LateRelease: return "마지막 요소가 소리 나는 동안 손을 떼세요. 늦으면 키어가 요소를 하나 더 보냅니다.";
    case GapShort: return "글자 끝에 dah 하나만큼 쉬세요. 간격이 리듬의 핵심이고, 붙으면 다른 글자로 들립니다.";
    case GapLong: return "점·선을 세지 말고 글자를 소리 덩어리로 바로 떠올리세요.";
    case DitLong: return "dit는 톡 치듯 짧게. 기준 막대 끝에서 떼세요.";
    case DitShort: return "dit도 끝까지 눌러 길이를 채우세요.";
    case DahLong: return "dah를 끌지 마세요. dah는 정확히 dit 3개입니다.";
    case DahShort: return "dah는 dit 3개 길이까지 채우세요. dit와 확실히 구별돼야 합니다.";
    case ElemGapOff: return "요소 사이는 항상 dit 하나 길이만큼만 쉬세요.";
    case HintUsed: return "힌트를 끄고 소리로 익혀야 속도가 늘어납니다.";
    case ElemHesitate: return "요소 사이는 dit 하나를 넘기지 마세요. 손가락을 양쪽 패들에 항상 대고 있으세요.";
    case SpeedOff: return "기준 소리(Space)를 듣고 그 빠르기에 손을 맞추세요.";
    case SqueezeLate: return "마지막 요소가 시작되면 그 절반 전에 반대쪽 패들을 떼세요 (예: A가 R로 나가면 dit 패들을 더 빨리).";
    }
    return "";
}

// Pass/scoring criteria. Every tolerance is "% off the reference": inside `ok` = full marks,
// falling linearly to zero at `zero`. Defaults follow what a listener (or decoder) still copies cleanly.
struct Criteria {
    double passScore = 85, maxRepeat = 3, levelAvg = 90, levelCount = 20, hintFactor = 80;
    double gapWeight = 2;                        // Pierpont: spacing matters more than the marks
    double elemOk = 20, elemZero = 60;           // straight: element length vs. your own dit (proportion)
    double speedOk = 25, speedZero = 60;         // straight: your dit vs. the reference dit
    double elemGapOk = 30, elemGapZero = 80;     // straight: gap inside a character vs. one dit
    double hesitateOk = 30, hesitateZero = 100;  // iambic: extra silence between elements, % of a dit
    double releaseOk = 50, releaseZero = 100;    // iambic: release after the last tone, % of a dit
    double shortOk = 25, shortZero = 60;         // character gap too short (letters run together)
    double longOk = 50, longZero = 150;          // character gap too long (hesitation)
    // Exam, after the old Korean amateur telegraphy practical (plain English, N chars/min for 3 min) and its
    // sending score sheet: 오자·탈자·용자 / 자호불명료 / 정정 / 기준속도미달 / 표준속도초과. The sheet's point values
    // were never published, so these deductions are defaults to tune.
    double examPass = 60;                        // KCA: 60 points to pass
    double examErr = 2, examUnclear = 1, examCorr = 1, examShort = 2, examOver = 5;  // points per occurrence
    double examUnclearBelow = 60;                // rhythm score under this = 자호불명료
    double examOverPct = 130;                    // sending faster than this % of the required rate = 표준속도초과
};

struct Verdict {
    int score = 0;          // 0..100
    unsigned habits = 0;    // Habit flags
    unsigned worst = 0;     // habit that cost the most points (0 = none)
    std::vector<std::string> notes;  // worst first: "<habit> — <measured vs reference>"
};

// Per-part score: full marks while |relErr| <= ok, falling linearly to zero at |relErr| >= zero.
inline double partScore(double relErr, double ok, double zero) {
    return std::clamp(1 - (std::fabs(relErr) - ok) / (zero - ok), 0.0, 1.0);
}

// Judge one keyed character. on/gaps: element lengths and the gaps between them (ms).
// charGap: silence before this character (ms, < 0 = not measurable). releaseLate: paddles all released
// this long after the last tone ended (ms, < 0 = released in time, NaN = unknown; ignored for straight key).
inline Verdict judge(const std::string &want, const std::string &got, const std::vector<double> &on,
                     const std::vector<double> &gaps, double charGap, double releaseLate, Keyer::Mode mode, bool hint,
                     const Timing &t, const Criteria &c = {}) {
    Verdict v;
    auto num = [](double x) { return std::to_string(int(std::lround(x))); };
    auto pct = [&](double x) { return (x > 0 ? "+" : "") + num(x * 100) + "%"; };
    auto one = [](double x) { char b[16]; std::snprintf(b, sizeof b, "%.1f", x); return std::string(b); };
    if (got != want) {
        v.habits = v.worst = WrongChar;
        v.notes.push_back(std::string(habitName(WrongChar)) + " — " + got + " ≠ " + want);
        if (got.size() > want.size() && got.compare(0, want.size(), want) == 0) {
            // Mode B sends the opposite element if that paddle is still held past the middle of the last one
            const bool latch = mode == Keyer::IambicB && got[want.size()] != want.back();
            v.worst = latch ? SqueezeLate : ExtraElem;
        } else if (got.size() < want.size() && want.compare(0, got.size(), got) == 0) {
            v.worst = MissingElem;
        }
        if (v.worst != WrongChar) { v.habits |= v.worst; v.notes.push_back(habitName(v.worst)); }
        return v;
    }

    struct Part { double s, w; unsigned h; std::string what; };
    std::vector<Part> parts;
    auto add = [&](double s, double w, unsigned h, const std::string &what) { parts.push_back({s, w, h, what}); };
    if (mode == Keyer::Straight) {  // the hand makes everything: judge proportions, then speed
        double units = 0, total = 0;
        for (size_t i = 0; i < on.size(); ++i) { units += want[i] == '.' ? 1 : 3; total += on[i]; }
        for (double g : gaps) { units += 1; total += g; }
        const double u = total / units;  // your own dit length in this character
        for (size_t i = 0; i < on.size(); ++i) {
            const bool dit = want[i] == '.';
            const double dev = on[i] / ((dit ? 1 : 3) * u) - 1;
            add(partScore(dev, c.elemOk / 100, c.elemZero / 100), 1,
                dit ? (dev > 0 ? DitLong : DitShort) : (dev > 0 ? DahLong : DahShort),
                std::to_string(i + 1) + "번째 " + (dit ? "dit " : "dah ") + num(on[i]) + "ms = 내 dit의 " +
                    one(on[i] / u) + "배 (기준 " + (dit ? "1" : "3") + "배)");
        }
        for (size_t i = 0; i < gaps.size(); ++i) {
            const double dev = gaps[i] / u - 1;
            add(partScore(dev, c.elemGapOk / 100, c.elemGapZero / 100), c.gapWeight, ElemGapOff,
                std::to_string(i + 1) + "번째 요소 사이 " + num(gaps[i]) + "ms (기준 dit 1개, " + pct(dev) + ")");
        }
        const double dev = u / t.ditMs - 1;
        add(partScore(dev, c.speedOk / 100, c.speedZero / 100), 1, SpeedOff,
            "내 dit " + num(u) + "ms / 기준 " + num(t.ditMs) + "ms (" + pct(dev) + ")");
    } else {  // the keyer makes the elements: judge what the hand controls
        for (size_t i = 0; i < gaps.size(); ++i) {
            const double extra = std::max(0.0, gaps[i] / t.ditMs - 1);
            add(partScore(extra, c.hesitateOk / 100, c.hesitateZero / 100), c.gapWeight, ElemHesitate,
                std::to_string(i + 1) + "번째 요소 사이 +" + num(gaps[i] - t.ditMs) + "ms 늦게 다음 패들");
        }
        if (!std::isnan(releaseLate)) {
            // the keyer re-reads the paddles one dit after the tone ends (~20 ms audio buffer is inside this number)
            const double late = std::max(0.0, releaseLate / t.ditMs);
            add(partScore(late, c.releaseOk / 100, c.releaseZero / 100), 1, LateRelease,
                "소리 끝난 뒤 +" + num(std::max(0.0, releaseLate)) + "ms에 뗌 (dit의 " + num(late * 100) +
                    "%, 기준 " + num(c.releaseOk) + "% 이내)");
        }
    }
    if (charGap >= 0) {
        // Anything from the standard 3-dit gap up to the Farnsworth gap is fine. Letters only run together
        // below the standard gap, so "too short" is measured against 3 dits, "too long" against Farnsworth.
        const double stdGap = 3 * t.ditMs;
        if (charGap < stdGap) {
            const double dev = charGap / stdGap - 1;
            add(partScore(dev, c.shortOk / 100, c.shortZero / 100), c.gapWeight, GapShort,
                "글자 간격 " + num(charGap) + "ms / 표준 최소 " + num(stdGap) + "ms = dit 3개 (" + pct(dev) + ")");
        } else if (charGap > t.charGapMs) {
            const double dev = charGap / t.charGapMs - 1;
            add(partScore(dev, c.longOk / 100, c.longZero / 100), c.gapWeight, GapLong,
                "글자 간격 " + num(charGap) + "ms / Farnsworth " + num(t.charGapMs) + "ms (" + pct(dev) + ")");
        } else {
            add(1, c.gapWeight, GapShort, "");
        }
    }

    double sum = 0, wsum = 0;
    for (auto &p : parts) { sum += p.s * p.w; wsum += p.w; }
    double mean = wsum > 0 ? sum / wsum : 1;
    std::stable_sort(parts.begin(), parts.end(), [](const Part &a, const Part &b) { return (1 - a.s) * a.w > (1 - b.s) * b.w; });
    for (auto &p : parts)
        if (p.s < 1) {
            if (!v.worst) v.worst = p.h;
            v.habits |= p.h;
            v.notes.push_back(std::string(habitName(p.h)) + " — " + p.what);
        }
    if (hint) {
        mean *= c.hintFactor / 100;
        v.habits |= HintUsed;
        if (!v.worst) v.worst = HintUsed;
        v.notes.push_back(std::string(habitName(HintUsed)) + " — 점수 ×" + one(c.hintFactor / 100));
    }
    v.score = int(std::lround(100 * mean));
    return v;
}

// ---- Exam grading ---------------------------------------------------------------------------------------
struct ExamChar { char c; int score; unsigned habits; };  // one sent letter with its rhythm verdict
struct SentenceGrade {
    int sub = 0, del = 0, ins = 0, unclear = 0, unsent = 0, score = 0;
    std::string sent;      // what was sent for this sentence, spaced like the target
    bool reached = false;  // at least one of its letters was sent
};
struct LetterGrade { char want; bool reached, ok; int score; unsigned habits; };  // per target letter
struct ExamGrade {
    int sub = 0, del = 0, ins = 0, unclear = 0, corr = 0, shortfall = 0, score = 0;
    bool over = false;
    int end = 0;                         // target letters covered by what was sent
    std::vector<SentenceGrade> sentences;
    std::vector<LetterGrade> letters;    // every target letter, in order (spaces skipped)
};

// Aligns what was sent to the target text (edit distance, free tail on the target) and grades it like the
// old exam sheet. sentences: target text, letters and spaces. required: letters needed in the exam time.
inline ExamGrade gradeExam(const std::vector<std::string> &sentences, const std::vector<ExamChar> &sent, int corrections,
                           int required, double elapsedMin, double ratePerMin, const Criteria &c) {
    std::string T;
    std::vector<int> sentOf;
    std::vector<bool> wordStart;
    for (size_t k = 0; k < sentences.size(); ++k) {
        bool start = true;
        for (char ch : sentences[k]) {
            if (ch == ' ') { start = true; continue; }
            wordStart.push_back(start && !sentOf.empty() && sentOf.back() == int(k));  // new word inside a sentence
            T += ch;
            sentOf.push_back(int(k));
            start = false;
        }
    }
    const int n = int(T.size()), m = int(sent.size());
    std::vector<std::vector<int>> dp(n + 1, std::vector<int>(m + 1));
    for (int i = 0; i <= n; ++i) dp[i][0] = i;
    for (int j = 0; j <= m; ++j) dp[0][j] = j;
    for (int i = 1; i <= n; ++i)
        for (int j = 1; j <= m; ++j)
            dp[i][j] = std::min({dp[i - 1][j - 1] + (T[i - 1] != sent[j - 1].c), dp[i - 1][j] + 1, dp[i][j - 1] + 1});
    int end = 0;
    for (int i = 0; i <= n; ++i)
        if (dp[i][m] <= dp[end][m]) end = i;  // ties: the furthest point reached

    ExamGrade g;
    g.end = end;
    g.sentences.resize(sentences.size());
    g.letters.resize(n);
    for (int i = 0; i < n; ++i) g.letters[i] = {T[i], i < end, false, 0, 0};
    std::vector<std::string> pieces(sentences.size());
    int i = end, j = m;
    while (i > 0 || j > 0) {  // backtrace; prefer match/substitution, then deletion, then insertion
        if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + (T[i - 1] != sent[j - 1].c)) {
            --i; --j;
            SentenceGrade &s = g.sentences[sentOf[i]];
            const bool ok = T[i] == sent[j].c;
            pieces[sentOf[i]].insert(0, (wordStart[i] ? " " : "") + std::string(1, sent[j].c));
            if (ok) {
                const bool unclear = sent[j].score < c.examUnclearBelow;
                g.letters[i] = {T[i], true, true, sent[j].score, sent[j].habits};
                g.unclear += unclear; s.unclear += unclear;
            } else {
                g.letters[i] = {T[i], true, false, 0, WrongChar};
                ++g.sub; ++s.sub;
            }
        } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
            --i;
            g.letters[i] = {T[i], true, false, 0, MissingElem};
            ++g.del; ++g.sentences[sentOf[i]].del;
            pieces[sentOf[i]].insert(0, wordStart[i] ? " _" : "_");
        } else {
            --j;
            const int k = i > 0 ? sentOf[i - 1] : 0;
            ++g.ins; ++g.sentences[k].ins;
            pieces[k].insert(0, std::string(1, sent[j].c));
        }
    }
    for (int k = end; k < n; ++k) ++g.sentences[sentOf[k]].unsent;
    for (size_t k = 0; k < sentences.size(); ++k) {
        SentenceGrade &s = g.sentences[k];
        s.sent = pieces[k];
        s.reached = !s.sent.empty();
        s.score = std::max(0, int(std::lround(100 - c.examErr * (s.sub + s.del + s.ins) - c.examUnclear * s.unclear - c.examShort * s.unsent)));
    }
    g.corr = corrections;
    g.shortfall = std::max(0, required - m);
    g.over = elapsedMin > 0 && m / elapsedMin > ratePerMin * c.examOverPct / 100;
    g.score = std::max(0, int(std::lround(100 - c.examErr * (g.sub + g.del + g.ins) - c.examUnclear * g.unclear -
                                          c.examCorr * g.corr - c.examShort * g.shortfall - (g.over ? c.examOver : 0))));
    return g;
}

// Turns keyed elements + silences into text. Straight-key elements (sym 0) are classified by length.
struct Decoder {
    double ditMs = 80, spaceMs = 560;  // spaceMs: silence that counts as a word space
    std::string pattern, text;

    void element(double ms, char sym) {
        pattern += sym ? sym : ms < 2 * ditMs ? '.' : '-';
        spaced = false;
    }
    // ms = silence since the last element ended; returns the finished character or 0
    char gap(double ms) {
        char c = 0;
        if (!pattern.empty() && ms > 2 * ditMs) {
            auto it = morseTable().find(pattern);
            c = it == morseTable().end() ? '*' : it->second;
            text += c;
            pattern.clear();
        }
        if (!spaced && pattern.empty() && ms > spaceMs) {
            text += ' ';
            spaced = true;
        }
        return c;
    }

private:
    bool spaced = true;
};
