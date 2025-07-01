#pragma once

#include "Barriers.hpp"
#include "GCUtils.hpp"
#include "ParallelMark.hpp"
#include "MarkAndSweepUtils.hpp"

namespace kotlin::gc::internal {

struct GCTraits {
    using Mark = mark::ParallelMark;
    using Barriers = Barriers;

    static void beforeSTW(GCData<GCTraits>& gcData, GCHandle gcHandle) {
        gcData.mark_.beginMarkingEpoch(gcHandle);
        // TODO hide inside beginMarkingEpoch?
        GCLogDebug(gcHandle.getEpoch(), "Main GC requested marking in mutators");
    }
    static void mark(GCData<GCTraits>& gcData, GCHandle gcHandle) {
         gcData.mark_.runMainInSTW();
         gcData.mark_.endMarkingEpoch();
         if (compiler::concurrentWeakSweep()) {
             // Expected to happen inside STW.
             Barriers::EnableWeakRefBarriers(gcHandle.getEpoch());
             resumeTheWorld(gcHandle);
         }

        gc::processWeaks<DefaultProcessWeaksTraits>(gcHandle, mm::ExternalRCRefRegistry::instance());

         if (compiler::concurrentWeakSweep()) {
             stopTheWorld(gcHandle, "GC stop the world #2: prepare heap for sweep");
             Barriers::DisableWeakRefBarriers();
         }
    }

    static bool concurrentSweep() {
        return true;
    }
};

}