package com.parksy.paddletrainer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

// On-screen paddles: left half = '[' (dit), right half = ']' (dah). Multi-touch, so both halves can be squeezed.
final class PaddleView extends View {
    interface Listener { void paddle(int phys, boolean down); }

    Listener listener;
    boolean swap;
    private final boolean[] down = new boolean[2];

    PaddleView(Context c) { super(c); }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        final int lifted = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
        boolean[] now = new boolean[2];
        if (action != MotionEvent.ACTION_CANCEL)
            for (int i = 0; i < e.getPointerCount(); ++i)
                if (i != lifted) now[e.getX(i) < getWidth() / 2f ? 0 : 1] = true;
        for (int side = 0; side < 2; ++side)
            if (now[side] != down[side]) {
                down[side] = now[side];
                if (listener != null) listener.paddle(side, now[side]);
            }
        invalidate();
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        final float d = getResources().getDisplayMetrics().density;
        c.save();
        c.scale(d, d);
        final float W = getWidth() / d, H = getHeight() / d;
        c.drawColor(Ui.BG);
        for (int side = 0; side < 2; ++side) {
            final boolean dah = (side == 1) != swap;
            final RectF r = new RectF(side * W / 2 + 6, 6, (side + 1) * W / 2 - 6, H - 6);
            final int color = dah ? android.graphics.Color.rgb(255, 165, 60) : android.graphics.Color.rgb(80, 160, 255);
            Ui.round(c, r, 14, down[side] ? color : Ui.alpha(color, 50));
            Ui.centred(c, dah ? "dah  ]" : "[  dit", new RectF(r.left, r.top, r.right, r.centerY() + 6), 22, down[side] ? Ui.BG : Ui.INK, true);
            Ui.centred(c, "화면 패들 (USB 패들도 됨)", new RectF(r.left, r.centerY() + 8, r.right, r.bottom - 4), 10, down[side] ? Ui.BG : Ui.MUTED, false);
        }
        c.restore();
    }
}
