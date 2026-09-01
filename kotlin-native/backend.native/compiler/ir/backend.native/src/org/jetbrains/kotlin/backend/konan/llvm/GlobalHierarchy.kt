/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import llvm.LLVMArrayType
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.isCache
import org.jetbrains.kotlin.backend.konan.ir.ClassIdInterval
import org.jetbrains.kotlin.backend.konan.ir.InterfaceTableEntry
import org.jetbrains.kotlin.backend.konan.ir.getSuperClassNotAny
import org.jetbrains.kotlin.backend.konan.ir.isAbstract
import org.jetbrains.kotlin.backend.konan.serialization.SerializedClassHierarchy
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.objcinterop.isObjCClass
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

// 32-items table seems like a good threshold.
internal const val MAX_BITS_PER_COLOR = 5

/**
 * Whether the hierarchy structures this compilation refers to are laid out by another one — the compilation
 * that produces the final binary. That is so for a cache, which cannot know the ids yet, and for a binary
 * linking caches, whose numbering has to cover their classes too.
 *
 * When it is not so, this compilation sees the whole program and bakes every id right into the code it emits:
 * no globals, and the interval of a class goes into the `classId_` of its type info as it always used to.
 */
internal val Context.hierarchyLaidOutByFinalLink: Boolean
    get() = shouldOptimize() && (config.produce.isCache || config.cachedLibraries.hasStaticCaches)

internal fun Context.interfaceId(irClass: IrClass): Int =
        globalHierarchyAnalysisResult.interfaceIds.getValue(irClass.classHierarchyStableName(this))

internal fun Context.classIds(irClass: IrClass): ClassIdInterval =
        globalHierarchyAnalysisResult.classIds.getValue(irClass.classHierarchyStableName(this))

internal fun Context.classIdLo(irClass: IrClass): Int = classIds(irClass).classIdLo

/**
 * The name a class is known by in the class hierarchy. It is the key the hierarchy pieces of the separate
 * compilations are stitched together by, and the base of the symbol names of all the hierarchy structures
 * of the class, which is what makes the ids computed here resolve against the code compiled into a cache.
 */
internal fun IrClass.classHierarchyStableName(context: Context): String = globalHierarchyName(context)

internal fun IrClass.classHierarchyIdsStructName(context: Context): String =
        classHierarchyIdsSymbolName(classHierarchyStableName(context))

internal fun IrClass.interfaceTableSymbolName(context: Context): String =
        interfaceTableSymbolName(classHierarchyStableName(context))

internal fun IrClass.interfaceIdSymbolName(context: Context): String =
        interfaceIdSymbolName(classHierarchyStableName(context))

internal fun interfaceIdSymbolName(stableName: String): String =
        globalHierarchySymbolName(stableName, KonanBinaryInterface.MANGLE_IFACE_ID_PREFIX)

internal fun classHierarchyIdsSymbolName(stableName: String): String =
        globalHierarchySymbolName(stableName, KonanBinaryInterface.MANGLE_CLASS_IDS_PREFIX)

internal fun interfaceTableSymbolName(stableName: String): String =
        globalHierarchySymbolName(stableName, KonanBinaryInterface.MANGLE_ITABLE_PREFIX)

private fun interfaceTableRecordsSymbolName(stableName: String): String =
        globalHierarchySymbolName(stableName, KonanBinaryInterface.MANGLE_ITABLE_RECORDS_PREFIX)

/** The vtable of the methods of one interface as implemented by one class. */
internal fun interfaceVTableSymbolName(classStableName: String, interfaceStableName: String): String =
        globalHierarchySymbolName("$classStableName#$interfaceStableName", KonanBinaryInterface.MANGLE_IFACE_VTABLE_PREFIX)

/**
 * Whether instances of [this] class dispatch interface calls and answer interface type checks through
 * an interface lookup table of their own. The other classes never own one: they have no instances.
 */
internal val IrClass.needsInterfaceTable: Boolean
    get() = !isInterface && !isAbstract() && !isObjCClass()

/**
 * The hierarchy record of [this] class, as seen by the compilation that declares it.
 * `Any` is deliberately not recorded as a superclass: it is materialized when the pieces
 * of the hierarchy are assembled into a `ClassHierarchy`.
 */
internal fun IrClass.buildClassHierarchyRecord(context: Context) = SerializedClassHierarchy(
        classStableName = classHierarchyStableName(context),
        isInterface = isInterface,
        superClassStableName = getSuperClassNotAny()?.classHierarchyStableName(context),
        interfaceStableNames = superTypes.mapNotNull { it.classOrNull?.owner }
                .filter { it.isInterface }
                .map { it.classHierarchyStableName(context) },
        interfaceVTableSize = if (isInterface) context.getLayoutBuilder(this).interfaceVTableEntries.size else 0,
        hasInterfaceTable = needsInterfaceTable,
)

/**
 * The hierarchy piece contributed by [this] element. Every class and interface below it is recorded,
 * including the local ones declared inside function bodies: the cached code refers to their class ids
 * as well, so a piece that skips them would leave those ids undefined in the final compilation.
 */
internal fun IrElement.buildClassHierarchyRecords(context: Context): List<SerializedClassHierarchy> {
    val records = mutableListOf<SerializedClassHierarchy>()
    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitClass(declaration: IrClass) {
            records.add(declaration.buildClassHierarchyRecord(context))
            super.visitClass(declaration)
        }
    })
    return records
}

/**
 * Emits the class ids computed by the global hierarchy analysis, for the whole program: the ids of the classes
 * coming from cached libraries are emitted here as well, as their caches only import them.
 */
internal fun ContextUtils.createClassHierarchyIdsStructs() {
    if (!context.hasGlobalHierarchyAnalysis() || !context.hierarchyLaidOutByFinalLink) return
    for ([stableName, hierarchyInfo] in context.globalHierarchyAnalysisResult.classIds) {
        val global = createGlobalHierarchyGlobal(classHierarchyIdsSymbolName(stableName), runtime.classHierarchyIdsType)
        global.setInitializer(Struct(runtime.classHierarchyIdsType,
                llvm.constInt32(hierarchyInfo.classIdLo), llvm.constInt32(hierarchyInfo.classIdHi)))
        global.setConstant(true)
        llvm.usedGlobals += global.llvmGlobal
    }
}

/**
 * Emits the interface ids computed by the global hierarchy analysis. Like the class ids, they can only be known
 * once the whole hierarchy is there, so every use site — a type check, an interface call, a table record —
 * reaches them through these globals rather than through a constant of its own.
 */
internal fun ContextUtils.createInterfaceIdGlobals() {
    if (!context.hasGlobalHierarchyAnalysis() || !context.hierarchyLaidOutByFinalLink) return
    for ([stableName, interfaceId] in context.globalHierarchyAnalysisResult.interfaceIds) {
        val global = createGlobalHierarchyGlobal(interfaceIdSymbolName(stableName), llvm.int32Type)
        global.setInitializer(llvm.constInt32(interfaceId))
        global.setConstant(true)
        llvm.usedGlobals += global.llvmGlobal
    }
}

/**
 * Emits the interface lookup tables of the whole program: the layout is keyed by the interface ids, which only
 * the final compilation knows, so the caches merely refer to the tables of the classes they declare. The vtables
 * the records point at are the other way around — a cache emits them for the classes it compiles.
 */
internal fun ContextUtils.createInterfaceTables() {
    // With the whole program in one module every table was already emitted next to the type info using it.
    if (!context.hasGlobalHierarchyAnalysis() || !context.hierarchyLaidOutByFinalLink) return
    context.globalHierarchyAnalysisResult.interfaceTables.forEach { [stableName, entries] -> createInterfaceTable(stableName, entries) }
}

/**
 * Emits the interface lookup table of the class [stableName]. The vtable of every record is the one the
 * compilation of that class emitted: already defined here if that is this very compilation, imported from
 * its cache otherwise.
 */
internal fun ContextUtils.createInterfaceTable(stableName: String, entries: List<InterfaceTableEntry>): ConstPointer {
    val [slots, size] = layOutInterfaceTable(entries) { it.interfaceId }
    val records = slots.map { entry ->
        // kInvalidInterfaceId is zero, and the ids are numerated from one, so an empty slot holds zeroes.
        Struct(runtime.interfaceTableRecordType,
                llvm.constInt32(entry?.interfaceId ?: 0),
                llvm.constInt32(entry?.vtableSize ?: 0),
                if (entry == null) llvm.nullPointer else interfaceVTable(stableName, entry),
        )
    }
    val recordsPtr = staticData.placeGlobalConstArray(interfaceTableRecordsSymbolName(stableName),
            runtime.interfaceTableRecordType, records)
    val initializer = Struct(runtime.interfaceTableType, llvm.constInt32(size), recordsPtr)
    val symbolName = interfaceTableSymbolName(stableName)

    if (!context.hierarchyLaidOutByFinalLink) {
        // Nothing outside this module refers to the table, so let it be internalized and stripped as usual.
        return staticData.placeGlobal(symbolName, initializer, isExported = false)
                .also { it.setConstant(true) }.pointer
    }

    val table = createGlobalHierarchyGlobal(symbolName, runtime.interfaceTableType)
    table.setInitializer(initializer)
    table.setConstant(true)
    // Has to be llvm.used, not llvm.compiler.used: only the former is what the internalizer takes as evidence
    // of a reference it cannot see, and internalizing a table the cached code refers to leaves that reference
    // unresolved. The cost is that the linker may not strip the tables nothing turns out to refer to.
    llvm.usedGlobals += table.llvmGlobal
    return table.pointer
}

private fun ContextUtils.interfaceVTable(classStableName: String, entry: InterfaceTableEntry): ConstPointer {
    // An interface with no methods of its own has no vtable to point at, and no call can ever reach for one.
    if (entry.vtableSize == 0) return llvm.nullPointer
    val symbolName = interfaceVTableSymbolName(classStableName, entry.interfaceStableName)
    return staticData.getGlobal(symbolName)?.pointer
            ?: constPointer(importGlobalHierarchyGlobal(symbolName, LLVMArrayType(llvm.pointerType, entry.vtableSize)!!))
}

/**
 * Places [entries] into the slots of an interface lookup table: a perfect hash table keyed by the color bits of
 * the interface ids when they fit into one, and a table sorted by the interface id otherwise. Returns the slots
 * along with the size to record in the table: the mask for the former shape, the negated count for the latter.
 * The details of the scheme are on [org.jetbrains.kotlin.backend.konan.ir.GlobalHierarchyAnalysis].
 */
internal fun <T> layOutInterfaceTable(entries: List<T>, interfaceId: (T) -> Int): Pair<List<T?>, Int> {
    // Find the optimal size. It must be a power of 2.
    var size = 1
    val maxSize = 1 shl MAX_BITS_PER_COLOR
    val used = BooleanArray(maxSize)
    while (size <= maxSize) {
        for (i in 0 until size)
            used[i] = false
        // Check for collisions.
        var ok = true
        for (entry in entries) {
            val index = interfaceId(entry) and (size - 1)
            if (used[index]) {
                ok = false
                break
            }
            used[index] = true
        }
        if (ok) break
        size *= 2
    }

    if (size <= maxSize) {
        val slots = arrayOfNulls<Any?>(size)
        for (entry in entries)
            slots[interfaceId(entry) and (size - 1)] = entry
        @Suppress("UNCHECKED_CAST")
        return Pair(slots.toList() as List<T?>, size - 1)
    }

    val sorted = entries.sortedBy(interfaceId)
    for (i in 1 until sorted.size)
        require(interfaceId(sorted[i - 1]) != interfaceId(sorted[i])) {
            "Different interfaces have same interface id: ${interfaceId(sorted[i])}"
        }
    return Pair(sorted, -sorted.size)
}
