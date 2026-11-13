package app.boxblox;

import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Map;
import static org.junit.Assert.*;

public final class FlagStoreTest {
    private FlagStore store(Map<String, Object> values) {
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("contains")) return values.containsKey(args[0]);
                    if (method.getName().equals("getString") || method.getName().equals("getBoolean")) {
                        return values.getOrDefault(args[0], args[1]);
                    }
                    throw new AssertionError("Unexpected preference call: " + method);
                });
        return new FlagStore(prefs);
    }

    @Test public void numericSuggestionIsStillAnExplicitOverride() {
        assertEquals(21L, store(Map.of("DFIntDebugFRMQualityLevelOverride", "21"))
                .engineOverrides().get("DFIntDebugFRMQualityLevelOverride"));
    }

    @Test public void explicitFalseIsRetainedInThePayload() {
        assertEquals(Boolean.FALSE, store(Map.of("FFlagDebugSkyGray", false))
                .engineOverrides().get("FFlagDebugSkyGray"));
    }

    @Test public void unsetAndWrapperValuesDoNotReachTheEngine() {
        assertTrue(store(Map.of()).engineOverrides().isEmpty());
        assertTrue(store(Map.of("wrapper.mouse_lock", true, "wrapper.console_mode", true))
                .engineOverrides().isEmpty());
    }

    @Test public void valuesHaveJsonCompatibleTypes() {
        Map<String, Object> values = store(Map.of("FFlagDebugSkyGray", true,
                "DFIntCSGLevelOfDetailSwitchingDistance", "1000")).engineOverrides();
        assertEquals(Map.of("FFlagDebugSkyGray", true,
                "DFIntCSGLevelOfDetailSwitchingDistance", 1000L), values);
    }

    /**
     * The outline follows the value for switches, presence for numbers.
     *
     * <p>Regression, both directions: a switch toggled back to its default must
     * stop being outlined, while a number re-entered at the catalogue's
     * suggestion must stay outlined because it is still written to the engine.
     */
    @Test public void switchOutlineTracksTheValue() {
        FlagStore off = store(Map.of("FFlagDebugSkyGray", false));
        assertFalse("a switch left at its default must not be outlined",
                off.isOverridden(FlagStore.spec("FFlagDebugSkyGray")));
        assertTrue("but it must still be sent to the engine",
                off.isExplicitlySet(FlagStore.spec("FFlagDebugSkyGray")));

        FlagStore on = store(Map.of("FFlagDebugSkyGray", true));
        assertTrue(on.isOverridden(FlagStore.spec("FFlagDebugSkyGray")));
    }

    @Test public void numberOutlineTracksPresence() {
        FlagStore atDefault = store(Map.of("DFIntDebugFRMQualityLevelOverride", "21"));
        assertTrue("a number set to the suggestion is still an explicit override",
                atDefault.isOverridden(FlagStore.spec("DFIntDebugFRMQualityLevelOverride")));
    }

    @Test public void unsetFlagIsNeitherOutlinedNorSent() {
        FlagStore empty = store(Map.of());
        FlagStore.Spec spec = FlagStore.spec("FFlagDebugSkyGray");
        assertFalse(empty.isOverridden(spec));
        assertFalse(empty.isExplicitlySet(spec));
    }

    /**
     * An UNSET number must not reach the engine at all.
     *
     * <p>Quality level has no level 0, so its default is the UNSET sentinel.
     * Writing that sentinel as a value would either put a bogus string in the
     * payload or force a level the engine does not accept; the flag has to be
     * omitted so the engine keeps its own.
     */
    @Test public void unsetNumberIsOmittedFromThePayload() {
        FlagStore unset = store(Map.of());
        assertFalse("an unset number must not be written",
                unset.engineOverrides().containsKey("DFIntDebugFRMQualityLevelOverride"));

        FlagStore set = store(Map.of("DFIntDebugFRMQualityLevelOverride", "7"));
        assertEquals(7L, set.engineOverrides().get("DFIntDebugFRMQualityLevelOverride"));
    }
}
