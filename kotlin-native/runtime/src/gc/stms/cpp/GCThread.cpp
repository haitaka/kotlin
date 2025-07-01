/*
 * Copyright 2010-2025 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "GCThread.hpp"

#include "Allocator.hpp"
#include "GCScheduler.hpp"
#include "GCTraits.hpp"
#include "Logging.hpp"
#include "ThreadSuspension.hpp"

using namespace kotlin;

gc::internal::GCThread::GCThread(GCStateHolder& state, alloc::Allocator& allocator, gcScheduler::GCScheduler& gcScheduler) noexcept :
    gcData_(state, allocator, gcScheduler, markQueue_), thread_(std::string_view("GC thread"), [this] { body(); }) {}

void gc::internal::GCThread::body() noexcept {
    while (true) {
        if (auto epoch = gcData_.state_.waitScheduled()) {
            PerformFullGC(*epoch);
        } else {
            break;
        }
    }
}

void gc::internal::GCThread::PerformFullGC(int64_t epoch) noexcept {
    kotlin::gc::internal::PerformFullGC<GCTraits>(gcData_, epoch);

}
