package com.rkh.callisto.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import com.rkh.callisto.model.ConnectionStateStore;

public final class CallistoOrbView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ConnectionStateStore.Status status = ConnectionStateStore.Status.DISCONNECTED;
    private ValueAnimator stateAnimator;
    private float phase;
    private float orbitRotation;

    public CallistoOrbView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
    }

    public void setConnectionStatus(ConnectionStateStore.Status next) {
        if (next == null) next = ConnectionStateStore.Status.DISCONNECTED;
        if (status == next && stateAnimator != null && stateAnimator.isRunning()) return;
        status = next;
        if (stateAnimator != null) stateAnimator.cancel();

        if (status == ConnectionStateStore.Status.CONNECTING
                || status == ConnectionStateStore.Status.CONNECTED
                || status == ConnectionStateStore.Status.DISCONNECTING) {
            float direction = status == ConnectionStateStore.Status.DISCONNECTING ? -1f : 1f;
            float startRotation = orbitRotation;
            stateAnimator = ValueAnimator.ofFloat(0f, 1f);
            stateAnimator.setDuration(status == ConnectionStateStore.Status.CONNECTING ? 850L
                    : status == ConnectionStateStore.Status.CONNECTED ? 8500L : 1350L);
            stateAnimator.setRepeatCount(ValueAnimator.INFINITE);
            stateAnimator.setRepeatMode(ValueAnimator.RESTART);
            stateAnimator.setInterpolator(new LinearInterpolator());
            stateAnimator.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                orbitRotation = startRotation + direction * phase * 360f;
                invalidate();
            });
            stateAnimator.start();
        } else {
            stateAnimator = null;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) * .26f;

        stroke.setColor(0x66FFFFFF);
        stroke.setStrokeWidth(dp(1.2f));
        RectF orbit = new RectF(cx - radius * 1.65f, cy - radius * .7f,
                cx + radius * 1.65f, cy + radius * .7f);
        canvas.save();
        float rotation = -13f + orbitRotation;
        canvas.rotate(rotation, cx, cy);
        canvas.drawOval(orbit, stroke);
        paint.setShader(null);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx + radius * 1.42f, cy, dp(2.8f), paint);
        canvas.restore();

        paint.setShader(new RadialGradient(cx - radius * .3f, cy - radius * .35f,
                radius * 1.45f,
                new int[]{0xFFFFFFFF, 0xFFC7C7C7, 0xFF5F5F5F},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        crater(canvas, cx - radius * .38f, cy - radius * .25f, radius * .22f, 0x75676767);
        crater(canvas, cx + radius * .31f, cy - radius * .12f, radius * .15f, 0x735C5C5C);
        crater(canvas, cx - radius * .05f, cy + radius * .34f, radius * .25f, 0x80636363);
        crater(canvas, cx + radius * .43f, cy + radius * .38f, radius * .09f, 0x775A5A5A);
        crater(canvas, cx + radius * .05f, cy - radius * .55f, radius * .08f, 0x80707070);

        stroke.setColor(status == ConnectionStateStore.Status.CONNECTED
                ? 0xFFFFFFFF : 0xB0B8B8B8);
        stroke.setStrokeWidth(dp(status == ConnectionStateStore.Status.CONNECTED ? 2f : 1.2f));
        canvas.drawCircle(cx, cy, radius + dp(3), stroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            animate().scaleX(.94f).scaleY(.94f).setDuration(90L)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            animate().scaleX(1f).scaleY(1f).setDuration(180L)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (stateAnimator != null) stateAnimator.cancel();
        super.onDetachedFromWindow();
    }

    private void crater(Canvas canvas, float x, float y, float radius, int color) {
        paint.setColor(color);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x55FFFFFF);
        canvas.drawCircle(x - dp(1), y - dp(1), radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
