package com.parksy.paddletrainer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// Android version of the desktop Paddle Trainer: practice (Koch letters, rhythm scoring), exam, stats, settings.
// Paddles: a USB adapter sending '[' ']' (OTG), or the on-screen paddle at the bottom.
public class MainActivity extends Activity {
    static final int WORD_LEN = 5;
    static final String KOCH = Morse.KOCH;

    // Exam grades: name, letters per minute, letters drawn from the learned Koch set (5-letter groups) or plain English.
    static final String[] GRADE_NAMES = {"배운 글자 5자 그룹 · 1분간 20자", "영문 보통어 · 1분간 20자 (연습)",
            "제3급 아마추어무선기사(전신급) · 1분간 35자", "제2급 아마추어무선기사 · 1분간 50자"};
    static final int[] GRADE_RATE = {20, 20, 35, 50};
    static final boolean[] GRADE_LEARNED = {true, false, false, false};
    static final String[] EXAM_TEXT = {
            "THE SUN IS UP", "WE MEET AT NOON", "THE RIVER RUNS FAST", "MY RADIO WORKS WELL", "SEND YOUR NAME AGAIN",
            "THE WIND IS FROM THE WEST", "IT IS COLD TODAY", "THE BAND IS OPEN", "I HEAR YOU VERY WELL", "PLEASE SEND SLOWER",
            "THE ANTENNA IS ON THE ROOF", "WE HAVE RAIN AND FOG", "MY POWER IS TEN WATTS", "THE TRAIN LEAVES AT SIX",
            "GOOD LUCK ON YOUR TEST", "THE MOON IS BRIGHT TONIGHT", "OUR CLUB MEETS ON FRIDAY", "THE SIGNAL IS FADING",
            "I WILL CALL YOU LATER", "THE KEY IS SMOOTH AND LIGHT", "SEA WATER IS VERY BLUE", "BIRDS SING IN THE MORNING",
            "THE ROAD IS LONG AND QUIET", "HE READS A BOOK AT NIGHT", "SHE PLAYS THE PIANO", "THE CAMP IS NEAR THE LAKE",
            "WE NEED MORE BATTERIES", "THE MAP SHOWS A SMALL TOWN", "KEEP YOUR SPACING EVEN", "THE OLD SHIP SAILS NORTH",
            "A WARM BREEZE CAME IN", "THE FIELD IS GREEN AND WIDE", "THANK YOU FOR THE CONTACT", "MY LOG BOOK IS FULL",
            "THE STORM PASSED QUICKLY", "THE CAT SLEEPS BY THE FIRE", "SNOW FELL ON THE HILLS", "WE WALKED TO THE MARKET"};

    // Scoring criteria in the settings dialog (key = Criteria field). Sources: ITU-R M.1677, Pierpont, W8RIT.
    static final class Field {
        final String key, label, suffix, tip; final double lo, hi;
        Field(String key, String label, double lo, double hi, String suffix, String tip) {
            this.key = key; this.label = label; this.lo = lo; this.hi = hi; this.suffix = suffix; this.tip = tip;
        }
    }
    static Field head(String label) { return new Field(null, label, 0, 0, "", ""); }
    static final Field[] FIELDS = {
            head("통과 / 진급"),
            new Field("passScore", "통과 점수", 50, 100, "점", "이 점수 미만이면 글자를 맞혀도 실패로 보고 다시 칩니다"),
            new Field("maxRepeat", "실패 시 최대 반복", 1, 10, "회", "실패 1번마다 성공 1번 필요, 최대 이 횟수"),
            new Field("levelAvg", "다음 글자 추가: 평균", 50, 100, "점", "코흐 방식: 90% 이상이면 다음 글자를 추가"),
            new Field("levelCount", "다음 글자 추가: 최근 시도", 5, 100, "회", "글자마다 최근 몇 번의 평균을 볼지"),
            new Field("hintFactor", "힌트 사용 시 점수", 0, 100, "%", ""),
            new Field("alertWindow", "습관 알림: 최근 몇 회를 볼지", 3, 50, "회", "같은 습관이 반복되면 지적 사항 위에 경고를 띄웁니다"),
            new Field("alertCount", "습관 알림: 몇 회 이상이면 경고", 2, 50, "회", "위 횟수 중 같은 습관이 이만큼 나오면 경고"),
            new Field("gapWeight", "간격 항목 가중치", 1, 5, "배", "Pierpont: 간격이 리듬의 핵심 — 간격 항목을 다른 항목의 몇 배로 셀지"),
            head("글자 간격 (dit 3개 ~ Farnsworth 사이면 만점)"),
            new Field("shortOk", "짧음: 만점 한계", 0, 100, "%", "표준 간격(dit 3개)보다 짧을 때. 글자가 붙으면 다른 글자로 들림 — 엄격하게"),
            new Field("shortZero", "짧음: 0점", 5, 100, "%", ""),
            new Field("longOk", "김: 만점 한계", 0, 300, "%", "Farnsworth 간격보다 길 때. 길면 느릴 뿐 알아듣는 데는 지장이 적음 — 넉넉하게"),
            new Field("longZero", "김: 0점", 10, 500, "%", ""),
            head("Iambic (키어가 요소 길이를 만듦)"),
            new Field("releaseOk", "늦게 뗌: 만점 한계 (dit 대비)", 0, 100, "%", "키어는 소리가 끝나고 dit 1개 뒤에 패들을 다시 읽음"),
            new Field("releaseZero", "늦게 뗌: 0점", 5, 200, "%", ""),
            new Field("hesitateOk", "요소 사이 머뭇거림: 만점 한계", 0, 100, "%", "W8RIT: 요소 사이는 dit 1개를 넘기지 말 것"),
            new Field("hesitateZero", "요소 사이 머뭇거림: 0점", 5, 200, "%", ""),
            head("스트레이트 (손이 모두 만듦)"),
            new Field("elemOk", "dit:dah 비율: 만점 한계", 0, 100, "%", "ITU: dah = dit 3개. 내 dit 대비 비율로 판정"),
            new Field("elemZero", "dit:dah 비율: 0점", 5, 200, "%", ""),
            new Field("elemGapOk", "요소 사이 간격: 만점 한계", 0, 100, "%", "ITU: 요소 사이 = dit 1개"),
            new Field("elemGapZero", "요소 사이 간격: 0점", 5, 200, "%", ""),
            new Field("speedOk", "속도: 만점 한계", 0, 100, "%", "내 dit 길이 vs 글자 WPM의 dit"),
            new Field("speedZero", "속도: 0점", 5, 200, "%", ""),
            head("검정 (옛 아마추어무선기사 전신 실기 송신 성적표 형식)"),
            new Field("examPass", "합격 점수", 0, 100, "점", "KCA 무선종사자 시험: 60점 이상 합격"),
            new Field("examErr", "오자·탈자·용자 1자당 감점", 0, 10, "점", "틀린 글자 / 빠뜨린 글자 / 더 친 글자"),
            new Field("examUnclear", "자호불명료 1자당 감점", 0, 10, "점", "글자는 맞지만 리듬 점수가 아래 기준 미만"),
            new Field("examUnclearBelow", "자호불명료 기준 (리듬 점수)", 0, 100, "점", "이 점수 미만이면 알아보기 어려운 글자로 봄"),
            new Field("examCorr", "정정 1회당 감점", 0, 10, "점", "dit 8개(........)로 정정"),
            new Field("examShort", "기준속도미달 1자당 감점", 0, 10, "점", "시간 안에 보내지 못한 글자 수"),
            new Field("examOver", "표준속도초과 감점", 0, 30, "점", "기준 속도보다 너무 빨리 보냄"),
            new Field("examOverPct", "표준속도초과 기준", 100, 300, "%", "기준 속도의 몇 %를 넘으면 초과로 볼지"),
    };
    static final String[] MODES = {"Iambic B", "Iambic A", "스트레이트"};

    // keyer / practice settings (desktop toolbar values)
    int mode = Morse.Keyer.IAMBIC_B, wpm = 18, eff = 10, freq = 600, vol = 50, level = 2;
    boolean swap, hint;
    String focus = "";  // focus letters: practise only these (empty = Koch order)
    String device = ""; // output device "type:product name", empty = system default
    Morse.Criteria crit = new Morse.Criteria();

    final Tone tone = new Tone();
    final Morse.Decoder dec = new Morse.Decoder();
    Morse.Timing timing = new Morse.Timing(80, 240, 560);
    List<Tone.Elem> cur = new ArrayList<>(), last = new ArrayList<>();  // elements of the character being keyed / the last judged one
    String lastRef = "";
    double lastEnd = -1;
    double allUpAt = -1;      // when both paddles were last released, -1 while one is held
    double prevCharEnd = -1;  // end of the previous correct character in this word
    int owe;                  // correct repeats still needed for the current letter (1 per miss, up to crit.maxRepeat)
    int ditStrays, dahStrays; // silent paddle presses since the last judged character
    Store store;
    String audioInfo = "";
    final Random rng = new Random();
    final Handler ui = new Handler(Looper.getMainLooper());
    final Runnable ticker = new Runnable() { @Override public void run() { tick(); ui.postDelayed(this, 15); } };

    // views
    int tab;
    final View[] pages = new View[3];
    final TextView[] tabBtns = new TextView[3];
    GameView game;
    ExamView examView;
    StatsView stats;
    PaddleView paddleView;
    TextView status, keyerInfo, alert;
    ArrayAdapter<CharSequence> fixes;
    ListView fixList;
    Spinner gradeBox, minBox, periodBox, kindBox;
    TextView startBtn, stopBtn, focusBtn;

    // exam
    final List<Morse.ExamChar> examSent = new ArrayList<>();
    int examCorr;
    double examStartMs, examLastMs, examPrevEnd = -1;

    float dp;
    int px(float v) { return Math.round(v * dp); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = new Store(getFilesDir());
        loadSettings();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, in) -> {  // edge-to-edge (targetSdk 35): keep clear of the system bars
            v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
            return in.consumeSystemWindowInsets();
        });

        LinearLayout tabs = row();
        final String[] names = {"연습", "검정", "통계"};
        for (int i = 0; i < 3; ++i) {
            final int k = i;
            tabBtns[i] = button(names[i], Color.rgb(24, 26, 42), v -> showTab(k));
            tabs.addView(tabBtns[i], new LinearLayout.LayoutParams(0, px(42), 1));
        }
        tabs.addView(button("⚙ 설정", Color.rgb(51, 85, 221), v -> openSettings()), new LinearLayout.LayoutParams(0, px(42), 1));
        root.addView(tabs);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // practice
        LinearLayout practice = new LinearLayout(this);
        practice.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = row();
        bar.addView(button("▶ 글자", Color.rgb(51, 85, 221), v -> listenTo(false)), weight());
        bar.addView(button("▶ 단어", Color.rgb(51, 85, 221), v -> listenTo(true)), weight());
        bar.addView(button("새 단어", Color.rgb(51, 85, 221), v -> newWord()), weight());
        focusBtn = button("🎯 집중", Color.rgb(51, 85, 221), v -> openFocus());
        bar.addView(focusBtn, weight());
        practice.addView(bar);
        keyerInfo = label(11, Ui.MUTED);
        practice.addView(keyerInfo);
        game = new GameView(this);
        game.crit = crit;
        practice.addView(game, new LinearLayout.LayoutParams(-1, px(430)));
        TextView fixTitle = label(12, Color.rgb(143, 180, 255));
        fixTitle.setText("지적 사항  (최근이 위)");
        fixTitle.setTypeface(Typeface.DEFAULT_BOLD);
        practice.addView(fixTitle);
        alert = label(12, Color.rgb(255, 210, 168));  // repeated bad habit warning (hidden when none)
        GradientDrawable alertBg = new GradientDrawable();
        alertBg.setColor(Color.rgb(90, 42, 16));
        alertBg.setStroke(px(1), Ui.ORANGE);
        alertBg.setCornerRadius(px(6));
        alert.setBackground(alertBg);
        alert.setPadding(px(8), px(6), px(8), px(6));
        alert.setVisibility(View.GONE);
        practice.addView(alert);
        fixes = new ArrayAdapter<CharSequence>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convert, parent);
                tv.setTextSize(13);
                tv.setMinHeight(0);
                tv.setMinimumHeight(0);
                tv.setPadding(px(8), px(1), px(8), px(1));
                return tv;
            }
        };
        fixList = new ListView(this);
        fixList.setAdapter(fixes);
        fixList.setDivider(null);
        fixList.setBackgroundColor(Color.rgb(20, 22, 38));
        practice.addView(fixList, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label(11, Color.rgb(136, 144, 168));
        practice.addView(status);
        pages[0] = practice;

        // exam
        LinearLayout examPage = new LinearLayout(this);
        examPage.setOrientation(LinearLayout.VERTICAL);
        gradeBox = spinner(GRADE_NAMES);
        examPage.addView(gradeBox);
        LinearLayout ebar = row();
        minBox = spinner(new String[]{"1 분", "2 분", "3 분", "4 분", "5 분"});
        minBox.setSelection(2);
        ebar.addView(minBox, weight());
        startBtn = button("▶ 시작", Color.rgb(46, 160, 90), v -> examStart());
        stopBtn = button("■ 끝내기", Color.rgb(200, 60, 70), v -> examFinish());
        ebar.addView(startBtn, weight());
        ebar.addView(stopBtn, weight());
        examPage.addView(ebar);
        examView = new ExamView(this);
        examView.crit = crit;
        examView.gradeName = GRADE_NAMES[0];
        examPage.addView(examView, new LinearLayout.LayoutParams(-1, 0, 1));
        gradeBox.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                if (!examView.running) { examView.gradeName = GRADE_NAMES[i]; examView.invalidate(); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        pages[1] = examPage;

        // stats
        LinearLayout statsPage = new LinearLayout(this);
        statsPage.setOrientation(LinearLayout.VERTICAL);
        LinearLayout sbar = row();
        periodBox = spinner(new String[]{"오늘", "최근 7일", "최근 30일", "전체"});
        periodBox.setSelection(1);
        kindBox = spinner(new String[]{"연습 + 검정", "연습만", "검정만"});
        sbar.addView(periodBox, weight());
        sbar.addView(kindBox, weight());
        statsPage.addView(sbar);
        ScrollView scroll = new ScrollView(this);
        stats = new StatsView(this);
        stats.store = store;
        scroll.addView(stats);
        statsPage.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        AdapterView.OnItemSelectedListener refresh = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { refreshStats(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        periodBox.setOnItemSelectedListener(refresh);
        kindBox.setOnItemSelectedListener(refresh);
        pages[2] = statsPage;

        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(-1, -1));

        paddleView = new PaddleView(this);
        paddleView.listener = this::paddle;
        root.addView(paddleView, new LinearLayout.LayoutParams(-1, px(120)));
        setContentView(root);

        apply();
        newWord();
        updateAlert();
        showTab(0);
        setExamButtons(false);
    }

    @Override protected void onResume() {
        super.onResume();
        startAudio();
        apply();
        newWordStatus();
        ui.post(ticker);
    }

    // ---- output device ----
    // Outputs worth playing a sidetone on (not the call earpiece or telephony routes).
    List<AudioDeviceInfo> outputs() {
        List<AudioDeviceInfo> out = new ArrayList<>();
        for (AudioDeviceInfo d : ((AudioManager) getSystemService(AUDIO_SERVICE)).getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            final int t = d.getType();
            if (t != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && t != AudioDeviceInfo.TYPE_TELEPHONY && t != AudioDeviceInfo.TYPE_REMOTE_SUBMIX &&
                    t != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && t != AudioDeviceInfo.TYPE_BUS) out.add(d);
        }
        return out;
    }
    static String deviceKey(AudioDeviceInfo d) { return d.getType() + ":" + d.getProductName(); }
    static String deviceLabel(AudioDeviceInfo d) {
        final int t = d.getType();
        final String kind = t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? "폰 스피커"
                : t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ? "유선 이어폰"
                : t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ? "블루투스 (소리 늦음)"
                : t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET ? "USB 오디오"
                : t == AudioDeviceInfo.TYPE_HDMI ? "HDMI" : "기타";
        return kind + " · " + d.getProductName();
    }

    void startAudio() {
        tone.preferred = null;
        for (AudioDeviceInfo d : outputs()) if (deviceKey(d).equals(device)) tone.preferred = d;
        final String err = tone.start(this);
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        audioInfo = String.format("소리: %s%s · 버퍼 %.1f ms", tone.preferred == null ? "시스템 기본" : deviceLabel(tone.preferred),
                tone.preferred == null && !device.isEmpty() ? " (선택한 장치가 없어 기본으로)" : "", tone.bufferMs);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(ticker);
        tone.stop();
        if (examView.running) examFinish();
        saveSettings();
        super.onPause();
    }

    // USB paddle adapter (mode 2) types '[' and ']'; key repeat is not the paddle. Space = hear the letter.
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        final int code = e.getKeyCode();
        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        final int phys = code == KeyEvent.KEYCODE_LEFT_BRACKET ? 0 : code == KeyEvent.KEYCODE_RIGHT_BRACKET ? 1 : -1;
        if (phys >= 0) {
            if (e.getRepeatCount() == 0) paddle(phys, down);
            return true;
        }
        if (code == KeyEvent.KEYCODE_SPACE) {
            if (down && e.getRepeatCount() == 0) listenTo(e.isShiftPressed());
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- small view helpers ----
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setPadding(px(4), px(4), px(4), px(4)); return l; }
    LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, px(42), 1); p.setMargins(px(3), 0, px(3), 0); return p; }
    TextView label(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(px(8), px(2), px(8), px(2));
        return t;
    }
    TextView button(String text, int color, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(6));
        t.setBackground(bg);
        t.setOnClickListener(click);
        t.setFocusable(false);
        return t;
    }
    Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setFocusable(false);
        return s;
    }
    void tint(TextView t, int color) { ((GradientDrawable) t.getBackground()).setColor(color); }

    void showTab(int k) {
        tab = k;
        for (int i = 0; i < 3; ++i) {
            pages[i].setVisibility(i == k ? View.VISIBLE : View.GONE);
            tint(tabBtns[i], i == k ? Color.rgb(42, 49, 96) : Color.rgb(24, 26, 42));
        }
        paddleView.setVisibility(k == 2 ? View.GONE : View.VISIBLE);
        if (k == 2) refreshStats();
    }

    void setExamButtons(boolean running) {
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        startBtn.setAlpha(running ? 0.4f : 1);
        stopBtn.setAlpha(running ? 1 : 0.4f);
        gradeBox.setEnabled(!running);
        minBox.setEnabled(!running);
    }

    // ---- settings ----
    SharedPreferences prefs() { return getSharedPreferences("settings", MODE_PRIVATE); }

    static double getCrit(Morse.Criteria c, String key) {
        try { return Morse.Criteria.class.getField(key).getDouble(c); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static void setCrit(Morse.Criteria c, String key, double v) {
        try { Morse.Criteria.class.getField(key).setDouble(c, v); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    void loadSettings() {
        SharedPreferences p = prefs();
        for (Field f : FIELDS)
            if (f.key != null) {
                try { setCrit(crit, f.key, Double.parseDouble(p.getString("criteria/" + f.key, String.valueOf(getCrit(crit, f.key))))); }
                catch (NumberFormatException ignored) {}
            }
        crit.sanitize();
        mode = p.getInt("ui/mode", mode); wpm = p.getInt("ui/wpm", wpm); eff = p.getInt("ui/eff", eff);
        freq = p.getInt("ui/freq", freq); vol = p.getInt("ui/vol", vol); level = p.getInt("ui/level", level);
        swap = p.getBoolean("ui/swap", false); hint = p.getBoolean("ui/hint", false);
        focus = focusPool(p.getString("ui/focus", ""));
        device = p.getString("ui/device", "");
    }

    void saveSettings() {
        SharedPreferences.Editor e = prefs().edit();
        for (Field f : FIELDS) if (f.key != null) e.putString("criteria/" + f.key, String.valueOf(getCrit(crit, f.key)));
        e.putInt("ui/mode", mode).putInt("ui/wpm", wpm).putInt("ui/eff", eff).putInt("ui/freq", freq).putInt("ui/vol", vol)
                .putInt("ui/level", level).putBoolean("ui/swap", swap).putBoolean("ui/hint", hint).putString("ui/focus", focus).putString("ui/device", device).apply();
    }

    void openSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(px(16), px(8), px(16), px(8));

        form.addView(section("소리 출력 장치"));
        final List<AudioDeviceInfo> outs = outputs();
        final String[] outNames = new String[outs.size() + 1];
        outNames[0] = "시스템 기본 (안드로이드가 고름)";
        int outSel = 0;
        for (int i = 0; i < outs.size(); ++i) {
            outNames[i + 1] = deviceLabel(outs.get(i));
            if (deviceKey(outs.get(i)).equals(device)) outSel = i + 1;
        }
        Spinner outBox = spinner(outNames);
        outBox.setSelection(outSel);
        form.addView(outBox);

        form.addView(section("키어 / 연습"));
        Spinner modeBox = spinner(MODES);
        modeBox.setSelection(mode);
        form.addView(modeBox);
        final Object[][] ints = {{"글자 WPM", 5, 40, wpm, "Farnsworth: 글자는 이 속도로 칩니다"},
                {"실효 WPM", 5, 40, eff, "글자·단어 사이 간격만 늘려 전체 속도를 이 값으로 맞춥니다"},
                {"톤 (Hz)", 300, 1200, freq, ""}, {"볼륨 (%)", 0, 100, vol, ""},
                {"연습 글자 수 (코흐 순서)", 2, KOCH.length(), level, "K M U R E S N A P T L W I . J Z = F O Y , V G 5 / Q 9 2 H 3 8 B ? 4 7 C 1 D 6 0 X"}};
        final List<EditText> intBoxes = new ArrayList<>();
        for (Object[] r : ints) intBoxes.add(numberRow(form, (String) r[0], (String) r[4], String.valueOf(r[3]), "", false));
        CheckBox swapBox = new CheckBox(this), hintBox = new CheckBox(this);
        swapBox.setText("좌우 반전 (dit ↔ dah)");
        swapBox.setChecked(swap);
        hintBox.setText("힌트 (점·선 모양 보기, 점수 감점)");
        hintBox.setChecked(hint);
        form.addView(swapBox);
        form.addView(hintBox);

        final List<EditText> critBoxes = new ArrayList<>();
        for (Field f : FIELDS) {
            if (f.key == null) { form.addView(section(f.label)); continue; }
            critBoxes.add(numberRow(form, f.label, f.tip, GameView.fmt(getCrit(crit, f.key)), f.suffix, true));
        }
        TextView src = label(11, Color.rgb(136, 144, 168));
        src.setText("기준 출처: ITU-R M.1677 (dit 1 · dah 3 · 요소 간격 1 · 글자 간격 3 · 단어 간격 7), " +
                "Pierpont 「The Art & Skill of Radio-Telegraphy」 (간격이 가장 중요, 세지 말고 소리로), " +
                "W8RIT 「Iambic Sending」 (요소 사이 dit 1개 이내, Mode B는 마지막 요소 절반 전에 떼기).\n" +
                "만점 한계 안이면 100%, 0점 값까지 직선으로 감점됩니다.");
        form.addView(src);
        ScrollView sv = new ScrollView(this);
        sv.addView(form);

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("설정").setView(sv)
                .setPositiveButton("저장", (d, w) -> {
                    final int[] lo = {5, 5, 300, 0, 2}, hi = {40, 40, 1200, 100, KOCH.length()};
                    final int[] v = new int[5];
                    for (int i = 0; i < 5; ++i) v[i] = (int) Math.max(lo[i], Math.min(hi[i], parse(intBoxes.get(i), (int) ints[i][3])));
                    final int o = outBox.getSelectedItemPosition();
                    final String newDevice = o == 0 ? "" : deviceKey(outs.get(o - 1));
                    if (!newDevice.equals(device)) {  // switch the sound to the chosen device now
                        device = newDevice;
                        tone.stop();
                        startAudio();
                    }
                    mode = modeBox.getSelectedItemPosition();
                    wpm = v[0]; eff = Math.min(v[1], v[0]); freq = v[2]; vol = v[3];
                    final boolean levelChanged = v[4] != level;
                    level = v[4];
                    swap = swapBox.isChecked();
                    hint = hintBox.isChecked();
                    int k = 0;
                    for (Field f : FIELDS)
                        if (f.key != null) setCrit(crit, f.key, Math.max(f.lo, Math.min(f.hi, parse(critBoxes.get(k++), getCrit(crit, f.key)))));
                    crit.sanitize();
                    saveSettings();
                    owe = Math.min(owe, (int) crit.maxRepeat);
                    game.owe = owe;
                    updateAlert();
                    apply();
                    if (levelChanged) newWord(); else newWordStatus();
                })
                .setNegativeButton("취소", null)
                .setNeutralButton("기본값으로 초기화", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {  // fill defaults, keep the dialog open
            final Morse.Criteria def = new Morse.Criteria();
            int k = 0;
            for (Field f : FIELDS) if (f.key != null) critBoxes.get(k++).setText(GameView.fmt(getCrit(def, f.key)));
        }));
        dlg.show();
    }

    static double parse(EditText t, double fallback) {
        try { return Double.parseDouble(t.getText().toString().trim()); } catch (NumberFormatException e) { return fallback; }
    }

    TextView section(String text) {
        TextView t = label(14, Color.rgb(143, 180, 255));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setText(text);
        t.setPadding(0, px(14), 0, px(4));
        return t;
    }

    EditText numberRow(LinearLayout form, String label, String tip, String value, String suffix, boolean decimal) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView l = label(13, Ui.INK);
        l.setPadding(0, 0, 0, 0);
        l.setText(label);
        texts.addView(l);
        if (!tip.isEmpty()) {
            TextView t = label(10, Ui.MUTED);
            t.setPadding(0, 0, 0, 0);
            t.setText(tip);
            texts.addView(t);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setText(value);
        e.setTextSize(14);
        e.setGravity(Gravity.END);
        e.setSelectAllOnFocus(true);
        r.addView(e, new LinearLayout.LayoutParams(px(76), -2));
        TextView s = label(12, Ui.MUTED);
        s.setText(suffix);
        r.addView(s, new LinearLayout.LayoutParams(px(32), -2));
        form.addView(r);
        return e;
    }

    // ---- keyer ----
    void apply() {
        timing = Morse.farnsworth(wpm, eff);
        synchronized (tone.mx) {
            tone.keyer.mode = mode;
            tone.keyer.dit = (int) Math.round(tone.rate * timing.ditMs / 1000);
            tone.freq = freq;
            tone.vol = vol / 100.0;
        }
        dec.ditMs = timing.ditMs;
        dec.spaceMs = (timing.charGapMs + timing.wordGapMs) / 2;
        game.timing = timing;
        game.hint = hint;
        game.straight = mode == Morse.Keyer.STRAIGHT;
        paddleView.swap = swap;
        paddleView.invalidate();
        keyerInfo.setText(MODES[mode] + " · 글자 " + wpm + " / 실효 " + eff + " WPM · 톤 " + freq + " Hz" + (hint ? " · 힌트" : "") +
                (swap ? " · 좌우 반전" : "") + "   (⚙ 설정에서 변경)");
    }

    void paddle(int phys, boolean down) {
        final int lane = swap ? 1 - phys : phys;
        if (lane == 1) tone.dahIn = down; else tone.ditIn = down;
        allUpAt = tone.ditIn || tone.dahIn ? -1 : Tone.now();
    }

    void listenTo(boolean wholeWord) {
        List<String> chars = new ArrayList<>();
        final int from = wholeWord ? 0 : game.pos, to = wholeWord ? game.word.length() : game.pos + 1;
        for (int i = from; i < Math.min(to, game.word.length()); ++i) chars.add(Morse.patternOf(game.word.charAt(i)));
        tone.play(chars, timing.charGapMs);
    }

    char target() { return game.pos < game.word.length() ? game.word.charAt(game.pos) : 0; }

    // recent average per character (last 20 tries): weak characters come up much more often
    double weight(char ch) {
        int n = 0;
        double sum = 0;
        for (int i = store.recs.size() - 1; i >= 0 && n < 20; --i)
            if (store.recs.get(i).ch == ch) { sum += store.recs.get(i).score; ++n; }
        final double d = 1 - sum / Math.max(n, 1) / 100;
        return n > 0 ? 1 + 12 * d * d : 4;  // never practised: extra weight
    }

    // focus letters (upper-cased, Koch letters only, no repeats); empty = normal Koch practice
    static String focusPool(String text) {
        StringBuilder pool = new StringBuilder();
        for (char c : text.toUpperCase(java.util.Locale.ROOT).toCharArray())
            if (KOCH.indexOf(c) >= 0 && pool.indexOf(String.valueOf(c)) < 0) pool.append(c);
        return pool.toString();
    }

    void openFocus() {
        EditText e = new EditText(this);
        e.setText(focus);
        e.setHint("예: R  (여러 글자도 됨: RK)");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("🎯 집중 연습")
                .setMessage("이 글자만 반복해서 연습합니다. 해제하면 코흐 순서로 돌아갑니다.")
                .setView(box)
                .setPositiveButton("적용", (d, w) -> { focus = focusPool(e.getText().toString()); saveSettings(); newWord(); })
                .setNeutralButton("해제", (d, w) -> { focus = ""; saveSettings(); newWord(); })
                .setNegativeButton("취소", null)
                .show();
    }

    void newWord() {
        final int n = level;
        final String set = focus.isEmpty() ? KOCH.substring(0, n) : focus;
        final double[] w = new double[set.length()];
        double total = 0;
        for (int i = 0; i < set.length(); ++i) total += w[i] = weight(set.charAt(i));
        StringBuilder word = new StringBuilder();
        for (int k = 0; k < WORD_LEN; ++k) {
            double r = rng.nextDouble() * total;
            int i = 0;
            while (i < set.length() - 1 && (r -= w[i]) > 0) ++i;
            word.append(set.charAt(i));
        }
        game.word = word.toString();
        game.pos = 0;
        game.doneColor.clear();
        last.clear();
        prevCharEnd = -1;
        owe = game.owe = 0;
        newWordStatus();
    }

    void newWordStatus() {
        final int n = level;
        stats.koch = KOCH.substring(0, n);
        // level-up rule: every practised character averages levelAvg+ over its last levelCount tries
        boolean ready = true;
        for (int i = 0; i < n && ready; ++i) {
            int cnt = 0;
            double sum = 0;
            for (int j = store.recs.size() - 1; j >= 0 && cnt < crit.levelCount; --j)
                if (store.recs.get(j).ch == KOCH.charAt(i)) { sum += store.recs.get(j).score; ++cnt; }
            ready = cnt >= crit.levelCount && sum / cnt >= crit.levelAvg;
        }
        final String next = ready && n < KOCH.length() ? "  ·  ★ 다음 글자 '" + KOCH.charAt(n) + "' 추가 권장 (⚙ 설정 → 연습 글자 수)"
                : "  ·  글자 추가 기준: 모든 글자 최근 " + GameView.fmt(crit.levelCount) + "회 평균 " + GameView.fmt(crit.levelAvg) + "점";
        final String head = focus.isEmpty() ? "연습 글자: " + stats.koch + next
                : "🎯 집중 연습: " + focus + "  ·  '🎯 집중' → 해제하면 코흐 순서(" + stats.koch + ")로 돌아갑니다";
        tint(focusBtn, focus.isEmpty() ? Color.rgb(51, 85, 221) : Ui.ORANGE);
        focusBtn.setText(focus.isEmpty() ? "🎯 집중" : "🎯 " + focus);
        status.setText(head + "\nUSB 패들 [ ] 키 (어댑터 모드 2, OTG) 또는 아래 화면 패들  ·  " + audioInfo + "  ·  v" + version());
    }

    String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    void refreshStats() {
        final int[] periods = {1, 7, 30, 0};
        stats.days = periods[periodBox.getSelectedItemPosition()];
        stats.kind = kindBox.getSelectedItemPosition() - 1;
        stats.refresh();
    }

    void tick() {
        final double now = Tone.now();
        List<Tone.Elem> els;
        double live;
        synchronized (tone.mx) {
            els = new ArrayList<>(tone.done);
            tone.done.clear();
            live = tone.liveStart;
            ditStrays += tone.keyer.ditStrays;
            dahStrays += tone.keyer.dahStrays;
            tone.keyer.ditStrays = tone.keyer.dahStrays = 0;
        }
        for (Tone.Elem e : els) {
            dec.element(e.ms, e.sym);
            cur.add(e);
            lastEnd = e.t1;
        }
        if (live < 0 && lastEnd > 0 && !cur.isEmpty()) {
            final String sent = dec.pattern.toString();
            final char c = dec.gap(now - lastEnd);
            if (c != 0) {
                if (tab == 1) examChar(sent, c);
                else judge(sent);
            }
        }
        if (dec.text.length() > 200) dec.text.delete(0, 100);
        if (examView.running) {
            examView.leftMs = (minBox.getSelectedItemPosition() + 1) * 60000.0 - (now - examStartMs);
            examView.live = dec.pattern.toString().replace('.', '·').replace('-', '—');
            examView.invalidate();
            if (examView.leftMs <= 0) examFinish();
        }

        final boolean keying = !cur.isEmpty() || live >= 0;
        final boolean fresh = keying || last.isEmpty();  // else keep showing the last result until you key again
        game.cmpRef = fresh ? Morse.patternOf(target()) : lastRef;
        game.elems = fresh ? new ArrayList<>(cur) : last;
        if (fresh) game.releaseAt = game.charGap = -1;
        game.liveStart = live;
        game.now = now;
        if (tab == 0) game.invalidate();
    }

    // ---- exam ----
    int examMinutes() { return minBox.getSelectedItemPosition() + 1; }

    void examStart() {
        final int gi = gradeBox.getSelectedItemPosition();
        final int required = GRADE_RATE[gi] * examMinutes();
        List<String> text = new ArrayList<>();
        int letters = 0;
        while (letters < required) {
            StringBuilder sentence = new StringBuilder();
            if (GRADE_LEARNED[gi]) {
                for (int g = 0; g < 3; ++g) {
                    if (g > 0) sentence.append(' ');
                    for (int k = 0; k < 5; ++k) {
                        char ch;
                        do ch = KOCH.charAt(rng.nextInt(level)); while (ch == ',');  // ',' would break the CSV record
                        sentence.append(ch);
                    }
                }
            } else {
                sentence.append(EXAM_TEXT[rng.nextInt(EXAM_TEXT.length)]);
            }
            for (char ch : sentence.toString().toCharArray()) if (ch != ' ') ++letters;
            text.add(sentence.toString());
        }
        examSent.clear();
        examCorr = 0;
        examPrevEnd = -1;
        cur.clear();
        dec.pattern.setLength(0);
        examView.sentences = text;
        examView.required = required;
        examView.sentCount = 0;
        examView.gradeName = GRADE_NAMES[gi];
        examView.running = true;
        examView.finished = false;
        examStartMs = examLastMs = Tone.now();
        examView.leftMs = examMinutes() * 60000.0;
        examRegrade(0);
        setExamButtons(true);
    }

    void examRegrade(double elapsedMin) {
        examView.g = Morse.gradeExam(examView.sentences, examSent, examCorr, examView.required, elapsedMin,
                GRADE_RATE[gradeBox.getSelectedItemPosition()], crit);
        examView.sentCount = examSent.size();
        examView.invalidate();
    }

    // A character decoded during the exam: rhythm-judge it as sent, or treat 6+ dits as the correction sign.
    void examChar(String sent, char c) {
        ditStrays = dahStrays = 0;  // ponytail: the exam sheet has no item for silent presses; practice catches them
        if (!examView.running) { cur.clear(); return; }
        if (sent.length() >= 6 && sent.indexOf('-') < 0) {  // ........ = error, erase the last letter
            if (!examSent.isEmpty()) examSent.remove(examSent.size() - 1);
            ++examCorr;
        } else {
            final double[] on = new double[cur.size()], gaps = new double[Math.max(0, cur.size() - 1)];
            for (int i = 0; i < cur.size(); ++i) {
                on[i] = cur.get(i).ms;
                if (i > 0) gaps[i - 1] = cur.get(i).t0 - cur.get(i - 1).t1;
            }
            double charGap = examPrevEnd >= 0 ? cur.get(0).t0 - examPrevEnd : -1;
            if (charGap > dec.spaceMs) charGap = -1;  // a word space: not graded
            final double releaseLate = allUpAt >= 0 ? allUpAt - cur.get(cur.size() - 1).t1 : Double.NaN;
            final Morse.Verdict v = Morse.judge(sent, sent, on, gaps, charGap, releaseLate, mode, false, timing, crit);
            examSent.add(new Morse.ExamChar(c, v.score, v.habits));
        }
        examPrevEnd = examLastMs = cur.get(cur.size() - 1).t1;
        cur.clear();
        examRegrade(0);
        if (examView.g.end == examView.g.letters.size()) examFinish();  // last letter sent: score it now
    }

    void examFinish() {
        if (!examView.running) return;
        examView.running = false;
        examView.finished = true;
        examView.live = "";
        examRegrade((examLastMs - examStartMs) / 60000.0);
        setExamButtons(false);
        if (examSent.isEmpty() && examCorr == 0) return;  // nothing keyed: not an attempt, keep it out of the records
        final Morse.ExamGrade g = examView.g;
        final int gi = gradeBox.getSelectedItemPosition();
        final long t = System.currentTimeMillis() / 1000;
        // per letter -> attempts.csv (kind 1), so exam letters show up in the per-character stats
        for (Morse.LetterGrade lg : g.letters)
            if (lg.reached) store.save(new Store.Rec(t, lg.want, lg.ok, lg.score, mode, wpm, eff, lg.habits, 1));
        for (int k = 0; k < g.sentences.size(); ++k) {
            final Morse.SentenceGrade sg = g.sentences.get(k);
            if (!sg.reached) continue;
            Store.SentRec sr = new Store.SentRec();
            sr.time = t; sr.idx = k; sr.target = examView.sentences.get(k); sr.sent = sg.sent;
            sr.sub = sg.sub; sr.del = sg.del; sr.ins = sg.ins; sr.unclear = sg.unclear; sr.unsent = sg.unsent; sr.score = sg.score;
            store.save(sr);
        }
        Store.ExamRec er = new Store.ExamRec();
        er.time = t; er.grade = GRADE_NAMES[gi]; er.rate = GRADE_RATE[gi]; er.minutes = examMinutes(); er.sent = examSent.size();
        er.sub = g.sub; er.del = g.del; er.ins = g.ins; er.unclear = g.unclear; er.corr = g.corr; er.shortfall = g.shortfall;
        er.over = g.over ? 1 : 0; er.score = g.score; er.pass = g.score >= crit.examPass ? 1 : 0;
        store.save(er);
    }

    // ---- practice ----
    // One result line on the game panel + the full findings at the top of the list.
    void report(char ch, String line, int color, Morse.Verdict v) {
        final String headLine = "'" + ch + "'  " + line;
        game.pop(headLine, color);
        List<CharSequence> rows = new ArrayList<>();
        rows.add(span(headLine, color, true));
        for (String n : v.notes) rows.add(span("   ⚠ " + n, Color.rgb(255, 150, 130), false));
        if (v.worst != 0) rows.add(span("   → " + Morse.habitAdvice(v.worst), Color.rgb(170, 200, 255), false));
        for (int i = rows.size() - 1; i >= 0; --i) fixes.insert(rows.get(i), 0);
        while (fixes.getCount() > 300) fixes.remove(fixes.getItem(fixes.getCount() - 1));
        fixList.setSelection(0);
    }

    static CharSequence span(String s, int color, boolean bold) {
        SpannableString sp = new SpannableString(s);
        sp.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sp.setSpan(new StyleSpan(Typeface.BOLD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    // Repeated bad habit over the last practice attempts -> banner above the findings list.
    void updateAlert() {
        List<Integer> recent = new ArrayList<>();
        for (int i = store.recs.size() - 1; i >= 0 && recent.size() < crit.alertWindow; --i)
            if (store.recs.get(i).kind == 0) recent.add(0, store.recs.get(i).habits);
        final int[] n = {0};
        final int h = Morse.repeatedHabit(recent, crit, n);
        alert.setVisibility(h != 0 ? View.VISIBLE : View.GONE);
        if (h != 0) alert.setText("⚠ 반복되는 습관: " + Morse.habitName(h) + " — 최근 " + recent.size() + "회 중 " + n[0] + "회\n→ " + Morse.habitAdvice(h));
    }

    void judge(String sent) {
        final char want = target();
        if (want == 0) { cur.clear(); return; }  // keyed during the word-clear pause
        final String ref = Morse.patternOf(want);
        final boolean straight = mode == Morse.Keyer.STRAIGHT;
        final double[] on = new double[cur.size()], gaps = new double[Math.max(0, cur.size() - 1)];
        for (int i = 0; i < cur.size(); ++i) {
            on[i] = cur.get(i).ms;
            if (i > 0) gaps[i - 1] = cur.get(i).t0 - cur.get(i - 1).t1;
        }
        final double charGap = prevCharEnd >= 0 ? cur.get(0).t0 - prevCharEnd : -1;
        // ponytail: release compared with the tone's render time; the audio buffer is not subtracted
        final double releaseLate = allUpAt >= 0 ? allUpAt - cur.get(cur.size() - 1).t1 : Double.NaN;
        final Morse.Verdict v = Morse.judge(ref, sent, on, gaps, charGap, releaseLate, mode, hint, timing, crit);
        Morse.markStrays(v, ditStrays, dahStrays);
        final boolean stray = (v.habits & Morse.STRAY_PRESS) != 0;
        ditStrays = dahStrays = 0;

        game.releaseAt = straight ? -1 : allUpAt;
        game.charGap = charGap;
        store.save(new Store.Rec(System.currentTimeMillis() / 1000, want, sent.equals(ref), v.score, mode, wpm, eff, v.habits, 0));
        updateAlert();

        last = new ArrayList<>(cur);
        lastRef = ref;
        cur.clear();
        ++game.attempts;
        game.scoreSum += v.score;
        if (!sent.equals(ref)) {
            game.combo = 0;
            prevCharEnd = -1;
            owe = Math.min(owe + 1, (int) crit.maxRepeat);
            game.owe = owe;
            final Character got = Morse.TABLE.get(sent);
            report(want, "MISS '" + (got == null ? '?' : got) + "'  ·  ↻ 같은 글자 " + owe + "회 더", Ui.RED, v);
            return;  // typing-tutor style: retry the same letter
        }
        prevCharEnd = last.get(last.size() - 1).t1;
        final int c = Ui.gradeColor(v.score);
        ++game.hits;
        game.combo = v.score >= 70 && !stray ? game.combo + 1 : 0;  // sloppy keying breaks the combo too
        game.bestCombo = Math.max(game.bestCombo, game.combo);
        game.score += Math.round(v.score * (1 + game.combo / 10.0));
        if (stray) {  // right letter, but a paddle was pressed that never sounded: the hand motion failed
            owe = Math.min(owe + 1, (int) crit.maxRepeat);
            game.owe = owe;
            report(want, "헛누름  ·  소리 안 난 패들 누름  ·  ↻ 같은 글자 " + owe + "회 더", Ui.RED, v);
            return;
        }
        if (v.score < crit.passScore) {  // right letter, rhythm under the pass mark: counts as a miss too
            owe = Math.min(owe + 1, (int) crit.maxRepeat);
            game.owe = owe;
            report(want, v.score + "점  ·  통과 " + GameView.fmt(crit.passScore) + " 미만  ·  ↻ 같은 글자 " + owe + "회 더", c, v);
            return;
        }
        if (owe > 0 && --owe > 0) {  // earlier misses: repeat the same letter until paid off
            game.owe = owe;
            report(want, Ui.gradeName(v.score) + " " + v.score + "  ·  ✔ 통과  ·  ↻ 같은 글자 " + owe + "회 더", c, v);
            return;
        }
        game.owe = 0;
        report(want, Ui.gradeName(v.score) + " " + v.score + "  ·  ✔ 통과 → 다음 글자", c, v);
        game.doneColor.add(c);
        if (++game.pos == game.word.length()) {
            game.score += 100;
            game.pop("WORD CLEAR! +100", Color.rgb(255, 215, 90));
            ui.postDelayed(this::newWord, 900);
        }
    }
}
