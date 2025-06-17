// Copyright 2005 Google LLC
// Author: chatham@google.com (Andrew Chatham)
// Author: satorux@google.com (Satoru Takabayashi)
//
// ElfReader handles reading in ELF. It can extract symbols from the
// current process, which may be used to symbolize stack traces
// without having to make a potentially dangerous call to fork().
//
// ElfReader dynamically allocates memory, so it is not appropriate to
// use once the address space might be corrupted, such as during
// process death.
//
// ElfReader supports both 32-bit and 64-bit ELF binaries.

#ifndef COMMON_DWARF_ELF_READER_H__
#define COMMON_DWARF_ELF_READER_H__

#include <string>
#include <string_view>
#include <vector>

#include "common/dwarf/types.h"
#include "common/using_std_string.h"


#endif  // COMMON_DWARF_ELF_READER_H__
