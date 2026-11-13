package app.boxblox;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/** Delivers explicit overrides through the guest's pre-engine-startup API. */
final class GuestFlagOverrides {
    private GuestFlagOverrides() {
    }

    static void preload(ClassLoader loader, Map<String, Object> overrides) throws Exception {
        Class<?> activity = Class.forName(RobloxStore.MAIN_GAME_ACTIVITY, false, loader);
        deliver(activity, payload(overrides));
        // This API returns void: delivery is observable, effective values are not.
        Diag.set("flags: supplied " + overrides.size() + " overrides to nativePreloadFlagOverrides");
    }

    static String payload(Map<String, Object> overrides) {
        // The APK's own caller passes a flat JSON object, not applicationSettings.
        // Preserve the same boolean/integer types as the inspectable local file.
        return new JSONObject(overrides).toString();
    }

    static void deliver(Class<?> activity, String payload) throws Exception {
        Method method = activity.getDeclaredMethod("nativePreloadFlagOverrides", String.class);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) {
            throw new NoSuchMethodException("Unsupported nativePreloadFlagOverrides signature");
        }
        method.setAccessible(true);
        method.invoke(null, payload);
    }
}
