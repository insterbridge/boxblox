package app.boxblox;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.junit.Assert.*;

public final class GuestFlagOverridesTest {
    public static final class ActivityApi {
        static String received;

        private static void nativePreloadFlagOverrides(String payload) {
            received = payload;
        }
    }

    public static final class InstanceApi {
        private void nativePreloadFlagOverrides(String payload) { }
    }

    public static final class WrongReturnApi {
        private static int nativePreloadFlagOverrides(String payload) { return 0; }
    }

    public static final class FailingApi {
        private static void nativePreloadFlagOverrides(String payload) {
            throw new UnsatisfiedLinkError("test: engine not loaded");
        }
    }

    @Test public void deliversFlatPayloadWithBooleanAndIntegerValues() throws Exception {
        String payload = GuestFlagOverrides.payload(Map.of(
                "FFlagDebugSkyGray", true, "DFIntDebugFRMQualityLevelOverride", 21L));
        GuestFlagOverrides.deliver(ActivityApi.class, payload);
        JSONObject received = new JSONObject(ActivityApi.received);
        assertEquals(2, received.length());
        assertEquals(Boolean.TRUE, received.get("FFlagDebugSkyGray"));
        assertEquals(21, received.getInt("DFIntDebugFRMQualityLevelOverride"));
        assertFalse(received.has("applicationSettings"));
    }

    @Test public void sendsEmptyPayloadAfterOverridesAreRemoved() throws Exception {
        GuestFlagOverrides.deliver(ActivityApi.class,
                GuestFlagOverrides.payload(Map.of("FFlagDebugSkyGray", true)));
        GuestFlagOverrides.deliver(ActivityApi.class, GuestFlagOverrides.payload(Map.of()));
        assertEquals("{}", ActivityApi.received);
    }

    @Test public void explicitFalseIsNotDropped() throws Exception {
        JSONObject payload = new JSONObject(GuestFlagOverrides.payload(Map.of("FFlagDebugSkyGray", false)));
        assertTrue(payload.has("FFlagDebugSkyGray"));
        assertEquals(Boolean.FALSE, payload.get("FFlagDebugSkyGray"));
    }

    @Test public void missingApiIsReportedInsteadOfClaimingDelivery() {
        assertThrows(NoSuchMethodException.class, () -> GuestFlagOverrides.deliver(Object.class, "{}"));
    }

    @Test public void signatureDriftIsRejected() {
        assertThrows(NoSuchMethodException.class, () -> GuestFlagOverrides.deliver(InstanceApi.class, "{}"));
        assertThrows(NoSuchMethodException.class, () -> GuestFlagOverrides.deliver(WrongReturnApi.class, "{}"));
    }

    @Test public void nativeFailureRemainsVisibleToTheCaller() {
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> GuestFlagOverrides.deliver(FailingApi.class, "{}"));
        assertTrue(error.getCause() instanceof UnsatisfiedLinkError);
    }
}
