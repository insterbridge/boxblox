package app.boxblox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The wrapper forwards game events to the guest untouched, and its back-key
 * IME behaviour is unchanged.
 *
 * <p>Buttons are observed in the engine's own native stream
 * ({@code nativePassMouseButton}), so the Java callback deliberately has no
 * mouse state of its own and nothing to test there. What is pinned here:
 * events pass through with the engine's answer, and {@link ButtonObserver.BackKeyTracker}
 * keeps the keyboard-dismiss semantics.
 */
public class ButtonObserverTest {

    private static final class RecordingCallback implements android.view.Window.Callback {
        private final boolean outcome;
        int motionCalls;

        RecordingCallback(boolean outcome) {
            this.outcome = outcome;
        }

        @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
            motionCalls++;
            return outcome;
        }
        @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
            return outcome;
        }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            return outcome;
        }
        @Override public boolean dispatchTrackballEvent(android.view.MotionEvent event) {
            return outcome;
        }
        @Override public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) {
            return outcome;
        }
        @Override public boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
            return outcome;
        }
        @Override public boolean onCreatePanelMenu(int featureId, android.view.Menu menu) {
            return outcome;
        }
        @Override public boolean onPreparePanel(int featureId, android.view.View view, android.view.Menu menu) {
            return outcome;
        }
        @Override public boolean onMenuOpened(int featureId, android.view.Menu menu) {
            return outcome;
        }
        @Override public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams attrs) {
        }
        @Override public void onContentChanged() {
        }
        @Override public void onWindowFocusChanged(boolean hasFocus) {
        }
        @Override public void onAttachedToWindow() {
        }
        @Override public void onDetachedFromWindow() {
        }
        @Override public android.view.View onCreatePanelView(int featureId) {
            return null;
        }
        @Override public boolean onMenuItemSelected(int featureId, android.view.MenuItem item) {
            return outcome;
        }
        @Override public void onPanelClosed(int featureId, android.view.Menu menu) {
        }
        @Override public boolean onSearchRequested() {
            return outcome;
        }
        @Override public boolean onSearchRequested(android.view.SearchEvent event) {
            return outcome;
        }
        @Override public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback) {
            return null;
        }
        @Override public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback, int type) {
            return null;
        }
        @Override public void onActionModeStarted(android.view.ActionMode mode) {
        }
        @Override public void onActionModeFinished(android.view.ActionMode mode) {
        }
    }

    @Test
    public void genericMotionPassThroughKeepsTheEngineResult() {
        RecordingCallback delegate = new RecordingCallback(true);
        ButtonObserver observer = new ButtonObserver(delegate, null);
        // A null MotionEvent is sufficient: nothing inspects its contents.
        assertTrue(observer.dispatchGenericMotionEvent(null));
        assertEquals(1, delegate.motionCalls);
    }

    @Test
    public void genericMotionPassThroughFalseIsPreservedToo() {
        RecordingCallback delegate = new RecordingCallback(false);
        ButtonObserver observer = new ButtonObserver(delegate, null);
        assertFalse(observer.dispatchGenericMotionEvent(null));
        assertEquals(1, delegate.motionCalls);
    }

    // MotionEvent/KeyEvent constants are stubs in a local unit test; these
    // are the Android values, inlined as the previous tests did.
    private static final int ACTION_DOWN = 0;
    private static final int ACTION_UP = 1;

    @Test
    public void backWhileKeyboardIsHiddenIsNotConsumed() {
        ButtonObserver.BackKeyTracker tracker = new ButtonObserver.BackKeyTracker();
        assertFalse(tracker.consume(ACTION_DOWN, 1000, 0, false));
    }

    @Test
    public void backWhileKeyboardIsVisibleIsConsumedAndDismisses() {
        ButtonObserver.BackKeyTracker tracker = new ButtonObserver.BackKeyTracker();
        assertTrue(tracker.consume(ACTION_DOWN, 1000, 0, true));
        assertTrue(tracker.consume(ACTION_UP, 1000, 0, true));
        assertTrue(ButtonObserver.BackKeyTracker.shouldDismiss(ACTION_UP, false));
    }

    @Test
    public void canceledBackDoesNotDismissTheKeyboard() {
        assertFalse(ButtonObserver.BackKeyTracker.shouldDismiss(ACTION_UP, true));
    }

    @Test
    public void focusResetClearsBackTracking() {
        ButtonObserver.BackKeyTracker tracker = new ButtonObserver.BackKeyTracker();
        tracker.consume(ACTION_DOWN, 1000, 0, true);
        tracker.reset();
        // Once reset, the up is a new gesture whose down never happened.
        assertFalse(tracker.consume(ACTION_UP, 1000, 0, true));
    }

    @Test
    public void repeatedBackStaysConsumedWithoutRecheckingVisibility() {
        ButtonObserver.BackKeyTracker tracker = new ButtonObserver.BackKeyTracker();
        tracker.consume(ACTION_DOWN, 1000, 0, true);
        // The repeat belongs to the original gesture: it is consumed even
        // though the IME is no longer reported visible.
        assertTrue(tracker.consume(ACTION_DOWN, 1000, 2, false));
    }
}
