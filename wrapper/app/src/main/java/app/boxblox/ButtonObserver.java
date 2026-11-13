package app.boxblox;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.Window;

import java.util.List;

/**
 * Wraps the guest window's callback.
 *
 * <b>Buttons are observed in native.</b> The engine's own
 * {@code nativePassMouseButton} runs for both the ordinary generic-motion
 * path and the captured-pointer path, so the wrapper keeps drag accounting
 * there. This class only wraps uncaptured mouse BUTTON_PRESS/RELEASE delegate
 * dispatch in the native getter-scope (so the guest processes the button
 * instead of requesting capture and swallowing it), forwards everything else
 * untouched, owns the IME watchdog and its back-key handling, delayed capture
 * samples, and F1.
 *
 * <b>Why the window callback and not a view listener.</b> The engine installs
 * its own {@code setOnGenericMotionListener} and {@code setOnCapturedPointerListener}
 * on the scene view; those single-slot setters would displace the engine's
 * listeners entirely. {@code Window.Callback} is upstream of that, and every
 * method that is not intercepted here is forwarded unchanged.
 */
final class ButtonObserver implements Window.Callback {

    /** Force-unlock key, matching Sober's convention. */
    private static final int UNLOCK_KEY = KeyEvent.KEYCODE_F1;

    private final Window.Callback delegate;
    private final MouseCaptureController controller;
    private android.view.View root;
    private boolean keyboardForced;
    private final BackKeyTracker backKey = new BackKeyTracker();

    /** Delayed capture samples; cancelled or labelled ended when the gesture ends. */
    private long sampleStartMs;
    private long sampleGestureId;
    private boolean samplesPending;
    private final Runnable earlyCaptureSample = () -> captureSample(250, false);
    private final Runnable lateCaptureSample = () -> captureSample(750, true);

    /** Consume an IME-dismissal key gesture, not individual visibility samples. */
    static final class BackKeyTracker {
        private boolean consuming;
        private long downTime;

        boolean consume(int action, long eventDownTime, int repeatCount, boolean imeVisible) {
            if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
                return false;
            }
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                consuming = imeVisible;
                downTime = eventDownTime;
            }
            if (!consuming || downTime != eventDownTime) {
                return false;
            }
            if (action == KeyEvent.ACTION_UP) {
                consuming = false;
            }
            return true;
        }

        static boolean shouldDismiss(int action, boolean canceled) {
            return action == KeyEvent.ACTION_UP && !canceled;
        }

        void reset() {
            consuming = false;
        }
    }

    ButtonObserver(Window.Callback delegate, MouseCaptureController controller) {
        this.delegate = delegate;
        this.controller = controller;
        if (controller != null) {
            controller.setSampleCanceller(this::cancelCaptureSamples);
        }
    }

    /** Drop delayed samples when the controller's gesture/cancel path ends. */
    private void cancelCaptureSamples() {
        if (!samplesPending) {
            return;
        }
        samplesPending = false;
        if (root != null) {
            root.removeCallbacks(earlyCaptureSample);
            root.removeCallbacks(lateCaptureSample);
        }
        Diag.set("capture samples cancelled by capture lifecycle after "
                + (SystemClock.elapsedRealtime() - sampleStartMs)
                + "ms gesture=" + sampleGestureId);
    }

    /**
     * Raise the soft keyboard whenever a text field takes focus.
     *
     * <p>Roblox's text boxes ask the platform for the IME only on its touch path.
     * With the desktop identity reported, the engine takes its desktop input
     * path instead and never calls {@code showSoftInput}, so a text box focuses
     * and accepts hardware keys but no keyboard appears (measured: typing into
     * the search box needed {@code adb input text}). This watches the view tree
     * for a focused {@code EditText} and asks for the keyboard itself; it adds
     * no listener to any view and replaces none of the engine's.
     */
    void attachKeyboard(android.view.View decor) {
        if (root != null || decor == null) {
            return;
        }
        root = decor;
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus instanceof android.widget.EditText) {
                newFocus.post(() -> showKeyboard((android.widget.EditText) newFocus));
            }
        });
    }

    private void showKeyboard(android.widget.EditText field) {
        android.view.inputmethod.InputMethodManager ime =
                (android.view.inputmethod.InputMethodManager) field.getContext()
                        .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (ime == null) {
            return;
        }
        field.requestFocus();
        if (ime.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)) {
            keyboardForced = true;
        }
    }

    /** Whether the wrapper has ever had to raise the keyboard itself. */
    boolean keyboardWasForced() {
        return keyboardForced;
    }

    /**
     * Clear tracked input state and run the one lifecycle cancel path.
     *
     * <p>The controller stops frame reconciliation, cancels pending request
     * tracking even when the platform reports no capture, releases actual
     * window capture, clears gesture state and releases native tracking. A JVM
     * unit test cannot load the native library, hence the swallowed
     * {@link Throwable}.
     */
    void reset() {
        reset("focus lost");
    }

    private void reset(String reason) {
        backKey.reset();
        if (controller != null) {
            controller.cancel(reason, true);
        } else {
            cancelCaptureSamples();
            releaseNativeLock(reason);
        }
    }

    /**
     * Ask the native side to release anything it owns.
     *
     * @return whether a lock was actually released
     */
    static boolean releaseNativeLock(String reason) {
        try {
            return EngineBridge.releaseMouseLock(reason);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------ Window.Callback

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        boolean scoped = false;
        boolean result;
        if (isMouseButtonEvent(event)) {
            // Only the delegate dispatch is inside the native scope: the
            // getter must see the original handler processing the button, and
            // the scope must close even when the guest throws.
            scoped = beginButtonDispatch();
        }
        try {
            result = delegate.dispatchGenericMotionEvent(event);
        } finally {
            if (scoped) {
                endButtonDispatch();
            }
        }
        maybeScheduleCaptureSample(event);
        return result;
    }

    private static boolean isMouseButtonEvent(MotionEvent event) {
        if (event == null || !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false;
        }
        int action = event.getActionMasked();
        return action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE;
    }

    private static boolean beginButtonDispatch() {
        try {
            EngineBridge.beginMouseButtonDispatch();
            return true;
        } catch (Throwable t) {
            // Optional enhancement; the delegate still receives the event.
            return false;
        }
    }

    private static void endButtonDispatch() {
        try {
            EngineBridge.endMouseButtonDispatch();
        } catch (Throwable t) {
            // The scope is native-side bookkeeping; never mask the guest result.
        }
    }

    /** Delayed samples are telemetry only; transition callbacks are primary evidence. */
    private static final long CAPTURE_SAMPLE_EARLY_MS = 250;
    private static final long CAPTURE_SAMPLE_LATE_MS = 750;

    /**
     * When a secondary press lands, sample capture state at +250 ms and
     * +750 ms.
     *
     * <p>Android does not report a denied {@code requestPointerCapture}: it is
     * fire-and-forget, so these delayed samples exist only to show whether
     * window-level capture was present at those instants. A captured release
     * bypasses generic motion entirely, so this method deliberately schedules
     * no release sample and must not be read as one. On an uncaptured release
     * pending samples are cancelled; a sample that runs after the controller's
     * gesture ended says so instead of implying a release.
     */
    private void maybeScheduleCaptureSample(MotionEvent event) {
        if (root == null || event == null) {
            return;
        }
        // Button codes are the platform constants; secondary remains 2.
        if (event.getActionButton() != MotionEvent.BUTTON_SECONDARY) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_BUTTON_PRESS) {
            samplesPending = true;
            sampleStartMs = SystemClock.elapsedRealtime();
            sampleGestureId = controller == null ? 0 : controller.activeGestureId();
            root.removeCallbacks(earlyCaptureSample);
            root.removeCallbacks(lateCaptureSample);
            root.postDelayed(earlyCaptureSample, CAPTURE_SAMPLE_EARLY_MS);
            root.postDelayed(lateCaptureSample, CAPTURE_SAMPLE_LATE_MS);
        } else if (action == MotionEvent.ACTION_BUTTON_RELEASE && samplesPending) {
            samplesPending = false;
            root.removeCallbacks(earlyCaptureSample);
            root.removeCallbacks(lateCaptureSample);
            Diag.set("capture samples cancelled at generic-motion release after "
                    + (SystemClock.elapsedRealtime() - sampleStartMs)
                    + "ms; a captured release bypasses this path");
        }
    }

    private void captureSample(long scheduledMs, boolean last) {
        if (!samplesPending) {
            return;
        }
        if (last) {
            samplesPending = false;
        }
        long actualMs = SystemClock.elapsedRealtime() - sampleStartMs;
        boolean ended = controller != null && !controller.hasActiveGesture();
        String state = controller == null ? "no controller" : controller.snapshot();
        if (ended) {
            Diag.set("capture sample +" + scheduledMs + "ms (actual +" + actualMs
                    + "ms) gesture=" + sampleGestureId
                    + ": gesture ended before the sample; stale observation, "
                    + "not evidence of a release; " + state);
        } else {
            Diag.set("capture sample +" + scheduledMs + "ms (actual +" + actualMs
                    + "ms) gesture=" + sampleGestureId
                    + ": delayed observation only; transition callbacks are primary "
                    + "evidence; " + state);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Back closes the keyboard before it reaches the game.
        //
        // The engine shows the IME for its own text boxes (see attachKeyboard),
        // and the platform does not give the keyboard its own back handling
        // here: the event is delivered to this activity's window and the guest's
        // view consumes it, so a back meant to dismiss the keyboard also opened
        // Roblox's in-game menu (measured). While the IME is visible, back is
        // consumed and used to hide it; every other key, and back once the
        // keyboard is gone, is forwarded untouched.
        // F1 releases wrapper right-drag capture and native tracking, never
        // engine-owned shift lock. Consumed only when it did something, so the
        // key still reaches the game otherwise.
        if (event.getKeyCode() == UNLOCK_KEY
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            boolean handled = controller != null
                    ? controller.onF1()
                    : releaseNativeLock("f1");
            if (handled) {
                return true;
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && backKey.consume(event.getAction(), event.getDownTime(), event.getRepeatCount(),
                        event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                                && keyboardVisible())) {
            if (BackKeyTracker.shouldDismiss(event.getAction(), event.isCanceled())) {
                hideKeyboard();
            }
            return true;
        }
        return delegate.dispatchKeyEvent(event);
    }

    /** Whether the soft keyboard is currently shown. */
    private boolean keyboardVisible() {
        if (root == null) {
            return false;
        }
        try {
            android.view.WindowInsets insets = root.getRootWindowInsets();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                return insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
            }
            // Before API 30, resized windows expose the IME through bottom
            // insets; panned windows instead shorten the visible display frame.
            int threshold = Math.round(100 * root.getResources().getDisplayMetrics().density);
            if (insets != null
                    && insets.getSystemWindowInsetBottom() - insets.getStableInsetBottom() > threshold) {
                return true;
            }
            android.graphics.Rect visible = new android.graphics.Rect();
            root.getWindowVisibleDisplayFrame(visible);
            int bars = insets == null ? 0
                    : insets.getStableInsetTop() + insets.getStableInsetBottom();
            return root.getRootView().getHeight() - visible.height() - bars > threshold;
        } catch (Throwable t) {
            // An unsupported query must not swallow back.
            return false;
        }
    }

    /** Dismiss the keyboard without changing the guest text field's focus. */
    private void hideKeyboard() {
        if (root == null) {
            return;
        }
        try {
            android.view.inputmethod.InputMethodManager ime =
                    (android.view.inputmethod.InputMethodManager) root.getContext()
                            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (ime != null) {
                ime.hideSoftInputFromWindow(root.getWindowToken(), 0);
            }
            keyboardForced = false;
        } catch (Throwable t) {
            // Nothing to do: the IME is already gone or unavailable.
        }
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return delegate.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return delegate.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return delegate.dispatchTrackballEvent(event);
    }

    @Override
    public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams attrs) {
        delegate.onWindowAttributesChanged(attrs);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        return delegate.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public void onContentChanged() {
        delegate.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (!hasFocus) {
            // The release lands on the window that took focus, not this one.
            reset("focus lost");
        } else if (controller != null) {
            // Re-read the confirmed window state after the focus-loss cancel;
            // this is a boundary snapshot, not motion-timing inference.
            controller.syncConfirmedCapture("focus gained");
        }
        delegate.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        delegate.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        reset("detached");
        delegate.onDetachedFromWindow();
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        // The guest sees the same confirmed transition it would without the
        // wrapper; the controller updates only from this callback.
        try {
            delegate.onPointerCaptureChanged(hasCapture);
        } finally {
            if (controller != null) {
                controller.onPointerCaptureChanged(hasCapture);
            }
        }
    }

    @Override
    public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data,
                                           Menu menu, int deviceId) {
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId);
    }

    @Override
    public android.view.View onCreatePanelView(int featureId) {
        return delegate.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, android.view.Menu menu) {
        return delegate.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, android.view.View view, android.view.Menu menu) {
        return delegate.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, android.view.Menu menu) {
        return delegate.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, android.view.MenuItem item) {
        return delegate.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onPanelClosed(int featureId, android.view.Menu menu) {
        delegate.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return delegate.onSearchRequested();
    }

    @Override
    public boolean onSearchRequested(android.view.SearchEvent event) {
        return delegate.onSearchRequested(event);
    }

    @Override
    public android.view.ActionMode onWindowStartingActionMode(
            android.view.ActionMode.Callback callback) {
        return delegate.onWindowStartingActionMode(callback);
    }

    @Override
    public android.view.ActionMode onWindowStartingActionMode(
            android.view.ActionMode.Callback callback, int type) {
        return delegate.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(android.view.ActionMode mode) {
        delegate.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(android.view.ActionMode mode) {
        delegate.onActionModeFinished(mode);
    }
}
