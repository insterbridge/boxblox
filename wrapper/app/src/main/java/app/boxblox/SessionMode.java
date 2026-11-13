package app.boxblox;

/**
 * The one question the wrapper's identity answers, as a single choice.
 *
 * <p>Replaces the former {@code wrapper.desktop_mode} boolean. Its three
 * values cover what the wrapper can actually make differ, and every call site
 * asks here rather than re-deriving from a feature toggle:
 *
 * <ul>
 *   <li><b>desktop</b> — PC layout, touchscreen reported absent (the measured
 *       pre-session-mode behavior, and the default)</li>
 *   <li><b>touch</b> — PC layout, touchscreen reported present: Sober's
 *       {@code fake-off}, the desktop UI with the screen still usable</li>
 *   <li><b>mobile</b> — the stock phone app: no feature spoof, no UA rewrite</li>
 * </ul>
 *
 * <p>A stored {@code wrapper.desktop_mode} migrates once: true becomes
 * {@code desktop}, false becomes {@code mobile}.
 */
final class SessionMode {

    static final String DESKTOP = "desktop";
    static final String TOUCH = "touch";
    static final String MOBILE = "mobile";

    private SessionMode() {
    }

    /** The stored session mode, migrating the retired boolean if present. */
    static String read(FlagStore flags) {
        String stored = flags.getChoice("wrapper.session_mode");
        if (stored != null) {
            return stored;
        }
        return flags.getBool("wrapper.desktop_mode", false) ? DESKTOP : MOBILE;
    }

    static void write(FlagStore flags, String mode) {
        flags.setChoice("wrapper.session_mode", mode);
    }

    /** True when the session claims a PC: feature spoof and UA rewrite apply. */
    static boolean desktopIdentity(String mode) {
        return !MOBILE.equals(mode);
    }

    /** True when the touchscreen feature is reported present. */
    static boolean reportTouchscreen(String mode) {
        return !DESKTOP.equals(mode);
    }
}
