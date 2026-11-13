// Host-side tests for the value-only mouse-lock state.
//
// Compile and run with:
//   g++ -std=c++17 -Wall -Wextra scripts/host_tests_mouse_lock_state.cpp -o /tmp/mouse-lock-tests && /tmp/mouse-lock-tests
//
// The device path is not exercised here. The tests cover the pure gesture,
// getter-gating and segment-boundary logic that replaced the old engine-field
// ownership machine. There is no engine pointer in the state and no test writes
// engine memory; the read-only mapping case proves that any accidental write
// through the checked value would fault.

#if defined(__linux__) && !defined(_GNU_SOURCE)
#define _GNU_SOURCE
#endif

#include "../app/src/main/cpp/mouse_lock_state.h"

#include <cstdio>
#include <cstdint>
#include <sys/mman.h>
#include <type_traits>
#include <unistd.h>
#include <utility>

using boxblox::MouseLockState;
using boxblox::Segment;

template <typename T, typename = void>
struct HasAttachField : std::false_type {};
template <typename T>
struct HasAttachField<T, std::void_t<decltype(&T::attachField)>> : std::true_type {};

template <typename T, typename = void>
struct HasFieldAccessor : std::false_type {};
template <typename T>
struct HasFieldAccessor<T, std::void_t<decltype(std::declval<T&>().field())>>
        : std::true_type {};

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond, name)                                                  \
    do {                                                                   \
        ++g_checks;                                                        \
        if (!(cond)) {                                                     \
            ++g_failures;                                                  \
            std::fprintf(stderr, "FAIL %s (line %d)\n", name, __LINE__);   \
        }                                                                  \
    } while (0)

static void makeReady(MouseLockState& state) {
    state.setSupported(true);
    state.setHookReady(true);
    state.setEnabled(true);
}

static void testDisabledAndNotInstalled() {
    MouseLockState disabled;
    disabled.setSupported(true);
    disabled.setHookReady(true);
    CHECK(!disabled.press(100).started, "disabled press starts nothing");
    CHECK(!disabled.gestureActive(), "disabled state is inactive");
    CHECK(!disabled.shouldReportCenter(false, false, true,
                                       boxblox::kMouseBehaviorLockCurrentPosition),
          "disabled getter keeps original false even while capture is confirmed");
    CHECK(disabled.shouldReportCenter(true, true, false,
                                      boxblox::kMouseBehaviorLockCenter),
          "disabled getter keeps original true even in dispatch scope");
    CHECK(!disabled.release(200).has_value(), "disabled release has no gesture");
    disabled.movement();
    CHECK(!disabled.gestureActive(), "disabled movement stays untracked");

    MouseLockState notInstalled;
    notInstalled.setEnabled(true);
    notInstalled.setSupported(true);
    CHECK(!notInstalled.press(100).started, "not-installed press starts nothing");
    CHECK(!notInstalled.shouldReportCenter(false, false, false,
                                           boxblox::kMouseBehaviorLockCurrentPosition),
          "not-installed getter stays original false");

    MouseLockState unsupported;
    unsupported.setEnabled(true);
    unsupported.setHookReady(true);
    CHECK(!unsupported.press(100).started, "unsupported press starts nothing");
    CHECK(!unsupported.shouldReportCenter(false, false, false,
                                          boxblox::kMouseBehaviorLockCurrentPosition),
          "unsupported getter stays original false");
}

static void testEngineReadFailures() {
    MouseLockState state;
    makeReady(state);
    const auto pressed = state.press(1000);
    CHECK(pressed.started && pressed.id == 1, "gesture starts for read-failure tests");
    CHECK(!state.shouldReportCenter(false, false, false,
                                    boxblox::kMouseBehaviorUnavailable),
          "unavailable engine read (-1) does not override original false");
    CHECK(state.shouldReportCenter(true, false, false,
                                   boxblox::kMouseBehaviorUnavailable),
          "unavailable engine read (-1) keeps original true");
    CHECK(boxblox::normalizeEngineBehavior(0) == boxblox::kMouseBehaviorDefault,
          "raw engine 0 normalizes to Default");
    CHECK(boxblox::normalizeEngineBehavior(1) == boxblox::kMouseBehaviorLockCenter,
          "raw engine 1 normalizes to LockCenter");
    CHECK(boxblox::normalizeEngineBehavior(2) == boxblox::kMouseBehaviorLockCurrentPosition,
          "raw engine 2 normalizes to LockCurrentPosition");
    CHECK(boxblox::normalizeEngineBehavior(3) == boxblox::kMouseBehaviorUnavailable,
          "raw engine 3 normalizes to unavailable");
    CHECK(boxblox::normalizeEngineBehavior(7) == boxblox::kMouseBehaviorUnavailable,
          "raw engine 7 normalizes to unavailable");
    CHECK(boxblox::normalizeEngineBehavior(-2) == boxblox::kMouseBehaviorUnavailable,
          "raw engine -2 normalizes to unavailable");
    CHECK(boxblox::normalizeEngineBehavior(boxblox::kMouseBehaviorUnavailable)
                  == boxblox::kMouseBehaviorUnavailable,
          "unavailable engine read stays unavailable");
    CHECK(!state.shouldReportCenter(false, false, false,
                                    boxblox::normalizeEngineBehavior(3)),
          "out-of-range engine read 3 does not override");
    CHECK(!state.shouldReportCenter(false, false, false,
                                    boxblox::normalizeEngineBehavior(7)),
          "out-of-range engine read 7 does not override");
    CHECK(!state.shouldReportCenter(false, false, false,
                                    boxblox::normalizeEngineBehavior(-2)),
          "out-of-range engine read -2 does not override");
    state.release(2000);
}

static void testRepeatedPressAndMovement() {
    MouseLockState state;
    makeReady(state);
    const auto first = state.press(1000);
    CHECK(first.started && first.id == 1, "first native secondary press starts gesture 1");
    CHECK(first.startedNanos == 1000, "gesture start timestamp recorded");

    state.movement();
    state.movement();
    const auto repeated = state.press(1500);
    CHECK(!repeated.started, "repeated press does not start a second gesture");
    CHECK(repeated.id == first.id, "repeated press keeps the same gesture id");
    CHECK(repeated.startedNanos == 1000, "repeated press keeps the original start");
    CHECK(state.activeGestureId() == 1, "active id unchanged by repeated press");
    CHECK(state.activeMovementCount() == 2, "repeated press does not reset movement");

    const auto closed = state.release(2000);
    CHECK(closed.has_value() && closed->id == 1 && closed->moves == 2,
          "release reports the genuine gesture and its movement");
    CHECK(closed->startedNanos == 1000 && closed->endedNanos == 2000,
          "release reports the matching start and end");
    CHECK(!state.gestureActive(), "release closes the gesture");

    const auto extra = state.press(2100);
    CHECK(extra.started && extra.id == 2, "next gesture gets a new monotonic id");
    CHECK(state.activeMovementCount() == 0, "new gesture starts at zero movement");
    state.movement();
    const auto second = state.release(2200);
    CHECK(second.has_value() && second->moves == 1,
          "movement after the previous release belongs to the new gesture only");
}

static void testReleaseWithoutGesture() {
    MouseLockState state;
    makeReady(state);
    const auto closed = state.release(1234);
    CHECK(!closed.has_value(), "release with no active gesture returns nothing");
    const auto again = state.release(1235);
    CHECK(!again.has_value(), "repeated release with no gesture is harmless");
    CHECK(!state.gestureActive(), "no gesture was created by release");
}

static void testMovementAfterReleaseExcluded() {
    MouseLockState state;
    makeReady(state);
    state.press(1000);
    state.movement();
    const auto closed = state.release(1100);
    CHECK(closed.has_value() && closed->moves == 1, "first gesture counted one move");
    state.movement();
    state.movement();
    state.movement();
    CHECK(state.activeMovementCount() == 1,
          "movement after release is not added to the closed gesture");
    const auto next = state.press(1200);
    CHECK(next.started && next.id == 2, "later press reopens tracking");
    CHECK(state.activeMovementCount() == 0,
          "movement after release did not leak into the next gesture");
    state.release(1300);
}

static void testLifecycleCancellation() {
    MouseLockState state;
    makeReady(state);
    state.press(1000);
    state.movement();
    state.movement();
    const auto cancelled = state.cancel(1750);
    CHECK(cancelled.has_value() && cancelled->id == 1 && cancelled->moves == 2,
          "lifecycle cancellation reports the active gesture once");
    CHECK(!state.gestureActive(), "lifecycle cancellation closes tracking");
    CHECK(!state.cancel(1800).has_value(), "cancellation closes only once");
    CHECK(!state.release(1900).has_value(), "release after cancellation has no gesture");
    state.movement();
    CHECK(state.activeMovementCount() == 2,
          "movement after cancellation is not counted");

    state.press(2000);
    const auto disabled = state.disable(2100);
    CHECK(disabled.has_value() && disabled->id == 2,
          "disable closes and reports the active gesture");
    CHECK(!state.gestureActive(), "disable closes tracking");
    CHECK(!state.press(2200).started, "press while disabled starts nothing");
    state.setEnabled(true);
    const auto reenabled = state.press(2300);
    CHECK(reenabled.started && reenabled.id == 3,
          "disable does not reset the monotonic gesture id");
    state.release(2400);
}

static void testDispatchScopeAndGetterTruthTable() {
    MouseLockState state;
    makeReady(state);
    CHECK(!boxblox::buttonDispatchScopeActive(), "dispatch scope starts inactive");

    CHECK(!state.shouldReportCenter(false, false, false, 0),
          "idle getter keeps original false");
    CHECK(state.shouldReportCenter(true, false, false, 0),
          "original center is preserved");
    CHECK(!state.shouldReportCenter(false, false, false,
                                    boxblox::kMouseBehaviorLockCenter),
          "idle getter ignores engine LockCenter");
    CHECK(!state.shouldReportCenter(false, false, false,
                                    boxblox::kMouseBehaviorLockCurrentPosition),
          "idle getter ignores engine LockCurrentPosition");
    CHECK(!state.shouldReportCenter(false, false, true,
                                    boxblox::kMouseBehaviorDefault),
          "engine-owned capture without a gesture passes through");
    CHECK(!state.shouldReportCenter(false, false, true,
                                    boxblox::kMouseBehaviorLockCurrentPosition),
          "engine-owned capture without a gesture does not fake center");

    state.press(1000);
    CHECK(state.shouldReportCenter(false, false, true,
                                   boxblox::kMouseBehaviorDefault),
          "confirmed capture during a tracked gesture reports true");
    CHECK(state.shouldReportCenter(false, false, false,
                                   boxblox::kMouseBehaviorLockCurrentPosition),
          "held secondary plus engine 2 reports true");
    CHECK(!state.shouldReportCenter(false, false, false,
                                    boxblox::kMouseBehaviorLockCenter),
          "held secondary plus engine 1 leaves original false");
    CHECK(!state.shouldReportCenter(false, false, false,
                                    boxblox::kMouseBehaviorDefault),
          "held secondary plus engine 0 leaves original false");
    CHECK(state.shouldReportCenter(true, false, false,
                                   boxblox::kMouseBehaviorLockCenter),
          "held secondary plus original center stays true");

    boxblox::beginButtonDispatchScope();
    CHECK(boxblox::buttonDispatchScopeActive(), "dispatch scope activates");
    CHECK(!state.shouldReportCenter(false, boxblox::buttonDispatchScopeActive(),
                                    true, boxblox::kMouseBehaviorLockCurrentPosition),
          "dispatch scope suppresses capture gating");
    CHECK(!state.shouldReportCenter(true, boxblox::buttonDispatchScopeActive(),
                                    false, boxblox::kMouseBehaviorLockCenter),
          "dispatch scope suppresses original center for button delegation");

    boxblox::beginButtonDispatchScope();
    CHECK(boxblox::buttonDispatchScopeActive(), "dispatch scope nests");
    boxblox::endButtonDispatchScope();
    CHECK(boxblox::buttonDispatchScopeActive(), "inner end leaves outer scope active");
    boxblox::endButtonDispatchScope();
    CHECK(!boxblox::buttonDispatchScopeActive(), "outer end clears the scope");
    boxblox::endButtonDispatchScope();
    CHECK(!boxblox::buttonDispatchScopeActive(), "unmatched end is harmless");

    CHECK(state.shouldReportCenter(false, false, true,
                                   boxblox::kMouseBehaviorLockCurrentPosition),
          "confirmed capture overrides again after the scope closes");
    state.release(2000);
}

static void testSegmentBoundaries() {
    const Segment segments[] = {
            {0x1000, 0x2000, true},
            {0x3000, 0x3800, false},
    };
    CHECK(boxblox::segmentCovers(segments, 2, 0x1000, 4, true),
          "segment accepts exact start");
    CHECK(boxblox::segmentCovers(segments, 2, 0x1FFC, 4, true),
          "segment accepts a read ending exactly at end");
    CHECK(!boxblox::segmentCovers(segments, 2, 0x2000, 4, true),
          "segment rejects exact end");
    CHECK(!boxblox::segmentCovers(segments, 2, 0x2004, 4, true),
          "segment rejects past end");
    CHECK(!boxblox::segmentCovers(segments, 2, 0x1FFC, 8, true),
          "segment rejects a straddling range");
    CHECK(!boxblox::segmentCovers(segments, 2, 0x0FFC, 8, true),
          "segment rejects a range starting below start");
    CHECK(!boxblox::segmentCovers(segments, 2, 0x2000, 0, true),
          "segment rejects zero-length read exactly at end");
    CHECK(boxblox::segmentCovers(segments, 2, 0x1000, 0x1000, true),
          "segment accepts the whole segment exactly");
    CHECK(!boxblox::segmentCovers(segments, 2, 0x1000, 0x1001, true),
          "segment rejects one byte past the end");
    CHECK(!boxblox::segmentCovers(segments, 2, 0x3000, 4, true),
          "executable requirement rejects a non-exec segment");
    CHECK(boxblox::segmentCovers(segments, 2, 0x3000, 4, false),
          "non-exec read accepts a non-exec segment");
    CHECK(boxblox::segmentCovers(segments, 2, 0x37FC, 4, false),
          "non-exec read accepts an ending-at-end range");
    CHECK(!boxblox::segmentCovers(segments, 2, 0x3800, 4, false),
          "non-exec read rejects exact end");
    CHECK(!boxblox::segmentCovers(segments, 2, 0x1000,
                                  static_cast<std::size_t>(-1), true),
          "huge size is rejected without unsigned underflow");
    CHECK(!boxblox::segmentCovers(segments, 2, UINTPTR_MAX - 1, 4, true),
          "address near the top of the address space is rejected");

    const Segment empty[] = {{0x2000, 0x1000, true}};
    CHECK(!boxblox::segmentCovers(empty, 1, 0x2000, 4, true),
          "malformed segment is ignored");
    CHECK(!boxblox::segmentCovers(nullptr, 0, 0x1000, 4, true),
          "null segment table is rejected");
}

static void testNoEngineWritePath() {
    // The state API accepts the engine value by value and has no attach-field
    // entry point. These compile-time checks pin that shape: constructing a
    // state from an engine pointer is not possible.
    static_assert(!std::is_constructible<MouseLockState, int*>::value,
                  "MouseLockState must not accept an engine field pointer");
    static_assert(!std::is_constructible<MouseLockState, const int*>::value,
                  "MouseLockState must not accept a const engine field pointer");
    static_assert(!HasAttachField<MouseLockState>::value,
                  "MouseLockState must not expose an attach-field API");
    static_assert(!HasFieldAccessor<MouseLockState>::value,
                  "MouseLockState must not expose an engine field accessor");

    const long pageSize = sysconf(_SC_PAGESIZE);
    CHECK(pageSize > 0, "page size available");
    if (pageSize <= 0) {
        return;
    }
    void* mapping = mmap(nullptr, static_cast<std::size_t>(pageSize) * 2,
                         PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(mapping != MAP_FAILED, "canary mapping allocated");
    if (mapping == MAP_FAILED) {
        return;
    }
    auto* words = static_cast<int*>(mapping);
    words[0] = 0x5A5A5A5A;
    words[1] = boxblox::kMouseBehaviorLockCurrentPosition;
    words[2] = static_cast<int>(0xA5A5A5A5u);
    const int protectResult = mprotect(mapping, static_cast<std::size_t>(pageSize) * 2,
                                       PROT_READ);
    CHECK(protectResult == 0, "canary mapping made read-only");
    if (protectResult != 0) {
        (void) munmap(mapping, static_cast<std::size_t>(pageSize) * 2);
        return;
    }
    const int* engineValue = &words[1];
    const int observed = *engineValue;

    MouseLockState state;
    makeReady(state);
    const auto pressed = state.press(100);
    CHECK(pressed.started, "gesture starts while engine mapping is read-only");
    state.movement();
    CHECK(state.shouldReportCenter(false, false, false, observed),
          "getter gate consumes the read-only engine value");
    const auto closed = state.release(200);
    CHECK(closed.has_value() && closed->moves == 1,
          "release works while engine mapping is read-only");
    state.disable(300);

    CHECK(words[0] == 0x5A5A5A5A, "canary word before engine value is untouched");
    CHECK(words[1] == observed, "engine value is untouched");
    CHECK(words[2] == static_cast<int>(0xA5A5A5A5u), "canary word after engine value is untouched");

    (void) munmap(mapping, static_cast<std::size_t>(pageSize) * 2);
}

int main() {
    testDisabledAndNotInstalled();
    testEngineReadFailures();
    testRepeatedPressAndMovement();
    testReleaseWithoutGesture();
    testMovementAfterReleaseExcluded();
    testLifecycleCancellation();
    testDispatchScopeAndGetterTruthTable();
    testSegmentBoundaries();
    testNoEngineWritePath();

    std::fprintf(stderr, "host state tests: %d checks, %d failures\n",
                 g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
