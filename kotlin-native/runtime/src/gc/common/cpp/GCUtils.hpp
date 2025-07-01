#pragma once

#include "Allocator.hpp"
#include "GCScheduler.hpp"
#include "GCStatistics.hpp"
#include "Logging.hpp"
#include "MarkAndSweepUtils.hpp"
#include "RootSet.hpp"
#include "ThreadData.hpp"
#include "ThreadRegistry.hpp"
#include "ThreadSuspension.hpp"

// TODO it's a temporary place for common GC routines

namespace kotlin::gc::internal {

template<typename GCTraits>
struct GCData : private MoveOnly {

    GCData(GCStateHolder &state,
           alloc::Allocator &allocator,
           gcScheduler::GCScheduler &gcScheduler,
    )
            : state_(state),
              allocator_(allocator),
              gcScheduler_(gcScheduler) {}
    GCStateHolder& state_;
    alloc::Allocator& allocator_;
    gcScheduler::GCScheduler& gcScheduler_;
    typename GCTraits::Mark mark_{};
};

// FIXME GCStrategy?
template<typename GCTraits>
void PerformFullGC(GCData<GCTraits>& gcData, int64_t epoch) noexcept {
    auto mainGCLock = mm::GlobalData::Instance().gc().gcLock();

    auto gcHandle = GCHandle::create(epoch);

    GCTraits::beforeSTW(gcData, gcHandle);
    // PMCS
    // markDispatcher_.beginMarkingEpoch(gcHandle);
    // GCLogDebug(epoch, "Main GC requested marking in mutators");
    // STWMS
    // ---

    stopTheWorld(gcHandle, "GC stop the world #1: mark");

    gcData.gcScheduler_.onGCStart();

    gcData.state_.start(epoch);

    GCTraits::mark(gcData, gcHandle);
    // PMCS
    // markDispatcher_.runMainInSTW();
    // markDispatcher_.endMarkingEpoch();
    // STWMS
    // gc::collectRootSet<internal::MarkTraits>(gcHandle, markQueue_, [](mm::ThreadData&) { return true; });
    // gc::Mark<internal::MarkTraits>(gcHandle, markQueue_);

    // TODO PMCS
    // if (compiler::concurrentWeakSweep()) {
    //     // Expected to happen inside STW.
    //     gc::EnableWeakRefBarriers(epoch);
    //     resumeTheWorld(gcHandle);
    // }

    // TODO common
    //gc::processWeaks<DefaultProcessWeaksTraits>(gcHandle, mm::ExternalRCRefRegistry::instance());

    // TODO PMCS
    // if (compiler::concurrentWeakSweep()) {
    //     stopTheWorld(gcHandle, "GC stop the world #2: prepare heap for sweep");
    //     gc::DisableWeakRefBarriers();
    // }

    // TODO outline as mark_.isolateMarkedHeapAndFinishMark()
    // By this point all the alive heap must be marked.
    // All the mutations (incl. allocations) after this method will be subject for the next GC.
    // This should really be done by each individual thread while waiting
    for (auto& thread : kotlin::mm::ThreadRegistry::Instance().LockForIter()) {
        thread.allocator().prepareForGC();
    }
    gcData.allocator_.prepareForGC();

    if (GCTraits::concurrentSweep()) {
        resumeTheWorld(gcHandle);
    }

    gcData.allocator_.sweep(gcHandle);
    gcData.gcScheduler_.onGCFinish(epoch, gcHandle.getKeptSizeBytes());

    if (!GCTraits::concurrentSweep()) {
         resumeTheWorld(gcHandle);
    }

    gcData.state_.finish(epoch);
    gcHandle.finished();

    // This may start a new thread. On some pthreads implementations, this may block waiting for concurrent thread
    // destructors running. So, it must ensured that no locks are held by this point.
    // TODO: Consider having an always on sleeping finalizer thread.
    gcData.allocator_.scheduleFinalization(gcHandle);
}

} // namespace kotlin::gc::internal