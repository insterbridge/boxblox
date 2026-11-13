// The wrapper's own JNI surface, bound once at startup.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "RobloxWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    LOGI("wrapper natives loaded");
    return JNI_VERSION_1_6;
}

/** The Roblox engine is not a dependency of this library; it is dlopen'd. */
JNIEXPORT jstring JNICALL
Java_app_boxblox_EngineBridge_abi(JNIEnv* env, jclass) {
    return env->NewStringUTF("arm64-v8a");
}

}  // extern "C"
