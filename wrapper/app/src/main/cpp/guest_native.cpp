// Loading the guest's engine from the guest's own class loader.
//
// Roblox's `libroblox.so` registers its natives in `JNI_OnLoad`, and it looks
// its Java classes up with `FindClass`. A JNI `FindClass` resolves through the
// loader attached to the *thread that is running*, falling back to the loader
// that loaded the calling native library. Loading the engine from a thread
// whose loader is the archive's is therefore the only way its `JNI_OnLoad` can
// find `com.roblox.engine.jni.*` - measured: loading it from the app's main
// thread aborts with
//
//     ClassNotFoundException: com.roblox.engine.jni.locale.NativeLocaleJavaInterface
//     on path: DexPathList[[zip file ".../app.boxblox.../base.apk"]]
//
// which is the wrapper's own archive, not Roblox's.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <optional>
#include <cerrno>
#include <unistd.h>
#include <sys/mman.h>
#include <cstring>

#include <atomic>
#include <cstdarg>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <time.h>

#include "mouse_lock_state.h"

#define LOG_TAG "RobloxWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// ------------------------------------------------------ engine mouse state
//
// MouseBehavior is one int32 inside the engine's WindowInputState. The engine's
// JNI getter reads it as `field == 1` (LockCenter) and returns that boolean.
//
// Values and offsets recovered from the device's own 2.745.1142 build,
// sha256 3d3dc6e8... They are per build: the fingerprints below are checked
// before any fixed-offset dereference, so a different build disables the
// enhancement instead of touching an unknown address.
//
// The read is value-only. No saved MouseBehavior value, no writable field
// pointer and no MouseBehavior write path exists anywhere in this file. (The
// unrelated JNI_OnLoad neutralization below still patches engine code, as
// before.)
constexpr std::uintptr_t kAccessorThunk = 0x1F52DE8;  // mov w0,#4 ; b accessor
constexpr std::uint32_t kAccessorWords[] = {0x52800080u, 0x14000001u};
constexpr std::uintptr_t kGetterThisMove = 0x555F998;  // mov x19,x0
constexpr std::uint32_t kGetterThisMoveInsn = 0xAA0003F3u;
constexpr std::uintptr_t kGetterEnterLockArg = 0x555F99C;  // add x0,x0,#0x50
constexpr std::uint32_t kGetterEnterLockArgInsn = 0x91014000u;
constexpr std::uintptr_t kGetterEnterLockCall = 0x555F9A0;  // bl lockEnterThunk
constexpr std::uint32_t kGetterEnterLockCallInsn = 0x972AF448u;
constexpr std::uintptr_t kGetterPointerLoad = 0x555F9A4;  // ldr x8,[x19,#0x10A8]
constexpr std::uint32_t kGetterPointerLoadInsn = 0xF9485668u;
constexpr std::uintptr_t kGetterExitLockArg = 0x555F9A8;  // add x0,x19,#0x50
constexpr std::uint32_t kGetterExitLockArgInsn = 0x91014260u;
constexpr std::uintptr_t kGetterFieldLoad = 0x555F9AC;  // ldr w8,[x8,#0x88]
constexpr std::uint32_t kGetterFieldLoadInsn = 0xB9408908u;
constexpr std::uintptr_t kGetterExitLockCall = 0x555F9B8;  // bl lockExitThunk
constexpr std::uint32_t kGetterExitLockCallInsn = 0x972C1514u;

// The getter brackets its two loads with calls to these two stripped helpers,
// both with x0 = guard+0x50 and w1 = 1. See the synchronization finding below.
constexpr std::uintptr_t kLockEnterThunk = 0x201CAC0;
constexpr std::uint32_t kLockEnterWords[] = {
        0xA9BF7BFDu, 0x910003FDu, 0x52800021u, 0x941BABA2u};
constexpr std::uintptr_t kLockExitThunk = 0x2064E08;
constexpr std::uint32_t kLockExitWords[] = {
        0xA9BF7BFDu, 0x910003FDu, 0x52800021u, 0x941A8B12u};

constexpr std::uintptr_t kGuardLockOffset = 0x50;
constexpr std::uintptr_t kWindowInputStateOffset = 0x10A8;
constexpr std::uintptr_t kMouseBehaviorOffset = 0x88;
constexpr std::size_t kMaxEngineSegments = 12;

// Synchronization finding, retained fixture .analysis/libroblox_2745.so
// (2.745.1142, sha256 3d3dc6e8...; offsets below are ELF virtual addresses).
//
// JNI getter 0x277c758 calls accessor thunk 0x1f52de8, then calls the engine
// getter 0x555f98c with the returned guard in x0. The getter does:
//
//     mov  x19, x0
//     add  x0, x0, #0x50
//     bl   0x201cac0            ; helper entered with x0 = guard+0x50, w1 = 1
//     ldr  x8, [x19, #0x10a8]   ; plain load
//     add  x0, x19, #0x50
//     ldr  w8, [x8, #0x88]      ; plain load
//     cmp  w8, #0x1
//     cset w20, eq
//     bl   0x2064e08            ; helper exited with the same arguments
//     mov  w0, w20
//     ret
//
// Both field loads are plain `ldr`: there is no `ldar`/`ldaxr`, no `dmb`/`dsb`
// and no atomic read-modify-write in the getter. The guard pointer and the
// WindowInputState pointer are re-derived on every call; nothing is cached
// across calls. The two helpers are unnamed in the stripped library, so this
// code only claims what the instructions show: the engine's own getter performs
// those two reads between those two calls, and our value read calls the same
// two helpers at the same guard+0x50 with the same w1=1 argument before doing
// the same two plain loads. It does not claim a stronger memory ordering than
// the engine getter itself uses.
//
// Host tests do not exercise this chain; they exercise the value-only state and
// the segment boundary rule in mouse_lock_state.h.

struct EngineLayout {
    std::uintptr_t base = 0;
    boxblox::Segment segments[kMaxEngineSegments];
    std::size_t segmentCount = 0;
    bool found = false;
    bool overflow = false;
};

std::mutex g_install_mutex;
std::mutex g_layout_mutex;
EngineLayout g_layout;
std::atomic<bool> g_layout_ready{false};

std::atomic<const char*> g_mouse_state_result{static_cast<const char*>("not attempted")};
std::atomic<bool> g_mouse_lock_enabled{false};
std::atomic<bool> g_engine_supported{false};
std::atomic<bool> g_enhancement_ready{false};
std::atomic<bool> g_pointer_captured{false};

std::atomic<const void*> g_engine_handle{nullptr};

using MouseLockGetter = jboolean (JNICALL*)(JNIEnv*, jclass);
using PassMouseButton = void (JNICALL*)(JNIEnv*, jclass, jfloat, jfloat, jboolean, jint);
using PassMouseMove = void (JNICALL*)(JNIEnv*, jclass, jfloat, jfloat, jfloat, jfloat);

std::atomic<MouseLockGetter> g_original_mouse_lock_getter{nullptr};
std::atomic<PassMouseButton> g_original_pass_mouse_button{nullptr};
std::atomic<PassMouseMove> g_original_pass_mouse_move{nullptr};
std::atomic<bool> g_getter_hook_installed{false};
std::atomic<bool> g_button_hook_installed{false};
std::atomic<bool> g_move_hook_installed{false};

// The engine's native button argument for the secondary button is 1. Java's
// MotionEvent.BUTTON_SECONDARY is 2 and is not this hook's domain.
constexpr jint kNativeSecondaryButton = 1;

boxblox::MouseLockState g_lock_state;

// ------------------------------------------------------------- trace sink
//
// The engine calls the hooks from ART-attached threads. A callback takes a
// local JNI reference to the current sink under g_sink_mutex, then releases the
// mutex before entering Java. setMouseLockTrace swaps the global ref under the
// same mutex, so a callback either sees the old ref (and its local reference
// keeps the object alive) or the new one; a stale global ref is never used. The
// gesture mutex is never held across a Java callback. A pending original engine
// exception is never cleared here.
std::atomic<JavaVM*> g_vm{nullptr};
std::mutex g_sink_mutex;
jobject g_trace_sink = nullptr;
jmethodID g_trace_log = nullptr;
jmethodID g_trace_button = nullptr;
jmethodID g_trace_enabled = nullptr;

bool attachEnv(JNIEnv** env, bool* attached) {
    *env = nullptr;
    *attached = false;
    JavaVM* vm = g_vm.load(std::memory_order_acquire);
    if (vm == nullptr) {
        return false;
    }
    jint rc = vm->GetEnv(reinterpret_cast<void**>(env), JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(env, nullptr) != JNI_OK) {
            return false;
        }
        *attached = true;
    }
    return *env != nullptr;
}

void detachEnv(bool attached) {
    if (!attached) {
        return;
    }
    JavaVM* vm = g_vm.load(std::memory_order_acquire);
    if (vm != nullptr) {
        vm->DetachCurrentThread();
    }
}

bool sinkInstalled() {
    std::lock_guard<std::mutex> lock(g_sink_mutex);
    return g_trace_sink != nullptr
            && g_vm.load(std::memory_order_relaxed) != nullptr;
}

// Caller holds g_sink_mutex. A local reference is safe even if another thread
// swaps and deletes the global reference before the callback finishes.
bool pinSinkLocked(JNIEnv* env, jmethodID methodId, jobject* sink,
                   jmethodID* method) {
    *sink = nullptr;
    *method = nullptr;
    if (g_trace_sink == nullptr || methodId == nullptr) {
        return false;
    }
    jobject local = env->NewLocalRef(g_trace_sink);
    if (local == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return false;
    }
    *sink = local;
    *method = methodId;
    return true;
}

void traceLock(const char* fmt, ...) {
    char buffer[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);

    if (!sinkInstalled()) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (!attachEnv(&env, &attached)) {
        return;
    }
    // Preserve an exception raised by the original engine call instead of
    // clearing it as a side effect of tracing.
    if (env->ExceptionCheck()) {
        detachEnv(attached);
        return;
    }
    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_sink_mutex);
        pinSinkLocked(env, g_trace_log, &sink, &method);
    }
    if (sink != nullptr && method != nullptr) {
        jstring line = env->NewStringUTF(buffer);
        if (line != nullptr) {
            env->CallVoidMethod(sink, method, line);
            env->DeleteLocalRef(line);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(sink);
    }
    detachEnv(attached);
}

void notifyMouseButton(bool down, std::int64_t gestureId) {
    if (!sinkInstalled()) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (!attachEnv(&env, &attached)) {
        return;
    }
    if (env->ExceptionCheck()) {
        detachEnv(attached);
        return;
    }
    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_sink_mutex);
        pinSinkLocked(env, g_trace_button, &sink, &method);
    }
    if (sink != nullptr && method != nullptr) {
        env->CallVoidMethod(sink, method, down ? JNI_TRUE : JNI_FALSE,
                            static_cast<jlong>(gestureId));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(sink);
    }
    detachEnv(attached);
}

void notifyEnabled(bool enabled) {
    if (!sinkInstalled()) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (!attachEnv(&env, &attached)) {
        return;
    }
    if (env->ExceptionCheck()) {
        detachEnv(attached);
        return;
    }
    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_sink_mutex);
        pinSinkLocked(env, g_trace_enabled, &sink, &method);
    }
    if (sink != nullptr && method != nullptr) {
        env->CallVoidMethod(sink, method, enabled ? JNI_TRUE : JNI_FALSE);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(sink);
    }
    detachEnv(attached);
}

// ------------------------------------------------------------ engine read
std::uint32_t load32(std::uintptr_t address) {
    return *reinterpret_cast<const volatile std::uint32_t*>(address);
}

bool wordsMatch(std::uintptr_t address, const std::uint32_t* words, std::size_t count) {
    for (std::size_t i = 0; i < count; ++i) {
        if (load32(address + i * sizeof(std::uint32_t)) != words[i]) {
            return false;
        }
    }
    return true;
}

bool fixedLayoutValid(const EngineLayout& layout) {
    if (layout.base == 0 || layout.segmentCount == 0) {
        return false;
    }
    const struct {
        std::uintptr_t offset;
        std::size_t bytes;
    } ranges[] = {
            {kAccessorThunk, sizeof(kAccessorWords)},
            {kGetterThisMove, sizeof(std::uint32_t)},
            {kGetterEnterLockArg, sizeof(std::uint32_t)},
            {kGetterEnterLockCall, sizeof(std::uint32_t)},
            {kGetterPointerLoad, sizeof(std::uint32_t)},
            {kGetterExitLockArg, sizeof(std::uint32_t)},
            {kGetterFieldLoad, sizeof(std::uint32_t)},
            {kGetterExitLockCall, sizeof(std::uint32_t)},
            {kLockEnterThunk, sizeof(kLockEnterWords)},
            {kLockExitThunk, sizeof(kLockExitWords)},
    };
    for (const auto& range : ranges) {
        if (range.offset > UINTPTR_MAX - layout.base) {
            return false;
        }
        if (!boxblox::segmentCovers(layout.segments, layout.segmentCount,
                                    layout.base + range.offset, range.bytes, true)) {
            return false;
        }
    }
    return wordsMatch(layout.base + kAccessorThunk, kAccessorWords,
                      sizeof(kAccessorWords) / sizeof(kAccessorWords[0]))
            && load32(layout.base + kGetterThisMove) == kGetterThisMoveInsn
            && load32(layout.base + kGetterEnterLockArg) == kGetterEnterLockArgInsn
            && load32(layout.base + kGetterEnterLockCall) == kGetterEnterLockCallInsn
            && load32(layout.base + kGetterPointerLoad) == kGetterPointerLoadInsn
            && load32(layout.base + kGetterExitLockArg) == kGetterExitLockArgInsn
            && load32(layout.base + kGetterFieldLoad) == kGetterFieldLoadInsn
            && load32(layout.base + kGetterExitLockCall) == kGetterExitLockCallInsn
            && wordsMatch(layout.base + kLockEnterThunk, kLockEnterWords,
                          sizeof(kLockEnterWords) / sizeof(kLockEnterWords[0]))
            && wordsMatch(layout.base + kLockExitThunk, kLockExitWords,
                          sizeof(kLockExitWords) / sizeof(kLockExitWords[0]));
}

int engineSegmentsCallback(struct dl_phdr_info* info, size_t, void* data) {
    auto* layout = static_cast<EngineLayout*>(data);
    const char* name = info->dlpi_name;
    if (name == nullptr || std::strstr(name, "libroblox.so") == nullptr) {
        return 0;
    }
    layout->found = true;
    layout->base = static_cast<std::uintptr_t>(info->dlpi_addr);
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || ph.p_memsz == 0) {
            continue;
        }
        if (layout->segmentCount >= kMaxEngineSegments) {
            layout->overflow = true;
            break;
        }
        boxblox::Segment& segment = layout->segments[layout->segmentCount++];
        segment.start = static_cast<std::uintptr_t>(info->dlpi_addr)
                + static_cast<std::uintptr_t>(ph.p_vaddr);
        segment.end = segment.start + static_cast<std::uintptr_t>(ph.p_memsz);
        segment.exec = (ph.p_flags & PF_X) != 0;
    }
    return 1;  // stop iterating
}

bool loadEngineLayout(void* handle) {
    if (g_layout_ready.load(std::memory_order_acquire)) {
        return true;
    }
    Dl_info info{};
    void* onLoad = handle == nullptr ? nullptr : dlsym(handle, "JNI_OnLoad");
    if (onLoad == nullptr || dladdr(onLoad, &info) == 0
            || info.dli_fbase == nullptr) {
        g_mouse_state_result.store("no engine load base");
        traceLock("mouse state: no engine load base");
        return false;
    }

    EngineLayout layout;
    layout.base = reinterpret_cast<std::uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(engineSegmentsCallback, &layout);
    if (!layout.found || layout.segmentCount == 0 || layout.overflow) {
        g_mouse_state_result.store("engine segments unavailable");
        traceLock("mouse state: engine segments unavailable");
        return false;
    }
    if (layout.base != reinterpret_cast<std::uintptr_t>(info.dli_fbase)) {
        g_mouse_state_result.store("engine load base mismatch");
        traceLock("mouse state: engine load base mismatch");
        return false;
    }
    if (!fixedLayoutValid(layout)) {
        g_mouse_state_result.store("fingerprint mismatch");
        traceLock("mouse state: engine fingerprint mismatch");
        return false;
    }

    {
        std::lock_guard<std::mutex> guard(g_layout_mutex);
        if (!g_layout_ready.load(std::memory_order_relaxed)) {
            g_layout = layout;
            g_layout_ready.store(true, std::memory_order_release);
        }
    }
    g_engine_supported.store(true, std::memory_order_release);
    g_mouse_state_result.store("ready (read-only)");
    traceLock("mouse state: fingerprints verified, read-only");
    return true;
}

bool ensureEngineLayout() {
    if (g_layout_ready.load(std::memory_order_acquire)) {
        if (fixedLayoutValid(g_layout)) {
            g_engine_supported.store(true, std::memory_order_release);
            return true;
        }
        g_engine_supported.store(false, std::memory_order_release);
        g_mouse_state_result.store("fingerprint mismatch");
        traceLock("mouse state: cached engine fingerprint no longer matches");
        return false;
    }
    void* handle = const_cast<void*>(g_engine_handle.load(std::memory_order_acquire));
    if (handle == nullptr) {
        handle = dlopen("libroblox.so", RTLD_NOLOAD | RTLD_LAZY);
    }
    if (handle == nullptr) {
        g_mouse_state_result.store("engine not mapped");
        traceLock("mouse state: engine not mapped");
        return false;
    }
    return loadEngineLayout(handle);
}

class EngineReadLock {
public:
    EngineReadLock(void (*exitFn)(void*, int), void* lock) : exit_(exitFn), lock_(lock) {}
    ~EngineReadLock() { exit_(lock_, 1); }

    EngineReadLock(const EngineReadLock&) = delete;
    EngineReadLock& operator=(const EngineReadLock&) = delete;

private:
    void (*exit_)(void*, int);
    void* lock_;
};

// The WindowInputState pointer refers to engine heap memory, so the library
// segment checks cannot cover its target. mincore proves the pages holding the
// fixed-offset read are mapped before the dereference; an unmapped ("dead")
// pointer returns -1 instead of faulting.
bool mappedReadable(std::uintptr_t address, std::size_t span) {
    if (span == 0 || address > UINTPTR_MAX - span) {
        return false;
    }
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) {
        return false;
    }
    const std::uintptr_t pageSizeValue = static_cast<std::uintptr_t>(pageSize);
    const std::uintptr_t pageMask = pageSizeValue - 1;
    const std::uintptr_t start = address & ~pageMask;
    const std::uintptr_t end = address + span;
    const std::uintptr_t length = (end - start + pageMask) & ~pageMask;
    if (length == 0 || length > pageSizeValue * 2) {
        return false;
    }
    unsigned char resident[2] = {0, 0};
    return mincore(reinterpret_cast<void*>(start), static_cast<std::size_t>(length),
                   resident) == 0;
}

/**
 * Read the current engine MouseBehavior without writing anything.
 *
 * <p>Returns 0/1/2, or -1 when the enhancement is disabled or not installed,
 * when the build fingerprints do not match, when the accessor returns no guard,
 * when a fixed offset falls outside its executable segment, or when the raw
 * value is outside 0..2. The chain is re-derived from scratch on every call:
 * accessor thunk -> guard -> guard+0x10A8 (WindowInputState) -> +0x88 int32.
 * The two plain loads happen between the same two helper calls the engine's own
 * getter makes, with the same guard+0x50 and w1=1 arguments. The MouseBehavior
 * int32 itself is only read; the engine's own lock helpers are the sole code
 * that may update their synchronization word, exactly as the engine getter does.
 */
int readEngineBehavior() {
    if (!g_mouse_lock_enabled.load(std::memory_order_acquire)
            || !g_enhancement_ready.load(std::memory_order_acquire)
            || !g_engine_supported.load(std::memory_order_acquire)) {
        return boxblox::kMouseBehaviorUnavailable;
    }
    if (!g_layout_ready.load(std::memory_order_acquire)) {
        return boxblox::kMouseBehaviorUnavailable;
    }

    const EngineLayout& layout = g_layout;
    if (!fixedLayoutValid(layout)) {
        static std::atomic<bool> reported{false};
        bool expected = false;
        if (reported.compare_exchange_strong(expected, true,
                std::memory_order_relaxed, std::memory_order_relaxed)) {
            traceLock("mouse state: fingerprint mismatch during read; enhancement disabled");
        }
        g_engine_supported.store(false, std::memory_order_release);
        g_enhancement_ready.store(false, std::memory_order_release);
        g_lock_state.setSupported(false);
        g_mouse_state_result.store("fingerprint mismatch at read");
        return boxblox::kMouseBehaviorUnavailable;
    }

    auto accessor = reinterpret_cast<void* (*)(int)>(layout.base + kAccessorThunk);
    void* guard = accessor(4);
    const std::uintptr_t guardAddress = reinterpret_cast<std::uintptr_t>(guard);
    if (guard == nullptr
            || (guardAddress & 7u) != 0
            || guardAddress > UINTPTR_MAX - kWindowInputStateOffset - sizeof(void*)
            || !boxblox::segmentCovers(layout.segments, layout.segmentCount,
                                       guardAddress,
                                       kWindowInputStateOffset + sizeof(void*),
                                       false)) {
        return boxblox::kMouseBehaviorUnavailable;
    }

    auto lockEnter = reinterpret_cast<void (*)(void*, int)>(layout.base + kLockEnterThunk);
    auto lockExit = reinterpret_cast<void (*)(void*, int)>(layout.base + kLockExitThunk);
    void* lockWord = reinterpret_cast<void*>(guardAddress + kGuardLockOffset);
    lockEnter(lockWord, 1);
    EngineReadLock held(lockExit, lockWord);

    void* state = *reinterpret_cast<void* const*>(guardAddress + kWindowInputStateOffset);
    const std::uintptr_t stateAddress = reinterpret_cast<std::uintptr_t>(state);
    int value = boxblox::kMouseBehaviorUnavailable;
    if (state != nullptr && (stateAddress & 7u) == 0
            && stateAddress >= 0x10000u
            && stateAddress <= UINTPTR_MAX - kMouseBehaviorOffset - sizeof(int)
            && mappedReadable(stateAddress,
                              static_cast<std::size_t>(kMouseBehaviorOffset) + sizeof(int))) {
        const int raw = *reinterpret_cast<const volatile int*>(
                reinterpret_cast<const char*>(state) + kMouseBehaviorOffset);
        value = boxblox::normalizeEngineBehavior(raw);
    }
    return value;
}

std::int64_t monotonicNanos() {
    struct timespec ts {};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return -1;
    }
    return static_cast<std::int64_t>(ts.tv_sec) * 1000000000LL
            + static_cast<std::int64_t>(ts.tv_nsec);
}

void reportGestureStart(const boxblox::PressOutcome& outcome) {
    traceLock("gesture %lld started at %lld ns (native secondary button 1)",
              static_cast<long long>(outcome.id),
              static_cast<long long>(outcome.startedNanos));
}

void reportGestureEnd(const char* what, const boxblox::GestureSummary& summary) {
    const bool haveClock = summary.startedNanos >= 0
            && summary.endedNanos >= summary.startedNanos;
    const long long durationMs = haveClock
            ? static_cast<long long>((summary.endedNanos - summary.startedNanos) / 1000000)
            : -1;
    if (g_move_hook_installed.load(std::memory_order_acquire)) {
        traceLock("%s: gesture %lld ended after %lld ms, moves=%ld",
                  what, static_cast<long long>(summary.id), durationMs, summary.moves);
    } else {
        traceLock("%s: gesture %lld ended after %lld ms, movement instrumentation unavailable",
                  what, static_cast<long long>(summary.id), durationMs);
    }
}

// ---------------------------------------------------------------- hooks
/**
 * The engine's own mouse-lock getter, gated by the value-only state.
 *
 * <p>The original getter always runs first and remains the source of the
 * engine's answer. Disabled, unsupported, not-installed or a missing original
 * is an exact pass-through. Otherwise: an active button-dispatch scope returns
 * false so the guest handler processes the button; confirmed Android capture
 * returns true; otherwise the original answer is kept, unless the tracked
 * secondary gesture is active and the current engine behavior is 2.
 */
jboolean JNICALL mouseLockedCenter(JNIEnv* env, jclass inputInterface) {
    const auto original = g_original_mouse_lock_getter.load(std::memory_order_acquire);
    if (original == nullptr) {
        return JNI_FALSE;
    }
    const jboolean originalCenter = original(env, inputInterface);
    if (!g_enhancement_ready.load(std::memory_order_acquire)
            || !g_mouse_lock_enabled.load(std::memory_order_acquire)
            || env->ExceptionCheck()) {
        return originalCenter;
    }

    const bool dispatchActive = boxblox::buttonDispatchScopeActive();
    const bool confirmedCapture = g_pointer_captured.load(std::memory_order_acquire);
    int engineBehavior = boxblox::kMouseBehaviorUnavailable;
    if (!dispatchActive && !confirmedCapture && originalCenter != JNI_TRUE
            && g_lock_state.enhancementReady() && g_lock_state.gestureActive()) {
        engineBehavior = readEngineBehavior();
    }
    const bool reportCenter = g_lock_state.shouldReportCenter(
            originalCenter == JNI_TRUE, dispatchActive, confirmedCapture, engineBehavior);
    return reportCenter ? JNI_TRUE : JNI_FALSE;
}

/**
 * Observe the engine's own button stream.
 *
 * <p>The original engine call is forwarded exactly once. Gesture identity is
 * taken before forwarding so the press is accounted even while the original
 * handler runs; the controller is notified only after that forward. No engine
 * button event is synthesized. Only the native secondary button (1) is tracked.
 */
void JNICALL passMouseButton(JNIEnv* env, jclass inputInterface, jfloat x, jfloat y,
                             jboolean down, jint button) {
    const bool secondary = button == kNativeSecondaryButton;
    boxblox::PressOutcome pressOutcome;
    std::optional<boxblox::GestureSummary> releaseSummary;

    if (secondary) {
        if (down == JNI_TRUE) {
            pressOutcome = g_lock_state.press(monotonicNanos());
        } else {
            releaseSummary = g_lock_state.release(monotonicNanos());
        }
    }

    const auto original = g_original_pass_mouse_button.load(std::memory_order_acquire);
    if (original != nullptr) {
        original(env, inputInterface, x, y, down, button);
    }
    if (env->ExceptionCheck()) {
        if (secondary && down == JNI_TRUE && pressOutcome.started) {
            g_lock_state.cancel(monotonicNanos());
        }
        return;
    }

    if (secondary && down == JNI_TRUE) {
        if (pressOutcome.started) {
            reportGestureStart(pressOutcome);
            notifyMouseButton(true, pressOutcome.id);
        }
    } else if (secondary && down != JNI_TRUE) {
        if (releaseSummary.has_value()) {
            reportGestureEnd("button release", *releaseSummary);
            notifyMouseButton(false, releaseSummary->id);
        } else {
            traceLock("button release: no active gesture");
        }
    }
}

/**
 * Count relative movement reaching the engine, only while a tracked gesture is
 * active. No per-move logging, allocation or engine access happens here.
 */
void JNICALL passMouseMove(JNIEnv* env, jclass inputInterface, jfloat x, jfloat y,
                           jfloat dx, jfloat dy) {
    g_lock_state.movement();
    const auto original = g_original_pass_mouse_move.load(std::memory_order_acquire);
    if (original != nullptr) {
        original(env, inputInterface, x, y, dx, dy);
    }
}

std::atomic<bool> g_jni_onload_inert{false};

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_app_boxblox_EngineBridge_mouseStateResult(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_mouse_state_result.load(std::memory_order_acquire));
}

JNIEXPORT jint JNICALL
Java_app_boxblox_EngineBridge_mouseBehavior(JNIEnv*, jclass) {
    return static_cast<jint>(readEngineBehavior());
}

JNIEXPORT void JNICALL
Java_app_boxblox_EngineBridge_setPointerCaptureState(JNIEnv*, jclass, jboolean captured) {
    const bool value = captured == JNI_TRUE;
    const bool was = g_pointer_captured.exchange(value, std::memory_order_acq_rel);
    if (was != value) {
        traceLock("pointer capture confirmed=%d", value ? 1 : 0);
    }
}

JNIEXPORT void JNICALL
Java_app_boxblox_EngineBridge_beginMouseButtonDispatch(JNIEnv*, jclass) {
    boxblox::beginButtonDispatchScope();
}

JNIEXPORT void JNICALL
Java_app_boxblox_EngineBridge_endMouseButtonDispatch(JNIEnv*, jclass) {
    boxblox::endButtonDispatchScope();
}

JNIEXPORT void JNICALL
Java_app_boxblox_EngineBridge_setMouseLockEnabled(JNIEnv*, jclass, jboolean enabled) {
    const bool value = enabled == JNI_TRUE;
    const bool was = g_mouse_lock_enabled.exchange(value, std::memory_order_acq_rel);
    if (!value) {
        // Always report the disabled state, even if it was already set: the
        // Java controller uses onEnabled(false) to cancel pending work, and a
        // sink may have been installed after the feature was last toggled.
        g_mouse_state_result.store("feature disabled");
        std::optional<boxblox::GestureSummary> cancelled =
                g_lock_state.disable(monotonicNanos());
        if (cancelled.has_value()) {
            reportGestureEnd("feature disabled", *cancelled);
        } else if (was) {
            traceLock("feature disabled: no active gesture");
        } else {
            traceLock("feature already disabled: no active gesture");
        }
        notifyEnabled(false);
        return;
    }
    if (was == value) {
        return;
    }
    g_lock_state.setEnabled(true);
    traceLock("feature enabled");
    if (!g_layout_ready.load(std::memory_order_acquire)) {
        ensureEngineLayout();
    }
    if (g_enhancement_ready.load(std::memory_order_acquire)) {
        g_mouse_state_result.store("ready (read-only)");
        notifyEnabled(true);
    } else {
        traceLock("feature enable deferred: hooks not ready");
    }
}

JNIEXPORT jboolean JNICALL
Java_app_boxblox_EngineBridge_releaseMouseLock(JNIEnv* env, jclass, jstring reason) {
    // The reason label is Java-owned diagnostic text; native only cancels its
    // own tracking. It never releases Android capture on this path.
    const char* label = nullptr;
    if (reason != nullptr) {
        label = env->GetStringUTFChars(reason, nullptr);
    }
    const char* what = label != nullptr ? label : "lifecycle release";
    std::optional<boxblox::GestureSummary> closed =
            g_lock_state.cancel(monotonicNanos());
    if (closed.has_value()) {
        reportGestureEnd(what, *closed);
    } else {
        traceLock("%s: no active gesture", what);
    }
    if (label != nullptr) {
        env->ReleaseStringUTFChars(reason, label);
    }
    return closed.has_value() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_boxblox_EngineBridge_setMouseLockTrace(JNIEnv* env, jclass, jobject sink) {
    if (env->ExceptionCheck()) {
        return;
    }
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);

    jobject newGlobal = nullptr;
    jmethodID log = nullptr;
    jmethodID button = nullptr;
    jmethodID enabled = nullptr;
    if (sink != nullptr) {
        jclass cls = env->GetObjectClass(sink);
        if (cls != nullptr) {
            log = env->GetMethodID(cls, "log", "(Ljava/lang/String;)V");
            if (log == nullptr && env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            button = env->GetMethodID(cls, "onMouseButton", "(ZJ)V");
            if (button == nullptr && env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            enabled = env->GetMethodID(cls, "onEnabled", "(Z)V");
            if (enabled == nullptr && env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            env->DeleteLocalRef(cls);
        }
        if (log != nullptr || button != nullptr || enabled != nullptr) {
            newGlobal = env->NewGlobalRef(sink);
        }
    }
    if (sink != nullptr && newGlobal == nullptr) {
        LOGW("setMouseLockTrace: sink has none of the required methods");
        return;
    }

    {
        std::lock_guard<std::mutex> lock(g_sink_mutex);
        jobject old = g_trace_sink;
        g_vm.store(vm, std::memory_order_release);
        g_trace_sink = newGlobal;
        g_trace_log = log;
        g_trace_button = button;
        g_trace_enabled = enabled;
        if (old != nullptr) {
            env->DeleteGlobalRef(old);
        }
    }
    traceLock("trace sink installed");
}

/**
 * Make the engine's {@code JNI_OnLoad} inert after it has run once.
 *
 * <p>Two loads happen and ART does not dedupe them, because it keys its
 * loaded-library map by the *requested name*: the wrapper loads the engine by
 * absolute path (the only form that works - {@code loadLibrary0} rejects a path,
 * and by-name lookup goes through a linker namespace that cannot be widened from
 * Java), and AGDK later calls {@code System.loadLibrary("roblox")} in its
 * {@code onCreate}. The second call runs {@code JNI_OnLoad} again, and the
 * engine's initialiser aborts when it runs twice.
 *
 * <p>The GOT cannot be patched for this: the engine never references its own
 * {@code JNI_OnLoad}, so there is no relocation entry for it. What is done
 * instead is rewriting the first instructions of the function itself, in the
 * process's own mapping of it, so any later call returns immediately. The file
 * on disk is untouched.
 */
JNIEXPORT jboolean JNICALL
Java_app_boxblox_EngineBridge_disableJniOnLoad(JNIEnv*, jclass) {
    void* handle = dlopen("libroblox.so", RTLD_NOLOAD | RTLD_LAZY);
    if (handle == nullptr) {
        handle = const_cast<void*>(g_engine_handle.load(std::memory_order_acquire));
    }
    if (handle == nullptr) {
        LOGW("disableJniOnLoad: no handle (RTLD_NOLOAD missed and none stored)");
        return JNI_FALSE;
    }
    auto* target = reinterpret_cast<unsigned char*>(dlsym(handle, "JNI_OnLoad"));
    if (target == nullptr) {
        LOGW("disableJniOnLoad: no JNI_OnLoad exported");
        return JNI_FALSE;
    }

    // aarch64: movz w0, #0x0006 ; movk w0, #0x0001, lsl #16 ; ret
    // i.e. return JNI_VERSION_1_6 without touching anything else.
    constexpr std::uint32_t kMovz = 0x528000C0u;   // movz w0, #6
    constexpr std::uint32_t kMovk = 0x72A00020u;   // movk w0, #1, lsl #16
    constexpr std::uint32_t kRet = 0xD65F03C0u;    // ret
    const std::uint32_t patch[3] = {kMovz, kMovk, kRet};

    std::uintptr_t addr = reinterpret_cast<std::uintptr_t>(target);
    if ((addr & 3u) != 0) {
        LOGW("disableJniOnLoad: target %p is not 4-byte aligned", target);
        return JNI_FALSE;
    }

    long pageSize = sysconf(_SC_PAGESIZE);
    std::uintptr_t page = addr & ~static_cast<std::uintptr_t>(pageSize - 1);
    // The function may straddle a page boundary; unprotect both pages.
    std::size_t span = (addr - page) + sizeof(patch);
    std::size_t pages = ((span + static_cast<std::size_t>(pageSize) - 1)
            / static_cast<std::size_t>(pageSize)) * static_cast<std::size_t>(pageSize);

    if (mprotect(reinterpret_cast<void*>(page), pages,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGW("disableJniOnLoad: mprotect failed (errno %d)", errno);
        return JNI_FALSE;
    }
    std::memcpy(target, patch, sizeof(patch));
    __builtin___clear_cache(reinterpret_cast<char*>(target),
                            reinterpret_cast<char*>(target) + sizeof(patch));
    mprotect(reinterpret_cast<void*>(page), pages, PROT_READ | PROT_EXEC);

    g_jni_onload_inert.store(true, std::memory_order_relaxed);
    LOGI("disableJniOnLoad: JNI_OnLoad at %p now returns immediately", target);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_app_boxblox_EngineBridge_noteEngineLoaded(JNIEnv*, jclass) {
    void* handle = dlopen("libroblox.so", RTLD_NOLOAD | RTLD_LAZY);
    if (handle != nullptr) {
        g_engine_handle.store(handle, std::memory_order_release);
        LOGI("noteEngineLoaded: handle=%p", handle);
        return JNI_TRUE;
    }
    LOGW("noteEngineLoaded: engine not mapped");
    return JNI_FALSE;
}

/**
 * Install the getter, button and movement hooks.
 *
 * <p>Every prerequisite is checked before the getter binding is replaced: the
 * Java methods and signatures exist, the engine layout fingerprints verify,
 * and the engine exports for the original button/move/getter calls exist. The
 * original pointer for each hook is published before RegisterNatives, and the
 * getter remains a pure pass-through until the enhancement-ready flag is set
 * after every essential registration succeeds.
 */
JNIEXPORT jboolean JNICALL
Java_app_boxblox_EngineBridge_installMouseLockHook(JNIEnv* env, jclass, jclass inputInterface) {
    std::lock_guard<std::mutex> installGuard(g_install_mutex);
    if (g_enhancement_ready.load(std::memory_order_acquire)) {
        traceLock("installMouseLockHook: already installed");
        return JNI_TRUE;
    }
    // A retry must stay a pass-through until the prerequisites below succeed
    // again; only the final block re-enables the state gate.
    g_lock_state.setHookReady(false);
    g_lock_state.setSupported(false);
    if (inputInterface == nullptr) {
        traceLock("installMouseLockHook failed: no input class");
        return JNI_FALSE;
    }
    if (env->GetStaticMethodID(inputInterface,
            "nativeGetMainWindowIsMouseLockedCenter", "()Z") == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        traceLock("installMouseLockHook failed: missing nativeGetMainWindowIsMouseLockedCenter()Z");
        return JNI_FALSE;
    }
    if (env->GetStaticMethodID(inputInterface, "nativePassMouseButton", "(FFZI)V") == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        traceLock("installMouseLockHook failed: missing nativePassMouseButton(FFZI)V");
        return JNI_FALSE;
    }
    const bool haveMoveMethod = env->GetStaticMethodID(
            inputInterface, "nativePassMouseMove", "(FFFF)V") != nullptr;
    if (!haveMoveMethod && env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    if (!ensureEngineLayout()) {
        traceLock("installMouseLockHook failed: engine mouse state unsupported");
        return JNI_FALSE;
    }

    void* handle = const_cast<void*>(g_engine_handle.load(std::memory_order_acquire));
    if (handle == nullptr) {
        handle = dlopen("libroblox.so", RTLD_NOLOAD | RTLD_LAZY);
    }
    if (handle == nullptr) {
        traceLock("installMouseLockHook failed: engine not mapped");
        return JNI_FALSE;
    }

    const auto getter = reinterpret_cast<MouseLockGetter>(dlsym(handle,
            "Java_com_roblox_engine_jni_NativeInputInterface_nativeGetMainWindowIsMouseLockedCenter"));
    if (getter == nullptr || getter == mouseLockedCenter) {
        traceLock("installMouseLockHook failed: getter export unavailable");
        return JNI_FALSE;
    }
    const auto button = reinterpret_cast<PassMouseButton>(dlsym(handle,
            "Java_com_roblox_engine_jni_NativeInputInterface_nativePassMouseButton"));
    if (button == nullptr || button == passMouseButton) {
        traceLock("installMouseLockHook failed: nativePassMouseButton export unavailable");
        return JNI_FALSE;
    }
    const auto move = reinterpret_cast<PassMouseMove>(dlsym(handle,
            "Java_com_roblox_engine_jni_NativeInputInterface_nativePassMouseMove"));

    // Publish every original pointer before any binding becomes callable.
    g_original_mouse_lock_getter.store(getter, std::memory_order_release);
    g_original_pass_mouse_button.store(button, std::memory_order_release);
    if (move != nullptr && move != passMouseMove) {
        g_original_pass_mouse_move.store(move, std::memory_order_release);
    }

    if (!g_button_hook_installed.load(std::memory_order_acquire)) {
        JNINativeMethod method{};
        method.name = const_cast<char*>("nativePassMouseButton");
        method.signature = const_cast<char*>("(FFZI)V");
        method.fnPtr = reinterpret_cast<void*>(passMouseButton);
        if (env->RegisterNatives(inputInterface, &method, 1) != JNI_OK) {
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            traceLock("installMouseLockHook failed: button registration rejected");
            return JNI_FALSE;
        }
        g_button_hook_installed.store(true, std::memory_order_release);
        traceLock("button hook installed (native secondary code 1; Java code 2 is not used)");
    }

    if (haveMoveMethod && g_original_pass_mouse_move.load(std::memory_order_acquire) != nullptr
            && !g_move_hook_installed.load(std::memory_order_acquire)) {
        JNINativeMethod method{};
        method.name = const_cast<char*>("nativePassMouseMove");
        method.signature = const_cast<char*>("(FFFF)V");
        method.fnPtr = reinterpret_cast<void*>(passMouseMove);
        if (env->RegisterNatives(inputInterface, &method, 1) != JNI_OK) {
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            traceLock("move hook unavailable: movement counts will be reported as unavailable");
        } else {
            g_move_hook_installed.store(true, std::memory_order_release);
            traceLock("move hook installed (gesture aggregation only)");
        }
    } else if (!haveMoveMethod ||
               g_original_pass_mouse_move.load(std::memory_order_acquire) == nullptr) {
        traceLock("movement instrumentation unavailable: movement counts will be reported as unavailable");
    }

    if (!g_getter_hook_installed.load(std::memory_order_acquire)) {
        JNINativeMethod method{};
        method.name = const_cast<char*>("nativeGetMainWindowIsMouseLockedCenter");
        method.signature = const_cast<char*>("()Z");
        method.fnPtr = reinterpret_cast<void*>(mouseLockedCenter);
        if (env->RegisterNatives(inputInterface, &method, 1) != JNI_OK) {
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            traceLock("installMouseLockHook failed: getter registration rejected");
            return JNI_FALSE;
        }
        g_getter_hook_installed.store(true, std::memory_order_release);
    }

    g_enhancement_ready.store(true, std::memory_order_release);
    g_lock_state.setSupported(true);
    g_lock_state.setHookReady(true);
    g_lock_state.setEnabled(g_mouse_lock_enabled.load(std::memory_order_acquire));
    traceLock("installMouseLockHook: hooks installed; getter gated by value-only state");

    if (g_mouse_lock_enabled.load(std::memory_order_acquire)) {
        g_mouse_state_result.store("ready (read-only)");
        notifyEnabled(true);
    } else {
        g_mouse_state_result.store("ready (read-only; feature disabled)");
    }
    return JNI_TRUE;
}

}  // extern "C"
