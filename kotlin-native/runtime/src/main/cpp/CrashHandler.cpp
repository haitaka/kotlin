#include "CrashHandler.hpp"

#include <csignal>
#include <cstdlib>
#include <array>
#include <mutex>

#include "KAssert.h"
#include "Porting.h"
#include "Logging.hpp"

#if KONAN_APPLE

// TODO include no_mach on TVOS/WATCHOS
#include "client/mac/handler/exception_handler.h"

#include <mach/exception_types.h>
#include <mach/mach_init.h>
#endif

namespace {
    [[maybe_unused]] int kHandledSignals[] = {
            //SIGABRT,
            SIGBUS,
            SIGFPE,
            SIGILL,
            SIGSEGV,
            //SIGTRAP
    };

    bool minidumpsEnabled() {
        return kotlin::compiler::minidumpLocation() != nullptr;
    }

#if KONAN_APPLE
    std::unique_ptr<google_breakpad::ExceptionHandler> gExceptionHandler = nullptr;

    bool exceptionFilter(void* context) {
        // TODO handle only certain signals?
        //      BUT HOW?????
        return true;
    }

    bool minidumpCallback(const char* dump_dir,
                          const char* minidump_id,
                          void* context,
                          bool succeeded) {
        // TODO handle only certain signals?
        if (succeeded) {
            konan::consoleErrorf("Minidump written to \"%s/%s.dmp\"\n", dump_dir, minidump_id);
        } else {
            konan::consoleErrorf("Failed to write minidump to \"%s/%s.dmp\"\n", dump_dir, minidump_id);
        }
        return false; // false -> do not abort the process and let other handlers to be called
    }

//    bool exceptionHandler(void* context,
//                          int exception_type,
//                          int exception_code,
//                          int exception_subcode,
//                          mach_port_t thread_name) {
//        std::string minidump_id;
//
//        // TODO we need this property from the caller here
//        bool report_current_thread = false;
//        // Putting the MinidumpGenerator in its own context will ensure that the
//        // destructor is executed, closing the newly created minidump file.
//        google_breakpad::MinidumpGenerator md(mach_task_self(),
//                             report_current_thread ? MACH_PORT_NULL :
//                             mach_thread_self());
//
//        // TODO we need thread_context here
//        // md.SetTaskContext(task_context);
//
//        if (exception_type && exception_code) {
//            // If this is a real exception, give the filter (if any) a chance to
//            // decide if this should be sent.
//            if (filter_ && !filter_(callback_context_))
//                return false;
//
//            md.SetExceptionInformation(exception_type, exception_code,
//                                       exception_subcode, thread_name);
//        }
//
//        TODO generate this name
//        result = md.Write(next_minidump_path_c_);
//#endif
//}

void kotlin::crashHandlerInit() noexcept {
    if (minidumpsEnabled()) {
#if KONAN_APPLE
        RuntimeLogInfo({logging::Tag::kRT}, "Initializing crash handler, minidumps will be written to \"%s\"",
                       compiler::minidumpLocation());

        gExceptionHandler = std::make_unique<google_breakpad::ExceptionHandler>(
                kotlin::compiler::minidumpLocation(),
                exceptionFilter,
                minidumpCallback,
                nullptr,
                true,
                nullptr // in-process generation
        );
#endif
    }
}

void kotlin::writeMinidump() noexcept {
    if (minidumpsEnabled()) {
#if KONAN_APPLE
        gExceptionHandler->WriteMinidump();
#endif
    }
}
