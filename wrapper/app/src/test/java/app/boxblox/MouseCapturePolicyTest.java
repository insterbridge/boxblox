package app.boxblox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Capture decision boundaries from local://mouse-lock-fix-contract.md.
 *
 * <p>Only observable policy transitions are asserted: request/release actions
 * and the state that gates the next action. Nothing here asserts log text,
 * Android wiring or native calls.
 */
public class MouseCapturePolicyTest {

    private static final long GESTURE_ONE = 1L;
    private static final long GESTURE_TWO = 2L;

    private static MouseCapturePolicy pressed(long gestureId) {
        MouseCapturePolicy policy = new MouseCapturePolicy();
        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryDown(gestureId));
        return policy;
    }

    private static void requested(MouseCapturePolicy policy, long gestureId) {
        assertEquals(MouseCapturePolicy.Action.REQUEST, policy.reconcile(2));
        assertTrue(policy.requestInFlight());
        assertEquals(gestureId, policy.activeGestureId());
    }

    private static void granted(MouseCapturePolicy policy) {
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(true));
        assertTrue(policy.captureConfirmed());
    }

    @Test
    public void secondaryPressWithEngineTwoRequestsExactlyOnce() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);

        assertEquals(MouseCapturePolicy.Action.REQUEST, policy.reconcile(2));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(2));

        assertTrue(policy.requestInFlight());
        assertFalse(policy.captureConfirmed());
    }

    @Test
    public void repeatedPressDoesNotDuplicateTheRequest() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);

        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryDown(GESTURE_ONE));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(2));

        assertTrue(policy.requestInFlight());
    }

    @Test
    public void confirmedCaptureSuppressesFurtherRequests() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);
        granted(policy);

        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(2));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(2));

        assertTrue(policy.captureConfirmed());
        assertFalse(policy.requestInFlight());
    }

    @Test
    public void engineZeroWhileThePressIsHeldReleases() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);
        granted(policy);

        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.reconcile(0));
        // A release already in flight is not re-issued on the next frame.
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(0));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(false));
        assertFalse(policy.captureConfirmed());
    }

    @Test
    public void menuResetThenRelatchRequestsOnlyOnceAgain() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);
        granted(policy);

        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.reconcile(0));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(false));

        assertEquals(MouseCapturePolicy.Action.REQUEST, policy.reconcile(2));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(2));
        assertTrue(policy.requestInFlight());
    }

    @Test
    public void engineShiftLockBehaviorOneNeverRequests() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);

        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(1));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(1));

        assertFalse(policy.requestInFlight());
        assertFalse(policy.captureConfirmed());
    }

    @Test
    public void engineShiftLockDoesNotReleaseEngineOwnedCapture() {
        MouseCapturePolicy policy = new MouseCapturePolicy();
        // No wrapper request was made: capture belongs to the engine.
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(true));

        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryDown(GESTURE_ONE));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(1));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryUp(GESTURE_ONE));

        assertTrue(policy.captureConfirmed());
    }

    @Test
    public void engineZeroDropsConfirmedCaptureEvenWithoutWrapperRequest() {
        MouseCapturePolicy policy = new MouseCapturePolicy();
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(true));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryDown(GESTURE_ONE));

        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.reconcile(0));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(false));
    }

    @Test
    public void lateGrantAfterGestureEndIsReleased() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);

        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryUp(GESTURE_ONE));
        assertFalse(policy.requestInFlight());

        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.onCaptureChanged(true));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(false));
    }

    @Test
    public void engineZeroBeforeGrantCancelsThePendingRequest() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);

        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(0));
        assertFalse(policy.requestInFlight());

        // The request was already in flight; its late success must not stick.
        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.onCaptureChanged(true));
    }

    @Test
    public void cancelBeforeGrantClearsTrackingAndReleasesTheLateGrant() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);

        policy.cancel();
        assertFalse(policy.requestInFlight());
        assertEquals(MouseCapturePolicy.NO_GESTURE, policy.activeGestureId());

        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(2));
        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.onCaptureChanged(true));
    }

    @Test
    public void cancelledConfirmedCaptureIsReleasedAgainIfStillGranted() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);
        granted(policy);

        policy.cancel();
        assertTrue(policy.captureConfirmed());
        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.onCaptureChanged(true));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(false));
    }

    @Test
    public void repeatedGesturesUseDistinctIdsAndStaleIdsCannotAct() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);
        granted(policy);
        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.secondaryUp(GESTURE_ONE));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(false));

        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryDown(GESTURE_TWO));
        requested(policy, GESTURE_TWO);
        granted(policy);

        // A stale up/down from the previous gesture must not touch gesture 2.
        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryUp(GESTURE_ONE));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryDown(GESTURE_ONE));
        assertEquals(GESTURE_TWO, policy.activeGestureId());
        assertTrue(policy.captureConfirmed());

        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.secondaryUp(GESTURE_TWO));
    }

    @Test
    public void unavailableBehaviorNeverRequests() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);

        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(-1));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(-1));

        assertFalse(policy.requestInFlight());
        assertFalse(policy.captureConfirmed());
    }

    @Test
    public void noSecondaryPressMeansNoRequest() {
        MouseCapturePolicy policy = new MouseCapturePolicy();

        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(2));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryUp(GESTURE_ONE));
        assertFalse(policy.requestInFlight());
    }

    @Test
    public void requestStopsAfterThePressEnds() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);

        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryUp(GESTURE_ONE));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(2));

        assertFalse(policy.requestInFlight());
        assertFalse(policy.needsReconciliation());
    }

    @Test
    public void f1SuppressesReacquisitionOnlyForTheCurrentGesture() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);

        assertTrue(policy.f1());
        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.onCaptureChanged(true));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.onCaptureChanged(false));
        assertEquals(MouseCapturePolicy.Action.NONE, policy.reconcile(2));

        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryDown(GESTURE_TWO));
        assertEquals(MouseCapturePolicy.Action.REQUEST, policy.reconcile(2));
    }

    @Test
    public void releaseBeforeGrantDoesNotReportAnAndroidRelease() {
        MouseCapturePolicy policy = pressed(GESTURE_ONE);
        requested(policy, GESTURE_ONE);

        // Ending the press cancels tracking but there is nothing to release yet.
        assertEquals(MouseCapturePolicy.Action.NONE, policy.secondaryUp(GESTURE_ONE));
        assertFalse(policy.needsReconciliation());

        // The later grant is the thing that has to be released.
        assertEquals(MouseCapturePolicy.Action.RELEASE, policy.onCaptureChanged(true));
    }
}
