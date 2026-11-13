// Host-testable mouse-lock gesture accounting and getter gating.
//
// This file deliberately has no JNI, Android, logging or engine dependencies.
// It never stores a pointer into engine memory: the engine answer arrives as an
// int by value, so no code path here can write engine state. The guest glue
// (guest_native.cpp) owns the read-only pointer chain and passes values in.
//
// Semantics implemented here, per the mouse-lock correction contract:
//
// 1. A gesture starts on the first native secondary-button DOWN while the
//    enhancement is enabled, supported and installed. Repeated DOWN while the
//    gesture is active keeps the same id, start time and movement counter.
// 2. Movement is counted only while that gesture is active. Release, lifecycle
//    cancellation and disable close it once.
// 3. The getter override is: dispatch scope -> false; confirmed Android capture
//    -> true; otherwise original engine answer, or true while the tracked
//    secondary gesture is active and the current engine behavior is
//    LockCurrentPosition (2). Disabled, unsupported or not-installed keeps the
//    original answer exactly.
// 4. No timeout, no motion-silence inference and no engine writes exist here.

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <optional>

namespace boxblox {

constexpr int kMouseBehaviorDefault = 0;
constexpr int kMouseBehaviorLockCenter = 1;
constexpr int kMouseBehaviorLockCurrentPosition = 2;
constexpr int kMouseBehaviorUnavailable = -1;

// Accept only the engine values that have meaning for this build. Any raw
// value outside 0..2 (including the -1 unavailable/dead-read sentinel) becomes
// -1 and never reaches the getter gate as a valid engine value.
constexpr int normalizeEngineBehavior(int raw) noexcept {
    return (raw >= kMouseBehaviorDefault && raw <= kMouseBehaviorLockCurrentPosition)
            ? raw
            : kMouseBehaviorUnavailable;
}

// One executable image segment. start/end are the half-open interval
// [start, end); segmentCovers rejects addresses at or past end.
struct Segment {
    std::uintptr_t start = 0;
    std::uintptr_t end = 0;
    bool exec = false;
};

// Range check for a fixed-offset read. The address is accepted only when the
// whole [addr, addr + size) range is inside one segment. addr == end and
// addr > end are rejected, as is a range that starts below start or straddles
// the end. All comparisons happen before any subtraction, so a huge size or an
// address past the end cannot underflow.
inline bool segmentCovers(const Segment* segments, std::size_t count,
                          std::uintptr_t addr, std::size_t size,
                          bool execRequired) {
    if (segments == nullptr) {
        return false;
    }
    for (std::size_t i = 0; i < count; ++i) {
        const Segment& segment = segments[i];
        if (segment.end <= segment.start) {
            continue;
        }
        if (execRequired && !segment.exec) {
            continue;
        }
        if (addr < segment.start || addr >= segment.end) {
            continue;
        }
        const std::uintptr_t available = segment.end - addr;
        if (static_cast<std::uintptr_t>(size) > available) {
            continue;
        }
        return true;
    }
    return false;
}

// Balanced thread-local scope around uncaptured mouse BUTTON_PRESS/RELEASE
// delegation. The glue's getter hook returns false while this is active so the
// original guest handler processes the button instead of requesting capture and
// dropping it. An unmatched end is harmless.
namespace dispatch_scope {
inline thread_local int depth = 0;
}  // namespace dispatch_scope

inline void beginButtonDispatchScope() {
    ++dispatch_scope::depth;
}

inline void endButtonDispatchScope() {
    if (dispatch_scope::depth > 0) {
        --dispatch_scope::depth;
    }
}

inline bool buttonDispatchScopeActive() {
    return dispatch_scope::depth > 0;
}

struct GestureSummary {
    std::int64_t id = 0;
    std::int64_t startedNanos = 0;
    std::int64_t endedNanos = 0;
    long moves = 0;
};

struct PressOutcome {
    bool started = false;
    std::int64_t id = 0;
    std::int64_t startedNanos = 0;
};

class MouseLockState {
public:
    MouseLockState() = default;

    MouseLockState(const MouseLockState&) = delete;
    MouseLockState& operator=(const MouseLockState&) = delete;

    void setEnabled(bool enabled) noexcept {
        enabled_.store(enabled, std::memory_order_release);
    }

    void setHookReady(bool ready) noexcept {
        hook_ready_.store(ready, std::memory_order_release);
    }

    void setSupported(bool supported) noexcept {
        supported_.store(supported, std::memory_order_release);
    }

    // Enabled, supported and installed together. This is the gate for both
    // gesture accounting and the getter override.
    bool enhancementReady() const noexcept {
        return enabled_.load(std::memory_order_acquire)
                && hook_ready_.load(std::memory_order_acquire)
                && supported_.load(std::memory_order_acquire);
    }

    // Native secondary-button DOWN. Returns the gesture identity. A repeated
    // DOWN while active returns the existing gesture with started == false and
    // does not reset its id, start time or movement count.
    PressOutcome press(std::int64_t nowNanos) noexcept {
        std::lock_guard<std::mutex> guard(mutex_);
        if (!enhancementReady()) {
            return {};
        }
        if (active_.load(std::memory_order_relaxed)) {
            return {false, active_id_.load(std::memory_order_relaxed),
                    started_nanos_.load(std::memory_order_relaxed)};
        }
        const std::int64_t id = next_id_++;
        active_id_.store(id, std::memory_order_relaxed);
        started_nanos_.store(nowNanos, std::memory_order_relaxed);
        moves_.store(0, std::memory_order_relaxed);
        active_.store(true, std::memory_order_release);
        return {true, id, nowNanos};
    }

    // Observed native secondary-button UP. Closes the active gesture, if any.
    std::optional<GestureSummary> release(std::int64_t nowNanos) noexcept {
        return close(nowNanos);
    }

    // Lifecycle cancellation (pause, focus loss, F1, detach, install loss).
    // Closes the active gesture, if any, with the same once-only semantics.
    std::optional<GestureSummary> cancel(std::int64_t nowNanos) noexcept {
        return close(nowNanos);
    }

    // Feature toggle. Disabling closes tracking and returns the closed gesture
    // so the caller can report it; enabling only changes the gate. The id
    // counter is never reset.
    std::optional<GestureSummary> disable(std::int64_t nowNanos) noexcept {
        enabled_.store(false, std::memory_order_release);
        return close(nowNanos);
    }

    // Relative movement entry. Only a movement belonging to an active gesture
    // is counted; no logging, allocation or engine access happens here.
    void movement() noexcept {
        if (active_.load(std::memory_order_acquire)) {
            moves_.fetch_add(1, std::memory_order_relaxed);
        }
    }

    bool gestureActive() const noexcept {
        return active_.load(std::memory_order_acquire);
    }

    std::int64_t activeGestureId() const noexcept {
        return active_id_.load(std::memory_order_acquire);
    }

    long activeMovementCount() const noexcept {
        return moves_.load(std::memory_order_acquire);
    }

    // Getter truth table. The caller only invokes this while the getter hook is
    // installed; this function still checks readiness so a disabled,
    // unsupported or not-installed enhancement is an exact original pass.
    bool shouldReportCenter(bool originalCenter, bool dispatchActive,
                            bool confirmedCapture, int engineBehavior) const noexcept {
        if (!enhancementReady()) {
            return originalCenter;
        }
        if (dispatchActive) {
            return false;
        }
        // Confirmed capture only keeps the guest's captured listener
        // forwarding while a tracked secondary gesture is active. With no
        // gesture the capture belongs to the engine (shift lock, first
        // person), so the original answer must pass through: the engine's
        // captured listener releases capture itself once its own state says
        // it no longer wants the lock. Forcing true here left shift lock
        // capture stuck until another right-drag.
        if (confirmedCapture && gestureActive()) {
            return true;
        }
        return originalCenter
                || (gestureActive()
                    && engineBehavior == kMouseBehaviorLockCurrentPosition);
    }

private:
    std::optional<GestureSummary> close(std::int64_t nowNanos) noexcept {
        std::lock_guard<std::mutex> guard(mutex_);
        if (!active_.exchange(false, std::memory_order_acq_rel)) {
            return std::nullopt;
        }
        GestureSummary summary;
        summary.id = active_id_.load(std::memory_order_relaxed);
        summary.startedNanos = started_nanos_.load(std::memory_order_relaxed);
        summary.endedNanos = nowNanos;
        summary.moves = moves_.load(std::memory_order_relaxed);
        return summary;
    }

    mutable std::mutex mutex_;
    std::atomic<bool> enabled_{false};
    std::atomic<bool> hook_ready_{false};
    std::atomic<bool> supported_{false};
    std::atomic<bool> active_{false};
    std::atomic<std::int64_t> active_id_{0};
    std::atomic<std::int64_t> started_nanos_{0};
    std::atomic<long> moves_{0};
    std::int64_t next_id_ = 1;  // guarded by mutex_
};

}  // namespace boxblox
