/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.ir

import llvm.LLVMABIAlignmentOfType
import llvm.LLVMABISizeOfType
import llvm.LLVMStoreSizeOfType
import org.jetbrains.kotlin.backend.common.getCompilerMessageLocation
import org.jetbrains.kotlin.backend.common.lower.coroutines.getOrCreateFunctionWithContinuationStub
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.llvm.CodegenLlvmHelpers
import org.jetbrains.kotlin.backend.konan.llvm.buildClassHierarchyRecords
import org.jetbrains.kotlin.backend.konan.llvm.classHierarchyStableName
import org.jetbrains.kotlin.backend.konan.llvm.computeFunctionName
import org.jetbrains.kotlin.backend.konan.llvm.localHash
import org.jetbrains.kotlin.backend.konan.llvm.toLLVMType
import org.jetbrains.kotlin.backend.konan.lower.bridgeTarget
import org.jetbrains.kotlin.backend.konan.serialization.ClassFieldsDeserializer
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.objcinterop.canObjCClassMethodBeCalledVirtually
import org.jetbrains.kotlin.ir.objcinterop.isKotlinObjCClass
import org.jetbrains.kotlin.ir.objcinterop.isObjCClassMethod
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

internal class OverriddenFunctionInfo(
        val function: IrSimpleFunction,
        val overriddenFunction: IrSimpleFunction,
        val policy: BridgesPolicy,
) {
    val needBridge: Boolean
        get() = function.target.needBridgeTo(overriddenFunction, policy)

    val bridgeDirections: BridgeDirections
        get() = function.target.bridgeDirectionsTo(overriddenFunction, policy)

    val canBeCalledVirtually: Boolean
        get() {
            if (overriddenFunction.isObjCClassMethod()) {
                return function.canObjCClassMethodBeCalledVirtually(overriddenFunction)
            }

            return overriddenFunction.isOverridable
        }

    val inheritsBridge: Boolean
        get() = !function.isReal
                && function.target.overrides(overriddenFunction)
                && function.bridgeDirectionsTo(overriddenFunction, policy).allNotNeeded()

    fun getImplementation(context: Context): IrSimpleFunction? {
        val target = function.target
        val implementation = if (!needBridge)
            target
        else {
            val bridgeOwner = if (inheritsBridge) {
                target // Bridge is inherited from superclass.
            } else {
                function
            }
            context.bridgesSupport.getBridge(OverriddenFunctionInfo(bridgeOwner, overriddenFunction, policy))
        }
        return if (implementation.modality == Modality.ABSTRACT) null else implementation
    }

    override fun toString(): String {
        return "(descriptor=$function, overriddenDescriptor=$overriddenFunction)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OverriddenFunctionInfo) return false

        if (function != other.function) return false
        if (overriddenFunction != other.overriddenFunction) return false

        return true
    }

    override fun hashCode(): Int {
        var result = function.hashCode()
        result = 31 * result + overriddenFunction.hashCode()
        return result
    }
}

internal class ClassGlobalHierarchyInfo(val classIdLo: Int, val classIdHi: Int, val interfaceId: Int) {
    companion object {
        val DUMMY = ClassGlobalHierarchyInfo(0, 0, 0)

        // 32-items table seems like a good threshold.
        val MAX_BITS_PER_COLOR = 5
    }
}

internal class GlobalHierarchyAnalysisResult(val bitsPerColor: Int)

internal class GlobalHierarchyAnalysis(val context: Context, val irModule: IrModuleFragment) {
    fun run() {
        /*
         * The algorithm for fast interface call and check:
         * Consider the following graph: the vertices are interfaces and two interfaces are
         * connected with an edge if there exists a class which inherits both of them.
         * Now find a proper vertex-coloring of that graph (such that no edge connects vertices of same color).
         * Assign to each interface a unique id in such a way that its color is stored in the lower bits of its id.
         * Assuming the number of colors used is reasonably small build then a perfect hash table for each class:
         *     for each interfaceId inherited: itable[interfaceId % size] == interfaceId
         * Since we store the color in the lower bits the division can be replaced with (interfaceId & (size - 1)).
         * This is indeed a perfect hash table by construction of the coloring of the interface graph.
         * Now to perform an interface call store in all itables pointers to vtables of that particular interface.
         * Interface call: *(itable[interfaceId & (size - 1)].vtable[methodIndex])(...)
         * Interface check: itable[interfaceId & (size - 1)].id == interfaceId
         *
         * Note that we have a fallback to a more conservative version if the size of an itable is too large:
         * just save all interface ids and vtables in sorted order and find the needed one with the binary search.
         * We can signal that using the sign bit of the type info's size field:
         *     if (size >= 0) { .. fast path .. }
         *     else binary_search(0, -size)
         */
        val interfaceColors = assignColorsToInterfaces()
        val maxColor = interfaceColors.values.maxOrNull() ?: 0
        var bitsPerColor = 0
        var x = maxColor
        while (x > 0) {
            ++bitsPerColor
            x /= 2
        }

        val maxInterfaceId = Int.MAX_VALUE shr bitsPerColor
        val colorCounts = IntArray(maxColor + 1)

        /*
         * Here's the explanation of what's happening here:
         * Given a tree we can traverse it with the DFS and save for each vertex two times:
         * the enter time (the first time we saw this vertex) and the exit time (the last time we saw it).
         * It turns out that if we assign then for each vertex the interval (enterTime, exitTime),
         * then the following claim holds for any two vertices v and w:
         * ----- v is ancestor of w iff interval(v) contains interval(w) ------
         * Now apply this idea to the classes hierarchy tree and we'll get a fast type check.
         *
         * And one more observation: for each pair of intervals they either don't intersect or
         * one contains the other. With that in mind, we can save in a type info only one end of an interval.
         */
        val root = context.irBuiltIns.anyClass.owner
        val immediateInheritors = mutableMapOf<IrClass, MutableList<IrClass>>()
        val allClasses = mutableListOf<IrClass>()
        irModule.acceptVoid(object: IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isInterface) {
                    val color = interfaceColors[declaration]!!
                    // Numerate from 1 (reserve 0 for invalid value).
                    val interfaceId = ++colorCounts[color]
                    assert (interfaceId <= maxInterfaceId) {
                        "Unable to assign interface id to ${declaration.name}"
                    }
                    context.getLayoutBuilder(declaration).hierarchyInfo =
                            ClassGlobalHierarchyInfo(0, 0, color or (interfaceId shl bitsPerColor))
                } else {
                    allClasses += declaration
                    if (declaration != root) {
                        val superClass = declaration.getSuperClassNotAny() ?: root
                        val inheritors = immediateInheritors.getOrPut(superClass) { mutableListOf() }
                        inheritors.add(declaration)
                    }
                }
                super.visitClass(declaration)
            }
        })
        var time = 0

        fun dfs(irClass: IrClass) {
            ++time
            // Make the Any's interval's left border -1 in order to correctly generate classes for ObjC blocks.
            val enterTime = if (irClass == root) -1 else time
            immediateInheritors[irClass]?.forEach { dfs(it) }
            val exitTime = time
            context.getLayoutBuilder(irClass).hierarchyInfo = ClassGlobalHierarchyInfo(enterTime, exitTime, 0)
        }

        dfs(root)

        context.globalHierarchyAnalysisResult = GlobalHierarchyAnalysisResult(bitsPerColor)
    }

    class InterfacesForbiddennessGraph(val nodes: List<IrClass>, val forbidden: List<List<Int>>) {

        fun computeColoringGreedy(): IntArray {
            val colors = IntArray(nodes.size) { -1 }
            var numberOfColors = 0
            val usedColors = BooleanArray(nodes.size)
            for (v in nodes.indices) {
                for (c in 0 until numberOfColors)
                    usedColors[c] = false
                for (u in forbidden[v])
                    if (colors[u] >= 0)
                        usedColors[colors[u]] = true
                var found = false
                for (c in 0 until numberOfColors)
                    if (!usedColors[c]) {
                        colors[v] = c
                        found = true
                        break
                    }
                if (!found)
                    colors[v] = numberOfColors++
            }
            return colors
        }

        companion object {
            fun build(irModuleFragment: IrModuleFragment): InterfacesForbiddennessGraph {
                val interfaceIndices = mutableMapOf<IrClass, Int>()
                val interfaces = mutableListOf<IrClass>()
                val forbidden = mutableListOf<MutableList<Int>>()
                irModuleFragment.acceptVoid(object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildrenVoid(this)
                    }

                    fun registerInterface(iface: IrClass) {
                        interfaceIndices.getOrPut(iface) {
                            forbidden.add(mutableListOf())
                            interfaces.add(iface)
                            interfaces.size - 1
                        }
                    }

                    override fun visitClass(declaration: IrClass) {
                        if (declaration.isInterface)
                            registerInterface(declaration)
                        else {
                            val implementedInterfaces = declaration.implementedInterfaces
                            implementedInterfaces.forEach { registerInterface(it) }
                            for (i in 0 until implementedInterfaces.size)
                                for (j in i + 1 until implementedInterfaces.size) {
                                    val v = interfaceIndices[implementedInterfaces[i]]!!
                                    val u = interfaceIndices[implementedInterfaces[j]]!!
                                    forbidden[v].add(u)
                                    forbidden[u].add(v)
                                }
                        }
                        super.visitClass(declaration)
                    }
                })
                return InterfacesForbiddennessGraph(interfaces, forbidden)
            }
        }
    }

    private fun assignColorsToInterfaces(): Map<IrClass, Int> {
        val graph = InterfacesForbiddennessGraph.build(irModule)
        val coloring = graph.computeColoringGreedy()
        return graph.nodes.mapIndexed { v, irClass -> irClass to coloring[v] }.toMap()
    }
}

internal data class NewClassGHAInfo(val classIdLo: Int, val classIdHi: Int)

/**
 * The ids [NewGHA] computed for the whole program, keyed by the stable names of the classes and interfaces.
 *
 * A class is identified by the interval of its subtree, so a check against it is a range check; an interface
 * is identified by a single colored id, so a check against it is a lookup in the itable of the object's class.
 */
/** One entry of the interface lookup table of a class: the interface, its id and the size of its vtable. */
internal class NewInterfaceTableEntry(
        val interfaceStableName: String,
        val interfaceId: Int,
        val vtableSize: Int,
)

internal class NewGHAResult(
        val classIds: Map<String, NewClassGHAInfo>,
        val interfaceIds: Map<String, Int>,
        /** The entries of the interface lookup table of every class that owns one, keyed by its stable name. */
        val interfaceTables: Map<String, List<NewInterfaceTableEntry>>,
        /** How many lower bits of an interface id hold its color, and thus how large the itables have to be. */
        val bitsPerColor: Int,
)

internal class NewGHA(val generationState: NativeGenerationState, val irModule: IrModuleFragment) {
    private val context = generationState.context

    fun run() {
        val cachedRecords = context.config.librariesWithDependencies()
                .filter { context.config.cachedLibraries.isLibraryCached(it) }
                .mapNotNull { library ->
                    context.config.cachedLibraries.getLibraryCache(library, allowIncomplete = true)
                }.flatMap {
                    it.serializedClassHierarchy
                }

        val hierarchy = ClassHierarchy(
                irModule.buildClassHierarchyRecords(context) + cachedRecords,
                anyName = context.irBuiltIns.anyClass.owner.classHierarchyStableName(context),
        )

        val classIds = mutableMapOf<String, NewClassGHAInfo>()
        var time = 0
        // Only the classes take part in the traversal: the intervals are a single inheritance numbering,
        // and the interfaces are numbered by the coloring below instead.
        fun dfs(classEntry: ClassHierarchy.ClassEntry) {
            ++time
            // Make the Any's interval's left border -1 in order to correctly generate classes for ObjC blocks.
            val enterTime = if (classEntry == hierarchy.any) -1 else time
            hierarchy.directSubtypes(classEntry).forEach { if (!it.isInterface) dfs(it) }
            val exitTime = time
            classIds[classEntry.name] = NewClassGHAInfo(enterTime, exitTime)
        }

        dfs(hierarchy.any)

        val colors = colorInterfaces(hierarchy)
        val maxColor = colors.values.maxOrNull() ?: 0
        var bitsPerColor = 0
        var x = maxColor
        while (x > 0) {
            ++bitsPerColor
            x /= 2
        }

        val maxInterfaceId = Int.MAX_VALUE shr bitsPerColor
        val colorCounts = IntArray(maxColor + 1)
        val interfaceIds = mutableMapOf<String, Int>()
        for ([entry, color] in colors) {
            // Numerate from 1 (reserve 0 for invalid value).
            val interfaceId = ++colorCounts[color]
            require(interfaceId <= maxInterfaceId) { "Unable to assign interface id to ${entry.name}" }
            interfaceIds[entry.name] = color or (interfaceId shl bitsPerColor)
        }

        // The layout of an interface table is keyed by the ids above, so it can only be decided here, once.
        val interfaceTables = hierarchy.entries.filter { it.hasInterfaceTable }.associate { entry ->
            entry.name to hierarchy.allSupertypes(entry).filter { it.isInterface }.map {
                NewInterfaceTableEntry(it.name, interfaceIds.getValue(it.name), it.interfaceVTableSize)
            }
        }

        context.newGHAResult = NewGHAResult(classIds, interfaceIds, interfaceTables, bitsPerColor)
    }

    /**
     * A proper coloring of the graph whose vertices are the interfaces of the program and whose edges connect
     * the interfaces implemented by one and the same class. Storing the color in the lower bits of an interface
     * id then makes `itable[interfaceId & (itableSize - 1)]` a perfect hash table; see [GlobalHierarchyAnalysis]
     * for the full explanation of the scheme.
     */
    private fun colorInterfaces(hierarchy: ClassHierarchy): Map<ClassHierarchy.ClassEntry, Int> {
        val interfaces = hierarchy.entries.filter { it.isInterface }
        val indices = HashMap<ClassHierarchy.ClassEntry, Int>(interfaces.size)
        interfaces.forEachIndexed { index, entry -> indices[entry] = index }

        val conflicts = Array(interfaces.size) { mutableSetOf<Int>() }
        for (entry in hierarchy.entries) {
            if (entry.isInterface) continue
            val implemented = hierarchy.allSupertypes(entry).filter { it.isInterface }.map { indices.getValue(it) }
            for (i in implemented.indices)
                for (j in i + 1 until implemented.size) {
                    conflicts[implemented[i]] += implemented[j]
                    conflicts[implemented[j]] += implemented[i]
                }
        }

        // Greedy coloring: take the first color no already colored conflicting interface has taken.
        val colors = IntArray(interfaces.size) { -1 }
        var numberOfColors = 0
        val used = BooleanArray(interfaces.size)
        for (v in interfaces.indices) {
            for (c in 0 until numberOfColors)
                used[c] = false
            for (u in conflicts[v])
                if (colors[u] >= 0)
                    used[colors[u]] = true
            colors[v] = (0 until numberOfColors).firstOrNull { !used[it] } ?: numberOfColors++
        }

        return interfaces.indices.associate { interfaces[it] to colors[it] }
    }
}

internal fun IrField.requiredAlignment(llvm: CodegenLlvmHelpers): Int {
    val llvmType = type.toLLVMType(llvm)
    val abiAlignment = if (llvmType == llvm.vector128Type) {
        8 // over-aligned objects are not supported now, and this worked somehow, so let's keep it as it for now
    } else {
        LLVMABIAlignmentOfType(llvm.runtime.targetData, llvmType)
    }
    return if (hasAnnotation(KonanFqNames.volatile)) {
        val size = LLVMABISizeOfType(llvm.runtime.targetData, llvmType).toInt()
        val alignment = maxOf(size, abiAlignment)
        require(alignment % size == 0) { "Bad alignment of field ${render()}: abiAlignment = ${abiAlignment}, size = ${size}"}
        require(alignment % abiAlignment == 0) { "Bad alignment of field ${render()}: abiAlignment = ${abiAlignment}, size = ${size}"}
        alignment
    } else {
        abiAlignment
    }
}


internal class ClassLayoutBuilder(val irClass: IrClass, val context: Context) {
    private val bridgesPolicy = context.config.bridgesPolicy

    private fun IrField.toFieldInfo(llvm: CodegenLlvmHelpers): FieldInfo {
        val isConst = correspondingPropertySymbol?.owner?.isConst ?: false
        require(!isConst || initializer?.expression is IrConst) { "A const val field ${render()} must have constant initializer" }
        return FieldInfo(name.asString(), type, isConst, symbol, requiredAlignment(llvm))
    }

    val vtableEntries: List<OverriddenFunctionInfo> by lazy {
        require(!irClass.isInterface) {
            buildString {
                appendLine("Expected a class, found interface:")
                appendLine("  IR: " + irClass.render())
                appendLine("  FQ name: " + irClass.fqNameForIrSerialization)
                irClass.fileOrNull?.let { file ->
                    appendLine("  Location: " + irClass.getCompilerMessageLocation(file))
                }
            }
        }

        context.logMultiple {
            +""
            +"BUILDING vTable for ${irClass.render()}"
        }

        val superVtableEntries = if (irClass.isSpecialClassWithNoSupertypes()) {
            emptyList()
        } else {
            val superClass = irClass.getSuperClassNotAny() ?: context.irBuiltIns.anyClass.owner
            context.getLayoutBuilder(superClass).vtableEntries
        }

        val methods = overridableOrOverridingMethods
        val newVtableSlots = mutableListOf<OverriddenFunctionInfo>()
        val overridenVtableSlots = mutableMapOf<IrSimpleFunction, OverriddenFunctionInfo>()

        context.logMultiple {
            +""
            +"SUPER vTable:"
            superVtableEntries.forEach { +"    ${it.overriddenFunction.render()} -> ${it.function.render()}" }

            +""
            +"METHODS:"
            methods.forEach { +"    ${it.render()}" }

            +""
            +"BUILDING INHERITED vTable"
        }

        val superVtableMap = superVtableEntries.groupBy { it.function }
        methods.forEach { overridingMethod ->
            overridingMethod.allOverriddenFunctions.forEach {
                val superMethods = superVtableMap[it]
                if (superMethods?.isNotEmpty() == true) {
                    newVtableSlots.add(OverriddenFunctionInfo(overridingMethod, it, bridgesPolicy))
                    superMethods.forEach { superMethod ->
                        overridenVtableSlots[superMethod.overriddenFunction] =
                                OverriddenFunctionInfo(overridingMethod, superMethod.overriddenFunction, bridgesPolicy)
                    }
                }
            }
        }
        val inheritedVtableSlots = superVtableEntries.map { superMethod ->
            overridenVtableSlots[superMethod.overriddenFunction]?.also {
                context.log { "Taking overridden ${superMethod.overriddenFunction.render()} -> ${it.function.render()}" }
            } ?: superMethod.also {
                context.log { "Taking super ${superMethod.overriddenFunction.render()} -> ${superMethod.function.render()}" }
            }
        }

        // Add all possible (descriptor, overriddenDescriptor) edges for now, redundant will be removed later.
        methods.mapTo(newVtableSlots) { OverriddenFunctionInfo(it, it, bridgesPolicy) }

        val inheritedVtableSlotsSet = inheritedVtableSlots.map { it.function to it.bridgeDirections }.toSet()

        val filteredNewVtableSlots = newVtableSlots
            .filterNot { inheritedVtableSlotsSet.contains(it.function to it.bridgeDirections) }
            .distinctBy { it.function to it.bridgeDirections }
            .filter { it.function.isOverridable }

        context.logMultiple {
            +""
            +"INHERITED vTable slots:"
            inheritedVtableSlots.forEach { +"    ${it.overriddenFunction.render()} -> ${it.function.render()}" }

            +""
            +"MY OWN vTable slots:"
            filteredNewVtableSlots.forEach { +"    ${it.overriddenFunction.render()} -> ${it.function.render()} ${it.function}" }
            +"DONE vTable for ${irClass.render()}"
        }

        inheritedVtableSlots + filteredNewVtableSlots.sortedBy { it.overriddenFunction.uniqueName }
    }

    fun vtableIndex(function: IrSimpleFunction): Int {
        val bridgeDirections = function.target.bridgeDirectionsTo(function, bridgesPolicy)
        val index = vtableEntries.indexOfFirst { it.function == function && it.bridgeDirections == bridgeDirections }
        require(index >= 0) { "${function.render()} is not found in vtable of ${irClass.render()}" }
        return index
    }

    fun overridingOf(function: IrSimpleFunction) =
            overridableOrOverridingMethods.firstOrNull { function in it.allOverriddenFunctions }?.let {
                OverriddenFunctionInfo(it, function, bridgesPolicy).getImplementation(context)
            }

    val interfaceVTableEntries: List<IrSimpleFunction> by lazy {
        require(irClass.isInterface)
        irClass.simpleFunctions()
                .map { it.getLoweredVersion() }
                .filter { f ->
                    f.isOverridable && f.bridgeTarget == null
                            && (f.isReal || f.overriddenSymbols.any { f.needBridgeTo(it.owner, bridgesPolicy) })
                }
                .sortedBy { it.uniqueName }
    }

    data class InterfaceTablePlace(val irInterface: IrClass?, val interfaceId: Int, val itableSize: Int, val methodIndex: Int) {
        companion object {
            val INVALID = InterfaceTablePlace(null, 0, -1, -1)
        }
    }

    val classId: Int get() = when {
        irClass.isKotlinObjCClass() -> 0
        irClass.isInterface -> when {
            context.ghaEnabled() -> hierarchyInfo.interfaceId
            // Only the compilation that lays out the whole hierarchy knows the interface ids; the others reach
            // them through the [interfaceIdSymbolName] globals, and never need a constant of their own.
            context.newGHAEnabled() -> context.newGHAResult.interfaceIds.getValue(irClass.classHierarchyStableName(context))
            else -> localHash(irClass.fqNameForIrSerialization.asString().toByteArray()).toInt()
        }
        else -> {
            if (context.ghaEnabled()) {
                hierarchyInfo.classIdLo
            } else {
                0
            }
        }
    }

    fun itablePlace(function: IrSimpleFunction): InterfaceTablePlace {
        require(irClass.isInterface) { "An interface expected but was ${irClass.name}" }
        val interfaceVTable = interfaceVTableEntries
        val index = interfaceVTable.indexOf(function)
        if (index >= 0)
            return InterfaceTablePlace(irClass, classId, interfaceVTable.size, index)
        val superFunction = function.overriddenSymbols.first().owner
        return context.getLayoutBuilder(superFunction.parentAsClass).itablePlace(superFunction)
    }

    class FieldInfo(val name: String, val type: IrType, val isConst: Boolean, val irFieldSymbol: IrFieldSymbol, val alignment: Int) {
        val irField: IrField?
            get() = if (irFieldSymbol.isBound) irFieldSymbol.owner else null
        init {
            require(alignment.countOneBits() == 1) { "Alignment should be power of 2" }
        }
    }

    /**
     * All fields of the class instance.
     * The order respects the class hierarchy, i.e. a class [fields] contains superclass [fields] as a prefix.
     */
    fun getFields(llvm: CodegenLlvmHelpers): List<FieldInfo> = getFieldsInternal(llvm)

    private var fields: List<FieldInfo>? = null

    // Synchronization is needed due to potential deserialization invocation while building fields for the super classes.
    @Synchronized
    private fun getFieldsInternal(llvm: CodegenLlvmHelpers): List<FieldInfo> {
        fields?.let { return it }

        val superClass = irClass.getSuperClassNotAny()
        val superFields = if (superClass != null) context.getLayoutBuilder(superClass).getFieldsInternal(llvm) else emptyList()

        val declaredFields = getDeclaredFields(llvm)
        val sortedDeclaredFields = if (irClass.hasAnnotation(KonanFqNames.noReorderFields))
            declaredFields
        else
            declaredFields.sortedByDescending {
                with(llvm) { LLVMStoreSizeOfType(runtime.targetData, it.type.toLLVMType(this)) }
            }

        return (superFields + sortedDeclaredFields).also { fields = it }
    }

    val associatedObjects by lazy {
        val result = mutableMapOf<IrClass, IrClass>()

        irClass.annotations.forEach {
            val irFile = irClass.fileOrNull

            val annotationClass = it.classSymbol.owner

            if (annotationClass.hasAnnotation(RuntimeNames.associatedObjectKey)) {
                val argument = it.argumentMapping.values.singleOrNull()

                val irClassReference = argument as? IrClassReference
                        ?: error(irFile, argument, "unexpected annotation argument")

                val associatedObject = irClassReference.symbol.owner

                if (associatedObject !is IrClass || !associatedObject.isObject) {
                    error(irFile, irClassReference, "argument is not a singleton")
                }

                if (annotationClass in result) {
                    error(
                            irFile,
                            it,
                            "duplicate value for ${annotationClass.name}, previous was ${result[annotationClass]?.name}"
                    )
                }

                result[annotationClass] = associatedObject
            }
        }

        result
    }

    lateinit var hierarchyInfo: ClassGlobalHierarchyInfo

    /**
     * Fields declared in the class.
     */
    fun getDeclaredFields(llvm: CodegenLlvmHelpers): List<FieldInfo> {
        val outerThisField = if (irClass.isInner)
            context.innerClassesSupport.getOuterThisField(irClass)
        else null

        val moduleDeserializer = context.moduleDeserializerProvider.getDeserializerOrNull(irClass)
        if (moduleDeserializer != null) {
            val classFieldsDeserializer = ClassFieldsDeserializer(context.config.cachedLibraries, context.irBuiltIns, moduleDeserializer)
            return classFieldsDeserializer.deserializeClassFields(irClass, outerThisField?.toFieldInfo(llvm))
        }
        val declarations = irClass.declarations.toMutableList()
        outerThisField?.let {
            if (!declarations.contains(it))
                declarations += it
        }
        return declarations.mapNotNull {
            when (it) {
                is IrField -> it.takeIf { it.isReal && !it.isStatic }?.toFieldInfo(llvm)
                is IrProperty -> it.takeIf { it.isReal }?.backingField?.takeIf { !it.isStatic }?.toFieldInfo(llvm)
                else -> null
            }
        }
    }

    /**
     * Normally, function should be already replaced. But if the function come from LazyIr, it can be not replaced.
     */
    fun IrSimpleFunction.getLoweredVersion() = when {
        isSuspend -> this.getOrCreateFunctionWithContinuationStub(context)
        else -> this
    }
    private val overridableOrOverridingMethods: List<IrSimpleFunction>
        get() = irClass.simpleFunctions()
                .map {it.getLoweredVersion() }
                .filter { it.isOverridableOrOverrides && it.bridgeTarget == null }

    private val IrFunction.uniqueName get() = computeFunctionName()
}
