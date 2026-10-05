#pragma once
#include <atomic>
#include <cstdint>
#include <mutex>
// Same lock linearizes admission, prepare and Stop. A ticket is never refreshed.
struct RequestEpoch {
    std::mutex mutex;
    std::uint64_t epoch = 0;
    std::atomic<bool> cancelled{false};
    std::uint64_t admit() { std::lock_guard<std::mutex> guard(mutex); return epoch; }
    void cancel() { std::lock_guard<std::mutex> guard(mutex); ++epoch; cancelled.store(true); }
    bool prepare(std::uint64_t expected) {
        std::lock_guard<std::mutex> guard(mutex);
        if (expected != epoch) return false;
        cancelled.store(false);
        return true;
    }
    bool begin(std::uint64_t expected) {
        std::lock_guard<std::mutex> guard(mutex);
        return expected == epoch && !cancelled.load();
    }
};
