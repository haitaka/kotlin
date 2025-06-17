#pragma once

#include <cassert>

namespace google_breakpad {
   class MinidumpGenerator  {
   public:
       void Write(const char*);
   };
}