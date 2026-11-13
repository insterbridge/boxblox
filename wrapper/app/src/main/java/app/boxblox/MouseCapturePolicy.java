package app.boxblox;

/**
 * Pure capture decision state machine.
 *
 * <p>It owns no Android object and performs no I/O, so the rules can be driven
 * from a JVM test without a device. {@link MouseCaptureController} feeds it
 * native gesture transitions, frame-time engine intent and confirmed platform
 * capture callbacks, and performs the single {@link Action} it returns.
 *
 * <p>Rules encoded here, from local://mouse-lock-fix-contract.md:
 * <ul>
 *   <li>request only while the tracked secondary gesture is down, the engine
 *       behavior is {@code 2} (LockCurrentPosition), no request is in flight,
 *       and capture is not confirmed;</li>
 *   <li>never request for a stale id, a gesture that was cancelled, or one
 *       that has ended;</li>
 *   <li>release when the engine intent clears to {@code 0}, and when a wrapper
 *       capture is still confirmed for the gesture that is ending;</li>
 *   <li>capture gain/loss is only taken from
 *       {@code onPointerCaptureChanged}; a grant that arrives after the
 *       gesture/request was cancelled is released immediately.</li>
 * </ul>
 *
 * <p>Engine behavior {@code 1} is shift lock: it keeps whatever capture the
 * engine already owns and never causes a wrapper request. {@code -1} means the
 * enhancement is unavailable/disabled/unsupported and never requests.
 */
final class MouseCapturePolicy {

    /** No gesture is tracked. Native gesture ids are positive. */
    static final long NO_GESTURE = 0L;

    enum Action {
        NONE,
        REQUEST,
        RELEASE
    }

    private long activeGesture = NO_GESTURE;
    private long lastGesture = NO_GESTURE;
    private long requestedGesture = NO_GESTURE;
    private boolean requestInFlight;
    private boolean captureConfirmed;
    private long captureGesture = NO_GESTURE;
    private boolean releaseInFlight;
    private boolean releaseOnGrant;
    private String reason = "idle";

    /**
     * A native secondary-down transition.
     *
     * <p>A repeat of the current id is ignored: it must not reset counters,
     * clear a pending request or duplicate the eventual capture request. A
     * newer id supersedes an unreleased gesture (a missed up), releasing only
     * capture the wrapper owns.
     */
    Action secondaryDown(long gestureId) {
        if (gestureId <= NO_GESTURE || gestureId <= lastGesture) {
            reason = "stale or repeated down ignored";
            return Action.NONE;
        }
        Action closing = closeGesture();
        activeGesture = gestureId;
        lastGesture = gestureId;
        requestInFlight = false;
        if (closing == Action.NONE) {
            reason = "secondary down";
        }
        return closing;
    }

    /** A native secondary-up transition. Stale ids cannot release a later gesture. */
    Action secondaryUp(long gestureId) {
        if (gestureId == NO_GESTURE || gestureId != activeGesture) {
            reason = "stale up ignored";
            return Action.NONE;
        }
        Action action = closeGesture();
        if (action == Action.NONE) {
            reason = "secondary up";
        }
        return action;
    }

    /**
     * Frame-time decision using the current engine behavior.
     *
     * <p>Called only while {@link #needsReconciliation()} is true. Returning
     * {@link Action#REQUEST} sets the in-flight flag, so a second call on the
     * next frame does not duplicate the request.
     */
    Action reconcile(int engineBehavior) {
        if (engineBehavior == 0) {
            boolean pendingCancelled = requestInFlight;
            if (pendingCancelled) {
                // The engine dropped the intent before the request landed. A
                // grant that still arrives must be released, not accepted.
                requestInFlight = false;
                releaseOnGrant = true;
                reason = "engine intent cleared while request pending";
            }
            if (captureConfirmed && !releaseInFlight) {
                // Intent zero explicitly drops platform capture, even if the
                // engine acquired it; this is the menu/reset path. A release
                // already in flight is not repeated every frame.
                captureGesture = NO_GESTURE;
                releaseInFlight = true;
                reason = "engine intent cleared";
                return Action.RELEASE;
            }
            if (!pendingCancelled) {
                reason = "engine intent zero";
            }
            return Action.NONE;
        }
        if (engineBehavior != 2) {
            // 1 is engine shift lock: it owns capture, never request. -1 is
            // unavailable/disabled/unsupported.
            reason = engineBehavior == 1
                    ? "engine shift lock; no wrapper request"
                    : "enhancement unavailable; no request";
            return Action.NONE;
        }
        if (activeGesture == NO_GESTURE) {
            reason = "no secondary gesture";
            return Action.NONE;
        }
        if (captureConfirmed) {
            reason = "capture already confirmed";
            return Action.NONE;
        }
        if (requestInFlight) {
            reason = "request already in flight";
            return Action.NONE;
        }
        if (releaseInFlight) {
            reason = "release already in flight";
            return Action.NONE;
        }
        requestInFlight = true;
        requestedGesture = activeGesture;
        releaseOnGrant = false;
        releaseInFlight = false;
        reason = "engine behavior 2 while secondary down";
        return Action.REQUEST;
    }

    /**
     * Confirmed platform capture transition, and the only source of gain/loss.
     *
     * <p>A grant with no wrapper request is engine-owned (for example shift
     * lock) and is accepted without releasing it. A grant after cancellation,
     * a gesture end or an engine-intent reset is released immediately.
     */
    Action onCaptureChanged(boolean hasCapture) {
        if (!hasCapture) {
            captureConfirmed = false;
            captureGesture = NO_GESTURE;
            // A pending request is independent of the confirmed state: a
            // release callback from the previous capture can arrive after the
            // next request was sent. Keep it so its grant is still owned.
            releaseInFlight = false;
            if (!requestInFlight && !releaseOnGrant) {
                requestedGesture = NO_GESTURE;
            }
            reason = "capture lost";
            return Action.NONE;
        }
        if (captureConfirmed) {
            if (releaseOnGrant || releaseInFlight) {
                // A release was already issued for this confirmed capture and
                // the framework reported it still/again granted.
                releaseOnGrant = false;
                releaseInFlight = true;
                captureGesture = NO_GESTURE;
                reason = "capture re-granted while release pending";
                return Action.RELEASE;
            }
            reason = "capture already confirmed";
            return Action.NONE;
        }
        captureConfirmed = true;
        if (releaseOnGrant || releaseInFlight) {
            releaseOnGrant = false;
            releaseInFlight = true;
            captureGesture = NO_GESTURE;
            reason = "late grant after cancellation";
            return Action.RELEASE;
        }
        if (activeGesture != NO_GESTURE
                && requestInFlight
                && requestedGesture == activeGesture) {
            requestInFlight = false;
            captureGesture = activeGesture;
            reason = "capture confirmed for wrapper request";
            return Action.NONE;
        }
        // No wrapper request is pending: the engine requested this capture
        // (shift lock, or its own generic listener). Keep it.
        requestInFlight = false;
        captureGesture = NO_GESTURE;
        reason = "capture confirmed without wrapper request";
        return Action.NONE;
    }

    /**
     * F1: release wrapper right-drag capture and inhibit reacquisition for
     * this gesture.
     *
     * <p>Gesture/request tracking is cleared like every other cancel path; a
     * request still in flight is marked so its late grant is released. Capture
     * the engine already owned (shift lock) is not released and stays
     * confirmed. Because tracking is gone, a later native up for the cleared
     * gesture is stale and cannot start a new request.
     */
    boolean f1() {
        boolean confirmedWrapper = activeGesture != NO_GESTURE
                && captureConfirmed && captureGesture == activeGesture;
        boolean wrapperOwned = activeGesture != NO_GESTURE
                && ((requestInFlight && requestedGesture == activeGesture)
                || confirmedWrapper);
        boolean pending = requestInFlight || releaseOnGrant;
        activeGesture = NO_GESTURE;
        requestInFlight = false;
        releaseInFlight = confirmedWrapper;
        captureGesture = NO_GESTURE;
        releaseOnGrant = pending;
        if (!pending) {
            requestedGesture = NO_GESTURE;
        }
        reason = wrapperOwned
                ? "F1 released wrapper capture"
                : "F1 left engine-owned capture alone";
        return wrapperOwned;
    }

    /**
     * Lifecycle cancel. Clears gesture and request tracking even when the
     * platform reports no capture; a late grant still releases.
     */
    void cancel() {
        boolean pending = requestInFlight || releaseOnGrant;
        boolean wrapperConfirmed = captureConfirmed && captureGesture != NO_GESTURE;
        activeGesture = NO_GESTURE;
        requestInFlight = false;
        releaseInFlight = wrapperConfirmed;
        captureGesture = NO_GESTURE;
        releaseOnGrant = pending;
        if (!pending) {
            requestedGesture = NO_GESTURE;
        }
        reason = "cancelled";
    }

    /** Whether frame reconciliation still has work: a live gesture or request. */
    boolean needsReconciliation() {
        return requestInFlight || activeGesture != NO_GESTURE;
    }

    long activeGestureId() {
        return activeGesture;
    }

    boolean captureConfirmed() {
        return captureConfirmed;
    }

    boolean requestInFlight() {
        return requestInFlight;
    }

    String reason() {
        return reason;
    }

    /** Close the tracked gesture, releasing only capture the wrapper requested. */
    private Action closeGesture() {
        if (activeGesture == NO_GESTURE) {
            return Action.NONE;
        }
        long ending = activeGesture;
        activeGesture = NO_GESTURE;
        if (requestInFlight) {
            requestInFlight = false;
            releaseOnGrant = true;
        }
        if (captureConfirmed && captureGesture == ending) {
            captureGesture = NO_GESTURE;
            releaseInFlight = true;
            reason = "gesture ended with wrapper capture";
            return Action.RELEASE;
        }
        reason = "gesture ended";
        return Action.NONE;
    }
}
