#pragma once

#include "GCUtils.hpp"
#include "MarkTraits.hpp"
#include "MarkAndSweepUtils.hpp"

namespace kotlin::gc::internal {

struct GCTraits {
    using Mark = MarkQueue;

    static void beforeSTW(GCData<GCTraits>&, GCHandle) {}
    static void mark(GCData<GCTraits>& gcData, GCHandle gcHandle) {
        MarkQueue& markQueue = gcData.mark_;
        gc::collectRootSet<internal::MarkTraits>(gcHandle, markQueue, [](mm::ThreadData &) { return true; });
        gc::Mark<internal::MarkTraits>(gcHandle, markQueue);
        gc::processWeaks<DefaultProcessWeaksTraits>(gcHandle, mm::ExternalRCRefRegistry::instance());
    }

    static bool concurrentSweep() {
        return false;
    }
};

}