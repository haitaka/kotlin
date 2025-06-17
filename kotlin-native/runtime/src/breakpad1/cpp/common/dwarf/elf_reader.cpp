// Copyright 2005 Google LLC
// Author: chatham@google.com (Andrew Chatham)
// Author: satorux@google.com (Satoru Takabayashi)
//
// Code for reading in ELF files.
//
// For information on the ELF format, see
// http://www.x86.org/ftp/manuals/tools/elf.pdf
//
// I also liked:
// http://www.caldera.com/developers/gabi/1998-04-29/contents.html
//
// A note about types: When dealing with the file format, we use types
// like Elf32_Word, but in the public interfaces we treat all
// addresses as uint64. As a result, we should be able to symbolize
// 64-bit binaries from a 32-bit process (which we don't do,
// anyway). size_t should therefore be avoided, except where required
// by things like mmap().
//
// Although most of this code can deal with arbitrary ELF files of
// either word size, the public ElfReader interface only examines
// files loaded into the current address space, which must all match
// the machine's native word size. This code cannot handle ELF files
// with a non-native byte ordering.
//
// TODO(chatham): It would be nice if we could accomplish this task
// without using malloc(), so we could use it as the process is dying.

//#ifndef _GNU_SOURCE
//#define _GNU_SOURCE  // needed for pread()
//#endif
//
//#ifdef HAVE_CONFIG_H
//#include <config.h>  // Must come first
//#endif

//#include <fcntl.h>
//#include <limits.h>
//#include <string.h>
//#include <sys/mman.h>
//#include <sys/stat.h>
//#include <sys/types.h>
//#include <unistd.h>

//#include <algorithm>
//#include <map>
//#include <string>
//#include <string_view>
//#include <vector>
//// TODO(saugustine): Add support for compressed debug.
//// Also need to add configure tests for zlib.
////#include "zlib.h"
//
////#include "third_party/musl/include/elf.h"
//#include "elf_reader.h"
////#include "common/using_std_string.h"
//
//// EM_AARCH64 is not defined by elf.h of GRTE v3 on x86.
////// TODO(dougkwan): Remove this when v17 is retired.
////#if !defined(EM_AARCH64)
////#define EM_AARCH64      183             /* ARM AARCH64 */
////#endif
////
////// Map Linux macros to their Apple equivalents.
////#if __APPLE__
////#ifndef __LITTLE_ENDIAN
////#define __LITTLE_ENDIAN __ORDER_LITTLE_ENDIAN__
////#endif  // __LITTLE_ENDIAN
////#ifndef __BIG_ENDIAN
////#define __BIG_ENDIAN __ORDER_BIG_ENDIAN__
////#endif  // __BIG_ENDIAN
////#ifndef __BYTE_ORDER
////#define __BYTE_ORDER __BYTE_ORDER__
////#endif  // __BYTE_ORDER
////#endif  // __APPLE__
////
////// TODO(dthomson): Can be removed once all Java code is using the Google3
////// launcher. We need to avoid processing PLT functions as it causes memory
////// fragmentation in malloc, which is fixed in tcmalloc - and if the Google3
////// launcher is used the JVM will then use tcmalloc. b/13735638
//////DEFINE_bool(elfreader_process_dynsyms, true,
//////            "Activate PLT function processing");
////
