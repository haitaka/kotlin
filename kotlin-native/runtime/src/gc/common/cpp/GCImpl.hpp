/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#pragma once

#include <cstddef>

#include "Barriers.hpp"
#include "GC.hpp"
#include "GCState.hpp"
#include "GCUtils.hpp"
#include "GCThread.hpp"
#include "ThreadData.hpp"

namespace kotlin::gc {

template<typename GCTraits>
class GCImpl : private Pinned {
public:

    class ThreadData : private Pinned {
    public:
        ThreadData(GCImpl& gc, mm::ThreadData& threadData) noexcept {}

        ~ThreadData();

        void OnSuspendForGC() noexcept;

        void safePoint() noexcept;

        void onThreadRegistration() noexcept;

        void onAllocation(ObjHeader* object) noexcept;

        typename GCTraits::Barriers::ThreadData barriers_{};
        // TODO
        //mark::ParallelMarkThreadData markDispatcher_;
    };

    // Header to be placed before each heap object. GC will use this to keep its data if needed.
    // This is used via `type_layout::descriptor_t`, which is specialized below.
    // If GC doesn't need any data, it can make `size()` return 0 and `alignment()`
    // return 1.
    // Note: GC does not deinitialize `ObjectData`, so the implementations must ensure that
    //       the destructor is a trivial one.
    // TODO class ObjectData;

    GCImpl(alloc::Allocator& allocator, gcScheduler::GCScheduler& gcScheduler) noexcept;
    ~GCImpl();


    void ClearForTests() noexcept;

    static void processObjectInMark(void* state, ObjHeader* object) noexcept;
    static void processArrayInMark(void* state, ArrayHeader* array) noexcept;

    // TODO: These should exist only in the scheduler.
    int64_t Schedule() noexcept;
    void WaitFinished(int64_t epoch) noexcept;
    void WaitFinalizers(int64_t epoch) noexcept;

    void onEpochFinalized(int64_t epoch) noexcept;

    static void beforeHeapRefUpdate(mm::DirectRefAccessor ref, ObjHeader* value, bool loadAtomic) noexcept;
    static OBJ_GETTER(weakRefReadBarrier, std_support::atomic_ref<ObjHeader*> weakReferee) noexcept;

    static bool isMarked(ObjHeader* object) noexcept;

    // This will drop the mark bit if it was set and return `true`.
    // If the mark bit was unset, this will return `false`.
    static bool tryResetMark(GC::ObjectData& objectData) noexcept;

private:
    std::unique_ptr<Impl> impl_;
    ThreadStateAware<std::mutex> gcLock_{};


    GCImpl(alloc::Allocator& allocator, gcScheduler::GCScheduler& gcScheduler) noexcept :
        gcData_(state_, allocator, gcScheduler)
        {}

    GCStateHolder state_;
    internal::GCData<GCTraits> gcData_;
//    internal::MainGCThread mainThread_;
//    internal::AuxiliaryGCThreads auxThreads_;
};


// TODO
//namespace barriers {
//class ExternalRCRefReleaseGuard::Impl {};
//} // namespace barriers

} // namespace kotlin::gc
