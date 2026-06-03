/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import llvm.DIScopeOpaqueRef

/**
 * A scope that can resolve source-location debug information. Parameterized over the debug-scope
 * handle type [Scope] and source-location type [Loc] so the contract stays independent of the
 * concrete LLVM/DWARF representation.
 */
internal interface SourceLocationProvider<Scope, Loc> {
    /** Returns location information for the given source [offset], or `null` if unavailable. */
    fun location(offset: Int): Loc?

    /** Returns the debug-scope handle for this scope, or `null` if unavailable. */
    fun scope(): Scope?
}

/**
 * LLVM/DWARF source location: a debug [scope] plus line/column, optionally [inlinedAt] another
 * location when produced from an inlined function body.
 */
internal class LocationInfo(
        val scope: DIScopeOpaqueRef,
        val line: Int,
        val column: Int,
        val inlinedAt: LocationInfo? = null,
)
