package com.rkh.callisto.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

public final class SpaceBackgroundView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] starX = new float[90];
    private final float[] starY = new float[90];
    private final float[] starR = new float[90];
    private final float[] starPhase = new float[90];
    private final float[] starSpeed = new float[90];
    private final int[] starBaseAlpha = new int[90];
    private boolean animateStars;

    public SpaceBackgroundView(Context context) {
        super(context);
        Random random = new Random(4107);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = random.nextFloat();
            starY[i] = random.nextFloat();
            starR[i] = 0.35f + random.nextFloat() * 1.25f;
            starPhase[i] = random.nextFloat() * (float) (Math.PI * 2d);
            starSpeed[i] = .0014f + random.nextFloat() * .0036f;
            starBaseAlpha[i] = 38 + random.nextInt(78);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        paint.setShader(null);
        paint.setColor(Color.BLACK);
        canvas.drawRect(0, 0, w, h, paint);

        paint.setShader(null);
        long now = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i < starX.length; i++) {
            float shimmer = .5f + .5f * (float) Math.sin(starPhase[i] + now * starSpeed[i]);
            int alpha = Math.min(255, starBaseAlpha[i] + Math.round(shimmer * 138f));
            paint.setColor(Color.argb(alpha, 255, 255, 255));
            canvas.drawCircle(starX[i] * w, starY[i] * h, starR[i] * getResources().getDisplayMetrics().density, paint);
        }
        if (animateStars) postInvalidateDelayed(45L);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animateStars = getWindowVisibility() == VISIBLE;
        if (animateStars) invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        animateStars = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        animateStars = visibility == VISIBLE;
        if (animateStars) invalidate();
    }
}
