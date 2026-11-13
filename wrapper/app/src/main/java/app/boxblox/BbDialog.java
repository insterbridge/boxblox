package app.boxblox;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

/**
 * A dialog whose width is decided before it is shown.
 *
 * <p>{@code AlertDialog} re-applies its own window layout as part of being
 * shown, so setting {@code WindowManager.LayoutParams} beforehand is discarded
 * and setting it in a show listener makes the dialog jump — it slides in at the
 * platform's width and then snaps to the requested one (both measured). This
 * class sets the width in {@link #onStart()}, which runs after the window exists
 * but before it is shown and animated, so the first frame is already correct.
 */
final class BbDialog extends Dialog {

    private final View content;
    private final int widthDp;

    BbDialog(Context context, View content, int widthDp) {
        super(context);
        this.content = content;
        this.widthDp = widthDp;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(content);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window == null) {
            return;
        }
        int screen = getContext().getResources().getDisplayMetrics().widthPixels;
        float density = getContext().getResources().getDisplayMetrics().density;
        int max = Math.round(widthDp * density);
        int inset = Math.round(32 * density);
        window.setLayout(Math.min(screen - inset, max), ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
