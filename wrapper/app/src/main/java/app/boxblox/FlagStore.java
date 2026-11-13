package app.boxblox;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores the user's overrides and writes the per-build local settings file.
 *
 * <p>The engine's LoadClientSettingsFromLocal log establishes that it reads this
 * file, not that every value becomes effective. The APK's wj.a.a() is a separate
 * path: it reads /data/local/tmp/ClientAppSettings.json, not our per-build file.
 * The production APK also skips its explicit nativePreloadFlagOverrides call.
 * {@link GuestFlagOverrides} supplies that handoff before guest activity startup.
 *
 * <p>The local file remains inspectable and per-build; native delivery and the
 * resulting visual behavior must be verified separately.
 */
final class FlagStore {

    /** A flag the UI can offer, with the type the engine expects. */
    static final class Spec {
        final String key;
        final String label;
        final String description;
        final Kind kind;
        /** Only used by {@link Kind#CHOICE}: the offered values, in order. */
        final java.util.List<String> choices;
        final String group;
        /** Only used by {@link Kind#NUMBER}. */
        final String defaultValue;
        /** Inclusive bounds for {@link Kind#NUMBER}; ignored otherwise. */
        final int min;
        final int max;
        /**
         * True when the flag only affects the desktop client.
         *
         * <p>It is still on the allowlist, so it is offered, but the engine on
         * Android has no Windows renderer to prefer and no DWM to bypass, so
         * these do nothing here. Marked rather than hidden: a user who knows the
         * flag exists should be able to find it.
         */
        final boolean desktopOnly;

        Spec(String key, String label, String description, Kind kind,
             String group, String defaultValue) {
            this(key, label, description, kind, group, defaultValue,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, false);
        }

        Spec(String key, String label, String description, Kind kind,
             String group, String defaultValue, int min, int max) {
            this(key, label, description, kind, group, defaultValue, min, max, false);
        }

        Spec(String key, String label, String description, Kind kind,
             String group, String defaultValue, boolean desktopOnly) {
            this(key, label, description, kind, group, defaultValue,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, desktopOnly);
        }

        Spec(String key, String label, String description, Kind kind, String group,
             String defaultValue, int min, int max, boolean desktopOnly) {
            this.key = key;
            this.label = label;
            this.description = description;
            this.kind = kind;
            this.group = group;
            this.defaultValue = defaultValue;
            this.choices = java.util.List.of();
            this.min = min;
            this.max = max;
            this.desktopOnly = desktopOnly;
        }

        /** A {@link Kind#CHOICE} spec: one of {@code choices} is stored. */
        Spec(String key, String label, String description, String group,
             String defaultValue, java.util.List<String> choices) {
            this.key = key;
            this.label = label;
            this.description = description;
            this.kind = Kind.CHOICE;
            this.group = group;
            this.defaultValue = defaultValue;
            this.choices = java.util.List.copyOf(choices);
            this.min = Integer.MIN_VALUE;
            this.max = Integer.MAX_VALUE;
            this.desktopOnly = false;
        }
    }

    enum Kind { BOOL, NUMBER, CHOICE }

    /**
     * The catalogue.
     *
     * <p>These are flags whose effect is documented by their own name and whose
     * values the engine accepts as-is. Deliberately small: every entry here is
     * something the UI can set without the user having to know the flag
     * language, and anything not listed is still reachable through the raw JSON
     * editor.
     */
    /**
     * A number whose default is "not set".
     *
     * <p>Used where the flag has no meaningful stock value to display: the card
     * shows a dash, the key is absent from the written payload, and the engine
     * keeps its own default. Distinct from any real number so it can never be
     * mistaken for a level the engine accepts.
     */
    static final String UNSET = "";

    static final List<Spec> CATALOGUE;

    static {
        List<Spec> specs = new ArrayList<>();

        // Wrapper controls. Not FFlags: these are answered by the wrapper's own
        // package manager and native hook, and are filtered out before anything
        // is written to the guest's file.
        specs.add(new Spec("wrapper.mouse_lock", "Mouse lock",
                "nativeGetMainWindowIsMouseLockedCenter", Kind.BOOL, "Wrapper", null));
        specs.add(new Spec("wrapper.session_mode", "Session mode",
                "Desktop keeps the PC layout and reports no touchscreen; Touch"
                        + " keeps the PC layout with the screen usable; Mobile is"
                        + " the stock app.",
                "Wrapper", "mobile", java.util.List.of("desktop", "touch", "mobile")));
        specs.add(new Spec("wrapper.server_location", "Server location",
                "DatacenterId of the joined server", Kind.BOOL, "Wrapper", null));
        specs.add(new Spec("wrapper.asset_overlay", "Asset overlay",
                "files/overlay, published to the files app", Kind.BOOL, "Wrapper", null));

        // Everything below is Roblox's Fast Flag Allowlist, in full and nothing
        // else. Since 29 September 2025 the client ignores any flag that is not
        // on this list, silently, so offering anything else would be a switch
        // that does nothing.
        // https://devforum.roblox.com/t/allowlist-for-local-client-configuration-via-fast-flags/3966569

        // Geometry
        //
        // The first two defaults are measured: the Android settings payload
        // ships 250 and 500, not the 10000 this catalogue used to claim. A wrong
        // default here is not cosmetic — it shows a stock value 40x the real one,
        // so touching the field silently changes CSG detail by that factor.
        //   https://clientsettingscdn.roblox.com/v2/settings/application/GoogleAndroidApp
        //
        // L23 and L34 are allowlisted and real, but no platform payload ships a
        // value for them: Android publishes only the two above, and the Windows
        // and Mac payloads publish none of the four. Their defaults are therefore
        // the ascending ladder 750 and 1000, which continues 250/500 rather than
        // claiming a measured stock value that does not exist.
        specs.add(new Spec("DFIntCSGLevelOfDetailSwitchingDistance", "CSG detail distance",
                "DFIntCSGLevelOfDetailSwitchingDistance", Kind.NUMBER, "Geometry", "250", 0, 10000));
        specs.add(new Spec("DFIntCSGLevelOfDetailSwitchingDistanceL12", "CSG detail (QL 1–2)",
                "DFIntCSGLevelOfDetailSwitchingDistanceL12", Kind.NUMBER, "Geometry", "500", 0, 10000));
        specs.add(new Spec("DFIntCSGLevelOfDetailSwitchingDistanceL23", "CSG detail (QL 2–3)",
                "DFIntCSGLevelOfDetailSwitchingDistanceL23", Kind.NUMBER, "Geometry", "750", 0, 10000));
        specs.add(new Spec("DFIntCSGLevelOfDetailSwitchingDistanceL34", "CSG detail (QL 3–4)",
                "DFIntCSGLevelOfDetailSwitchingDistanceL34", Kind.NUMBER, "Geometry", "1000", 0, 10000));

        // Rendering
        //
        // Quality levels run 1..21; there is no level 0. The default is
        // therefore UNSET, not a number: the card shows a dash, nothing is
        // written to the engine, and the engine picks its own level exactly as
        // the stock app does. Setting a value forces that level, which is what
        // this flag means.
        specs.add(new Spec("DFIntDebugFRMQualityLevelOverride", "Quality level",
                "DFIntDebugFRMQualityLevelOverride", Kind.NUMBER, "Rendering", UNSET, 1, 21));
        specs.add(new Spec("DFFlagTextureQualityOverrideEnabled", "Texture override",
                "DFFlagTextureQualityOverrideEnabled", Kind.BOOL, "Rendering", null));
        // The four below have no published stock value: none of them appears in
        // the Android, iOS, Windows or Mac settings payloads, so the engine's
        // defaults are compiled in and there is nothing to copy. Showing a
        // number would be inventing one — the old "0" and "1" were guesses, and
        // a 0 is indistinguishable from a real setting the user chose. They are
        // UNSET instead: dashed out, absent from the payload, engine decides.
        specs.add(new Spec("DFIntTextureQualityOverride", "Texture quality",
                "DFIntTextureQualityOverride", Kind.NUMBER, "Rendering", UNSET, 0, 3));
        specs.add(new Spec("FIntDebugForceMSAASamples", "MSAA samples",
                "FIntDebugForceMSAASamples", Kind.NUMBER, "Rendering", UNSET, 1, 8));
        specs.add(new Spec("FIntFRMMaxGrassDistance", "Grass distance (max)",
                "FIntFRMMaxGrassDistance", Kind.NUMBER, "Rendering", UNSET, 0, 10000));
        specs.add(new Spec("FIntFRMMinGrassDistance", "Grass distance (min)",
                "FIntFRMMinGrassDistance", Kind.NUMBER, "Rendering", UNSET, 0, 10000));
        specs.add(new Spec("DFFlagDebugPauseVoxelizer", "Pause voxelizer",
                "DFFlagDebugPauseVoxelizer", Kind.BOOL, "Rendering", null));
        specs.add(new Spec("FFlagDebugSkyGray", "Gray sky",
                "FFlagDebugSkyGray", Kind.BOOL, "Rendering", null));

        // Rendering backends: desktop only. The Android client already selects
        // GLES or Vulkan itself and these name Windows backends.
        specs.add(new Spec("FFlagDebugGraphicsPreferVulkan", "Prefer Vulkan",
                "FFlagDebugGraphicsPreferVulkan", Kind.BOOL, "Rendering", null, true));
        specs.add(new Spec("FFlagDebugGraphicsPreferD3D11", "Prefer Direct3D 11",
                "FFlagDebugGraphicsPreferD3D11", Kind.BOOL, "Rendering", null, true));
        specs.add(new Spec("FFlagDebugGraphicsPreferOpenGL", "Prefer OpenGL",
                "FFlagDebugGraphicsPreferOpenGL", Kind.BOOL, "Rendering", null, true));

        // UI
        // Unpublished, like the rendering numbers above.
        specs.add(new Spec("FIntGrassMovementReducedMotionFactor", "Grass motion",
                "FIntGrassMovementReducedMotionFactor", Kind.NUMBER, "User interface", UNSET, 0, 100));
        // Desktop only: Windows DWM fullscreen handling. Unlike its neighbours
        // this one does have a published value: the Android and iOS payloads
        // both ship true.
        specs.add(new Spec("FFlagHandleAltEnterFullscreenManually", "Alt+Enter fullscreen",
                "FFlagHandleAltEnterFullscreenManually", Kind.BOOL, "User interface", null, true));
        // Desktop only: Windows DPI scaling.
        specs.add(new Spec("DFFlagDisableDPIScale", "Disable DPI scaling",
                "DFFlagDisableDPIScale", Kind.BOOL, "User interface", null, true));

        CATALOGUE = Collections.unmodifiableList(specs);
    }

    /** The catalogue without the wrapper-only entries. */
    static List<Spec> engineSpecs() {
        List<Spec> out = new ArrayList<>();
        for (Spec spec : CATALOGUE) {
            if (!spec.key.startsWith("wrapper.")) {
                out.add(spec);
            }
        }
        return out;
    }

    static Spec spec(String key) {
        for (Spec spec : CATALOGUE) {
            if (spec.key.equals(key)) {
                return spec;
            }
        }
        return null;
    }

    private static final String PREFS = "boxblox.flags";

    private final SharedPreferences prefs;

    FlagStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    FlagStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * Whether the card should be outlined: the setting differs from its default.
     *
     * <p>The test depends on the kind, because the two kinds mean different
     * things by "changed":
     *
     * <ul>
     *   <li><b>Boolean</b> — the value differs from the default. Presence alone
     *       is not enough: toggling a switch on and back off leaves a stored
     *       value identical to the default, and treating that as changed left
     *       every switch the user had ever touched outlined permanently
     *       (reported).</li>
     *   <li><b>Number and choice</b> — any explicit value counts, including one
     *       equal to the catalogue's suggestion. These are typed in, not
     *       flipped: a number set to the default is still a deliberate override
     *       that will be written to the engine, so it stays outlined
     *       (reported the other way round: comparing values here made a
     *       re-entered default silently stop showing as set).</li>
     * </ul>
     */
    boolean isOverridden(Spec spec) {
        if (!prefs.contains(spec.key)) {
            return false;
        }
        if (spec.kind == Kind.BOOL) {
            return prefs.getBoolean(spec.key, defaultBool(spec)) != defaultBool(spec);
        }
        return true;
    }

    /**
     * Whether the key was ever set explicitly, whatever the value.
     *
     * <p>This is what the engine payload uses: an explicit {@code false} still
     * has to be sent, because the CDN's own default for a flag may be
     * {@code true}, and dropping it would let the CDN win.
     */
    boolean isExplicitlySet(Spec spec) {
        return prefs.contains(spec.key);
    }

    boolean getBool(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    void setBool(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    /** A stored choice value, or null when the flag was never set. */
    String getChoice(String key) {
        return prefs.getString(key, null);
    }

    void setChoice(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    /**
     * The default for a boolean flag: off. Every wrapper switch changes what the
     * session claims to be or adds behaviour the stock app does not have, so
     * the wrapper starts as close to the stock app as it can.
     */
    static boolean defaultBool(Spec spec) {
        return false;
    }


    String getNumber(String key, String fallback) {
        return prefs.getString(key, fallback);
    }

    void setNumber(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }


    void clear(String key) {
        prefs.edit().remove(key).apply();
    }

    void clearAll() {
        prefs.edit().clear().apply();
    }

    /**
     * The overrides that will actually reach the guest, as flag key to value.
     *
     * <p>Wrapper-only entries are excluded: they are answered by the wrapper's
     * own package manager and native hook, not by the engine's flag system, and
     * writing them into the guest's file would hand the engine keys it does not
     * know.
     */
    Map<String, Object> engineOverrides() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Spec spec : engineSpecs()) {
            // The write test, not the outline test: an explicit false must still
            // be sent, or the CDN's default would win.
            if (!isExplicitlySet(spec)) {
                continue;
            }
            if (spec.kind == Kind.BOOL) {
                out.put(spec.key, prefs.getBoolean(spec.key, false));
            } else {
                String raw = prefs.getString(spec.key, spec.defaultValue);
                if (raw == null || raw.isEmpty()) {
                    // UNSET: nothing to force, so the key stays out of the
                    // payload and the engine keeps its own level.
                    continue;
                }
                try {
                    out.put(spec.key, Long.parseLong(raw));
                } catch (NumberFormatException e) {
                    // Keep invalid numeric preferences out of both payloads;
                    // engine flags of this kind must describe whole numbers.
                    Diag.set("skipping non-numeric " + spec.key + "=" + raw);
                }
            }
        }
        return out;
    }

    int overrideCount() {
        return engineOverrides().size();
    }

    /**
     * Write the payload to the active build's flag file.
     *
     * <p>An empty override set removes the file instead of writing {@code {}}:
     * the archive treats an absent file as "no overrides" and a present empty
     * one as an override payload it has to parse, and there is no reason to make
     * it do that work.
     */
    boolean writeFor(RobloxStore.Build build) {
        // The engine resolves the file through
        // getApplicationContext().getFilesDir(), and GuestContext.getFilesDir()
        // is <dataDir>/files, so the file has to live under files/exe/ClientSettings.
        // Writing to <dataDir>/exe/ClientSettings put it where nothing reads it.
        File dir = new File(build.getDataDir(), "files/exe/ClientSettings");
        File target = new File(dir, "ClientAppSettings.json");
        Map<String, Object> overrides = engineOverrides();
        try {
            if (overrides.isEmpty()) {
                java.nio.file.Files.deleteIfExists(target.toPath());
                java.nio.file.Files.deleteIfExists(new File(dir, "ClientAppSettings.json.bak").toPath());
                java.nio.file.Files.deleteIfExists(new File(dir, "ClientAppSettings.json.new").toPath());
                Diag.set("flags: no overrides");
                return true;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Diag.set("could not create " + dir);
                return false;
            }
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Object> entry : overrides.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            android.util.AtomicFile file = new android.util.AtomicFile(target);
            FileOutputStream out = null;
            try {
                out = file.startWrite();
                out.write(bytes);
                file.finishWrite(out);
            } catch (Exception e) {
                file.failWrite(out);
                throw e;
            }
            Diag.set("flags: wrote " + overrides.size() + " to " + target);
            return true;
        } catch (Throwable t) {
            Diag.set("flags: write failed: " + t);
            return false;
        }
    }

    /** Describe only the local file, not whether the engine applied its values. */
    String describeFile(RobloxStore.Build build) {
        // Same directory writeFor uses: the engine reads the file via
        // getApplicationContext().getFilesDir(), which GuestContext resolves to
        // <dataDir>/files.
        File dir = new File(build.getDataDir(), "files/exe/ClientSettings");
        File target = new File(dir, "ClientAppSettings.json");
        if (!target.isFile()) {
            return "none";
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(target.toPath());
            if (bytes.length == 0) return "empty";
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8)).length() + " keys";
        } catch (Throwable t) {
            return "unreadable";
        }
    }
}
