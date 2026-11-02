package com.parksy.paddletrainer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

// Sidetone + keyer on a low-latency AudioTrack thread; the keyer runs per sample in render().
final class Tone implements Runnable {
    static final class Elem {  // wall-clock ms of a keyed tone; ms is sample-exact
        final double t0, t1, ms; final char sym;
        Elem(double t0, double t1, double ms, char sym) { this.t0 = t0; this.t1 = t1; this.ms = ms; this.sym = sym; }
    }

    static double now() { return SystemClock.elapsedRealtimeNanos() / 1e6; }

    final Morse.Keyer keyer = new Morse.Keyer();
    volatile boolean ditIn, dahIn;
    double freq = 600, vol = 0.5;
    int rate = 48000;
    final Object mx = new Object();            // guards keyer settings, done, liveStart, demo
    final ArrayList<Elem> done = new ArrayList<>();  // finished elements, drained by the UI
    double liveStart = -1;                     // wall ms of the tone sounding now, -1 when silent
    double bufferMs;

    private AudioTrack track;
    private Thread th;
    private volatile boolean running;
    private int burst = 192;
    private boolean wasOn;
    private long onSamples;
    private char onSym;
    private double env, phase;
    private int[] demo = new int[0];  // +samples tone, -samples silence
    private int demoPos, demoLeft;

    // reference sound: characters at the keyer speed, charGapMs of silence between them; paddles ignored meanwhile
    void play(List<String> chars, double charGapMs) {
        synchronized (mx) {
            ArrayList<Integer> d = new ArrayList<>();
            for (String pattern : chars) {
                for (char c : pattern.toCharArray()) { d.add((c == '.' ? 1 : 3) * keyer.dit); d.add(-keyer.dit); }
                if (!d.isEmpty()) d.set(d.size() - 1, -(int) (charGapMs * rate / 1000));
            }
            demo = new int[d.size()];
            for (int i = 0; i < demo.length; ++i) demo[i] = d.get(i);
            demoPos = 0;
            demoLeft = demo.length == 0 ? 0 : demo[0];
        }
    }

    String start(Context ctx) {  // returns an error text, null on success
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        try { rate = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)); } catch (Exception e) { rate = 48000; }
        try { burst = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)); } catch (Exception e) { burst = 192; }
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), burst * 2 * 4))
                    .build();
            track.setBufferSizeInFrames(burst * 2);  // two bursts: smallest that does not glitch on most phones
        } catch (Exception e) {
            return "오디오 초기화 실패: " + e.getMessage();
        }
        bufferMs = track.getBufferSizeInFrames() * 1000.0 / rate;
        synchronized (mx) { keyer.dit = Math.max(1, keyer.dit); }
        track.play();
        running = true;
        th = new Thread(this, "tone");
        th.start();
        return null;
    }

    void stop() {
        running = false;
        if (th != null) try { th.join(500); } catch (InterruptedException ignored) {}
        if (track != null) { track.stop(); track.release(); track = null; }
        th = null;
    }

    @Override public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        float[] buf = new float[burst];
        while (running) {
            render(buf);
            if (track.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING) < 0) break;
        }
    }

    private void render(float[] out) {
        final double base = now(), msPerSample = 1000.0 / rate;
        final double ramp = 1.0 / (rate * 0.005);  // 5 ms attack/decay, no clicks
        synchronized (mx) {
            keyer.ditIn = ditIn;  // paddles sampled once per audio burst (a few ms)
            keyer.dahIn = dahIn;
            for (int i = 0; i < out.length; ++i) {
                boolean on;
                if (demoPos < demo.length) {
                    on = demo[demoPos] > 0;
                    if (--demoLeft <= 0 && ++demoPos < demo.length) demoLeft = Math.abs(demo[demoPos]);
                } else {
                    on = keyer.step();
                    if (on != wasOn) {
                        final double t = base + i * msPerSample;
                        if (on) { liveStart = t; onSamples = 0; onSym = keyer.sym; }
                        else { done.add(new Elem(liveStart, t, onSamples * msPerSample, onSym)); liveStart = -1; }
                        wasOn = on;
                    }
                    if (on) ++onSamples;
                }
                env = on ? Math.min(1.0, env + ramp) : Math.max(0.0, env - ramp);
                phase = (phase + 2 * Math.PI * freq / rate) % (2 * Math.PI);
                out[i] = (float) (Math.sin(phase) * env * vol);
            }
        }
    }
}
