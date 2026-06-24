/*
 * Copyright 2010-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "concurrent/ParallelProcessor.hpp"

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include <atomic>
#include <cstdint>
#include <list>
#include <optional>
#include <vector>

#include "IntrusiveList.hpp"
#include "SingleThreadExecutor.hpp"
#include "TestSupport.hpp"

using ::testing::_;
using namespace kotlin;

namespace {

struct Task {
    template <typename WorkList>
    static void workLoop(WorkList& workList) {
        while (Task* task = workList.tryPop()) {
            RuntimeAssert(!task->done_.load(), "Tasks are not idempotent");
            task->done_ = true;
        }
    }

    Task* next() const noexcept { return next_; }

    void setNext(Task* next) noexcept {
        RuntimeAssert(next, "next cannot be nullptr");
        next_ = next;
    }

    bool trySetNext(Task* next) noexcept {
        RuntimeAssert(next, "next cannot be nullptr");
        if (next_ == nullptr) {
            next_ = next;
            return true;
        }
        return false;
    }

    std::atomic<bool> done_ = false;
    Task* next_ = nullptr;
};

auto createWork(std::size_t size) {
    std::list<Task> batch;
    for (size_t i = 0; i < size; ++i) {
        batch.emplace_back();
    }
    return batch;
}

template <typename WorkList, typename Iterable>
void offerWork(WorkList& wl, Iterable& batch) {
    for (auto& task : batch) {
        bool accepted = wl.tryPush(task);
        RuntimeAssert(accepted, "Must be accepted");
    }
}

using ListImpl = intrusive_forward_list<Task>;
constexpr auto kBatchSize = 256;
constexpr auto kBatchPoolSize = 4;
using Processor = ParallelProcessor<ListImpl, kBatchSize, kBatchPoolSize>;
using Worker = typename Processor::Worker;
using WorkSource = typename Processor::WorkSource;

// BoundedQueue::dequeue() is permitted to report "empty" while a batch is actually
// present: on a weak memory model its acquire-load of the head cell's sequence_ may
// lag the producer's release-store. That stale read is the root of KT-86432, but on
// a strongly-ordered host (e.g. Apple silicon) it is practically unhittable by
// timing. StaleReadBoundedQueue reproduces it on demand: while staleReadEveryN is
// N > 0, every N-th dequeue() returns nothing.
std::atomic<std::uint64_t> staleReadEveryN{0};

template <typename T, std::size_t kCapacity>
class StaleReadBoundedQueue : public BoundedQueue<T, kCapacity> {
public:
    std::optional<T> dequeue() {
        if (std::uint64_t n = staleReadEveryN.load(std::memory_order_relaxed)) {
            if (dequeueCount_.fetch_add(1, std::memory_order_relaxed) % n == 0) {
                return std::nullopt;
            }
        }
        return BoundedQueue<T, kCapacity>::dequeue();
    }

private:
    std::atomic<std::uint64_t> dequeueCount_{0};
};

// Small batches keep many batches in flight, so dequeue() (and the injected stale
// read) fire frequently.
constexpr auto kStressBatchSize = 4;
constexpr auto kStressBatchPoolSize = 64; // power of 2, as BoundedQueue suggests
using StressProcessor = ParallelProcessor<ListImpl, kStressBatchSize, kStressBatchPoolSize, StaleReadBoundedQueue>;
using StressWorker = typename StressProcessor::Worker;

} // namespace

TEST(ParallelProcessorTest, ContededRegistration) {
    Processor processor;
    std::vector<std::unique_ptr<Worker>> workers(kDefaultThreadCount);

    std::atomic<bool> start = false;
    std::list<ScopedThread> threads;
    for (int i = 0; i < kDefaultThreadCount; ++i) {
        threads.emplace_back([i, &start, &workers, &processor] {
            while (!start.load()) {
            }
            workers[i] = std::make_unique<Worker>(processor);
        });
    }

    start = true;

    for (auto& t : threads) {
        t.join();
    }

    EXPECT_THAT(processor.registeredWorkers(), kDefaultThreadCount);

    workers.clear();
}

TEST(ParallelProcessorTest, Sharing) {
    Processor processor;

    Worker giver(processor);
    Worker taker(processor);
    EXPECT_THAT(processor.registeredWorkers(), 2);

    auto twoBatches = createWork(kBatchSize * 2);
    offerWork(giver, twoBatches);

    EXPECT_TRUE(taker.retainsNoWork());

    // have to steal from giver
    EXPECT_NE(taker.tryPop(), nullptr);

    EXPECT_FALSE(taker.retainsNoWork());
    taker.clear();
    giver.clear();
}

TEST(ParallelProcessorTest, SharingFromNonWorkerSource) {
    Processor processor;

    WorkSource giver(processor);
    EXPECT_THAT(processor.registeredWorkers(), 0);

    Worker taker(processor);
    EXPECT_THAT(processor.registeredWorkers(), 1);

    auto work = createWork(kBatchSize * 2);
    offerWork(giver, work);

    EXPECT_TRUE(taker.retainsNoWork());

    // have to steal from giver
    EXPECT_NE(taker.tryPop(), nullptr);

    EXPECT_FALSE(taker.retainsNoWork());
    taker.clear();
    giver.clear();
}

TEST(ParallelProcessorTest, Overflow) {
    Processor processor;
    Worker worker(processor);

    auto workSize = kBatchSize * (kBatchPoolSize + 2);
    auto work = createWork(workSize);
    offerWork(worker, work);

    std::size_t poppedCount = 0;
    while (worker.tryPop() != nullptr) {
        ++poppedCount;
    }
    EXPECT_THAT(poppedCount, workSize);
    EXPECT_THAT(worker.retainsNoWork(), true);
}

TEST(ParallelProcessorTest, ForceFlush) {
    Processor processor;

    WorkSource source(processor);

    auto workSize = kBatchSize / 2;
    auto halfBatch = createWork(workSize);
    offerWork(source, halfBatch);

    EXPECT_THAT(source.forceFlush(), true);
    EXPECT_THAT(source.retainsNoWork(), true);

    Worker checker(processor);
    std::size_t poppedCount = 0;
    while (checker.tryPop() != nullptr) {
        ++poppedCount;
    }
    EXPECT_THAT(poppedCount, workSize);
}

TEST(ParallelProcessorTest, ForceFlushWithOverflow) {
    Processor processor;

    // Fill up the processor's work pool
    Worker overflower(processor);

    auto poolSizeBatches = createWork(kBatchSize * kBatchPoolSize);
    offerWork(overflower, poolSizeBatches);

    EXPECT_THAT(overflower.forceFlush(), true);

    // No overflow a local work source
    WorkSource source(processor);

    auto twoBatches = createWork(kBatchSize * 2);
    offerWork(source, twoBatches);

    // no space to flush into
    EXPECT_THAT(source.forceFlush(), false);

    // drain the processor's pool
    while (overflower.tryPop() != nullptr) {}

    // now can flush
    EXPECT_THAT(source.forceFlush(), true);
    EXPECT_THAT(source.retainsNoWork(), true);
}

TEST(ParallelProcessorTest, NoWorkStrandedOnTermination) {
    // With dequeue() injecting the KT-86432 stale read, a worker can see the shared
    // queue as empty while batches remain. If it is the last worker going to sleep,
    // it declares termination (sets allDone_) and the still-queued work is never
    // processed -- which is what this test catches.
    staleReadEveryN.store(4, std::memory_order_relaxed);
    struct SeamReset {
        ~SeamReset() { staleReadEveryN.store(0, std::memory_order_relaxed); }
    } seamReset;

    constexpr int kWorkers = 4;
    constexpr std::size_t kSeedBatches = 48; // must be <= kStressBatchPoolSize
    constexpr std::size_t kSeedTasks = kSeedBatches * kStressBatchSize;
    constexpr int kIterations = 500;

    for (int iteration = 0; iteration < kIterations; ++iteration) {
        auto tasks = createWork(kSeedTasks);
        std::atomic<int> processed{0};
        std::atomic<bool> processedTwice{false};
        StressProcessor processor;

        // Seed all the work into the shared queue up front, from a non-worker
        // source, so the registered workers are exactly the draining threads.
        {
            StressProcessor::WorkSource source(processor);
            offerWork(source, tasks);
            RuntimeAssert(source.forceFlush(), "seed must fit into the shared pool");
        }

        std::atomic<bool> start{false};
        std::list<ScopedThread> threads;
        for (int w = 0; w < kWorkers; ++w) {
            threads.emplace_back([&] {
                StressWorker worker(processor);
                while (!start.load(std::memory_order_acquire)) {
                }
                while (Task* task = worker.tryPop()) {
                    if (task->done_.exchange(true, std::memory_order_relaxed)) {
                        processedTwice.store(true, std::memory_order_relaxed);
                    }
                    processed.fetch_add(1, std::memory_order_relaxed);
                }
            });
        }
        start.store(true, std::memory_order_release);
        for (auto& t : threads) {
            t.join();
        }

        ASSERT_FALSE(processedTwice.load()) << "iteration " << iteration;
        ASSERT_EQ(processed.load(), static_cast<int>(kSeedTasks))
                << "Work stranded by premature termination at iteration " << iteration << "; "
                << (static_cast<int>(kSeedTasks) - processed.load()) << " task(s) never processed";
    }
}
