// Paddle Trainer: hear the paddle, see element timing, practise characters against a reference.
#define NOMINMAX
#include <windows.h>
#include <audioclient.h>
#include <avrt.h>
#include <mmdeviceapi.h>
#include <wrl/client.h>
#include <QtWidgets>
#include <atomic>
#include <future>
#include <thread>
#include "morse.h"
using Microsoft::WRL::ComPtr;

constexpr double TAU = 6.283185307179586;
static const char KOCH[] = "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X";
constexpr int WORD_LEN = 5;

struct Elem { double t0, t1, ms; char sym; };  // wall-clock ms of a keyed tone; ms is sample-exact

// Sidetone + keyer on a WASAPI low-latency render thread; the keyer runs per sample in render().
class Tone {
public:
    ~Tone() { running = false; if (th.joinable()) th.join(); }
    Keyer keyer;
    std::atomic<bool> ditIn{false}, dahIn{false};
    double freq = 600, vol = 0.5;
    int rate = 48000;
    const QElapsedTimer *clock = nullptr;
    QMutex mx;               // guards keyer settings, done, liveStart, demo
    QVector<Elem> done;      // finished elements, drained by the GUI
    double liveStart = -1;   // wall ms of the tone sounding now, -1 when silent

    // reference sound: characters at the keyer speed, charGapMs of silence between them; paddles ignored meanwhile
    void play(const std::vector<std::string> &chars, double charGapMs) {
        QMutexLocker l(&mx);
        demo.clear();
        for (auto &pattern : chars) {
            for (char c : pattern) demo << (c == '.' ? 1 : 3) * keyer.dit << -keyer.dit;
            if (!demo.isEmpty()) demo.last() = -int(charGapMs * rate / 1000);
        }
        demoPos = 0;
        demoLeft = demo.isEmpty() ? 0 : demo[0];
    }

    double bufferMs = 0;      // audio buffer = added output latency
    bool lowLatency = false;  // driver accepted a smaller-than-default engine period

    QString start() {  // returns an error text, empty on success
        std::promise<QString> ready;
        auto result = ready.get_future();
        running = true;
        th = std::thread([this, &ready] { run(ready); });
        const QString err = result.get();
        if (!err.isEmpty()) { running = false; th.join(); }
        return err;
    }

private:
    void run(std::promise<QString> &ready) {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        {
            ComPtr<IMMDeviceEnumerator> en;
            ComPtr<IMMDevice> dev;
            ComPtr<IAudioClient> ac;
            ComPtr<IAudioClient3> ac3;
            ComPtr<IAudioRenderClient> rc;
            WAVEFORMATEX *fmt = nullptr;
            HANDLE ev = CreateEventW(nullptr, FALSE, FALSE, nullptr);
            UINT32 bufFrames = 0;
            const char *fail = nullptr;
            if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&en)))) fail = "장치 목록";
            else if (FAILED(en->GetDefaultAudioEndpoint(eRender, eConsole, &dev))) fail = "기본 출력 장치";
            else if (FAILED(dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &ac))) fail = "오디오 클라이언트";
            else if (FAILED(ac->GetMixFormat(&fmt)) || fmt->wBitsPerSample != 32) fail = "출력 형식(32bit float 아님)";
            else {
                // smallest engine period the driver allows (Win10+), else the default ~10 ms
                UINT32 def, fund, mn, mx;
                const bool low = SUCCEEDED(ac.As(&ac3)) && SUCCEEDED(ac3->GetSharedModeEnginePeriod(fmt, &def, &fund, &mn, &mx)) &&
                                 SUCCEEDED(ac3->InitializeSharedAudioStream(AUDCLNT_STREAMFLAGS_EVENTCALLBACK, mn, fmt, nullptr));
                lowLatency = low;
                if (!low && FAILED(ac->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, 0, 0, fmt, nullptr))) fail = "초기화";
                else if (FAILED(ac->SetEventHandle(ev)) || FAILED(ac->GetBufferSize(&bufFrames)) || FAILED(ac->GetService(IID_PPV_ARGS(&rc)))) fail = "버퍼";
            }
            const int channels = fmt ? fmt->nChannels : 0;
            if (fmt) { rate = int(fmt->nSamplesPerSec); CoTaskMemFree(fmt); }
            bufferMs = bufFrames * 1000.0 / rate;
            ready.set_value(fail ? QString("오디오 %1 실패").arg(fail) : QString());
            if (!fail) {
                DWORD idx = 0;
                HANDLE task = AvSetMmThreadCharacteristicsW(L"Pro Audio", &idx);
                ac->Start();
                while (running) {
                    WaitForSingleObject(ev, 200);
                    UINT32 pad = 0;
                    BYTE *buf = nullptr;
                    if (FAILED(ac->GetCurrentPadding(&pad))) break;
                    const UINT32 n = bufFrames - pad;
                    if (!n || FAILED(rc->GetBuffer(n, &buf))) continue;
                    render(reinterpret_cast<float *>(buf), n, channels);
                    rc->ReleaseBuffer(n, 0);
                }
                ac->Stop();
                if (task) AvRevertMmThreadCharacteristics(task);
            }
            CloseHandle(ev);
        }
        CoUninitialize();
    }

    void render(float *out, UINT32 n, int channels) {
        const double base = clock->nsecsElapsed() / 1e6, msPerSample = 1000.0 / rate;
        const double ramp = 1.0 / (rate * 0.005);  // 5 ms attack/decay, no clicks
        QMutexLocker l(&mx);
        keyer.ditIn = ditIn;  // paddles sampled once per audio period (a few ms)
        keyer.dahIn = dahIn;
        for (UINT32 i = 0; i < n; ++i) {
            bool on;
            if (demoPos < demo.size()) {
                on = demo[demoPos] > 0;
                if (--demoLeft <= 0 && ++demoPos < demo.size()) demoLeft = std::abs(demo[demoPos]);
            } else {
                on = keyer.step();
                if (on != wasOn) {
                    const double t = base + i * msPerSample;
                    if (on) { liveStart = t; onSamples = 0; onSym = keyer.sym; }
                    else { done.push_back({liveStart, t, onSamples * msPerSample, onSym}); liveStart = -1; }
                    wasOn = on;
                }
                onSamples += on;
            }
            env = on ? qMin(1.0, env + ramp) : qMax(0.0, env - ramp);
            phase = std::fmod(phase + TAU * freq / rate, TAU);
            const float v = float(std::sin(phase) * env * vol);
            for (int c = 0; c < channels; ++c) out[i * channels + c] = v;
        }
    }

    std::thread th;
    std::atomic<bool> running{false};
    bool wasOn = false;
    qint64 onSamples = 0;
    char onSym = 0;
    double env = 0, phase = 0;
    QVector<int> demo;  // +samples tone, -samples silence
    int demoPos = 0, demoLeft = 0;
};

// Scrolling view of raw paddle contacts and the keyed tone.
class Timeline : public QWidget {
public:
    struct Bar { double t0, t1; QString label; };  // t1 < 0 while held
    QVector<Bar> lanes[3];
    QVector<QPair<double, QString>> marks;          // decoded chars under the tone lane
    double now = 0, liveTone = -1, ditMs = 80, windowMs = 4000;

    Timeline() { setMinimumHeight(170); }
    void prune() {
        auto old = [&](const Bar &b) { return b.t1 >= 0 && b.t1 < now - windowMs; };
        for (auto &l : lanes) l.erase(std::remove_if(l.begin(), l.end(), old), l.end());
        marks.erase(std::remove_if(marks.begin(), marks.end(), [&](auto &m) { return m.first < now - windowMs; }), marks.end());
    }

protected:
    void paintEvent(QPaintEvent *) override {
        QPainter p(this);
        p.fillRect(rect(), QColor(12, 13, 20));
        const double left = 90, w = width() - left - 10;
        auto X = [&](double t) { return left + w - (now - t) / windowMs * w; };
        const int markH = 36, laneH = (height() - markH) / 3;
        p.setPen(QColor(32, 36, 48));
        for (double t = now - std::fmod(now, ditMs); X(t) >= left; t -= ditMs)
            p.drawLine(QPointF(X(t), 0), QPointF(X(t), height() - markH));
        static const char *names[] = {"[ dit", "] dah", "소리"};
        static const QColor colors[] = {QColor(80, 160, 255), QColor(255, 165, 60), QColor(80, 230, 140)};
        QFont f = font();
        for (int i = 0; i < 3; ++i) {
            const double y = i * laneH;
            f.setPointSize(10); p.setFont(f);
            p.setPen(QColor(150, 155, 170));
            p.drawText(QRectF(10, y, left - 14, laneH), Qt::AlignVCenter, names[i]);
            f.setPointSize(8); p.setFont(f);
            auto draw = [&](double t0, double t1, const QString &label) {
                QRectF r(QPointF(qMax(left, X(t0)), y + 6), QPointF(X(t1), y + laneH - 6));
                if (r.width() <= 0) return;
                p.fillRect(r, colors[i]);
                if (r.width() > 34) { p.setPen(Qt::black); p.drawText(r, Qt::AlignCenter, label); }
            };
            for (auto &b : lanes[i]) draw(b.t0, b.t1 < 0 ? now : b.t1, b.label);
            if (i == 2 && liveTone >= 0) draw(liveTone, now, {});
        }
        f.setPointSize(16); f.setBold(true); p.setFont(f);
        p.setPen(Qt::white);
        for (auto &m : marks) p.drawText(QRectF(X(m.first) - 30, height() - markH, 60, markH), Qt::AlignCenter, m.second);
        f.setPointSize(8); f.setBold(false); p.setFont(f);
        p.setPen(QColor(100, 105, 120));
        p.drawText(rect().adjusted(0, 2, -8, 0), Qt::AlignRight | Qt::AlignTop, QString("격자 1칸 = 1 dit = %1 ms").arg(ditMs, 0, 'f', 0));
    }
};

static QColor gradeColor(int score) {
    return score >= 95 ? QColor(90, 220, 255) : score >= 85 ? QColor(80, 230, 140) : score >= 70 ? QColor(255, 210, 70) : QColor(255, 120, 70);
}
static QString gradeName(int score) {
    return score >= 95 ? "PERFECT" : score >= 85 ? "GREAT" : score >= 70 ? "GOOD" : "SLOPPY";
}
// status colours for score tiles: good / warning / critical, always shown with the number
static QColor statusColor(double score) {
    return score >= 85 ? QColor(46, 160, 90) : score >= 70 ? QColor(200, 150, 30) : QColor(200, 60, 70);
}

// Game screen: target word, reference-vs-you timing bars, habit feedback, score/combo/grade.
class Game : public QWidget {
public:
    QString word;
    int pos = 0;
    QVector<QColor> doneColor;
    std::string cmpRef;          // pattern the comparison bars are drawn against
    QVector<Elem> elems;         // user's elements for that pattern
    double liveStart = -1, now = 0, releaseAt = -1, charGap = -1;
    Timing timing{80, 240, 560};
    bool hint = true, straight = false;
    int owe = 0;                 // correct repeats still needed before the current letter is done
    const Criteria *crit = nullptr;
    int score = 0, combo = 0, bestCombo = 0, attempts = 0, hits = 0;
    double scoreSum = 0;
    QString popText;
    QColor popColor;
    double popAt = -1e9;

    Game() { setMinimumHeight(470); }
    void pop(const QString &text, QColor c) { popText = text; popColor = c; popAt = now; }

protected:
    void paintEvent(QPaintEvent *) override {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        QLinearGradient bg(0, 0, 0, height());
        bg.setColorAt(0, QColor(22, 20, 48));
        bg.setColorAt(1, QColor(8, 8, 16));
        p.fillRect(rect(), bg);
        const int W = width();
        const double dit = timing.ditMs;
        auto setFont = [&](int pt, bool bold = true) { QFont f = font(); f.setPointSize(pt); f.setBold(bold); p.setFont(f); };
        auto signedPct = [](double d) { return QString("%1%2%").arg(d >= 0 ? "+" : "").arg(qRound(d * 100)); };

        // HUD
        setFont(14);
        p.setPen(QColor(255, 215, 90));
        p.drawText(QRect(20, 8, 400, 34), Qt::AlignLeft | Qt::AlignVCenter, QString("SCORE %1").arg(score));
        p.setPen(combo >= 5 ? QColor(255, 90, 210) : QColor(170, 175, 210));
        p.drawText(QRect(0, 8, W, 34), Qt::AlignCenter, QString("COMBO x%1   (최고 %2)").arg(combo).arg(bestCombo));
        p.setPen(QColor(120, 220, 255));
        p.drawText(QRect(0, 8, W - 20, 34), Qt::AlignRight | Qt::AlignVCenter,
                   QString("평균 %1점   ✔%2  ✘%3").arg(attempts ? qRound(scoreSum / attempts) : 0).arg(hits).arg(attempts - hits));

        // target word, typing-tutor style
        const int cell = 86, y0 = 56, x0 = (W - cell * word.size()) / 2;
        const QColor repeat(255, 150, 60);
        for (int i = 0; i < word.size(); ++i) {
            const QRect r(x0 + i * cell + 6, y0, cell - 12, 86);
            p.setPen(i == pos ? QPen(owe ? repeat : QColor(90, 210, 255), 3) : Qt::NoPen);
            p.setBrush(i == pos ? QColor(40, 80, 130, 170) : QColor(30, 32, 50));
            p.drawRoundedRect(r, 12, 12);
            setFont(40);
            p.setPen(i < pos ? doneColor.value(i) : i == pos ? Qt::white : QColor(85, 90, 110));
            p.drawText(r, Qt::AlignCenter, word.mid(i, 1));
        }
        if (hint && pos < word.size()) {  // pattern under the current letter
            const std::string pat = patternOf(word[pos].toLatin1());
            int w = 0;
            for (char c : pat) w += (c == '.' ? 10 : 30) + 6;
            double x = x0 + pos * cell + cell / 2.0 - (w - 6) / 2.0;
            p.setPen(Qt::NoPen);
            p.setBrush(QColor(90, 210, 255));
            for (char c : pat) { const int bw = c == '.' ? 10 : 30; p.drawRoundedRect(QRectF(x, y0 + 96, bw, 9), 4, 4); x += bw + 6; }
        }
        if (owe && pos < word.size()) {  // this letter must be repeated before moving on
            const QRectF badge(x0 + pos * cell + cell / 2.0 - 90, y0 + 104, 180, 24);
            p.setPen(Qt::NoPen);
            p.setBrush(repeat);
            p.drawRoundedRect(badge, 12, 12);
            setFont(11);
            p.setPen(QColor(20, 16, 10));
            p.drawText(badge, Qt::AlignCenter, QString("↻ 같은 글자 %1회 더").arg(owe));
        }

        // reference vs you
        const QRect panel(20, 186, W - 40, height() - 200);
        p.setPen(QColor(55, 65, 100));
        p.setBrush(QColor(16, 18, 34, 220));
        p.drawRoundedRect(panel, 12, 12);
        QVector<std::pair<double, double>> ref;
        double t = 0;
        for (char c : cmpRef) { ref.push_back({t, t + (c == '.' ? 1 : 3) * dit}); t = ref.back().second + dit; }
        const double base = elems.isEmpty() ? liveStart : elems[0].t0;
        double userEnd = elems.isEmpty() ? 0 : elems.last().t1 - base;
        if (liveStart >= 0 && base >= 0) userEnd = now - base;
        if (releaseAt >= 0 && base >= 0) userEnd = qMax(userEnd, releaseAt - base);
        const double total = qMax(qMax(t, userEnd) + dit, 8 * dit);
        const double L = panel.left() + 80, R = panel.right() - 24, px = (R - L) / total;
        const int yRef = panel.top() + 56, yYou = panel.top() + 126, h = 34;

        setFont(12);
        p.setPen(QColor(150, 160, 200));
        p.drawText(QRect(panel.left() + 14, yRef, 70, h), Qt::AlignVCenter, "기준");
        p.drawText(QRect(panel.left() + 14, yYou, 70, h), Qt::AlignVCenter, "나");
        setFont(9, false);
        QString head = QString("글자 '%1'   dit %2 · dah %3 · 요소 간격 %2 · 글자 간격 %4 ms")
                           .arg(cmpRef.empty() ? QString() : QString(QChar(morseTable().at(cmpRef))))
                           .arg(dit, 0, 'f', 0).arg(3 * dit, 0, 'f', 0).arg(timing.charGapMs, 0, 'f', 0);
        if (charGap >= 0) head += QString("   ·   내 글자 간격 %1 ms (%2)").arg(qRound(charGap)).arg(signedPct(charGap / timing.charGapMs - 1));
        p.drawText(panel.adjusted(14, 8, -14, 0), Qt::AlignTop | Qt::AlignLeft, head);
        p.drawText(panel.adjusted(14, 26, -14, 0), Qt::AlignTop | Qt::AlignLeft,
                   QString(straight ? "채점: dit:dah 비율 ±%1% · 요소 간격 ±%2% · 속도 ±%3% · 글자 간격 −%4%/+%5% 이내 만점"
                                    : "채점: 요소 사이 머뭇거림 %1% · 패들 뗌 dit의 %2% · 글자 간격 −%4%/+%5% 이내 만점 (요소 길이는 키어 몫)%3")
                       .arg(straight ? crit->elemOk : crit->hesitateOk).arg(straight ? crit->elemGapOk : crit->releaseOk)
                       .arg(straight ? QString::number(crit->speedOk) : QString())  // %3 must exist in both texts
                       .arg(crit->shortOk).arg(crit->longOk));

        for (auto &[a, b] : ref) {
            const QRectF r(L + a * px, yRef, (b - a) * px, h);
            p.setPen(QPen(QColor(90, 210, 255), 2));
            p.setBrush(QColor(90, 210, 255, 50));
            p.drawRoundedRect(r, 6, 6);
            p.setPen(QPen(QColor(90, 210, 255, 70), 1, Qt::DashLine));  // guides down to your row
            p.drawLine(QPointF(r.left(), yRef + h), QPointF(r.left(), yYou + h));
            p.drawLine(QPointF(r.right(), yRef + h), QPointF(r.right(), yYou + h));
        }
        auto devColor = [](double d, double ok, double zero) {
            d = std::fabs(d) * 100;
            return d <= ok ? QColor(80, 230, 140) : d < zero ? QColor(255, 210, 70) : QColor(255, 80, 90);
        };
        for (int i = 0; i < elems.size(); ++i) {
            const Elem &e = elems[i];
            const QRectF r(L + (e.t0 - base) * px, yYou, e.ms * px, h);
            const char sym = e.sym ? e.sym : e.ms < 2 * dit ? '.' : '-';
            const bool known = i < ref.size() && cmpRef[i] == sym;
            const double dev = known ? e.ms / (ref[i].second - ref[i].first) - 1 : 1;
            const QColor c = straight || !known ? devColor(dev, crit->elemOk, crit->elemZero) : QColor(150, 160, 200);  // keyer-made lengths are not yours
            p.setPen(Qt::NoPen);
            p.setBrush(c);
            p.drawRoundedRect(r, 6, 6);
            setFont(9);
            p.setPen(c);
            p.drawText(QRectF(r.center().x() - 50, yYou + h + 4, 100, 18), Qt::AlignCenter,
                       !known ? "틀림" : straight ? signedPct(dev) : QString("%1ms").arg(qRound(e.ms)));
            if (straight && i > 0) {  // gap before this element
                const double gap = e.t0 - elems[i - 1].t1, gdev = gap / dit - 1;
                setFont(8, false);
                p.setPen(devColor(gdev, crit->elemGapOk, crit->elemGapZero));
                p.drawText(QRectF(L + (elems[i - 1].t1 - base) * px - 30, yYou - 18, gap * px + 60, 16), Qt::AlignCenter, signedPct(gdev));
            }
        }
        if (liveStart >= 0 && base >= 0) {
            p.setPen(Qt::NoPen);
            p.setBrush(QColor(255, 255, 255, 200));
            p.drawRoundedRect(QRectF(L + (liveStart - base) * px, yYou, (now - liveStart) * px, h), 6, 6);
        }
        if (!straight && releaseAt >= 0 && base >= 0 && !elems.isEmpty()) {  // when the hand let go
            const double late = (releaseAt - elems.last().t1) / dit;
            const QColor c = late * 100 > crit->releaseOk ? QColor(255, 80, 90) : QColor(240, 240, 255);
            const double x = L + (releaseAt - base) * px;
            p.setPen(QPen(c, 2));
            p.drawLine(QPointF(x, yYou - 8), QPointF(x, yYou + h + 8));
            setFont(9);
            p.drawText(QRectF(x - 60, yYou - 26, 120, 16), Qt::AlignCenter,
                       late * 100 > crit->releaseOk ? QString("뗌 +%1ms 늦음").arg(qRound(releaseAt - elems.last().t1)) : QString("뗌"));
        }

        // last result: one steady line under the bars, shown up to 5 s (details are in the list on the right)
        const double age = now - popAt;
        if (age >= 0 && age < 5000) {
            QColor c = popColor;
            c.setAlphaF(std::min(1.0, (5000 - age) / 500));  // fade out over the last half second
            setFont(20);
            p.setPen(c);
            p.drawText(QRect(panel.left() + 20, yYou + h + 30, panel.width() - 40, 34), Qt::AlignVCenter | Qt::AlignLeft, popText);
        }
        setFont(9, false);
        p.setPen(QColor(120, 125, 150));
        p.drawText(panel.adjusted(14, 0, -14, -8), Qt::AlignBottom | Qt::AlignLeft,
                   QString("초록 만점 · 노랑 감점 · 빨강 0점   ·   통과 %1점   ·   Space: 글자 소리  ·  Shift+Space: 단어 소리  ·  ⚙ 설정에서 기준 변경").arg(crit->passScore));
    }
};

// One judged character, appended to attempts.csv.
struct Rec {
    qint64 time;
    char ch;
    bool ok;
    int score, mode, wpm, eff;
    unsigned habits;
    int kind = 0;  // 0 = practice, 1 = exam
};

// One exam (exams.csv) and one sentence of it (sentences.csv); `time` (exam start) links them.
struct ExamRec { qint64 time; QString grade; int rate, minutes, sent, sub, del, ins, unclear, corr, shortfall, over, score, pass; };
struct SentRec { qint64 time; int idx; QString target, sent; int sub, del, ins, unclear, unsent, score; };

// Progress over time: summary, per-character tiles, daily score trend, most frequent habits.
class Stats : public QWidget {
public:
    const QVector<Rec> *recs = nullptr;
    const QVector<ExamRec> *exams = nullptr;
    const QVector<SentRec> *sents = nullptr;
    int days = 7;      // period length, 0 = all time
    int kind = -1;     // -1 = all, 0 = practice only, 1 = exam only
    QString koch;      // characters currently in practice

    Stats() { setMouseTracking(true); }

    struct Agg {
        int n = 0, ok = 0;
        double sum = 0;
        double avg() const { return n ? sum / n : 0; }
    };
    // attempts made `to` (inclusive) .. `from` (exclusive) days ago; from = 0 means no lower bound.
    // habit != 0: sum counts attempts showing that habit instead of score.
    Agg agg(char ch, int from, int to, unsigned habit = 0) const {
        Agg a;
        const QDate today = QDate::currentDate();
        for (const Rec &r : *recs) {
            const qint64 back = QDateTime::fromSecsSinceEpoch(r.time).date().daysTo(today);
            if ((from && back >= from) || back < to || (ch && r.ch != ch) || (kind >= 0 && r.kind != kind)) continue;
            ++a.n;
            a.ok += r.ok;
            a.sum += habit ? ((r.habits & habit) != 0) : r.score;
        }
        return a;
    }
    int contentHeight(int w) const {
        const int cols = qMax(1, (w - 40) / 100);
        const int rows = (chars().size() + cols - 1) / cols;
        int examRows = 0;
        for (const ExamRec &e : recentExams()) examRows += 34 + 22 * sentencesOf(e.time).size();
        return 146 + rows * 92 + 20 + 30 + 230 + 30 + 7 * 24 + 20 + 34 + qMax(30, examRows) + 60;
    }
    QVector<ExamRec> recentExams() const {  // newest first, within the period, at most 5
        QVector<ExamRec> out;
        const QDate today = QDate::currentDate();
        for (int i = exams->size() - 1; i >= 0 && out.size() < 5; --i)
            if (!days || QDateTime::fromSecsSinceEpoch((*exams)[i].time).date().daysTo(today) < days) out << (*exams)[i];
        return out;
    }
    QVector<SentRec> sentencesOf(qint64 time) const {
        QVector<SentRec> out;
        for (const SentRec &s : *sents)
            if (s.time == time) out << s;
        return out;
    }

protected:
    QVector<QPair<QRectF, QString>> tips;  // hover targets of the trend chart

    QString chars() const {
        QString s = koch;
        for (const Rec &r : *recs)
            if (!s.contains(QChar(r.ch))) s += QChar(r.ch);
        return s;
    }

    void resizeEvent(QResizeEvent *) override { setMinimumHeight(contentHeight(width())); }

    void mouseMoveEvent(QMouseEvent *e) override {
        for (auto &[r, text] : tips)
            if (r.contains(e->pos())) { QToolTip::showText(e->globalPos(), text, this); return; }
        QToolTip::hideText();
    }

    void paintEvent(QPaintEvent *) override {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.fillRect(rect(), QColor(14, 15, 24));
        auto setFont = [&](int pt, bool bold = true) { QFont f = font(); f.setPointSize(pt); f.setBold(bold); p.setFont(f); };
        const QColor ink(216, 220, 240), muted(130, 136, 160), grid(40, 44, 60);
        const int W = width();
        tips.clear();

        // summary tiles
        const Agg now = agg(0, days, 0), prev = days ? agg(0, 2 * days, days) : Agg{};
        const QString period = days == 1 ? "오늘" : days ? QString("최근 %1일").arg(days) : "전체 기간";
        struct Tile { QString label, value, sub; };
        const QVector<Tile> tiles = {
            {period + " 시도", QString::number(now.n), "글자 수"},
            {"정답률", now.n ? QString("%1%").arg(qRound(100.0 * now.ok / now.n)) : "-",
             prev.n ? QString("이전 %1%").arg(qRound(100.0 * prev.ok / prev.n)) : ""},
            {"평균 점수", now.n ? QString::number(qRound(now.avg())) : "-",
             prev.n ? QString("이전 %1 (%2%3)").arg(qRound(prev.avg())).arg(now.avg() >= prev.avg() ? "▲" : "▼").arg(qRound(std::fabs(now.avg() - prev.avg())))
                    : "틀린 글자 = 0점"},
        };
        const double tw = (W - 40 - 2 * 16) / 3.0;
        for (int i = 0; i < tiles.size(); ++i) {
            const QRectF r(20 + i * (tw + 16), 16, tw, 80);
            p.setPen(Qt::NoPen);
            p.setBrush(QColor(24, 27, 44));
            p.drawRoundedRect(r, 10, 10);
            setFont(9, false); p.setPen(muted);
            p.drawText(r.adjusted(14, 8, -14, 0), Qt::AlignTop | Qt::AlignLeft, tiles[i].label);
            setFont(24); p.setPen(ink);
            p.drawText(r.adjusted(14, 22, -14, 0), Qt::AlignTop | Qt::AlignLeft, tiles[i].value);
            setFont(9, false); p.setPen(muted);
            p.drawText(r.adjusted(14, 0, -14, -10), Qt::AlignBottom | Qt::AlignRight, tiles[i].sub);
        }

        // per-character tiles
        int y = 116;
        setFont(12); p.setPen(ink);
        p.drawText(QRect(20, y, W - 40, 24), Qt::AlignVCenter, QString("글자별 평균 점수 (%1, 화살표 = 이전 같은 기간 대비)").arg(period));
        y += 30;
        const QString cs = chars();
        const int cols = qMax(1, (W - 40) / 100);
        for (int i = 0; i < cs.size(); ++i) {
            const char ch = cs[i].toLatin1();
            const Agg a = agg(ch, days, 0), b = days ? agg(ch, 2 * days, days) : Agg{};
            const QRectF r(20 + (i % cols) * 100, y + (i / cols) * 92, 92, 84);
            p.setPen(Qt::NoPen);
            p.setBrush(a.n ? statusColor(a.avg()).darker(170) : QColor(28, 30, 44));
            p.drawRoundedRect(r, 10, 10);
            if (a.n) { p.setBrush(statusColor(a.avg())); p.drawRoundedRect(QRectF(r.left(), r.top(), 6, r.height()), 3, 3); }
            setFont(20); p.setPen(ink);
            p.drawText(r.adjusted(12, 4, 0, 0), Qt::AlignTop | Qt::AlignLeft, cs.mid(i, 1));
            setFont(16); p.setPen(a.n ? ink : muted);
            p.drawText(r.adjusted(0, 6, -10, 0), Qt::AlignTop | Qt::AlignRight, a.n ? QString::number(qRound(a.avg())) : "-");
            setFont(8, false); p.setPen(QColor(200, 205, 225));
            const QString trend = a.n && b.n ? QString("  %1%2").arg(a.avg() >= b.avg() ? "▲" : "▼").arg(qRound(std::fabs(a.avg() - b.avg()))) : "";
            p.drawText(r.adjusted(12, 0, -8, -24), Qt::AlignBottom | Qt::AlignLeft,
                       a.n ? QString("정답 %1%%2").arg(qRound(100.0 * a.ok / a.n)).arg(trend) : "기록 없음");
            p.drawText(r.adjusted(12, 0, -8, -8), Qt::AlignBottom | Qt::AlignLeft, a.n ? QString("%1회").arg(a.n) : "");
        }
        y += (cs.size() + cols - 1) / cols * 92 + 20;

        // daily trend: one series, fixed 0..100 axis, gaps on days without practice
        setFont(12); p.setPen(ink);
        p.drawText(QRect(20, y, W - 40, 24), Qt::AlignVCenter, "일별 평균 점수 (최근 30일)");
        y += 30;
        const QRectF plot(56, y + 10, W - 96, 180);
        setFont(8, false);
        for (int v = 0; v <= 100; v += 25) {
            const double gy = plot.bottom() - v / 100.0 * plot.height();
            p.setPen(QPen(grid, 1));
            p.drawLine(QPointF(plot.left(), gy), QPointF(plot.right(), gy));
            p.setPen(muted);
            p.drawText(QRectF(10, gy - 8, 40, 16), Qt::AlignRight | Qt::AlignVCenter, QString::number(v));
        }
        const QDate today = QDate::currentDate();
        auto X = [&](int back) { return plot.right() - back / 29.0 * plot.width(); };
        QPainterPath path;
        bool open = false;
        QVector<QPointF> dots;
        for (int back = 29; back >= 0; --back) {
            const Agg a = agg(0, back + 1, back);
            if (back % 7 == 0) {
                p.setPen(muted);
                p.drawText(QRectF(X(back) - 30, plot.bottom() + 4, 60, 16), Qt::AlignCenter, today.addDays(-back).toString("M/d"));
            }
            if (!a.n) { open = false; continue; }
            const QPointF pt(X(back), plot.bottom() - a.avg() / 100 * plot.height());
            open ? path.lineTo(pt) : path.moveTo(pt);
            open = true;
            dots << pt;
            tips.push_back({QRectF(pt.x() - 10, plot.top(), 20, plot.height()),
                            QString("%1\n평균 %2점 · 정답 %3% · %4회").arg(today.addDays(-back).toString("yyyy-MM-dd"))
                                .arg(qRound(a.avg())).arg(qRound(100.0 * a.ok / a.n)).arg(a.n)});
        }
        const QColor series(90, 170, 255);
        p.setPen(QPen(series, 2));
        p.setBrush(Qt::NoBrush);
        p.drawPath(path);
        for (const QPointF &d : dots) {
            p.setPen(QPen(QColor(14, 15, 24), 2));  // surface ring
            p.setBrush(series);
            p.drawEllipse(d, 4.5, 4.5);
        }
        if (dots.isEmpty()) { setFont(10, false); p.setPen(muted); p.drawText(plot, Qt::AlignCenter, "아직 기록이 없습니다"); }
        y = int(plot.bottom()) + 40;

        // habits: share of attempts in the period that showed each one
        setFont(12); p.setPen(ink);
        p.drawText(QRect(20, y, W - 40, 24), Qt::AlignVCenter, QString("자주 나오는 버릇 (%1, 시도 대비 비율)").arg(period));
        y += 30;
        QVector<QPair<double, unsigned>> hs;
        for (unsigned h = 1; h <= SqueezeLate; h <<= 1) {
            const Agg a = agg(0, days, 0, h);
            if (a.sum > 0) hs.push_back({a.sum / a.n, h});
        }
        std::sort(hs.begin(), hs.end(), [](auto &a, auto &b) { return a.first > b.first; });
        setFont(10, false);
        if (hs.isEmpty()) { p.setPen(muted); p.drawText(QRect(20, y, W - 40, 22), Qt::AlignVCenter, now.n ? "지적된 버릇이 없습니다" : "-"); }
        for (int i = 0; i < qMin(7, hs.size()); ++i) {
            const double share = hs[i].first;
            const QRectF bar(300, y + 5, qMax(4.0, 300 * share / hs[0].first), 12);  // scaled to the most frequent
            p.setPen(ink);
            p.drawText(QRect(20, y, 276, 22), Qt::AlignVCenter, habitName(hs[i].second));
            p.setPen(Qt::NoPen);
            p.setBrush(QColor(255, 120, 90));
            p.drawRoundedRect(bar, 3, 3);
            p.setPen(muted);
            p.drawText(QRectF(bar.right() + 10, y, W - bar.right() - 30, 22), Qt::AlignVCenter,
                       QString("%1%   %2").arg(qRound(share * 100)).arg(i == 0 ? habitAdvice(hs[i].second) : ""));
            y += 24;
        }
        y = qMax(y, int(plot.bottom()) + 40 + 30 + 7 * 24) + 20;

        // exams, sentence by sentence
        setFont(12); p.setPen(ink);
        p.drawText(QRect(20, y, W - 40, 24), Qt::AlignVCenter, QString("검정 기록 — 문장별 (%1, 최근 5회)").arg(period));
        y += 34;
        const QVector<ExamRec> ex = recentExams();
        if (ex.isEmpty()) { setFont(10, false); p.setPen(muted); p.drawText(QRect(20, y, W - 40, 22), Qt::AlignVCenter, "아직 검정 기록이 없습니다"); }
        for (const ExamRec &e : ex) {
            const QRectF head(20, y, W - 40, 28);
            p.setPen(Qt::NoPen);
            p.setBrush(QColor(24, 27, 44));
            p.drawRoundedRect(head, 6, 6);
            setFont(10);
            p.setPen(e.pass ? QColor(80, 230, 140) : QColor(255, 100, 100));
            p.drawText(head.adjusted(10, 0, 0, 0), Qt::AlignVCenter,
                       QString("%1점 %2").arg(e.score).arg(e.pass ? "합격" : "불합격"));
            setFont(10, false);
            p.setPen(ink);
            p.drawText(head.adjusted(110, 0, -10, 0), Qt::AlignVCenter,
                       QString("%1  ·  %2  ·  %3분  ·  보냄 %4자  ·  오자 %5 탈자 %6 용자 %7 · 불명료 %8 · 정정 %9 · 속도미달 %10%11")
                           .arg(QDateTime::fromSecsSinceEpoch(e.time).toString("MM-dd HH:mm"), e.grade).arg(e.minutes)
                           .arg(e.sent).arg(e.sub).arg(e.del).arg(e.ins).arg(e.unclear).arg(e.corr).arg(e.shortfall)
                           .arg(e.over ? " · 속도초과" : ""));
            y += 34;
            for (const SentRec &sr : sentencesOf(e.time)) {
                setFont(10, false);
                p.setPen(statusColor(sr.score));
                p.drawText(QRect(36, y, 60, 20), Qt::AlignVCenter, QString("%1점").arg(sr.score));
                p.setPen(ink);
                p.drawText(QRect(96, y, (W - 140) / 2, 20), Qt::AlignVCenter, QString("%1. %2").arg(sr.idx + 1).arg(sr.target));
                p.setPen(muted);
                const QString errs = QString("오 %1 탈 %2 용 %3 불 %4%5").arg(sr.sub).arg(sr.del).arg(sr.ins).arg(sr.unclear)
                                         .arg(sr.unsent ? QString(" · 미송신 %1").arg(sr.unsent) : "");
                p.drawText(QRect(96 + (W - 140) / 2, y, (W - 140) / 2, 20), Qt::AlignVCenter,
                           QString("→ %1    (%2)").arg(sr.sent.isEmpty() ? "-" : sr.sent, errs));
                y += 22;
            }
        }
    }
};

// Exam screen: target sentences coloured by how each letter was sent, a countdown, and the score sheet.
class ExamView : public QWidget {
public:
    std::vector<std::string> sentences;
    ExamGrade g;
    bool running = false, finished = false;
    double leftMs = 0;
    int required = 0, sentCount = 0;
    QString gradeName, live;
    const Criteria *crit = nullptr;

    ExamView() { setMinimumHeight(480); }

protected:
    void paintEvent(QPaintEvent *) override {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.fillRect(rect(), QColor(14, 15, 24));
        auto setFont = [&](int pt, bool bold = true, bool mono = false) {
            QFont f = mono ? QFont("Consolas") : font();
            f.setPointSize(pt); f.setBold(bold); p.setFont(f);
        };
        const QColor ink(216, 220, 240), muted(130, 136, 160);
        const int W = width();

        setFont(13); p.setPen(ink);
        p.drawText(QRect(20, 12, W - 40, 30), Qt::AlignVCenter | Qt::AlignLeft, gradeName);
        const int secs = qMax(0, int(std::ceil(leftMs / 1000)));
        setFont(26); p.setPen(running && secs <= 10 ? QColor(255, 120, 90) : ink);
        p.drawText(QRect(20, 6, W - 40, 44), Qt::AlignVCenter | Qt::AlignRight, QString("%1:%2").arg(secs / 60).arg(secs % 60, 2, 10, QChar('0')));
        setFont(11, false); p.setPen(muted);
        p.drawText(QRect(20, 46, W - 40, 22), Qt::AlignVCenter | Qt::AlignLeft,
                   QString("보낸 글자 %1 / 필요 %2   ·   오자 %3 · 탈자 %4 · 용자 %5 · 불명료 %6 · 정정 %7   ·   현재 %8점   ·   지금 치는 중: %9")
                       .arg(sentCount).arg(required).arg(g.sub).arg(g.del).arg(g.ins).arg(g.unclear).arg(g.corr).arg(g.score)
                       .arg(live.isEmpty() ? "-" : live));

        if (sentences.empty()) {
            setFont(12, false); p.setPen(muted);
            p.drawText(rect().adjusted(40, 80, -40, -40), Qt::AlignCenter | Qt::TextWordWrap,
                       "등급과 시간을 고르고 '시작'을 누르세요.\n\n"
                       "옛 아마추어무선기사 전신 실기 형식: 영문 보통어를 정해진 속도(1분간 N자)로 3분간 송신합니다.\n"
                       "틀렸으면 dit 8개(........)로 정정 후 다시 치세요 — 정정 1회마다 감점됩니다.\n"
                       "단어 사이 간격은 채점하지 않습니다.");
            return;
        }

        // sentences: letter cells, colour = result; the next letter to send is outlined
        const int rows = int(sentences.size());
        const int rowH = qBound(36, (height() - 90) / qMax(1, rows), 58);
        const int cell = qBound(14, rowH / 2, 24);
        int li = 0, y = 78;
        for (int k = 0; k < rows; ++k) {
            int x = 24;
            setFont(cell * 2 / 3, true, true);
            for (char ch : sentences[k]) {
                if (ch == ' ') { x += cell / 2; continue; }
                const LetterGrade &lg = g.letters[li];
                const QColor c = !lg.reached ? QColor(90, 95, 120)
                               : !lg.ok      ? QColor(255, 80, 90)
                               : lg.score < crit->examUnclearBelow ? QColor(255, 210, 70) : QColor(80, 230, 140);
                const QRectF box(x, y, cell, cell + 4);
                if (running && li == g.end) {
                    p.setPen(QPen(QColor(90, 210, 255), 2));
                    p.setBrush(QColor(90, 210, 255, 40));
                    p.drawRoundedRect(box.adjusted(-2, -2, 2, 2), 4, 4);
                }
                p.setPen(c);
                p.drawText(box, Qt::AlignCenter, QString(QChar(ch)));
                x += cell;
                ++li;
            }
            const SentenceGrade &sg = g.sentences[k];
            if (sg.reached) {
                setFont(qMax(8, cell / 2), false, true);
                p.setPen(muted);
                p.drawText(QRect(24, y + cell + 4, W - 48, rowH - cell - 4), Qt::AlignTop | Qt::AlignLeft,
                           QString("→ %1").arg(QString::fromStdString(sg.sent)));
            }
            y += rowH;
        }

        if (!finished) return;
        // score sheet, like the old exam's sending record
        const QRectF box(W - 460, 70, 440, 270);
        p.setPen(QPen(QColor(60, 66, 100), 1));
        p.setBrush(QColor(20, 22, 38, 240));
        p.drawRoundedRect(box, 12, 12);
        const bool pass = g.score >= crit->examPass;
        setFont(18); p.setPen(pass ? QColor(80, 230, 140) : QColor(255, 100, 100));
        p.drawText(box.adjusted(20, 12, -20, 0), Qt::AlignTop | Qt::AlignLeft, QString("%1점  %2").arg(g.score).arg(pass ? "합격" : "불합격"));
        setFont(9, false); p.setPen(muted);
        p.drawText(box.adjusted(20, 18, -20, 0), Qt::AlignTop | Qt::AlignRight, QString("합격 %1점 이상").arg(crit->examPass));
        struct Row { QString name; int n; double each; };
        const QVector<Row> sheet = {
            {"오자 · 탈자 · 용자", g.sub + g.del + g.ins, crit->examErr}, {"자호불명료", g.unclear, crit->examUnclear},
            {"정정", g.corr, crit->examCorr}, {"기준속도미달 (글자)", g.shortfall, crit->examShort},
            {"표준속도초과", g.over ? 1 : 0, crit->examOver}};
        int ry = int(box.top()) + 60;
        setFont(10); p.setPen(muted);
        p.drawText(QRect(int(box.left()) + 20, ry, 200, 20), Qt::AlignVCenter, "항목");
        p.drawText(QRect(int(box.left()) + 220, ry, 80, 20), Qt::AlignVCenter | Qt::AlignRight, "횟수");
        p.drawText(QRect(int(box.left()) + 300, ry, 110, 20), Qt::AlignVCenter | Qt::AlignRight, "감점");
        ry += 26;
        setFont(11, false);
        for (const Row &row : sheet) {
            p.setPen(row.n ? ink : muted);
            p.drawText(QRect(int(box.left()) + 20, ry, 200, 22), Qt::AlignVCenter, row.name);
            p.drawText(QRect(int(box.left()) + 220, ry, 80, 22), Qt::AlignVCenter | Qt::AlignRight, QString::number(row.n));
            p.setPen(row.n ? QColor(255, 140, 120) : muted);
            p.drawText(QRect(int(box.left()) + 300, ry, 110, 22), Qt::AlignVCenter | Qt::AlignRight,
                       row.n ? QString("−%1").arg(row.n * row.each) : "0");
            ry += 26;
        }
        setFont(9, false); p.setPen(muted);
        p.drawText(box.adjusted(20, 0, -20, -10), Qt::AlignBottom | Qt::AlignLeft, "기록: 통계 탭 → 검정 기록 (문장별) · 글자별 타일");
    }
};

// Exam grades: name, letters per minute, letters drawn from the learned Koch set (5-letter groups) or plain English.
struct ExamGradeDef { const char *name; int rate; bool learned; };
static const ExamGradeDef EXAM_GRADES[] = {
    {"배운 글자 5자 그룹 · 1분간 20자", 20, true},
    {"영문 보통어 · 1분간 20자 (연습)", 20, false},
    {"제3급 아마추어무선기사(전신급) · 1분간 35자", 35, false},
    {"제2급 아마추어무선기사 · 1분간 50자", 50, false},
};
// Plain-English sentences for the exam (letters and spaces only).
static const char *const EXAM_TEXT[] = {
    "THE SUN IS UP", "WE MEET AT NOON", "THE RIVER RUNS FAST", "MY RADIO WORKS WELL", "SEND YOUR NAME AGAIN",
    "THE WIND IS FROM THE WEST", "IT IS COLD TODAY", "THE BAND IS OPEN", "I HEAR YOU VERY WELL", "PLEASE SEND SLOWER",
    "THE ANTENNA IS ON THE ROOF", "WE HAVE RAIN AND FOG", "MY POWER IS TEN WATTS", "THE TRAIN LEAVES AT SIX",
    "GOOD LUCK ON YOUR TEST", "THE MOON IS BRIGHT TONIGHT", "OUR CLUB MEETS ON FRIDAY", "THE SIGNAL IS FADING",
    "I WILL CALL YOU LATER", "THE KEY IS SMOOTH AND LIGHT", "SEA WATER IS VERY BLUE", "BIRDS SING IN THE MORNING",
    "THE ROAD IS LONG AND QUIET", "HE READS A BOOK AT NIGHT", "SHE PLAYS THE PIANO", "THE CAMP IS NEAR THE LAKE",
    "WE NEED MORE BATTERIES", "THE MAP SHOWS A SMALL TOWN", "KEEP YOUR SPACING EVEN", "THE OLD SHIP SAILS NORTH",
    "A WARM BREEZE CAME IN", "THE FIELD IS GREEN AND WIDE", "THANK YOU FOR THE CONTACT", "MY LOG BOOK IS FULL",
    "THE STORM PASSED QUICKLY", "THE CAT SLEEPS BY THE FIRE", "SNOW FELL ON THE HILLS", "WE WALKED TO THE MARKET",
};

class App : public QWidget {
public:

    App() {
        setWindowTitle("Paddle Trainer");
        clock.start();
        tone.clock = &clock;
        loadRecs();

        auto *bar = new QHBoxLayout;
        auto add = [&](const QString &label, QWidget *w) { bar->addWidget(new QLabel(label)); bar->addWidget(w); };
        mode->addItems({"Iambic B", "Iambic A", "스트레이트"});
        wpm->setRange(5, 40); wpm->setValue(18);
        eff->setRange(5, 40); eff->setValue(10);
        eff->setToolTip("Farnsworth: 글자는 '글자 WPM'으로 치고, 글자·단어 사이 간격만 늘려 전체 속도를 이 값으로 맞춥니다");
        freq->setRange(300, 1200); freq->setSingleStep(50); freq->setValue(600);
        vol->setRange(0, 100); vol->setValue(50); vol->setMaximumWidth(90);
        level->setRange(2, int(sizeof KOCH) - 1); level->setValue(2);
        span->setRange(1, 15); span->setValue(4);
        add("모드", mode); add("글자 WPM", wpm); add("실효 WPM", eff); add("톤", freq); add("볼륨", vol);
        add("글자 수", level); add("표시 초", span);
        bar->addWidget(swap); bar->addWidget(hint);
        auto *listen = new QPushButton("▶ 글자");
        auto *listenWord = new QPushButton("▶ 단어");
        auto *skip = new QPushButton("새 단어");
        auto *setup = new QPushButton("⚙ 설정");
        bar->addStretch();
        bar->addWidget(listen); bar->addWidget(listenWord); bar->addWidget(skip); bar->addWidget(setup);

        auto *practice = new QWidget;
        auto *lay = new QVBoxLayout(practice);
        lay->addLayout(bar);
        auto *row = new QHBoxLayout;
        auto *fixBox = new QVBoxLayout;
        auto *fixTitle = new QLabel("지적 사항  (최근이 위)");
        fixTitle->setStyleSheet("color:#8fb4ff; font-weight:bold");
        fixList->setWordWrap(true);
        fixList->setFocusPolicy(Qt::NoFocus);
        fixList->setSelectionMode(QAbstractItemView::NoSelection);
        fixList->setStyleSheet("QListWidget { background:#141626; border:1px solid #2a2f50; border-radius:6px; font-size:11pt; }"
                               "QListWidget::item { padding:2px 4px; }");
        fixBox->addWidget(fixTitle);
        fixBox->addWidget(fixList);
        auto *fixPanel = new QWidget;
        fixPanel->setLayout(fixBox);
        fixPanel->setFixedWidth(430);
        row->addWidget(game, 1);
        row->addWidget(fixPanel);
        lay->addLayout(row, 3);
        lay->addWidget(timeline, 1);
        lay->addWidget(status);
        status->setStyleSheet("color:#8890a8");

        auto *statsPage = new QWidget;
        auto *sl = new QVBoxLayout(statsPage);
        auto *periodBar = new QHBoxLayout;
        periodBox->addItems({"오늘", "최근 7일", "최근 30일", "전체"});
        periodBox->setCurrentIndex(1);
        periodBar->addWidget(new QLabel("기간"));
        periodBar->addWidget(periodBox);
        kindBox->addItems({"연습 + 검정", "연습만", "검정만"});
        periodBar->addWidget(new QLabel("  구분"));
        periodBar->addWidget(kindBox);
        periodBar->addStretch();
        auto *pathLabel = new QLabel(QString("기록 파일: %1").arg(QDir::toNativeSeparators(recPath())));
        pathLabel->setStyleSheet("color:#8890a8");
        pathLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
        periodBar->addWidget(pathLabel);
        sl->addLayout(periodBar);
        auto *scroll = new QScrollArea;
        scroll->setWidgetResizable(true);
        scroll->setFrameShape(QFrame::NoFrame);
        scroll->setWidget(stats);
        sl->addWidget(scroll);
        stats->recs = &recs;
        stats->exams = &exams;
        stats->sents = &sentRecs;

        auto *examBar = new QHBoxLayout;
        for (auto &gd : EXAM_GRADES) examGradeBox->addItem(gd.name);
        examMin->setRange(1, 5); examMin->setValue(3); examMin->setSuffix(" 분");
        examBar->addWidget(new QLabel("등급"));
        examBar->addWidget(examGradeBox);
        examBar->addWidget(new QLabel("시간"));
        examBar->addWidget(examMin);
        examBar->addStretch();
        examBar->addWidget(new QLabel("키어 설정(모드·글자 WPM·톤)은 연습 탭 것을 씁니다"));
        examBar->addWidget(examStartBtn);
        examBar->addWidget(examStopBtn);
        examStopBtn->setEnabled(false);
        auto *examLay = new QVBoxLayout(examPage);
        examLay->addLayout(examBar);
        examLay->addWidget(examView, 1);
        examView->crit = &crit;
        examView->gradeName = examGradeBox->currentText();

        tabs->addTab(practice, "연습");
        tabs->addTab(examPage, "검정");
        tabs->addTab(statsPage, "통계");
        tabs->setFocusPolicy(Qt::NoFocus);
        auto *top = new QVBoxLayout(this);
        top->setContentsMargins(0, 0, 0, 0);
        top->addWidget(tabs);

        for (auto *w : std::initializer_list<QWidget *>{game, timeline, examView}) w->installEventFilter(this);
        qApp->installEventFilter(this);
        connect(mode, qOverload<int>(&QComboBox::currentIndexChanged), this, &App::apply);
        for (auto *sb : {wpm, eff, freq, span}) connect(sb, qOverload<int>(&QSpinBox::valueChanged), this, &App::apply);
        connect(vol, &QSlider::valueChanged, this, &App::apply);
        connect(hint, &QCheckBox::toggled, this, &App::apply);
        connect(level, qOverload<int>(&QSpinBox::valueChanged), this, &App::newWord);
        connect(listen, &QPushButton::clicked, this, [this] { listenTo(false); });
        connect(listenWord, &QPushButton::clicked, this, [this] { listenTo(true); });
        connect(skip, &QPushButton::clicked, this, &App::newWord);
        connect(periodBox, qOverload<int>(&QComboBox::currentIndexChanged), this, &App::refreshStats);
        connect(tabs, &QTabWidget::currentChanged, this, &App::refreshStats);
        connect(setup, &QPushButton::clicked, this, &App::openSettings);
        connect(kindBox, qOverload<int>(&QComboBox::currentIndexChanged), this, &App::refreshStats);
        connect(examStartBtn, &QPushButton::clicked, this, &App::examStart);
        connect(examStopBtn, &QPushButton::clicked, this, &App::examFinish);
        connect(examGradeBox, qOverload<int>(&QComboBox::currentIndexChanged), this, [this] {
            if (!examView->running) { examView->gradeName = examGradeBox->currentText(); examView->update(); }
        });
        for (auto *b : {examStartBtn, examStopBtn}) b->setFocusPolicy(Qt::NoFocus);
        for (auto *b : {listen, listenWord, skip, setup}) b->setFocusPolicy(Qt::NoFocus);

        game->crit = &crit;
        loadSettings();
        startAudio();
        apply();
        newWord();
        auto *timer = new QTimer(this);
        timer->setTimerType(Qt::PreciseTimer);
        connect(timer, &QTimer::timeout, this, &App::tick);
        timer->start(15);
    }

protected:
    bool eventFilter(QObject *o, QEvent *e) override {
        switch (e->type()) {
        case QEvent::KeyPress:
        case QEvent::KeyRelease: {
            auto *k = static_cast<QKeyEvent *>(e);
            const bool down = e->type() == QEvent::KeyPress;
            if (k->key() == Qt::Key_Space) {
                if (down && !k->isAutoRepeat()) listenTo(k->modifiers() & Qt::ShiftModifier);
                return true;
            }
            const int phys = k->key() == Qt::Key_BracketLeft ? 0 : k->key() == Qt::Key_BracketRight ? 1 : -1;
            if (phys < 0) return false;
            if (!k->isAutoRepeat()) paddle(phys, down);  // Windows key-repeat is not the paddle
            return true;
        }
        case QEvent::MouseButtonPress:
        case QEvent::MouseButtonDblClick:  // fast dits arrive as double-clicks
        case QEvent::MouseButtonRelease: {
            if (o != game && o != timeline && o != examView) return false;
            auto *m = static_cast<QMouseEvent *>(e);
            if (m->button() != Qt::LeftButton && m->button() != Qt::RightButton) return false;
            paddle(m->button() == Qt::LeftButton ? 0 : 1, e->type() != QEvent::MouseButtonRelease);
            return true;
        }
        default:
            return false;
        }
    }

private:
    QElapsedTimer clock;
    Tone tone;
    QString audioInfo;
    Decoder dec;
    Timing timing{80, 240, 560};
    QVector<Elem> cur, last;   // elements of the character being keyed / the last judged one
    std::string lastRef;
    double lastEnd = -1;
    double allUpAt = -1;       // when both paddles were last released, -1 while one is held
    double prevCharEnd = -1;   // end of the previous correct character in this word
    int owe = 0;               // correct repeats still needed for the current letter (1 per miss, up to crit.maxRepeat)
    Criteria crit;             // scoring rules, edited in the settings dialog
    QVector<Rec> recs;
    QTabWidget *tabs = new QTabWidget;
    Game *game = new Game;
    Timeline *timeline = new Timeline;
    Stats *stats = new Stats;
    QComboBox *periodBox = new QComboBox;
    QLabel *status = new QLabel;
    QListWidget *fixList = new QListWidget;  // feedback per judged character, newest on top
    QComboBox *mode = new QComboBox;
    QSpinBox *wpm = new QSpinBox, *eff = new QSpinBox, *freq = new QSpinBox, *level = new QSpinBox, *span = new QSpinBox;
    QSlider *vol = new QSlider(Qt::Horizontal);
    QCheckBox *swap = new QCheckBox("좌우 반전"), *hint = new QCheckBox("힌트");
    QComboBox *kindBox = new QComboBox;
    // exam
    QWidget *examPage = new QWidget;
    ExamView *examView = new ExamView;
    QComboBox *examGradeBox = new QComboBox;
    QSpinBox *examMin = new QSpinBox;
    QPushButton *examStartBtn = new QPushButton("▶ 시작"), *examStopBtn = new QPushButton("■ 끝내기");
    std::vector<ExamChar> examSent;
    int examCorr = 0;
    double examStartMs = 0, examLastMs = 0, examPrevEnd = -1;
    QVector<ExamRec> exams;
    QVector<SentRec> sentRecs;

    double ms() const { return clock.nsecsElapsed() / 1e6; }
    char target() const { return game->pos < game->word.size() ? game->word[game->pos].toLatin1() : 0; }

    static QString recPath() {
        const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
        QDir().mkpath(dir);
        return dir + "/attempts.csv";
    }
    void loadRecs() {
        QFile f(recPath());
        if (!f.open(QIODevice::ReadOnly | QIODevice::Text)) return;
        f.readLine();  // header
        while (!f.atEnd()) {
            const QList<QByteArray> c = f.readLine().trimmed().split(',');
            if (c.size() < 8 || c[1].isEmpty()) continue;
            recs.push_back({c[0].toLongLong(), c[1][0], c[2] == "1", c[3].toInt(), c[4].toInt(), c[5].toInt(), c[6].toInt(), c[7].toUInt(),
                            c.size() > 8 ? c[8].toInt() : 0});
        }
        QFile fe(dataPath("exams.csv"));
        if (fe.open(QIODevice::ReadOnly | QIODevice::Text)) {
            fe.readLine();
            while (!fe.atEnd()) {
                const QStringList c = QString::fromUtf8(fe.readLine().trimmed()).split(',');
                if (c.size() < 14) continue;
                exams.push_back({c[0].toLongLong(), c[1], c[2].toInt(), c[3].toInt(), c[4].toInt(), c[5].toInt(), c[6].toInt(),
                                 c[7].toInt(), c[8].toInt(), c[9].toInt(), c[10].toInt(), c[11].toInt(), c[12].toInt(), c[13].toInt()});
            }
        }
        QFile fs(dataPath("sentences.csv"));
        if (fs.open(QIODevice::ReadOnly | QIODevice::Text)) {
            fs.readLine();
            while (!fs.atEnd()) {
                const QStringList c = QString::fromUtf8(fs.readLine().trimmed()).split(',');
                if (c.size() < 10) continue;
                sentRecs.push_back({c[0].toLongLong(), c[1].toInt(), c[2], c[3], c[4].toInt(), c[5].toInt(), c[6].toInt(),
                                    c[7].toInt(), c[8].toInt(), c[9].toInt()});
            }
        }
    }
    void saveRec(const Rec &r) {
        recs.push_back(r);
        QFile f(recPath());
        const bool fresh = !f.exists();
        if (!f.open(QIODevice::Append | QIODevice::Text)) return;
        if (fresh) f.write("time,char,ok,score,mode,wpm,eff,habits,kind\n");
        f.write(QString("%1,%2,%3,%4,%5,%6,%7,%8,%9\n").arg(r.time).arg(QChar(r.ch)).arg(int(r.ok)).arg(r.score)
                    .arg(r.mode).arg(r.wpm).arg(r.eff).arg(r.habits).arg(r.kind).toLatin1());
    }
    static QString dataPath(const QString &name) { return QFileInfo(recPath()).absolutePath() + "/" + name; }
    // appends one CSV line (header first if the file is new); commas inside fields become '·'
    static void appendCsv(const QString &name, const QString &header, QStringList fields) {
        QFile f(dataPath(name));
        const bool fresh = !f.exists();
        if (!f.open(QIODevice::Append | QIODevice::Text)) return;
        if (fresh) f.write((header + "\n").toUtf8());
        for (QString &x : fields) x.replace(',', QChar(0xB7));
        f.write((fields.join(',') + "\n").toUtf8());
    }

    // Scoring criteria shown in the settings dialog. Sources: ITU-R M.1677 (1:3 / 1:3:7 spacing),
    // Pierpont "The Art & Skill of Radio-Telegraphy" (spacing first), W8RIT "Iambic Sending" (release, no gap > 1 dit).
    struct Field { const char *key, *label; double Criteria::*m; double lo, hi, step; const char *suffix, *tip; };
    static const std::vector<Field> &fields() {
        static const std::vector<Field> f = {
            {nullptr, "통과 / 진급", nullptr, 0, 0, 0, "", ""},
            {"passScore", "통과 점수", &Criteria::passScore, 50, 100, 5, " 점", "이 점수 미만이면 글자를 맞혀도 실패로 보고 다시 칩니다"},
            {"maxRepeat", "실패 시 최대 반복", &Criteria::maxRepeat, 1, 10, 1, " 회", "실패 1번마다 성공 1번 필요, 최대 이 횟수"},
            {"levelAvg", "다음 글자 추가: 평균", &Criteria::levelAvg, 50, 100, 1, " 점", "코흐 방식: 90% 이상이면 다음 글자를 추가"},
            {"levelCount", "다음 글자 추가: 최근 시도", &Criteria::levelCount, 5, 100, 5, " 회", "글자마다 최근 몇 번의 평균을 볼지"},
            {"hintFactor", "힌트 사용 시 점수", &Criteria::hintFactor, 0, 100, 5, " %", ""},
            {"gapWeight", "간격 항목 가중치", &Criteria::gapWeight, 1, 5, .5, " 배", "Pierpont: 간격이 리듬의 핵심 — 간격 항목을 다른 항목의 몇 배로 셀지"},
            {nullptr, "글자 간격 (dit 3개 ~ Farnsworth 사이면 만점)", nullptr, 0, 0, 0, "", ""},
            {"shortOk", "짧음: 만점 한계", &Criteria::shortOk, 0, 100, 5, " %", "표준 간격(dit 3개)보다 짧을 때. 글자가 붙으면 다른 글자로 들림 — 엄격하게"},
            {"shortZero", "짧음: 0점", &Criteria::shortZero, 5, 100, 5, " %", ""},
            {"longOk", "김: 만점 한계", &Criteria::longOk, 0, 300, 10, " %", "Farnsworth 간격보다 길 때. 길면 느릴 뿐 알아듣는 데는 지장이 적음 — 넉넉하게"},
            {"longZero", "김: 0점", &Criteria::longZero, 10, 500, 10, " %", ""},
            {nullptr, "Iambic (키어가 요소 길이를 만듦)", nullptr, 0, 0, 0, "", ""},
            {"releaseOk", "늦게 뗌: 만점 한계 (dit 대비)", &Criteria::releaseOk, 0, 100, 5, " %", "키어는 소리가 끝나고 dit 1개 뒤에 패들을 다시 읽음"},
            {"releaseZero", "늦게 뗌: 0점", &Criteria::releaseZero, 5, 200, 5, " %", ""},
            {"hesitateOk", "요소 사이 머뭇거림: 만점 한계", &Criteria::hesitateOk, 0, 100, 5, " %", "W8RIT: 요소 사이는 dit 1개를 넘기지 말 것"},
            {"hesitateZero", "요소 사이 머뭇거림: 0점", &Criteria::hesitateZero, 5, 200, 5, " %", ""},
            {nullptr, "스트레이트 (손이 모두 만듦)", nullptr, 0, 0, 0, "", ""},
            {"elemOk", "dit:dah 비율: 만점 한계", &Criteria::elemOk, 0, 100, 5, " %", "ITU: dah = dit 3개. 내 dit 대비 비율로 판정"},
            {"elemZero", "dit:dah 비율: 0점", &Criteria::elemZero, 5, 200, 5, " %", ""},
            {"elemGapOk", "요소 사이 간격: 만점 한계", &Criteria::elemGapOk, 0, 100, 5, " %", "ITU: 요소 사이 = dit 1개"},
            {"elemGapZero", "요소 사이 간격: 0점", &Criteria::elemGapZero, 5, 200, 5, " %", ""},
            {"speedOk", "속도: 만점 한계", &Criteria::speedOk, 0, 100, 5, " %", "내 dit 길이 vs 글자 WPM의 dit"},
            {"speedZero", "속도: 0점", &Criteria::speedZero, 5, 200, 5, " %", ""},
            {nullptr, "검정 (옛 아마추어무선기사 전신 실기 송신 성적표 형식)", nullptr, 0, 0, 0, "", ""},
            {"examPass", "합격 점수", &Criteria::examPass, 0, 100, 5, " 점", "KCA 무선종사자 시험: 60점 이상 합격"},
            {"examErr", "오자·탈자·용자 1자당 감점", &Criteria::examErr, 0, 10, .5, " 점", "틀린 글자 / 빠뜨린 글자 / 더 친 글자"},
            {"examUnclear", "자호불명료 1자당 감점", &Criteria::examUnclear, 0, 10, .5, " 점", "글자는 맞지만 리듬 점수가 아래 기준 미만"},
            {"examUnclearBelow", "자호불명료 기준 (리듬 점수)", &Criteria::examUnclearBelow, 0, 100, 5, " 점", "이 점수 미만이면 알아보기 어려운 글자로 봄"},
            {"examCorr", "정정 1회당 감점", &Criteria::examCorr, 0, 10, .5, " 점", "dit 8개(........)로 정정"},
            {"examShort", "기준속도미달 1자당 감점", &Criteria::examShort, 0, 10, .5, " 점", "시간 안에 보내지 못한 글자 수"},
            {"examOver", "표준속도초과 감점", &Criteria::examOver, 0, 30, 1, " 점", "기준 속도보다 너무 빨리 보냄"},
            {"examOverPct", "표준속도초과 기준", &Criteria::examOverPct, 100, 300, 10, " %", "기준 속도의 몇 %를 넘으면 초과로 볼지"},
        };
        return f;
    }
    static QString settingsPath() { return QFileInfo(recPath()).absolutePath() + "/settings.ini"; }
    // ok/zero pairs: zero must lie beyond ok or the score slope divides by zero
    static void sanitize(Criteria &c) {
        const std::pair<double Criteria::*, double Criteria::*> pairs[] = {
            {&Criteria::shortOk, &Criteria::shortZero}, {&Criteria::longOk, &Criteria::longZero},
            {&Criteria::releaseOk, &Criteria::releaseZero}, {&Criteria::hesitateOk, &Criteria::hesitateZero},
            {&Criteria::elemOk, &Criteria::elemZero}, {&Criteria::elemGapOk, &Criteria::elemGapZero},
            {&Criteria::speedOk, &Criteria::speedZero}};
        for (auto [ok, zero] : pairs) c.*zero = std::max(c.*zero, c.*ok + 5);
    }
    std::vector<std::pair<const char *, QSpinBox *>> uiBoxes() const {
        return {{"wpm", wpm}, {"eff", eff}, {"freq", freq}, {"level", level}, {"span", span}};
    }
    void loadSettings() {
        QSettings st(settingsPath(), QSettings::IniFormat);
        for (auto &f : fields())
            if (f.m) crit.*f.m = st.value(QString("criteria/") + f.key, crit.*f.m).toDouble();
        sanitize(crit);
        mode->setCurrentIndex(st.value("ui/mode", mode->currentIndex()).toInt());
        for (auto [key, sb] : uiBoxes()) sb->setValue(st.value(QString("ui/") + key, sb->value()).toInt());
        vol->setValue(st.value("ui/vol", vol->value()).toInt());
        swap->setChecked(st.value("ui/swap", false).toBool());
        hint->setChecked(st.value("ui/hint", false).toBool());
    }
    void saveSettings() {
        QSettings st(settingsPath(), QSettings::IniFormat);
        for (auto &f : fields())
            if (f.m) st.setValue(QString("criteria/") + f.key, crit.*f.m);
        st.setValue("ui/mode", mode->currentIndex());
        for (auto [key, sb] : uiBoxes()) st.setValue(QString("ui/") + key, sb->value());
        st.setValue("ui/vol", vol->value());
        st.setValue("ui/swap", swap->isChecked());
        st.setValue("ui/hint", hint->isChecked());
    }
    void closeEvent(QCloseEvent *e) override { saveSettings(); QWidget::closeEvent(e); }

    void openSettings() {
        QDialog dlg(this);
        dlg.setWindowTitle("채점 기준 설정");
        auto *form = new QFormLayout;
        std::vector<std::pair<QDoubleSpinBox *, const Field *>> boxes;
        for (auto &f : fields()) {
            if (!f.m) {
                auto *head = new QLabel(f.label);
                head->setStyleSheet("color:#8fb4ff; font-weight:bold; margin-top:8px");
                form->addRow(head);
                continue;
            }
            auto *sb = new QDoubleSpinBox;
            sb->setRange(f.lo, f.hi);
            sb->setSingleStep(f.step);
            sb->setDecimals(f.step < 1 ? 1 : 0);
            sb->setSuffix(f.suffix);
            sb->setValue(crit.*f.m);
            sb->setToolTip(f.tip);
            auto *label = new QLabel(f.label);
            label->setToolTip(f.tip);
            form->addRow(label, sb);
            boxes.push_back({sb, &f});
        }
        auto *src = new QLabel("기준 출처: ITU-R M.1677 (dit 1 · dah 3 · 요소 간격 1 · 글자 간격 3 · 단어 간격 7), "
                               "Pierpont 「The Art & Skill of Radio-Telegraphy」 (간격이 가장 중요, 세지 말고 소리로), "
                               "W8RIT 「Iambic Sending」 (요소 사이 dit 1개 이내, Mode B는 마지막 요소 절반 전에 떼기).\n"
                               "만점 한계 안이면 100%, 0점 값까지 직선으로 감점됩니다. 항목 이름에 마우스를 올리면 설명이 나옵니다.\n"
                               "저장 위치: " + QDir::toNativeSeparators(settingsPath()));
        src->setWordWrap(true);
        src->setTextInteractionFlags(Qt::TextSelectableByMouse);
        src->setStyleSheet("color:#8890a8");
        auto *buttons = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel | QDialogButtonBox::RestoreDefaults);
        buttons->button(QDialogButtonBox::RestoreDefaults)->setText("기본값으로 초기화");
        buttons->button(QDialogButtonBox::Ok)->setText("저장");
        buttons->button(QDialogButtonBox::Cancel)->setText("취소");
        connect(buttons, &QDialogButtonBox::accepted, &dlg, &QDialog::accept);
        connect(buttons, &QDialogButtonBox::rejected, &dlg, &QDialog::reject);
        connect(buttons->button(QDialogButtonBox::RestoreDefaults), &QPushButton::clicked, &dlg, [&] {
            const Criteria d;
            for (auto [sb, f] : boxes) sb->setValue(d.*(f->m));
        });
        auto *inner = new QWidget;
        inner->setLayout(form);
        auto *scroll = new QScrollArea;
        scroll->setWidget(inner);
        scroll->setWidgetResizable(true);
        scroll->setFrameShape(QFrame::NoFrame);
        auto *lay = new QVBoxLayout(&dlg);
        lay->addWidget(scroll);
        lay->addWidget(src);
        lay->addWidget(buttons);
        dlg.resize(560, 760);
        if (dlg.exec() != QDialog::Accepted) return;
        for (auto [sb, f] : boxes) crit.*(f->m) = sb->value();
        sanitize(crit);
        saveSettings();
        owe = qMin(owe, int(crit.maxRepeat));
        game->update();
    }

    void startAudio() {
        const QString err = tone.start();
        if (!err.isEmpty()) QMessageBox::warning(this, "Paddle Trainer", err);
        audioInfo = QString("오디오 버퍼 %1 ms").arg(tone.bufferMs, 0, 'f', 1);
    }

    void apply() {
        if (eff->value() > wpm->value()) { eff->setValue(wpm->value()); return; }  // re-enters apply()
        timing = farnsworth(wpm->value(), eff->value());
        {
            QMutexLocker l(&tone.mx);
            tone.keyer.mode = Keyer::Mode(mode->currentIndex());
            tone.keyer.dit = qRound(tone.rate * timing.ditMs / 1000);
            tone.freq = freq->value();
            tone.vol = vol->value() / 100.0;
        }
        dec.ditMs = timeline->ditMs = timing.ditMs;
        dec.spaceMs = (timing.charGapMs + timing.wordGapMs) / 2;
        game->timing = timing;
        timeline->windowMs = span->value() * 1000;
        game->hint = hint->isChecked();
        game->straight = mode->currentIndex() == Keyer::Straight;
    }

    void paddle(int phys, bool down) {
        const int lane = swap->isChecked() ? 1 - phys : phys;
        (lane ? tone.dahIn : tone.ditIn) = down;
        auto &bars = timeline->lanes[lane];
        const bool held = !bars.isEmpty() && bars.last().t1 < 0;
        const double t = ms();
        if (down && !held) bars.push_back({t, -1, {}});
        else if (!down && held) { bars.last().t1 = t; bars.last().label = QString::number(qRound(t - bars.last().t0)); }
        allUpAt = tone.ditIn || tone.dahIn ? -1 : t;
    }

    void listenTo(bool wholeWord) {
        std::vector<std::string> chars;
        const int from = wholeWord ? 0 : game->pos, to = wholeWord ? game->word.size() : game->pos + 1;
        for (int i = from; i < qMin(to, int(game->word.size())); ++i) chars.push_back(patternOf(game->word[i].toLatin1()));
        tone.play(chars, timing.charGapMs);
    }

    // recent average per character (last 20 tries): weak characters come up much more often
    // avg 95 -> 1.0, 85 -> 1.3, 70 -> 2.1, 50 -> 4, 0 -> 13 (squared so weak ones stand out)
    double weight(char ch) const {
        int n = 0;
        double sum = 0;
        for (int i = recs.size() - 1; i >= 0 && n < 20; --i)
            if (recs[i].ch == ch) { sum += recs[i].score; ++n; }
        const double d = 1 - sum / qMax(n, 1) / 100;
        return n ? 1 + 12 * d * d : 4;  // never practised: extra weight
    }

    void newWord() {
        const int n = level->value();
        QVector<double> w(n);
        double total = 0;
        for (int i = 0; i < n; ++i) total += w[i] = weight(KOCH[i]);
        game->word.clear();
        for (int k = 0; k < WORD_LEN; ++k) {
            double r = QRandomGenerator::global()->bounded(total);
            int i = 0;
            while (i < n - 1 && (r -= w[i]) > 0) ++i;
            game->word += KOCH[i];
        }
        game->pos = 0;
        game->doneColor.clear();
        last.clear();
        prevCharEnd = -1;
        owe = game->owe = 0;
        stats->koch = QString::fromLatin1(KOCH, n);
        // level-up rule: every practised character averages 90+ over its last 20 tries
        bool ready = true;
        for (int i = 0; i < n && ready; ++i) {
            int cnt = 0;
            double sum = 0;
            for (int j = recs.size() - 1; j >= 0 && cnt < crit.levelCount; --j)
                if (recs[j].ch == KOCH[i]) { sum += recs[j].score; ++cnt; }
            ready = cnt >= crit.levelCount && sum / cnt >= crit.levelAvg;
        }
        const QString next = ready && n < int(sizeof KOCH) - 1 ? QString("   ·   ★ 다음 글자 '%1' 추가 권장").arg(KOCH[n])
                                                                : QString("   ·   글자 추가 기준: 모든 글자 최근 20회 평균 90점");
        status->setText(QString("연습 글자: %1%2   ·   [ ] 키 (어댑터 모드 2) 또는 화면 위 마우스 좌/우 클릭   ·   %3").arg(stats->koch, next, audioInfo));
    }

    void refreshStats() {
        static const int periods[] = {1, 7, 30, 0};
        stats->days = periods[periodBox->currentIndex()];
        stats->kind = kindBox->currentIndex() - 1;
        stats->setMinimumHeight(stats->contentHeight(qMax(400, stats->width())));
        stats->update();
    }

    void tick() {
        const double now = ms();
        QVector<Elem> els;
        double live;
        {
            QMutexLocker l(&tone.mx);
            els.swap(tone.done);
            live = tone.liveStart;
        }
        for (auto &e : els) {
            dec.element(e.ms, e.sym);
            cur.push_back(e);
            lastEnd = e.t1;
            const char s = dec.pattern.back();
            timeline->lanes[2].push_back({e.t0, e.t1, QString("%1 %2").arg(s == '.' ? "·" : "—").arg(qRound(e.ms))});
        }
        if (live < 0 && lastEnd > 0 && !cur.isEmpty()) {
            const std::string sent = dec.pattern;
            if (char c = dec.gap(now - lastEnd)) {
                timeline->marks.push_back({(cur[0].t0 + lastEnd) / 2, QString(QChar(c))});
                if (tabs->currentWidget() == examPage) examChar(sent, c);
                else judge(sent);
            }
        }
        if (dec.text.size() > 200) dec.text.erase(0, 100);
        if (examView->running) {
            examView->leftMs = examMin->value() * 60000.0 - (now - examStartMs);
            examView->live = QString::fromStdString(dec.pattern).replace('.', QChar(0xB7)).replace('-', QChar(0x2014));
            examView->update();
            if (examView->leftMs <= 0) examFinish();
        }

        const bool keying = !cur.isEmpty() || live >= 0;
        const bool fresh = keying || last.isEmpty();  // else keep showing the last result until you key again
        game->cmpRef = fresh ? patternOf(target()) : lastRef;
        game->elems = fresh ? cur : last;
        if (fresh) game->releaseAt = game->charGap = -1;
        game->liveStart = live;
        game->now = timeline->now = now;
        timeline->liveTone = live;
        timeline->prune();
        game->update();
        timeline->update();
    }

    void examStart() {
        const ExamGradeDef &gd = EXAM_GRADES[examGradeBox->currentIndex()];
        const int required = gd.rate * examMin->value();
        std::vector<std::string> text;
        int letters = 0;
        const int n = level->value();
        auto *rng = QRandomGenerator::global();
        while (letters < required) {
            std::string sentence;
            if (gd.learned) {
                for (int gIdx = 0; gIdx < 3; ++gIdx) {
                    if (gIdx) sentence += ' ';
                    for (int k = 0; k < 5; ++k) {
                        char ch;
                        do ch = KOCH[rng->bounded(n)]; while (ch == ',');  // ',' would break the CSV record
                        sentence += ch;
                    }
                }
            } else {
                sentence = EXAM_TEXT[rng->bounded(int(std::size(EXAM_TEXT)))];
            }
            letters += int(std::count_if(sentence.begin(), sentence.end(), [](char ch) { return ch != ' '; }));
            text.push_back(sentence);
        }
        examSent.clear();
        examCorr = 0;
        examPrevEnd = -1;
        cur.clear();
        dec.pattern.clear();
        examView->sentences = text;
        examView->required = required;
        examView->sentCount = 0;
        examView->gradeName = examGradeBox->currentText();
        examView->running = true;
        examView->finished = false;
        examStartMs = examLastMs = ms();
        examView->leftMs = examMin->value() * 60000.0;
        examRegrade(0);
        examStartBtn->setEnabled(false);
        examStopBtn->setEnabled(true);
        examGradeBox->setEnabled(false);
        examMin->setEnabled(false);
    }

    void examRegrade(double elapsedMin) {
        const ExamGradeDef &gd = EXAM_GRADES[examGradeBox->currentIndex()];
        examView->g = gradeExam(examView->sentences, examSent, examCorr, examView->required, elapsedMin, gd.rate, crit);
        examView->sentCount = int(examSent.size());
        examView->update();
    }

    // A character decoded during the exam: rhythm-judge it as sent, or treat 6+ dits as the correction sign.
    void examChar(const std::string &sent, char c) {
        if (!examView->running) { cur.clear(); return; }
        if (sent.size() >= 6 && sent.find('-') == std::string::npos) {  // ........ = error, erase the last letter
            if (!examSent.empty()) examSent.pop_back();
            ++examCorr;
        } else {
            std::vector<double> on, gaps;
            for (int i = 0; i < cur.size(); ++i) {
                on.push_back(cur[i].ms);
                if (i) gaps.push_back(cur[i].t0 - cur[i - 1].t1);
            }
            double charGap = examPrevEnd >= 0 ? cur[0].t0 - examPrevEnd : -1;
            if (charGap > dec.spaceMs) charGap = -1;  // a word space: not graded
            const double releaseLate = allUpAt >= 0 ? allUpAt - cur.last().t1 : NAN;
            const Verdict v = ::judge(sent, sent, on, gaps, charGap, releaseLate, Keyer::Mode(mode->currentIndex()), false, timing, crit);
            examSent.push_back({c, v.score, v.habits});
        }
        examPrevEnd = cur.last().t1;
        examLastMs = cur.last().t1;
        cur.clear();
        examRegrade(0);
        if (examView->g.end == int(examView->g.letters.size())) examFinish();  // last letter sent: score it now
    }

    void examFinish() {
        if (!examView->running) return;
        examView->running = false;
        examView->finished = true;
        examView->live.clear();
        examRegrade((examLastMs - examStartMs) / 60000.0);
        examStartBtn->setEnabled(true);
        examStopBtn->setEnabled(false);
        examGradeBox->setEnabled(true);
        examMin->setEnabled(true);
        examView->update();
        if (examSent.empty() && !examCorr) return;  // nothing keyed: not an attempt, keep it out of the records
        const ExamGrade &g = examView->g;
        const ExamGradeDef &gd = EXAM_GRADES[examGradeBox->currentIndex()];
        const qint64 t = QDateTime::currentSecsSinceEpoch();
        // per letter -> attempts.csv (kind 1), so exam letters show up in the per-character stats
        for (const LetterGrade &lg : g.letters)
            if (lg.reached) saveRec({t, lg.want, lg.ok, lg.score, mode->currentIndex(), wpm->value(), eff->value(), lg.habits, 1});
        // per sentence -> sentences.csv
        for (int k = 0; k < int(g.sentences.size()); ++k) {
            const SentenceGrade &sg = g.sentences[k];
            if (!sg.reached) continue;
            const SentRec sr{t, k, QString::fromStdString(examView->sentences[k]), QString::fromStdString(sg.sent),
                             sg.sub, sg.del, sg.ins, sg.unclear, sg.unsent, sg.score};
            sentRecs.push_back(sr);
            appendCsv("sentences.csv", "time,idx,target,sent,sub,del,ins,unclear,unsent,score",
                      {QString::number(t), QString::number(k), sr.target, sr.sent, QString::number(sr.sub), QString::number(sr.del),
                       QString::number(sr.ins), QString::number(sr.unclear), QString::number(sr.unsent), QString::number(sr.score)});
        }
        // the exam -> exams.csv
        const ExamRec er{t, gd.name, gd.rate, examMin->value(), int(examSent.size()), g.sub, g.del, g.ins, g.unclear, g.corr,
                         g.shortfall, int(g.over), g.score, int(g.score >= crit.examPass)};
        exams.push_back(er);
        QStringList f{QString::number(er.time), er.grade};
        for (int x : {er.rate, er.minutes, er.sent, er.sub, er.del, er.ins, er.unclear, er.corr, er.shortfall, er.over, er.score, er.pass})
            f << QString::number(x);
        appendCsv("exams.csv", "time,grade,rate,minutes,sent,sub,del,ins,unclear,corr,shortfall,over,score,pass", f);
    }

    // One result line on the game panel + the full findings at the top of the list.
    void report(char ch, const QString &line, QColor c, const Verdict &v) {
        game->pop(QString("'%1'  %2").arg(QChar(ch)).arg(line), c);
        QStringList rows{QString("'%1'  %2").arg(QChar(ch)).arg(line)};
        for (auto &n : v.notes) rows << "   ⚠ " + QString::fromStdString(n);
        if (v.worst) rows << QString("   → ") + habitAdvice(v.worst);
        for (int i = rows.size() - 1; i >= 0; --i) {
            auto *item = new QListWidgetItem(rows[i]);
            item->setForeground(i == 0 ? c : rows[i].startsWith("   ⚠") ? QColor(255, 150, 130) : QColor(170, 200, 255));
            if (i == 0) { QFont f = item->font(); f.setBold(true); item->setFont(f); }
            fixList->insertItem(0, item);
        }
        while (fixList->count() > 300) delete fixList->takeItem(fixList->count() - 1);
        fixList->scrollToTop();
    }

    void judge(const std::string &sent) {
        const char want = target();
        if (!want) { cur.clear(); return; }  // keyed during the word-clear pause
        const std::string ref = patternOf(want);
        const bool straight = mode->currentIndex() == Keyer::Straight;
        std::vector<double> on, gaps;
        for (int i = 0; i < cur.size(); ++i) {
            on.push_back(cur[i].ms);
            if (i) gaps.push_back(cur[i].t0 - cur[i - 1].t1);
        }
        const double charGap = prevCharEnd >= 0 ? cur[0].t0 - prevCharEnd : -1;
        // ponytail: release compared with the tone's render time; the ~20 ms audio buffer is not subtracted
        const double releaseLate = allUpAt >= 0 ? allUpAt - cur.last().t1 : NAN;
        const Verdict v = ::judge(ref, sent, on, gaps, charGap, releaseLate, Keyer::Mode(mode->currentIndex()), hint->isChecked(), timing, crit);

        game->releaseAt = straight ? -1 : allUpAt;
        game->charGap = charGap;
        saveRec({QDateTime::currentSecsSinceEpoch(), want, sent == ref, v.score, mode->currentIndex(), wpm->value(), eff->value(), v.habits});

        last = cur;
        lastRef = ref;
        cur.clear();
        ++game->attempts;
        game->scoreSum += v.score;
        if (sent != ref) {
            game->combo = 0;
            prevCharEnd = -1;
            owe = qMin(owe + 1, int(crit.maxRepeat));
            const auto it = morseTable().find(sent);
            game->owe = owe;
            report(want, QString("MISS '%1'  ·  ↻ 같은 글자 %2회 더").arg(it == morseTable().end() ? QChar('?') : QChar(it->second)).arg(owe),
                   QColor(255, 80, 90), v);
            return;  // typing-tutor style: retry the same letter
        }
        prevCharEnd = last.last().t1;
        const QColor c = gradeColor(v.score);
        ++game->hits;
        game->combo = v.score >= 70 ? game->combo + 1 : 0;  // sloppy keying breaks the combo too
        game->bestCombo = qMax(game->bestCombo, game->combo);
        game->score += qRound(v.score * (1 + game->combo / 10.0));
        if (v.score < crit.passScore) {  // right letter, rhythm under the pass mark: counts as a miss too
            owe = qMin(owe + 1, int(crit.maxRepeat));
            game->owe = owe;
            report(want, QString("%1점  ·  통과 %2 미만  ·  ↻ 같은 글자 %3회 더").arg(v.score).arg(crit.passScore).arg(owe), c, v);
            return;
        }
        if (owe > 0 && --owe > 0) {  // earlier misses: repeat the same letter until paid off
            game->owe = owe;
            report(want, QString("%1 %2  ·  ✔ 통과  ·  ↻ 같은 글자 %3회 더").arg(gradeName(v.score)).arg(v.score).arg(owe), c, v);
            return;
        }
        game->owe = 0;
        report(want, QString("%1 %2  ·  ✔ 통과 → 다음 글자").arg(gradeName(v.score)).arg(v.score), c, v);
        game->doneColor.push_back(c);
        if (++game->pos == game->word.size()) {
            game->score += 100;
            game->pop("WORD CLEAR! +100", QColor(255, 215, 90));
            QTimer::singleShot(900, this, &App::newWord);
        }
    }
};

int main(int argc, char **argv) {
    QApplication a(argc, argv);
    a.setApplicationName("PaddleTrainer");
    a.setFont(QFont("Malgun Gothic", 10));
    a.setStyleSheet(R"(
        QWidget { background: #0e0f18; color: #d8dcf0; }
        QSpinBox, QComboBox { background: #1c1f33; border: 1px solid #3a4068; border-radius: 5px; padding: 3px 6px; }
        QPushButton { background: #3355dd; color: white; border: none; border-radius: 6px; padding: 6px 12px; font-weight: bold; }
        QPushButton:hover { background: #4a6cff; }
        QTabBar::tab { background: #181a2a; color: #9098b8; padding: 8px 22px; border-top-left-radius: 6px; border-top-right-radius: 6px; }
        QTabBar::tab:selected { background: #2a3160; color: white; font-weight: bold; }
        QTabWidget::pane { border: none; }
        QToolTip { background: #1c1f33; color: #d8dcf0; border: 1px solid #3a4068; }
        QSlider::groove:horizontal { height: 6px; background: #2a2f4a; border-radius: 3px; }
        QSlider::handle:horizontal { background: #5ad2ff; width: 14px; margin: -5px 0; border-radius: 7px; })");
    App w;
    w.resize(1280, 840);
    w.show();
    return a.exec();
}
