#include "CrashHandler.hpp"
#include "KAssert.h"
#include <csignal>
#include <cstdlib>
#include <csetjmp>
#include <cstdio>
#include <execinfo.h>
#include <array>
#include <mutex>
#include "StackTrace.hpp"
#include "std_support/Span.hpp"
#include "Porting.h"
#include "Logging.hpp"

namespace {
    constexpr int kSignals[] = {
            //SIGABRT,
            SIGBUS,
            SIGFPE,
            SIGILL,
            SIGSEGV,
            //SIGTRAP
    };
    constexpr auto kNumSignals = std::size(kSignals);
    struct sigaction prevActions[kNumSignals];

//    jmp_buf jumpBuffer;
    std::array<void*, 1024> stackTraceBuf{};
    int stackTraceSize = 0;
    // TODO
    //std::mutex mutex{};

    [[maybe_unused]] void printStackTrace() {
        //std::lock_guard lock(mutex);
        //RuntimeFail("Caught smth :c");
        auto trace = kotlin::std_support::span<void* const>(stackTraceBuf.data(), stackTraceSize);
        auto stackTraceStrings = kotlin::GetStackTraceStrings(trace);
        for (auto& frame : stackTraceStrings) {
            konan::consoleErrorUtf8(frame.c_str(), frame.size());
            konan::consoleErrorf("\n");
        }
        abort();
    }

    void CrashRecoverySignalHandler(int signal) {
        RuntimeLogInfo({kotlin::logging::Tag::kLogging}, "Handling %ud", signal);
        // TODO llvm checks for context here

        // unblock the signal handling
        sigset_t sigMask;
        sigemptyset(&sigMask);
        sigaddset(&sigMask, signal);
        sigprocmask(SIG_UNBLOCK, &sigMask, nullptr);

        RuntimeFail("Something went wrong: signal = %d", signal);
        int framesNumber = backtrace(stackTraceBuf.data(), 1024);
        stackTraceSize = framesNumber;

        // TODO should we long-jump here??
        printStackTrace();
    }

    // Setup the signal handler.
    void installExceptionOrSignalHandlers() {
        struct sigaction handler{};
        handler.sa_handler = CrashRecoverySignalHandler;
        handler.sa_flags = 0; // TODO what is this?
        sigemptyset(&handler.sa_mask); // TODO ???

        for (unsigned i = 0; i != kNumSignals; ++i) {
            RuntimeLogInfo({kotlin::logging::Tag::kLogging}, "Installing handler for %ud", i);
            sigaction(kSignals[i], &handler, &prevActions[i]);
        }
    }

    // Restore the previous signal handlers.
    [[maybe_unused]] void uninstallExceptionOrSignalHandlers() {
        for (unsigned i = 0; i != kNumSignals; ++i) {
            RuntimeLogInfo({ kotlin::logging::Tag::kLogging }, "Uninstalling handler for %ud", i);
            sigaction(kSignals[i], &prevActions[i], nullptr);
        }
    }


//    void handleSignal(int signum) {
//        int framesNumber = backtrace(stackTraceBuf.data(), 1024);
//        stackTraceSize = framesNumber;
//        longjmp(jumpBuffer, 42);
//    }

}

void kotlin::crashHandlerInit() noexcept {
    // TODO do we still need this??
    // init everything
    void* dummy = NULL;
    backtrace(&dummy, 1);

//    if (setjmp(jumpBuffer) == 42) {
//        printStackTrace();
//        abort();
//    }
    //signal(SIGSEGV, handleSignal);
    installExceptionOrSignalHandlers();
}