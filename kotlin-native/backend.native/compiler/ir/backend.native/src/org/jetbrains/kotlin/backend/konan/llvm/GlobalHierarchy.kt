package org.jetbrains.kotlin.backend.konan.llvm

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ir.getSuperClassNotAny
import org.jetbrains.kotlin.backend.konan.serialization.SerializedClassHierarchy
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

/**
 * The name a class is known by in the class hierarchy. It is the key the hierarchy pieces of the separate
 * compilations are stitched together by, and, being also the symbol name of the class ids global, the very
 * thing that makes the ids computed here resolve against the code compiled into a cache.
 */
internal fun IrClass.classHierarchyStableName(context: Context): String =
        classHierarchyIdsSymbolName(context) // TODO without prefix

internal fun IrClass.classHierarchyIdsStructName(context: Context): String =
        classHierarchyStableName(context) // TODO with prefix

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
    if (!context.newGHAEnabled()) return
    for ([classStableName, hierarchyInfo] in context.newGHAResult.data) {
        val symbolName = classStableName // TODO with prefix
        val global = staticData.getGlobal(symbolName)
                ?: staticData.createGlobal(runtime.classHierarchyIdsType, symbolName, isExported = true)
        global.setInitializer(Struct(runtime.classHierarchyIdsType, llvm.constInt32(hierarchyInfo.classIdLo), llvm.constInt32(hierarchyInfo.classIdHi)))
        global.setConstant(true)
        llvm.usedGlobals += global.llvmGlobal
    }
}
