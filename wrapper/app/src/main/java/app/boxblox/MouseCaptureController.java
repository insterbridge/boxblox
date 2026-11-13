package app.boxblox;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;

import java.util.ArrayDeque;

/**
 * Owns the Android side of the mouse-lock correction.
 *
 * <p>It is the installed native {@link EngineBridge.MouseLockTrace} and turns
 * native gesture transitions, frame-time engine intent and confirmed platform
 * capture callbacks into exactly one of the {@link MouseCapturePolicy}
 * decisions: request pointer capture, release it, or do nothing.
 *
 * <p><b>Threading.</b> Every piece of policy and view state is main-thread
 * confined. Native callbacks can arrive on an engine thread, so they are put on
 * one queue and drained by one main-looper runnable; the engine thread never
 * waits for the UI. Frame reconciliation reuses one runnable, is scheduled at
 * most once at a time, and exists only while a gesture or request needs it.
 * Motion silence is never evidence of a release.
 *
 * <p><b>Capture truth.</b> Confirmed capture changes only through
 * {@link #onPointerCaptureChanged(boolean)}, which the window callback forwards
 * after the guest. A request is fire-and-forget and is not treated as capture.
 */
final class MouseCaptureController implements EngineBridge.MouseLockTrace {

    private static final long FRAME_MS = 16L;

    private final Handler mainHandler;
    private final View root;
    private final MouseCapturePolicy policy = new MouseCapturePolicy();
    private final ArrayDeque<Event> pending = new ArrayDeque<>();
    private final Runnable reconcileRunnable = this::reconcile;

    private boolean enabled;
    private boolean reconcilePosted;
    private boolean drainPosted;
    private int lastLoggedBehavior = Integer.MIN_VALUE;
    private Runnable sampleCanceller;

    MouseCaptureController(Activity activity, boolean enabled) {
        Window window = activity == null ? null : activity.getWindow();
        this.root = window == null ? null : window.getDecorView();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.enabled = enabled;
    }

    /**
     * Install the Diag/native sink. Must run before
     * {@link EngineBridge#setMouseLockEnabled(boolean)} and
     * {@link EngineBridge#installMouseLockHook(Class)} so every install
     * outcome and later transition reaches the same sink.
     */
    void installTrace() {
        Diag.set("mouse capture: sink install (feature="
                + (enabled ? "enabled" : "disabled") + ", root=" + rootIdentity() + ")");
        try {
            EngineBridge.setMouseLockTrace(this);
        } catch (Throwable t) {
            Diag.set("mouse capture: trace unavailable: " + t);
        }
        boolean platformCapture = platformCapture();
        MouseCapturePolicy.Action action = platformCapture
                ? policy.onCaptureChanged(true)
                : MouseCapturePolicy.Action.NONE;
        setNativeCaptureState(platformCapture);
        Diag.set("mouse capture: initial platform capture=" + platformCapture
                + " action=" + action + " (" + policy.reason() + ")");
    }

    /** Let the window callback cancel its delayed diagnostic samples with the gesture. */
    void setSampleCanceller(Runnable canceller) {
        sampleCanceller = canceller;
    }

    /**
     * Whether this controller is bound to that activity's window.
     *
     * <p>A guest activity can be recreated (for example after a
     * configuration change), and a controller that kept the old decor view
     * would request and release capture on a detached window. The lifecycle
     * owner replaces it instead of reusing it.
     */
    boolean isFor(Activity activity) {
        Window window = activity == null ? null : activity.getWindow();
        return window != null && window.getDecorView() == root;
    }

    // ------------------------------------------------------------ trace sink

    @Override
    public void log(String line) {
        // Native lines already name their subject ("mouse state:", "gesture",
        // "button release", "installMouseLockHook"); prefixing again produced
        // "mouse mouse state:".
        Diag.set(line);
    }

    @Override
    public void onMouseButton(boolean down, long gestureId) {
        if (onMainThread()) {
            handleButton(down, gestureId);
        } else {
            enqueue(Event.button(down, gestureId));
        }
    }

    @Override
    public void onEnabled(boolean enabled) {
        if (onMainThread()) {
            handleEnabled(enabled);
        } else {
            enqueue(Event.enabled(enabled));
        }
    }

    // ------------------------------------------------------- public actions

    /** Confirmed window capture callback from {@link ButtonObserver}. */
    void onPointerCaptureChanged(boolean hasCapture) {
        if (!onMainThread()) {
            mainHandler.post(() -> onPointerCaptureChanged(hasCapture));
            return;
        }
        MouseCapturePolicy.Action action = policy.onCaptureChanged(hasCapture);
        setNativeCaptureState(hasCapture);
        Diag.set("mouse capture: pointer capture confirmed=" + hasCapture
                + " gesture=" + policy.activeGestureId()
                + " request=" + policy.requestInFlight()
                + " platform=" + platformCapture()
                + " action=" + action + " (" + policy.reason() + ")"
                + " t=" + nowMs() + "ms " + focusIdentity());
        if (action == MouseCapturePolicy.Action.RELEASE) {
            releasePlatformCapture(policy.reason());
        }
        if (policy.needsReconciliation()) {
            scheduleReconcile();
        } else {
            cancelReconcile();
        }
    }

    /**
     * One lifecycle/F1 entry point.
     *
     * @param reason                  diagnostic label
     * @param releasePlatformCapture  release the window's actual capture when present
     */
    void cancel(String reason, boolean releasePlatformCapture) {
        if (!onMainThread()) {
            mainHandler.post(() -> cancel(reason, releasePlatformCapture));
            return;
        }
        cancelInternal(reason, releasePlatformCapture);
    }

    /** Run after the activity resumes, before the next input event. */
    void onResume(boolean featureEnabled) {
        if (!onMainThread()) {
            mainHandler.post(() -> onResume(featureEnabled));
            return;
        }
        enabled = featureEnabled;
        syncConfirmedCapture("resume");
    }

    /** F1: release wrapper right-drag capture, never engine-owned capture. */
    boolean onF1() {
        if (!onMainThread()) {
            return false;
        }
        boolean wrapperOwned = policy.f1();
        Diag.set("mouse capture: F1 wrapper-owned=" + wrapperOwned
                + " gesture=" + policy.activeGestureId()
                + " behavior=" + safeBehavior()
                + " confirmed=" + policy.captureConfirmed()
                + " platform=" + platformCapture()
                + " t=" + nowMs() + "ms (" + policy.reason() + ")");
        if (wrapperOwned) {
            releasePlatformCapture("f1");
        }
        boolean nativeActive = releaseNative("f1");
        Diag.set("mouse capture: F1 native tracking released=" + nativeActive);
        // Every F1/cancel path stops frame reconciliation, delayed samples and
        // queued transitions; a late capture callback is handled directly by
        // onPointerCaptureChanged.
        cancelReconcile();
        cancelSamples();
        synchronized (pending) {
            pending.clear();
        }
        return wrapperOwned || nativeActive;
    }

    long activeGestureId() {
        return policy.activeGestureId();
    }

    boolean hasActiveGesture() {
        return policy.activeGestureId() != MouseCapturePolicy.NO_GESTURE;
    }

    /** Window capture state, focused view identity and gesture, for samples. */
    String snapshot() {
        return "window-capture=" + platformCapture()
                + " controller-confirmed=" + policy.captureConfirmed()
                + " gesture=" + policy.activeGestureId()
                + " " + focusIdentity();
    }

    // ------------------------------------------------------------ main logic

    private void handleButton(boolean down, long gestureId) {
        if (!enabled) {
            Diag.set("mouse capture: native button " + (down ? "down" : "up")
                    + " gesture=" + gestureId + " ignored (feature disabled)");
            return;
        }
        if (down) {
            // Recover from a missed gain/loss callback at a clean gesture
            // boundary, never from motion timing.
            syncConfirmedCapture("gesture down");
        }
        MouseCapturePolicy.Action action = down
                ? policy.secondaryDown(gestureId)
                : policy.secondaryUp(gestureId);
        int behavior = safeBehavior();
        Diag.set("mouse capture: native button " + (down ? "down" : "up")
                + " gesture=" + gestureId
                + " active=" + policy.activeGestureId()
                + " behavior=" + behavior
                + " confirmed=" + policy.captureConfirmed()
                + " t=" + nowMs() + "ms"
                + " action=" + action + " (" + policy.reason() + ")");
        if (action == MouseCapturePolicy.Action.RELEASE) {
            releasePlatformCapture(policy.reason());
        }
        if (down) {
            // Runs after the current Window.Callback dispatch has returned.
            scheduleReconcile();
        } else if (policy.needsReconciliation()) {
            scheduleReconcile();
        } else {
            cancelReconcile();
        }
    }

    private void handleEnabled(boolean featureEnabled) {
        Diag.set("mouse capture: feature " + (featureEnabled ? "enabled" : "disabled")
                + " t=" + nowMs() + "ms");
        enabled = featureEnabled;
        if (featureEnabled) {
            syncConfirmedCapture("enable");
        } else {
            cancelInternal("disabled", true);
        }
    }

    private void cancelInternal(String reason, boolean releaseActual) {
        cancelReconcile();
        cancelSamples();
        synchronized (pending) {
            pending.clear();
        }
        policy.cancel();
        Diag.set("mouse capture: cancel reason=" + reason
                + " gesture=" + policy.activeGestureId()
                + " behavior=" + safeBehavior()
                + " confirmed=" + policy.captureConfirmed()
                + " platform=" + platformCapture()
                + " t=" + nowMs() + "ms (" + policy.reason() + ")");
        if (releaseActual) {
            releasePlatformCapture(reason);
        }
        boolean nativeActive = releaseNative(reason);
        Diag.set("mouse capture: cancel reason=" + reason
                + " native tracking released=" + nativeActive);
    }

    private void reconcile() {
        reconcilePosted = false;
        if (!enabled) {
            return;
        }
        int behavior = safeBehavior();
        long gesture = policy.activeGestureId();
        MouseCapturePolicy.Action action = policy.reconcile(behavior);
        if (action != MouseCapturePolicy.Action.NONE || behavior != lastLoggedBehavior) {
            lastLoggedBehavior = behavior;
            Diag.set("mouse capture: reconcile gesture=" + gesture
                    + " behavior=" + behavior
                    + " confirmed=" + policy.captureConfirmed()
                    + " request=" + policy.requestInFlight()
                    + " platform=" + platformCapture()
                    + " t=" + nowMs() + "ms"
                    + " action=" + action + " (" + policy.reason() + ")"
                    + " " + focusIdentity());
        }
        if (action == MouseCapturePolicy.Action.REQUEST) {
            requestPlatformCapture();
        } else if (action == MouseCapturePolicy.Action.RELEASE) {
            releasePlatformCapture(policy.reason());
        }
        if (policy.needsReconciliation()) {
            scheduleReconcile();
        }
    }

    /** Re-read confirmed window capture at a lifecycle boundary. */
    void syncConfirmedCapture(String why) {
        boolean platformCapture = platformCapture();
        boolean changed = platformCapture != policy.captureConfirmed();
        // Always feed the boundary state: if a confirmed wrapper capture is
        // still marked for release, this re-issues that release instead of
        // assuming the callback already arrived.
        MouseCapturePolicy.Action action = policy.onCaptureChanged(platformCapture);
        setNativeCaptureState(platformCapture);
        if (changed || action != MouseCapturePolicy.Action.NONE) {
            Diag.set("mouse capture: " + why + " sync confirmed=" + platformCapture
                    + " gesture=" + policy.activeGestureId()
                    + " action=" + action + " (" + policy.reason() + ")"
                    + " t=" + nowMs() + "ms");
        }
        if (action == MouseCapturePolicy.Action.RELEASE) {
            releasePlatformCapture(policy.reason());
        }
        if (policy.needsReconciliation()) {
            scheduleReconcile();
        } else {
            cancelReconcile();
        }
    }

    private void requestPlatformCapture() {
        if (platformCapture()) {
            // The engine's own generic listener may have won the race. Adopt
            // the confirmed state instead of requesting again from a possibly
            // different view (capture is per-view).
            syncConfirmedCapture("request skipped: capture already present");
            return;
        }
        View target = captureTarget();
        if (target == null) {
            Diag.set("mouse capture: request skipped (no root view)");
            return;
        }
        if (target == root) {
            // Never capture on the decor: it would take the events the engine's
            // own listener needs. The engine's generic listener can still
            // request capture itself on the next movement.
            Diag.set("mouse capture: request skipped (no engine view identified)");
            return;
        }
        try {
            target.requestPointerCapture();
            Diag.set("mouse capture: request sent gesture=" + policy.activeGestureId()
                    + " behavior=" + safeBehavior()
                    + " target=" + describe(target)
                    + " t=" + nowMs() + "ms " + focusIdentity());
        } catch (Throwable t) {
            Diag.set("mouse capture: request failed: " + t);
        }
    }

    /**
     * The view that must own captured pointer events.
     *
     * <p>Capture is per-view: requesting it on the decor would route captured
     * events to the decor and starve the engine's own
     * {@code setOnCapturedPointerListener}. The guest's focused input/scene
     * view, or its AGDK content view, is the view the engine installed that
     * listener on; text widgets are excluded, and the decor is only a last
     * resort.
     */
    private View captureTarget() {
        if (root == null) {
            return null;
        }
        View content = null;
        try {
            content = root.findViewById(android.R.id.content);
        } catch (Throwable ignored) {
            // Fall back to the focused/root view.
        }
        View contentChild = content;
        if (content instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) content;
            if (group.getChildCount() > 0) {
                contentChild = group.getChildAt(0);
            }
        }
        View focused = null;
        try {
            focused = root.findFocus();
        } catch (Throwable ignored) {
            // Identity is diagnostic only.
        }
        if (focused != null && focused != root
                && !(focused instanceof android.widget.TextView)) {
            // The game's own input view gets focus; TextViews (text boxes,
            // buttons) are not the engine's captured-pointer listener target.
            return focused;
        }
        if (contentChild != null) {
            return contentChild;
        }
        return focused != null ? focused : root;
    }

    private void releasePlatformCapture(String reason) {
        boolean platformCapture = platformCapture();
        if (root != null) {
            // Window-level release is a no-op when capture is already gone;
            // calling it unconditionally also covers a grant callback that
            // arrives just before hasPointerCapture() reflects it.
            try {
                root.releasePointerCapture();
            } catch (Throwable t) {
                Diag.set("mouse capture: release failed: " + t);
            }
        }
        Diag.set("mouse capture: release reason=" + reason
                + " gesture=" + policy.activeGestureId()
                + " behavior=" + safeBehavior()
                + " confirmed=" + policy.captureConfirmed()
                + " platform=" + platformCapture
                + " t=" + nowMs() + "ms");
    }

    // -------------------------------------------------------------- frames

    /**
     * Schedule one frame reconciliation. The runnable is reused and at most
     * one is outstanding; native callbacks only mark state and never request
     * capture inline.
     */
    private void scheduleReconcile() {
        if (!enabled || reconcilePosted || !policy.needsReconciliation()) {
            return;
        }
        reconcilePosted = true;
        boolean posted = false;
        if (root != null && root.isAttachedToWindow()) {
            try {
                root.postOnAnimation(reconcileRunnable);
                posted = true;
            } catch (Throwable ignored) {
                // Fall back to the handler below.
            }
        }
        if (!posted && !mainHandler.postDelayed(reconcileRunnable, FRAME_MS)) {
            reconcilePosted = false;
        }
    }

    private void cancelSamples() {
        Runnable canceller = sampleCanceller;
        if (canceller != null) {
            try {
                canceller.run();
            } catch (Throwable ignored) {
                // Diagnostics only.
            }
        }
    }

    private void cancelReconcile() {
        reconcilePosted = false;
        try {
            mainHandler.removeCallbacks(reconcileRunnable);
        } catch (Throwable ignored) {
            // Nothing to cancel.
        }
        if (root != null) {
            try {
                root.removeCallbacks(reconcileRunnable);
            } catch (Throwable ignored) {
                // Nothing to cancel.
            }
        }
    }

    // ------------------------------------------------------------- plumbing

    private static boolean releaseNative(String reason) {
        try {
            return EngineBridge.releaseMouseLock(reason);
        } catch (Throwable t) {
            return false;
        }
    }

    private int safeBehavior() {
        try {
            return EngineBridge.mouseBehavior();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void setNativeCaptureState(boolean hasCapture) {
        try {
            EngineBridge.setPointerCaptureState(hasCapture);
        } catch (Throwable t) {
            // The hook may not be installed yet; the next callback retries.
        }
    }

    private boolean platformCapture() {
        if (root == null) {
            return false;
        }
        try {
            return root.hasPointerCapture();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean onMainThread() {
        return Looper.myLooper() == mainHandler.getLooper();
    }

    private static long nowMs() {
        return SystemClock.elapsedRealtime();
    }

    private String rootIdentity() {
        return root == null ? "null" : describe(root);
    }

    private String focusIdentity() {
        View focus = null;
        if (root != null) {
            try {
                focus = root.findFocus();
            } catch (Throwable ignored) {
                // Identity is diagnostic only.
            }
        }
        return "focus=" + (focus == null ? "none" : describe(focus));
    }

    private static String describe(View view) {
        return view.getClass().getName() + '#' + Integer.toHexString(System.identityHashCode(view));
    }

    private void enqueue(Event event) {
        boolean post;
        synchronized (pending) {
            pending.addLast(event);
            post = !drainPosted;
            drainPosted = true;
        }
        if (post && !mainHandler.post(this::drainPending)) {
            synchronized (pending) {
                drainPosted = false;
            }
        }
    }

    private void drainPending() {
        while (true) {
            Event event;
            synchronized (pending) {
                event = pending.pollFirst();
                if (event == null) {
                    drainPosted = false;
                    return;
                }
            }
            if (event.button) {
                handleButton(event.down, event.gestureId);
            } else {
                handleEnabled(event.enabled);
            }
        }
    }

    private static final class Event {
        final boolean button;
        final boolean down;
        final long gestureId;
        final boolean enabled;

        private Event(boolean button, boolean down, long gestureId, boolean enabled) {
            this.button = button;
            this.down = down;
            this.gestureId = gestureId;
            this.enabled = enabled;
        }

        static Event button(boolean down, long gestureId) {
            return new Event(true, down, gestureId, false);
        }

        static Event enabled(boolean enabled) {
            return new Event(false, false, MouseCapturePolicy.NO_GESTURE, enabled);
        }
    }
}
