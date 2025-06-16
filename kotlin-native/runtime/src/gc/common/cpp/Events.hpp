#pragma once

#include <array>
#include <mutex>
#include "Utils.hpp"

namespace kotlin::gc {

    enum class GCState {
        // PMCS
        kIdle,

        kPreMark,
        kMarkReady,
        kMarkSTW,

        kRS,
        kRSGlobal,

        kMarkParallel,

        kWeakSweepBarriers,
        kConcurrentWeakSweep,
        kSTW2,
        kPrepareAllocator,
        kResumeWorld,
        kSweep,
        kScheduleFinalization,

        kSIZE
    };

    enum class MutatorEvent {
        kCreateWorker,
        kFailLockRS,

        kRSLock,
        kRSPublish,
        kRSAddStack,
        kRSAddTLS,

        kRSForeignLock,
        kRSForeignPublish,
        kRSForeignAddStack,
        kRSForeignAddTLS,

        kMarkParallel,
        // TODO sweep

        kSweepPage,

        kAllocObj,
        kAllocArray,
        kLoad,
        kStore,

        kAttach,
        kDetach,

        kNative,
        kRunnable,

        kTryRef,

        kSIZE
    };

    template<typename T, typename S>
    using array = std::array<T, static_cast<size_t>(S::kSIZE)>;

    class EventsCounter : Pinned {
    public:
        ~EventsCounter() {
            printCounters();
        }
        void gcEvent(GCState gcEvent) {
            std::lock_guard lock(mutex_);
            gcState_ = gcEvent;
        }
        void mutatorEvent(MutatorEvent mutatorEvent) {
            std::lock_guard lock(mutex_);
            counters_[static_cast<size_t>(gcState_)][static_cast<size_t>(mutatorEvent)] += 1;
        }
        void printCounters() {
            std::lock_guard lock(mutex_);
            for (size_t i = 0; i < static_cast<size_t>(GCState::kSIZE); ++i) {
                for (size_t j = 0; j < static_cast<size_t>(MutatorEvent::kSIZE); ++j) {
                    fprintf(stderr, "# GCEVENT(%ld, %ld) = %ld\n", i, j, counters_[i][j]);
                }
            }
        }
    private:
        std::mutex mutex_;
        GCState gcState_ = GCState::kIdle;
        //std::array<std::array<size_t, static_cast<size_t>(MutatorEvent::kSIZE)>, static_cast<size_t>(GCState::kSIZE)> counters_{};
        array<array<size_t, MutatorEvent>, GCState> counters_{};
    };

    void gcEvent(GCState gcEvent);
    void mutatorEvent(MutatorEvent mutatorEvent);
}