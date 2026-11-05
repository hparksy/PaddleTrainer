// Self-check for keyer + decoder (no Qt): cl /std:c++17 /EHsc test_morse.cpp && test_morse
#include "morse.h"
#include <cstdio>

static int fails = 0;
#define CHECK(x) if (!(x)) { std::printf("FAIL line %d: %s\n", __LINE__, #x); ++fails; }

// Runs the keyer; paddles are released at `releaseAt`. Returns the elements keyed.
static std::string keyed(Keyer k, int samples, int releaseAt, int *firstToneLen = nullptr) {
    std::string out;
    bool was = false;
    int len = 0;
    for (int i = 0; i < samples; ++i) {
        if (i == releaseAt) k.ditIn = k.dahIn = false;
        const bool on = k.step();
        if (on && !was) out += k.sym;
        if (on && out.size() == 1) ++len;
        was = on;
    }
    if (firstToneLen) *firstToneLen = len;
    return out;
}

int main() {
    Keyer k;
    k.dit = 10;
    k.ditIn = true;
    int len = 0;
    CHECK(keyed(k, 100, 1000, &len) == ".....");  // held dit paddle: dit + gap = 20 samples each
    CHECK(len == 10);

    k.ditIn = false; k.dahIn = true;
    CHECK(keyed(k, 80, 1000, &len) == "--");
    CHECK(len == 30);

    k.ditIn = k.dahIn = true;  // short squeeze
    CHECK(keyed(k, 200, 5) == ".-");             // mode B: remembered dah
    k.mode = Keyer::IambicA;
    CHECK(keyed(k, 200, 5) == ".");
    CHECK(keyed(k, 200, 85) == ".-.-");          // held squeeze alternates

    // silent presses: a paddle pressed and released without its element ever being sent
    auto strays = [](Keyer::Mode mode, auto dit, auto dah) {
        Keyer s;
        s.dit = 10;
        s.mode = mode;
        std::string out;
        bool was = false;
        for (int i = 0; i < 200; ++i) {
            s.ditIn = dit(i);
            s.dahIn = dah(i);
            const bool on = s.step();
            if (on && !was) out += s.sym;
            was = on;
        }
        return out + "|" + std::to_string(s.ditStrays) + std::to_string(s.dahStrays);
    };
    auto during = [](int a, int b) { return [=](int i) { return i >= a && i < b; }; };
    auto never = [](int) { return false; };
    CHECK(strays(Keyer::IambicA, during(0, 5), during(2, 8)) == ".|01");   // Mode A: dah tapped mid-dit, never sent
    CHECK(strays(Keyer::IambicB, during(0, 5), during(2, 8)) == ".-|00");  // Mode B: the tap is remembered and sent
    CHECK(strays(Keyer::IambicB, during(0, 3), never) == ".|00");          // a plain dit
    CHECK(strays(Keyer::IambicB, [](int i) { return i < 3 || (i >= 6 && i < 9); }, never) == ".|10");  // dit re-tapped mid-dit
    CHECK(strays(Keyer::IambicB, during(0, 25), never) == "..|00");        // held dit: two dits, nothing silent
    Verdict sv = judge(".-", ".-", {60, 180}, {60}, -1, -5, Keyer::IambicB, false, farnsworth(20, 20));
    markStrays(sv, 0, 1);
    CHECK((sv.habits & StrayPress) && sv.worst == StrayPress && sv.notes.size() == 1 && sv.score == 100);

    // repeated habit alert: 4 of the last 10
    Criteria ac;
    int cnt = 0;
    std::vector<unsigned> hist = {GapShort, GapShort, GapShort, GapShort, GapShort, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    CHECK(repeatedHabit(hist, ac) == 0);                          // old ones scrolled out of the window
    hist = {0, GapShort, WrongChar | MissingElem, GapShort, 0, GapShort | HintUsed, WrongChar | MissingElem, GapShort, 0, 0};
    CHECK(repeatedHabit(hist, ac, &cnt) == GapShort && cnt == 4);
    hist = {WrongChar | MissingElem, WrongChar | MissingElem, WrongChar | MissingElem, WrongChar | MissingElem};
    CHECK(repeatedHabit(hist, ac) == MissingElem);                 // the specific reason, not just "wrong"
    CHECK(repeatedHabit({HintUsed, HintUsed, HintUsed, HintUsed}, ac) == 0);
    CHECK(repeatedHabit({}, ac) == 0);

    for (const char *o : ORDERS) {  // every order holds the same letters once each
        std::string a = o, b = ORDERS[0];
        std::sort(a.begin(), a.end());
        std::sort(b.begin(), b.end());
        CHECK(a == b && a.size() == 41 && std::adjacent_find(a.begin(), a.end()) == a.end());
    }
    CHECK(letterWeight(0, 0, 0) == 4);                             // never practised
    CHECK(letterWeight(20, 2000, 0) == 1);                         // perfect letter
    CHECK(letterWeight(20, 2000, 1) == 3);                         // one late letter already comes up 3x as often
    CHECK(letterWeight(20, 2000, 9) == 11);                        // fails capped, so other letters still appear

    Decoder d;
    d.ditMs = 60;
    d.spaceMs = 300;
    d.element(60, '.'); d.element(180, '-');
    CHECK(d.gap(100) == 0);                      // still inside the character
    CHECK(d.gap(200) == 'A');
    d.gap(400);
    d.element(50, 0); d.element(200, 0); d.element(40, 0);  // straight key, classified by length
    CHECK(d.gap(200) == 'R');
    CHECK(d.text == "A R");
    CHECK(patternOf('Q') == "--.-");

    const Timing t = farnsworth(20, 20);
    CHECK(t.ditMs == 60 && t.charGapMs == 180);
    const Timing f = farnsworth(18, 10);              // Farnsworth stretches only the spacing
    CHECK(std::lround(f.charGapMs) == 621 && std::lround(f.ditMs) == 67);

    const auto S = Keyer::Straight, B = Keyer::IambicB;
    Verdict v = judge(".-", ".-", {60, 180}, {60}, 180, -1, S, false, t);
    CHECK(v.score == 100 && v.habits == 0 && v.worst == 0);
    v = judge(".-", ".-", {90, 180}, {60}, 180, -1, S, false, t);    // dit 1.4x my own dit
    CHECK(v.score == 94 && v.worst == DitLong);
    v = judge(".-", ".-", {70, 200}, {66}, 200, -1, S, false, t);    // ordinary hand variation: full marks
    CHECK(v.score == 100 && v.habits == 0);
    v = judge(".-", ".-", {60, 180}, {60}, 180, -1, S, true, t);     // hint caps the score
    CHECK(v.score == 80 && v.worst == HintUsed);
    v = judge(".-", ".-", {60, 180}, {60}, -1, 30, B, false, t);     // iambic: released within the gap's first half
    CHECK(v.score == 100 && v.habits == 0);
    v = judge(".-", ".-", {60, 180}, {60}, -1, 60, B, false, t);     // a whole dit late: about to send an extra element
    CHECK(v.score == 67 && v.worst == LateRelease);
    v = judge(".-", ".-", {60, 180}, {120}, -1, -5, B, false, t);    // a dit of hesitation before the dah
    CHECK(v.score == 33 && v.worst == ElemHesitate);
    v = judge(".-", ".-", {60, 180}, {60}, 400, -5, B, false, t);    // hesitation before the char
    CHECK(v.score == 96 && v.worst == GapLong);
    v = judge(".-", ".-", {60, 180}, {60}, 90, -5, B, false, t);     // half the gap: letters run together
    CHECK(v.score == 71 && v.worst == GapShort && v.notes.size() == 1);
    v = judge(".-", ".-", {67, 200}, {67}, 350, -5, B, false, f);    // Farnsworth: between 3 dits (200) and 621 is fine
    CHECK(v.score == 100 && v.habits == 0);
    v = judge(".-", ".-", {67, 200}, {67}, 120, -5, B, false, f);    // below 3 dits: letters run together
    CHECK(v.worst == GapShort && v.score < 85);
    Criteria lax;
    lax.shortZero = 100;
    CHECK(judge(".-", ".-", {60, 180}, {60}, 90, -5, B, false, t, lax).score > 71);  // criteria are honoured
    v = judge(".-", ".", {}, {}, -1, -1, B, false, t);               // dah never sent
    CHECK(v.score == 0 && v.worst == MissingElem);
    v = judge(".-", ".-.", {}, {}, -1, -1, B, false, t);             // A -> R: Mode B latched the dit paddle
    CHECK(v.worst == SqueezeLate);
    v = judge("..", "...", {}, {}, -1, -1, B, false, t);             // dit paddle simply held too long
    CHECK(v.worst == ExtraElem);

    // exam: two sentences, 12 letters required; one substitution, one dropped letter, one extra letter
    auto ex = [](const std::string &s, int score = 100) {
        std::vector<ExamChar> out;
        for (char ch : s) out.push_back({ch, score, 0});
        return out;
    };
    const std::vector<std::string> text = {"THE SUN", "IS UP"};
    ExamGrade eg = gradeExam(text, ex("THESUNISUP"), 0, 10, 2, 5, Criteria{});
    CHECK(eg.score == 100 && eg.end == 10 && eg.sentences[1].sent == "IS UP" && eg.letters[9].ok);
    eg = gradeExam(text, ex("TXESNISUUP"), 1, 10, 2, 5, Criteria{});     // X for H, U dropped, extra U
    CHECK(eg.sub == 1 && eg.del == 1 && eg.ins == 1 && eg.corr == 1);
    CHECK(eg.score == 100 - 2 * 3 - 1);
    CHECK(eg.sentences[0].sub == 1 && eg.sentences[0].del == 1 && eg.sentences[1].ins == 1);
    eg = gradeExam(text, ex("THESU", 40), 0, 10, 1, 5, Criteria{});       // stopped early, sloppy rhythm
    CHECK(eg.end == 5 && eg.unclear == 5 && eg.shortfall == 5 && !eg.sentences[1].reached && !eg.letters[5].reached);
    CHECK(eg.score == 100 - 5 - 2 * 5);
    eg = gradeExam(text, ex("THESUNISUP"), 0, 10, 1, 5, Criteria{});      // 10 letters in 1 min against 5/min
    CHECK(eg.over && eg.score == 95);

    std::printf(fails ? "%d failed\n" : "ok\n", fails);
    return fails != 0;
}
