package app.boxblox;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The pill switch from the approved design.
 *
 * <p>The platform's {@code Switch} cannot be made to look like this: it derives
 * its thumb geometry from the track drawable's intrinsic bounds and applies its
 * own theme tints at inflate time, so a 19dp thumb on a 26dp track overhangs and
 * programmatic tints are discarded (both measured on device). Drawing it here
 * makes the geometry explicit and independent of the theme.
 *
 * <p>Checked state grows the thumb from 13dp to 19dp while it travels, which is
 * what the mockup's two states specify.
 */
public final class ToggleView extends View {

    private static final float WIDTH_DP = 44f;
    private static final float HEIGHT_DP = 26f;
    private static final float THUMB_OFF_DP = 13f;
    private static final float THUMB_ON_DP = 19f;
    private static final float INSET_DP = 4f;

    private static final int COLOR_TRACK_ON = 0xFFD0BCFF;
    private static final int COLOR_TRACK_OFF = 0xFF36343B;
    private static final int COLOR_STROKE = 0xFF938F99;
    private static final int COLOR_THUMB_ON = 0xFF381E72;
    private static final int COLOR_THUMB_OFF = 0xFF938F99;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    private final float density;
    private boolean checked;
    /** 0 = off, 1 = on; animated so the thumb slides rather than jumps. */
    private float progress;
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(ToggleView view, boolean checked);
    }

    public ToggleView(Context context) {
        this(context, null);
    }

    public ToggleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(2));
        strokePaint.setColor(COLOR_STROKE);
        setClickable(true);
        setFocusable(true);
    }

    public void setChecked(boolean value) {
        if (checked == value) {
            return;
        }
        checked = value;
        animateTo(value ? 1f : 0f);
    }

    public boolean isChecked() {
        return checked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    private void animateTo(float target) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(140);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    /** Set the visual state without animating, for the first bind. */
    void setCheckedImmediate(boolean value) {
        checked = value;
        progress = value ? 1f : 0f;
        if (animator != null) {
            animator.cancel();
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round(dp(WIDTH_DP)), Math.round(dp(HEIGHT_DP)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float radius = h / 2f;

        trackRect.set(0, 0, w, h);
        trackPaint.setColor(lerp(COLOR_TRACK_OFF, COLOR_TRACK_ON, progress));
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint);
        if (progress < 1f) {
            // The outline fades out as the track fills, so the checked state is
            // a solid pill rather than a filled pill with a ring around it.
            //
            // The stroke is centred on its path, so drawing it on the full
            // bounds put half of it outside the view and the canvas clipped it:
            // the ring came out uneven, heavy on the long edges and cut off at
            // the corners. Insetting by half the stroke width keeps all of it
            // inside.
            float halfStroke = strokePaint.getStrokeWidth() / 2f;
            trackRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
            strokePaint.setAlpha((int) (255 * (1f - progress)));
            canvas.drawRoundRect(trackRect, radius - halfStroke, radius - halfStroke, strokePaint);
        }

        float thumbDiameter = dp(THUMB_OFF_DP)
                + (dp(THUMB_ON_DP) - dp(THUMB_OFF_DP)) * progress;
        float thumbRadius = thumbDiameter / 2f;
        float inset = dp(INSET_DP);
        // Travel is symmetric: the same gap on the left when off and on the
        // right when on, which is what makes the two states look like one
        // control rather than two.
        float startX = inset + thumbRadius;
        float endX = w - inset - thumbRadius;
        float cx = startX + (endX - startX) * progress;

        thumbPaint.setColor(lerp(COLOR_THUMB_OFF, COLOR_THUMB_ON, progress));
        canvas.drawCircle(cx, h / 2f, thumbRadius, thumbPaint);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        checked = !checked;
        animateTo(checked ? 1f : 0f);
        if (listener != null) {
            listener.onCheckedChanged(this, checked);
        }
        return true;
    }

    private float dp(float value) {
        return value * density;
    }

    private static int lerp(int from, int to, float t) {
        int a = (int) (((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
