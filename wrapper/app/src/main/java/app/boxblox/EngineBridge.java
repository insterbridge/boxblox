package app.boxblox;

/** Native engine loading and the mouse-lock control surface. */
final class EngineBridge {

    /**
     * A destination for mouse-lock diagnostics and native transitions.
     *
     * <p>{@link #log(String)} may be called on any engine thread; the
     * controller's implementation writes it straight to the Diag file.
     * {@link #onMouseButton(boolean, long)} and {@link #onEnabled(boolean)}
     * update UI/capture state, so the implementation marshals them to the main
     * thread and never blocks the engine thread. Native notifications are made
     * outside the native state/global-reference locks.
     */
    interface MouseLockTrace {
        void log(String line);

        /**
         * A native secondary-button transition.
         *
         * @param down      {@code true} for the secondary press
         * @param gestureId the native gesture this transition belongs to
         */
        void onMouseButton(boolean down, long gestureId);

        /** Enhancement state change; {@code false} closes any active tracking. */
        void onEnabled(boolean enabled);
    }

    static {
        System.loadLibrary("wrapper");
    }

    private EngineBridge() {
    }

    /**
     * Preserve the original static JNI getter, pass-through.
     *
     * <p>While the enhancement is enabled and supported, the getter returns
     * {@code false} inside a Java mouse-button dispatch scope, {@code true}
     * while platform capture is confirmed, and otherwise the original center
     * lock or a live secondary-down plus engine behavior {@code 2}. Disabled,
     * unsupported or failed installation leaves the original unchanged.
     */
    static native boolean installMouseLockHook(Class<?> inputInterface);

    /** Enable native capture accounting; enabling does not itself request capture. */
    static native void setMouseLockEnabled(boolean enabled);

    /** Make the engine's JNI_OnLoad inert, so AGDK's by-name load is a no-op. */
    static native boolean disableJniOnLoad();

    /** Record the engine's handle after System.load, so later JNI can find it. */
    static native boolean noteEngineLoaded();

    /** Outcome of resolving the engine's mouse state, for diagnostics. */
    static native String mouseStateResult();

    /**
     * Current engine MouseBehavior: {@code 0} default, {@code 1} LockCenter,
     * {@code 2} LockCurrentPosition.
     *
     * <p>Read-only and current: it dereferences the live engine state with the
     * same synchronization/lifetime discipline as the engine getter, never a
     * cached field. Returns {@code -1} when the enhancement is disabled,
     * unsupported or unavailable.
     */
    static native int mouseBehavior();

    /**
     * Open a thread-local nesting scope around uncaptured mouse
     * BUTTON_PRESS/BUTTON_RELEASE delegation.
     *
     * <p>Inside the scope the hooked engine getter returns {@code false}, so
     * the original guest handler processes the button instead of the generic
     * listener requesting capture and swallowing it. Balanced by
     * {@link #endMouseButtonDispatch()} in a {@code finally}; a no-op when the
     * enhancement is disabled or unsupported.
     */
    static native void beginMouseButtonDispatch();

    /** Close the dispatch scope opened by {@link #beginMouseButtonDispatch()}. */
    static native void endMouseButtonDispatch();

    /**
     * Report the confirmed Android window-capture callback state.
     *
     * <p>This is the state the framework reported through
     * {@code onPointerCaptureChanged}, not a request that was merely sent. The
     * engine getter uses it to keep forwarding final button events to the
     * guest's captured listener; the controller drops capture explicitly when
     * engine intent clears.
     */
    static native void setPointerCaptureState(boolean captured);

    /**
     * Release whatever the wrapper owns on the engine.
     *
     * <p>The single relinquish entry for every lifecycle path: F1, focus loss,
     * pause and the feature toggle (the toggle releases on its own). Returns
     * whether a lock was actually released, so callers can decide whether to
     * consume the key. It is not by itself an Android pointer-capture release.
     */
    static native boolean releaseMouseLock(String reason);

    /** Install the mouse-lock diagnostic sink, or clear it with {@code null}. */
    static native void setMouseLockTrace(MouseLockTrace trace);
}
