/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <cstdint>
#include <cstdlib>
#include <deque>
#include <set>
#include <string.h>
#include <stdio.h>
#include <unordered_map>
#include <vector>

#include <pthread.h>
#include "PthreadUtils.h"

#include "Exceptions.h"
#include "ExternalRCRef.hpp"
#include "KAssert.h"
#include "Memory.h"
#include "Natives.h"
#include "Runtime.h"
#include "Types.h"
#include "Worker.h"
#include "objc_support/AutoreleasePool.hpp"

using namespace kotlin;

using ExecuteJob = KRef (*)(KRef, ObjHeader**);

extern "C" {

RUNTIME_NORETURN void ThrowWorkerAlreadyTerminated();
RUNTIME_NORETURN void ThrowWrongWorkerOrAlreadyTerminated();
RUNTIME_NORETURN void ThrowFutureInvalidState();
mm::RawExternalRCRef* WorkerExecuteLaunchpad(ExecuteJob job, mm::RawExternalRCRef* jobArgument);
void WorkerExecuteAfterLaunchpad(mm::RawExternalRCRef* job);
void Kotlin_Worker_executeEventLoop(KRef worker);

}  // extern "C"

namespace {

enum class WorkerExceptionHandling {
    kDefault, // Perform the default processing of unhandled exception.
    kIgnore, // Do nothing on exception escaping job unit.
};

} // namespace

namespace {

class Future;

enum {
  INVALID = 0,
  SCHEDULED = 1,
  COMPUTED = 2,
  CANCELLED = 3,
  THROWN = 4
};

enum JobKind {
  JOB_NONE = 0,
  JOB_TERMINATE = 1,
  // Order is important in sense that all job kinds after this one is considered
  // processed for APIs returning request process status.
  JOB_REGULAR = 2,
  JOB_EXECUTE_AFTER = 3,
};

enum class WorkerKind {
  kNative,  // Workers created using Worker.start public API.
  kOther,   // Any other kind of workers.
};

struct Job {
  enum JobKind kind;
  union {
    struct {
      ExecuteJob function;
      mm::RawExternalRCRef* argument;
      Future* future;
    } regularJob;

    struct {
      Future* future;
      bool waitDelayed;
    } terminationRequest;

    struct {
      mm::RawExternalRCRef* operation;
      uint64_t whenExecute;
    } executeAfter;
  };
};

struct JobCompare {
  bool operator() (const Job& lhs, const Job& rhs) const {
    RuntimeAssert(lhs.kind == JOB_EXECUTE_AFTER && rhs.kind == JOB_EXECUTE_AFTER, "Must be delayed jobs");
    return lhs.executeAfter.whenExecute < rhs.executeAfter.whenExecute;
  }
};

// Using multiset instead of regular set, because we compare the jobs only by `whenExecute`.
// So if `whenExecute` of two different jobs is the same, the jobs are considered equivalent,
// and set would simply drop one of them.
typedef std::multiset<Job, JobCompare> DelayedJobSet;

}  // namespace

class Worker : Pinned {
 public:
  Worker(WorkerExceptionHandling exceptionHandling, WorkerKind kind) : kind_(kind), exceptionHandling_(exceptionHandling) {
    kotlin::ThreadStateGuard guard(ThreadState::kNative);
    pthread_mutex_init(&lock_, nullptr);
    pthread_cond_init(&cond_, nullptr);
  }

  ~Worker();

  void startEventLoop();

  void putJob(Job job, bool toFront);
  void putDelayedJob(Job job);

  bool waitDelayed(bool blocking);

  Job getJob(bool blocking);

  KLong checkDelayedLocked();

  bool waitForQueueLocked(KLong timeoutMicroseconds, KLong* remaining);

  RUNTIME_NODEBUG JobKind processQueueElement(bool blocking);

  bool park(KLong timeoutMicroseconds, bool process);

  WorkerExceptionHandling exceptionHandling() const { return exceptionHandling_; }

  WorkerKind kind() const { return kind_; }

  pthread_t thread() const { return thread_; }

  MemoryState* memoryState() { return memoryState_; }

  bool isTerminated();

  void attachToCurrentThread();

 private:
  void setThread(pthread_t thread) {
      // TODO rewrite
    // For workers started using the Worker API, we set thread_ in startEventLoop when calling pthread_create.
    // But we also set thread_ in WorkerInit to handle the main thread and threads calling Kotlin from native code.
    // In this case, thread id will be set twice for workers started by the Worker API.
    // This assert takes this into account.
    RuntimeAssert(thread_ == 0 || pthread_equal(thread_, thread), "Cannot overwrite thread id for worker %p", this);
    thread_ = thread;
  }

  void setMemoryState(MemoryState* state) {
    RuntimeAssert(memoryState_ == nullptr, "MemoryState is already set for the worker %p", this);
    memoryState_ = state;
  }

  WorkerKind kind_;
  std::deque<Job> queue_;
  DelayedJobSet delayed_;
  // Lock and condition for waiting on the queue.
  pthread_mutex_t lock_;
  pthread_cond_t cond_;
  WorkerExceptionHandling exceptionHandling_;
  bool terminated_ = false;
  pthread_t thread_ = 0;
  // MemoryState for worker's thread.
  // We set it in WorkerInit and use to correctly switch thread states in woker's destructor.
  MemoryState* memoryState_ = nullptr;
};

namespace {

void waitInNativeState(pthread_cond_t* cond, pthread_mutex_t* mutex) {
    kotlin::compactObjectPoolInCurrentThread();
    CallWithThreadState<ThreadState::kNative>(pthread_cond_wait, cond, mutex);
}

void waitInNativeState(pthread_cond_t* cond,
          pthread_mutex_t* mutex,
          uint64_t timeoutNanoseconds,
          uint64_t* microsecondsPassed = nullptr) {
    kotlin::compactObjectPoolInCurrentThread();
    CallWithThreadState<ThreadState::kNative>(WaitOnCondVar, cond, mutex, timeoutNanoseconds, microsecondsPassed);
}

KULong pthreadToNumber(pthread_t thread) {
    static_assert(sizeof(pthread_t) <= sizeof(KULong), "Casting pthread_t to ULong will lose data");
    // That's almost std::bit_cast. The latter requires sizeof equality of types.
    KULong result = 0;
    memcpy(&result, &thread, sizeof(pthread_t));
    return result;
}

class Locker {
public:
    explicit Locker(pthread_mutex_t* lock, bool switchThreadState = true) : lock_(lock), switchThreadState_(switchThreadState) {
        if (switchThreadState) {
            kotlin::ThreadStateGuard guard(kotlin::ThreadState::kNative, true);
            pthread_mutex_lock(lock_);
        } else {
            // We may need to create a locker when the current thread is already unregistered in the memory subsystem.
            // For such cases we allow locking without switching or checking the thread state.
            pthread_mutex_lock(lock_);
        }
    }
    Locker(pthread_mutex_t* lock, MemoryState* memoryState) : lock_(lock), memoryState_(memoryState) {
        kotlin::ThreadStateGuard guard(memoryState, kotlin::ThreadState::kNative, true);
        pthread_mutex_lock(lock_);
    }

    ~Locker() {
        kotlin::ThreadStateGuard guard;
        if (switchThreadState_) {
            if (memoryState_ != nullptr) {
                guard = kotlin::ThreadStateGuard(memoryState_, ThreadState::kNative, true);
            } else {
                guard = kotlin::ThreadStateGuard(ThreadState::kNative, true);
            }
        }
        pthread_mutex_unlock(lock_);
    }

private:
    pthread_mutex_t* lock_;
    bool switchThreadState_ = true;
    MemoryState* memoryState_ = nullptr;
};

class Future {
 public:
  Future(KInt id) : state_(SCHEDULED), id_(id) {
    kotlin::ThreadStateGuard guard(ThreadState::kNative);
    pthread_mutex_init(&lock_, nullptr);
    pthread_cond_init(&cond_, nullptr);
  }

  ~Future() {
    {
      Locker locker(&lock_);
      // No one cared to consume result - dispose it.
      result_.reset();
    }
    kotlin::ThreadStateGuard guard(ThreadState::kNative);
    pthread_mutex_destroy(&lock_);
    pthread_cond_destroy(&cond_);
  }

  mm::OwningExternalRCRef consumeResultUnlocked() {
    Locker locker(&lock_);
    while (state_ == SCHEDULED) {
      waitInNativeState(&cond_, &lock_);
    }
    // TODO: maybe use message from exception?
    if (state_ == THROWN)
        ThrowIllegalStateException();
    return std::move(result_);
  }

  void storeResultUnlocked(mm::OwningExternalRCRef result, bool ok);

  void cancelUnlocked(MemoryState* memoryState);

  KInt stateUnlocked() const {
      Locker locker(&lock_);
      return state_;
  }
  // Those are called with the lock taken.
  KInt id() const { return id_; }

 private:
  // State of future execution.
  KInt state_;
  // Integer id of the future.
  KInt id_;
  mm::OwningExternalRCRef result_;
  // Lock and condition for waiting on the future.
  mutable pthread_mutex_t lock_;
  mutable pthread_cond_t cond_;
};

class State {
 public:
  State() {
    kotlin::ThreadStateGuard guard(ThreadState::kNative);
    pthread_mutex_init(&lock_, nullptr);
    pthread_cond_init(&cond_, nullptr);

    currentFutureId_ = 1;
    currentVersion_ = 0;
  }

  ~State() {
    kotlin::ThreadStateGuard guard(ThreadState::kNative);
    // TODO: some sanity check here?
    pthread_mutex_destroy(&lock_);
    pthread_cond_destroy(&cond_);
  }

  Future* addJobToWorkerUnlocked(
      Worker* worker, ExecuteJob jobFunction, mm::OwningExternalRCRef jobArgument, bool toFront) {
    Future* future = nullptr;
    Locker locker(&lock_);

    future = new Future(nextFutureId());
    futures_[future->id()] = future;

    Job job;
    if (jobFunction == nullptr) {
      job.kind = JOB_TERMINATE;
      job.terminationRequest.future = future;
      job.terminationRequest.waitDelayed = !toFront;
    } else {
      job.kind = JOB_REGULAR;
      job.regularJob.function = jobFunction;
      job.regularJob.argument = jobArgument.detach();
      job.regularJob.future = future;
    }

    worker->putJob(job, toFront);

    return future;
  }

  bool executeJobAfterInWorkerUnlocked(Worker* worker, mm::OwningExternalRCRef operation, KLong afterMicroseconds) {
    Locker locker(&lock_); // FIXME what's the point of lock now?

    RuntimeAssert(afterMicroseconds >= 0, "afterMicroseconds cannot be negative");

    Job job;
    job.kind = JOB_EXECUTE_AFTER;
    job.executeAfter.operation = operation.detach();
    if (afterMicroseconds == 0) {
      worker->putJob(job, false);
    } else {
      job.executeAfter.whenExecute = konan::getTimeMicros() + afterMicroseconds;
      worker->putDelayedJob(job);
    }
    return true; // FIXME always true?
  }

  bool scheduleJobInWorkerUnlocked(Worker* worker, mm::OwningExternalRCRef operation) {
      Locker locker(&lock_); // FIXME what's the point of lock now?
      Job job;
      job.kind = JOB_EXECUTE_AFTER;
      job.executeAfter.operation = operation.detach();
      worker->putJob(job, false);
      return true; // FIXME always true?
  }

  // Returns `true` if something was indeed processed.
  bool processQueueUnlocked(Worker* worker) {
      // TODO assert current thread
    JobKind kind = worker->processQueueElement(false);
    return kind != JOB_NONE && kind != JOB_TERMINATE;
  }

  bool parkUnlocked(Worker* worker, KLong timeoutMicroseconds, KBoolean process) {
      // Can only park current worker.
      // TODO assert current thread
      return worker->park(timeoutMicroseconds, process);
  }

  KInt stateOfFutureUnlocked(KInt id) {
    Locker locker(&lock_);
    auto it = futures_.find(id);
    if (it == futures_.end()) return INVALID;
    return it->second->stateUnlocked();
  }

  mm::OwningExternalRCRef consumeFutureUnlocked(KInt id) {
    Future* future = nullptr;
    mm::OwningExternalRCRef result;
    {
      Locker locker(&lock_);
      auto it = futures_.find(id);
      if (it == futures_.end()) {
        // Caller checks [stateOfFutureUnlocked] first, so this code is reachable
        // only when trying to consume future twice concurrently.
        ThrowFutureInvalidState();
      }
      future = it->second;
    }

    result = future->consumeResultUnlocked();

    {
       Locker locker(&lock_);
       auto it = futures_.find(id);
       if (it != futures_.end()) {
         futures_.erase(it);
         delete future;
       }
    }

    return result;
  }

  KBoolean waitForAnyFuture(KInt version, KInt millis) {
    Locker locker(&lock_);
    if (version != currentVersion_) return false;

    if (millis < 0) {
      waitInNativeState(&cond_, &lock_);
      return true;
    }

    uint64_t nsDelta = millis * 1000000LL;
    waitInNativeState(&cond_, &lock_, nsDelta);
    return true;
  }

  void signalAnyFuture() {
    kotlin::AssertThreadState(ThreadState::kNative);
    {
      Locker locker(&lock_);
      currentVersion_++;
    }
    pthread_cond_broadcast(&cond_);
  }

  void signalAnyFuture(MemoryState* memoryState) {
    kotlin::AssertThreadState(memoryState, ThreadState::kNative);
    {
      Locker locker(&lock_, memoryState);
      currentVersion_++;
    }
    pthread_cond_broadcast(&cond_);
  }

  KInt versionToken() {
    Locker locker(&lock_);
    return currentVersion_;
  }

  // All those called with lock taken.
  KInt nextFutureId() { return currentFutureId_++; }

  void destroyWorkerThreadDataUnlocked(Worker* worker) {
      // TODO check if "native" i.e. spawned with thread worker
    pthread_detach(worker->thread());
  }

  void checkNativeWorkersLeakLocked() {
      // FIXME???
//    size_t remainingNativeWorkers = 0;
//    for (const auto& kvp : workers_) {
//      Worker* worker = kvp.second;
//      if (worker->kind() == WorkerKind::kNative) {
//        ++remainingNativeWorkers;
//      }
//    }
//
//    if (remainingNativeWorkers != 0) {
//      konan::consoleErrorf(
//        "Unfinished workers detected, %zu workers leaked!\n"
//        "Use `Platform.isMemoryLeakCheckerActive = false` to avoid this check.\n",
//        remainingNativeWorkers);
//      konan::consoleFlush();
//      std::abort();
//    }
  }

  KULong getWorkerPlatformThreadIdUnlocked(Worker* worker) {
      Locker locker(&lock_);
      return pthreadToNumber(worker->thread());
  }

  OBJ_GETTER0(getActiveWorkers) {
      std::vector<KInt> workers;
      {
          Locker locker(&lock_);

          // TODO
//          workers.reserve(workers_.size());
//          for (auto [id, worker] : workers_) {
//              workers.push_back(id);
//          }
      }
      ObjHolder arrayHolder;
      AllocArrayInstance(theIntArrayTypeInfo, workers.size(), arrayHolder.slot());
      std::copy(workers.begin(), workers.end(), IntArrayAddressOfElementAt(arrayHolder.obj()->array(), 0));
      RETURN_OBJ(arrayHolder.obj());
  }

 private:
  pthread_mutex_t lock_;
  pthread_cond_t cond_;
  std::unordered_map<KInt, Future*> futures_;
  //std::unordered_map<KInt, pthread_t> terminating_native_workers_;
  //KInt currentWorkerId_;
  KInt currentFutureId_;
  KInt currentVersion_;
};

State* theState() {
  static State* state = nullptr;

  if (state != nullptr) {
    return state;
  }

  State* result = new State();

  State* old = __sync_val_compare_and_swap(&state, nullptr, result);
  if (old != nullptr) {
    delete result;
    // Someone else inited this data.
    return old;
  }
  return state;
}

void Future::storeResultUnlocked(mm::OwningExternalRCRef result, bool ok) {
  kotlin::ThreadStateGuard guard(ThreadState::kNative);
  {
    Locker locker(&lock_);
    state_ = ok ? COMPUTED : THROWN;
    result_ = std::move(result);
    // Beware here: although manual clearly says that pthread_cond_broadcast() could be called outside
    // of the taken lock, it's not on macOS (as of 10.13.1). If moved outside of the lock,
    // some notifications are missing.
    pthread_cond_broadcast(&cond_);
  }
  theState()->signalAnyFuture();
}

void Future::cancelUnlocked(MemoryState* memoryState) {
  kotlin::AssertThreadState(memoryState, ThreadState::kNative);
  {
    Locker locker(&lock_, memoryState);
    state_ = CANCELLED;
    result_ = nullptr;
    pthread_cond_broadcast(&cond_);
  }
  theState()->signalAnyFuture(memoryState);
}

// Defined in RuntimeUtils.kt.
extern "C" void ReportUnhandledException(KRef e);

KInt execute(Worker* worker, mm::OwningExternalRCRef jobArgument, ExecuteJob jobFunction) {
  Future* future = theState()->addJobToWorkerUnlocked(worker, jobFunction, std::move(jobArgument), false);
  if (future == nullptr) ThrowWorkerAlreadyTerminated();
  return future->id();
}

void executeAfter(Worker* worker, mm::OwningExternalRCRef job, KLong afterMicroseconds) {
  if (!theState()->executeJobAfterInWorkerUnlocked(worker, std::move(job), afterMicroseconds))
    ThrowWorkerAlreadyTerminated();
}

KBoolean processQueue(Worker* worker) {
   return theState()->processQueueUnlocked(worker);
}

KBoolean park(Worker* worker, KLong timeoutMicroseconds, KBoolean process) {
   return theState()->parkUnlocked(worker, timeoutMicroseconds, process);
}

KInt stateOfFuture(KInt id) {
  return theState()->stateOfFutureUnlocked(id);
}

mm::OwningExternalRCRef consumeFuture(KInt id) {
  return theState()->consumeFutureUnlocked(id);
}

KInt requestTermination(Worker* worker, KBoolean processScheduledJobs) {
  Future* future = theState()->addJobToWorkerUnlocked(
      worker, nullptr, nullptr, /* toFront = */ !processScheduledJobs);
  if (future == nullptr) ThrowWorkerAlreadyTerminated(); // FIXME handle this case properly!!!
  return future->id();
}

KBoolean waitForAnyFuture(KInt version, KInt millis) {
  return theState()->waitForAnyFuture(version, millis);
}

KInt versionToken() {
  return theState()->versionToken();
}

KULong platformThreadId(Worker* worker) {
    return theState()->getWorkerPlatformThreadIdUnlocked(worker);
}

OBJ_GETTER0(activeWorkers) {
    RETURN_RESULT_OF0(theState()->getActiveWorkers);
}

}  // namespace

void WorkerDestroyThreadDataIfNeeded(Worker* worker) {
  theState()->destroyWorkerThreadDataUnlocked(worker);
}

void WaitNativeWorkersTermination() {
    TODO("REMOVE?");
  //theState()->waitNativeWorkersTerminationUnlocked(true, [](KInt worker) { return true; });
}

void WaitNativeWorkerTermination(Worker* wantedWorker) {
    // FIXME check if worker terminated?
    pthread_join(wantedWorker->thread(), nullptr);
}

bool WorkerSchedule(Worker* worker, mm::OwningExternalRCRef job) {
    return theState()->scheduleJobInWorkerUnlocked(worker, std::move(job));
}

Worker::~Worker() {
  RuntimeAssert(pthread_equal(thread(), pthread_self()),
                "Worker destruction must be executed by the worker thread.");
  // Cleanup jobs in the queue.
  for (auto job : queue_) {
      switch (job.kind) {
          case JOB_REGULAR:
              mm::releaseExternalRCRef(job.regularJob.argument);
              mm::disposeExternalRCRef(job.regularJob.argument);
              job.regularJob.future->cancelUnlocked(memoryState_);
              break;
          case JOB_EXECUTE_AFTER: {
              // TODO: what do we do here? Shall we execute them?
              mm::releaseExternalRCRef(job.executeAfter.operation);
              mm::disposeExternalRCRef(job.executeAfter.operation);
              break;
          }
          case JOB_TERMINATE: {
              // TODO: any more processing here?
              job.terminationRequest.future->cancelUnlocked(memoryState_);
              break;
          }
          case JOB_NONE: {
              RuntimeCheck(false, "Cannot be in queue");
              break;
          }
      }
  }

  for (auto job : delayed_) {
      RuntimeAssert(job.kind == JOB_EXECUTE_AFTER, "Must be delayed");
      mm::releaseExternalRCRef(job.executeAfter.operation);
      mm::disposeExternalRCRef(job.executeAfter.operation);
  }

  kotlin::AssertThreadState(memoryState_, ThreadState::kNative);
  pthread_mutex_destroy(&lock_);
  pthread_cond_destroy(&cond_);
}

void Worker::putJob(Job job, bool toFront) {
  kotlin::ThreadStateGuard guard(ThreadState::kNative);
  Locker locker(&lock_);
  if (toFront)
    queue_.push_front(job);
  else
    queue_.push_back(job);
  pthread_cond_signal(&cond_);
}

void Worker::putDelayedJob(Job job) {
  kotlin::ThreadStateGuard guard(ThreadState::kNative);
  Locker locker(&lock_);
  delayed_.insert(job);
  pthread_cond_signal(&cond_);
}

bool Worker::waitDelayed(bool blocking) {
  Locker locker(&lock_);
  if (delayed_.size() == 0) return false;
  if (blocking) waitForQueueLocked(-1, nullptr);
  return true;
}

Job Worker::getJob(bool blocking) {
  Locker locker(&lock_);
  RuntimeAssert(!terminated_, "Must not be terminated");
  if (queue_.size() == 0 && !blocking) return Job { .kind = JOB_NONE, .regularJob = {} };
  waitForQueueLocked(-1, nullptr);
  auto result = queue_.front();
  queue_.pop_front();
  return result;
}

KLong Worker::checkDelayedLocked() {
  if (delayed_.size() == 0) {
    return -1;
  }
  auto it = delayed_.begin();
  auto job = *it;
  RuntimeAssert(job.kind == JOB_EXECUTE_AFTER, "Must be delayed job");
  auto now = konan::getTimeMicros();
  if (job.executeAfter.whenExecute <= now) {
    // Note: `delayed_` is multiset sorted only by `whenExecute`.
    // So using erase(it) instead of erase(job) is crucial,
    // because the latter would remove all the jobs with the same `whenExecute`.
    delayed_.erase(it);
    queue_.push_back(job);
    return 0;
  } else {
    return job.executeAfter.whenExecute - now;
  }
}

bool Worker::waitForQueueLocked(KLong timeoutMicroseconds, KLong* remaining) {
  while (queue_.size() == 0) {
    KLong closestToRunMicroseconds = checkDelayedLocked();
    if (closestToRunMicroseconds == 0) {
        continue;
    }
    if (timeoutMicroseconds >= 0) {
        closestToRunMicroseconds = (timeoutMicroseconds < closestToRunMicroseconds || closestToRunMicroseconds < 0)
          ? timeoutMicroseconds
          : closestToRunMicroseconds;
    }
    if (closestToRunMicroseconds == 0) {
      // Just no wait at all here.
    } else if (closestToRunMicroseconds > 0) {
      // Protect from potential overflow, cutting at 10_000_000 seconds, aka 115 days.
      if (closestToRunMicroseconds > 10LL * 1000 * 1000 * 1000 * 1000)
        closestToRunMicroseconds = 10LL * 1000 * 1000 * 1000 * 1000;
      uint64_t nsDelta = closestToRunMicroseconds * 1000LL;
      uint64_t microsecondsPassed = 0;
      waitInNativeState(&cond_, &lock_, nsDelta, remaining ? &microsecondsPassed : nullptr);
      if (remaining) {
        *remaining = timeoutMicroseconds - microsecondsPassed;
      }
    } else {
      waitInNativeState(&cond_, &lock_);
      if (remaining) *remaining = 0;
    }
    if (timeoutMicroseconds >= 0) return queue_.size() != 0;
  }
  return true;
}

bool Worker::park(KLong timeoutMicroseconds, bool process) {
  {
    Locker locker(&lock_);
    if (terminated_) {
      return false;
    }
    auto arrived = false;
    KLong remaining = timeoutMicroseconds;
    do {
      arrived = waitForQueueLocked(remaining, &remaining);
    } while (remaining > 0 && !arrived);
    if (!process) {
      return arrived;
    }
    if (!arrived) {
      return false;
    }
  }
  return processQueueElement(false) >= JOB_REGULAR;
}

JobKind Worker::processQueueElement(bool blocking) {
  if (terminated_) return JOB_TERMINATE;
  Job job = getJob(blocking);
  switch (job.kind) {
    case JOB_NONE: {
      break;
    }
    case JOB_TERMINATE: {
      if (job.terminationRequest.waitDelayed) {
        if (waitDelayed(blocking)) {
          putJob(job, false);
          return JOB_NONE;
        }
      }
      terminated_ = true;
      job.terminationRequest.future->storeResultUnlocked(nullptr, true);
      break;
    }
    case JOB_EXECUTE_AFTER: {
      try {
          objc_support::AutoreleasePool autoreleasePool;
          WorkerExecuteAfterLaunchpad(job.executeAfter.operation);
      } catch(ExceptionObjHolder& e) {
        switch (exceptionHandling()) {
          case WorkerExceptionHandling::kIgnore: break;
          case WorkerExceptionHandling::kDefault:
              kotlin::ProcessUnhandledException(e.GetExceptionObject());
              break;
        }
      }

      break;
    }
    case JOB_REGULAR: {
      mm::OwningExternalRCRef result;
      bool ok = true;
      try {
          objc_support::AutoreleasePool autoreleasePool;
          result.reset(WorkerExecuteLaunchpad(job.regularJob.function, job.regularJob.argument));
      } catch (ExceptionObjHolder& e) {
        ok = false;
        switch (exceptionHandling()) {
            case WorkerExceptionHandling::kIgnore:
                break;
            case WorkerExceptionHandling::kDefault: // TODO: Pass exception object into the future and do nothing in the default case.
                ReportUnhandledException(e.GetExceptionObject());
                break;
        }
      }
      // Notify the future.
      job.regularJob.future->storeResultUnlocked(std::move(result), ok);
      break;
    }
    default: {
      RuntimeCheck(false, "Must be exhaustive");
    }
  }
  return job.kind;
}

namespace {

void* workerRoutine(void* argument) {
    Worker* worker = reinterpret_cast<Worker*>(argument);
    // TODO revise
    // Only run this routine in the runnable state. The moment between this routine exiting and thread
    // destructors running will be spent in the native state. `Kotlin_deinitRuntimeCallback` ensures
    // that runtime deinitialization switches back to the runnable state.
    kotlin::ThreadStateGuard guard(worker->memoryState(), ThreadState::kRunnable);
    Kotlin_Worker_executeEventLoop();
    return nullptr;
}

} // namespace

void Worker::startEventLoop() {
    kotlin::ThreadStateGuard guard(ThreadState::kNative);
    pthread_create(&thread_, nullptr, workerRoutine, this);
}

bool Worker::isTerminated() {
    kotlin::ThreadStateGuard guard(ThreadState::kNative);
    Locker locker(&lock_);
    return terminated_;
}

void Worker::attachToCurrentThread() {
    kotlin::ThreadStateGuard guard(ThreadState::kNative);
    Locker locker(&lock_);
    setThread(pthread_self());
}


extern "C" {

Worker* Kotlin_Worker_createWorkerInternal(KBoolean reportErrors, KBoolean spawnThread) {
    auto exceptionHandling = reportErrors ? WorkerExceptionHandling::kDefault : WorkerExceptionHandling::kIgnore;
    WorkerKind kind = spawnThread ? WorkerKind::kNative : WorkerKind::kOther;

    Worker* worker = new Worker(exceptionHandling, kind);
    if (spawnThread) {
        worker->startEventLoop();
    }
    return worker;
}

void Kotlin_Worker_destroyWorkerInternal(Worker* worker) {
    // FIXME ensure something? Like terminated?
    delete worker;
}

void Kotlin_Worker_attachToCurrentThreadInternal(Worker* worker) {
    worker->attachToCurrentThread();
}

KBoolean Kotlin_Worker_processQueueElementBlocking(Worker* worker) {
    JobKind result = worker->processQueueElement(true);
    return result == JOB_TERMINATE;
}

KInt Kotlin_Worker_requestTerminationWorkerInternal(Worker* worker, KBoolean processScheduledJobs) {
  return requestTermination(worker, processScheduledJobs);
}

KInt Kotlin_Worker_executeInternal(Worker* worker, mm::RawExternalRCRef* jobArgument, ExecuteJob job) {
  return execute(worker, mm::OwningExternalRCRef(jobArgument), job);
}

void Kotlin_Worker_executeAfterInternal(Worker* worker, mm::RawExternalRCRef* job, KLong afterMicroseconds) {
  executeAfter(worker, mm::OwningExternalRCRef(job), afterMicroseconds);
}

KBoolean Kotlin_Worker_processQueueInternal(Worker* worker) {
  return processQueue(worker);
}

KBoolean Kotlin_Worker_parkInternal(Worker* worker, KLong timeoutMicroseconds, KBoolean process) {
  return park(worker, timeoutMicroseconds, process);
}

KInt Kotlin_Worker_stateOfFuture(KInt id) {
  return stateOfFuture(id);
}

mm::RawExternalRCRef* Kotlin_Worker_consumeFuture(KInt id) {
    return consumeFuture(id).detach();
}

KBoolean Kotlin_Worker_waitForAnyFuture(KInt versionToken, KInt millis) {
  return waitForAnyFuture(versionToken, millis);
}

KInt Kotlin_Worker_versionToken() {
  return versionToken();
}

void Kotlin_Worker_waitTermination(Worker* worker) {
    WaitNativeWorkerTermination(worker);
}

KULong Kotlin_Worker_getPlatformThreadIdInternal(Worker* worker) {
    return platformThreadId(worker);
}

OBJ_GETTER0(Kotlin_Worker_getActiveWorkersInternal) {
    RETURN_RESULT_OF0(activeWorkers);
}

OBJ_GETTER(Kotlin_Worker_invokeCFunction, ExecuteJob job, KRef jobArgument) {
    CurrentFrameGuard guard;
    RETURN_RESULT_OF(job, jobArgument);
}

}  // extern "C"
