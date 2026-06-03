/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

/**
 * Generic scope-stack framework: the enclosing-scope chain, the enter/exit/exception-wrapping
 * lifecycle, and the push/run/pop discipline, with no dependency on the concrete code-generation
 * context. `CodeContext` builds on [ScopedContext] by adding its code-generation operations.
 *
 * [C] is the concrete context type (the F-bounded self type), so navigation stays strongly typed.
 */
internal interface ScopedContext<C : ScopedContext<C>> {
    /** The enclosing scope, or `null` for the root scope. */
    val outer: C?

    /** Called when this scope is pushed onto the stack. */
    fun onEnter() {}

    /** Called when this scope is removed from the stack. */
    fun onExit() {}

    /**
     * Called when an exception escapes a [ScopeStack.using] block guarded by this scope; the
     * returned exception is rethrown instead of the original.
     */
    fun wrapException(e: Exception): Exception = e
}

/**
 * Walks the [ScopedContext.outer] chain starting from this scope (inclusive) outward, returning the
 * first scope satisfying [predicate], or `null` if none matches.
 */
internal inline fun <C : ScopedContext<C>> C.findEnclosing(predicate: (C) -> Boolean): C? {
    var scope: C? = this
    while (scope != null) {
        if (predicate(scope)) return scope
        scope = scope.outer
    }
    return null
}

/**
 * A mutable stack of [ScopedContext]s providing the standard "substitute the current scope while
 * running a block, then restore it" discipline used during code generation.
 */
internal class ScopeStack<C : ScopedContext<C>>(initial: C) {
    var current: C = initial
        private set

    /**
     * Runs [block] with [scope] substituted as [current] (invoking [ScopedContext.onEnter] /
     * [ScopedContext.onExit] around it). A `null` [scope] leaves [current] unchanged. Exceptions
     * escaping [block] are passed through [ScopedContext.wrapException] of [scope].
     */
    inline fun <R> using(scope: C?, block: () -> R): R {
        val old = current
        if (scope != null) {
            current = scope
            scope.onEnter()
        }
        try {
            return block()
        } catch (e: Exception) {
            throw (scope?.wrapException(e) ?: e)
        } finally {
            scope?.onExit()
            current = old
        }
    }
}
