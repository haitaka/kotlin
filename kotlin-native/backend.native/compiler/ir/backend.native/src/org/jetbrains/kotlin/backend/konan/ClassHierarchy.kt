/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.konan.serialization.SerializedClassHierarchy

/**
 * The class hierarchy of the whole program, assembled from the pieces the separate compilations contributed:
 * every cache it is linked against brings its own [records], and the module being compiled brings the rest.
 * An individual piece may be incomplete — it is allowed to reference supertypes recorded by another one —
 * but the assembled hierarchy is the complete picture.
 */
class ClassHierarchy(records: List<SerializedClassHierarchy>, anyName: String) {

    val any: ClassEntry

    val entries: List<ClassEntry>

    class ClassEntry(
            val name: String,
            val isInterface: Boolean,
            /** The number of the itable vtable entries of this interface; zero for a class. */
            val interfaceVTableSize: Int = 0,
            /** Whether this class can have instances, and thus needs an interface lookup table of its own. */
            val hasInterfaceTable: Boolean = false,
    ) {
        var superClass: ClassEntry? = null
            internal set
        var interfaces: List<ClassEntry> = emptyList()
            internal set

        override fun equals(other: Any?): Boolean =
                this === other || (other is ClassEntry && other.name == name)
        override fun hashCode(): Int = name.hashCode()
        override fun toString(): String = name

        val directSupertypes: List<ClassEntry>
            get() = listOfNotNull(superClass) + interfaces
    }

    init {
        val entriesByName = records.associateBy(
                { it.classStableName },
                { ClassEntry(it.classStableName, it.isInterface, it.interfaceVTableSize, it.hasInterfaceTable) }
        ).toMutableMap()

        // Any is never recorded as a superclass, so it is materialized here instead, and every class
        // that has no superclass of its own ends up inheriting from it. Interfaces stay parentless.
        any = entriesByName.getOrPut(anyName) { ClassEntry(anyName, isInterface = false) }

        // One pass over the records is a fixpoint, and only because every recorded name is already in the map:
        // that leaves [entryFor] creating entries for the unrecorded names alone, and those have no supertypes
        // to follow. Seed the map lazily instead, and linking would have to become a proper closure.
        for (record in records) {
            val entry = entriesByName.getValue(record.classStableName)
            entry.superClass = when {
                record.superClassStableName != null -> entriesByName[record.superClassStableName]!!
                entry === any || entry.isInterface -> null
                else -> any
            }
            entry.interfaces = record.interfaceStableNames.map { entriesByName[it]!! }
        }

        entries = entriesByName.values.toList()
    }

    private val directSubs: Map<ClassEntry, List<ClassEntry>> = entries
            .flatMap { sub -> sub.directSupertypes.map { it to sub } }
            .groupBy({ it.first }, { it.second })

    // Transitive closures. Memoized DFS is safe — the graph is acyclic by construction.
    // For large hierarchies, swap Set<ClassEntry> for bitsets over a dense index.
    private val allSupers: Map<ClassEntry, Set<ClassEntry>> by lazy { closure { it.directSupertypes } }
    private val allSubs: Map<ClassEntry, Set<ClassEntry>> by lazy { closure { directSubtypes(it) } }

    private fun closure(next: (ClassEntry) -> List<ClassEntry>): Map<ClassEntry, Set<ClassEntry>> {
        val memo = HashMap<ClassEntry, Set<ClassEntry>>()
        fun visit(e: ClassEntry): Set<ClassEntry> = memo.getOrPut(e) {
            val acc = LinkedHashSet<ClassEntry>()
            for (n in next(e)) { acc.add(n); acc.addAll(visit(n)) }
            acc
        }
        entries.forEach(::visit)
        return memo
    }

//    operator fun contains(cls: ClassEntry): Boolean = cls in directSupers
//
//    fun allClasses(): Sequence<ClassEntry> = directSupers.keys.asSequence()

    // ---- Direct edges ------------------------------------------------------

    //fun directSupertypes(cls: ClassEntry): List<ClassEntry> = directSupers[cls] ?: emptyList()

    fun directSubtypes(cls: ClassEntry): List<ClassEntry> = directSubs[cls] ?: emptyList()

    // ---- Transitive relations ---------------------------------------------

    /** Transitive supertypes, excluding [cls] itself. */
    fun allSupertypes(cls: ClassEntry): Set<ClassEntry> = allSupers[cls] ?: emptySet()

    /** Transitive subtypes, excluding [cls] itself. Drives devirtualization / exhaustiveness. */
    //fun allSubtypes(cls: ClassEntry): Set<ClassEntry> = allSubs[cls] ?: emptySet()

    /** Hot path — O(1) after the closure precompute in [build]. */
//    fun isSubtypeOf(sub: ClassEntry, sup: ClassEntry): Boolean =
//            sub == sup || sup in (allSupers[sub] ?: emptySet())

    /**
     * Minimal common supertypes — the LUB *set*. Kotlin has no unique LUB under
     * multiple interface inheritance, so this returns the most-derived shared
     * supertypes and lets the caller intersect / decide. May be empty (e.g. two
     * unrelated interfaces, since [ANY] is not injected for interfaces).
     */
//    fun commonSupertypes(a: ClassEntry, b: ClassEntry): List<ClassEntry> {
//        val aReach = (allSupers[a] ?: emptySet()) + a
//        val bReach = (allSupers[b] ?: emptySet()) + b
//        val common = aReach intersect bReach
//        // Keep only minimal elements: drop any that is a supertype of another common element.
//        return common.filter { c ->
//            common.none { other -> other != c && c in (allSupers[other] ?: emptySet()) }
//        }
//    }
}
