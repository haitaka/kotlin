/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import llvm.LLVMTypeRef
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.config.LoggingContext
import org.jetbrains.kotlin.backend.common.linkage.partial.createPartialLinkageSupportForLowerings
import org.jetbrains.kotlin.backend.konan.cexport.CAdapterExportedElements
import org.jetbrains.kotlin.backend.konan.ir.*
import org.jetbrains.kotlin.backend.konan.llvm.KonanMetadata
import org.jetbrains.kotlin.backend.konan.lower.*
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportCodeSpec
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportedInterface
import org.jetbrains.kotlin.backend.konan.serialization.ExternalDeclarationFileNameProvider
import org.jetbrains.kotlin.backend.konan.serialization.ModuleDeserializerProvider
import org.jetbrains.kotlin.backend.konan.serialization.InlineFunctionDeserializer
import org.jetbrains.kotlin.backend.konan.serialization.KonanIrLinker
import org.jetbrains.kotlin.backend.konan.serialization.KonanPartialModuleDeserializer
import org.jetbrains.kotlin.backend.konan.serialization.TrivialGettersDeserializer
import org.jetbrains.kotlin.builtins.konan.KonanBuiltIns
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.util.ReferenceSymbolTable
import org.jetbrains.kotlin.ir.util.isTrivialGetter
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.utils.addToStdlib.getOrSetIfNull
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private var IrClass.layoutBuilder: ClassLayoutBuilder? by irAttribute(copyByDefault = false)

// TODO: Can be renamed or merged with KonanBackendContext
internal class Context(
        config: NativeSecondStageCompilationConfig,
        val sourcesModules: Set<ModuleDescriptor>,
        @OptIn(K1Deprecation::class)
        override val builtIns: KonanBuiltIns,
        override val irBuiltIns: IrBuiltIns,
        val irModules: Map<Path, IrModuleFragment>,
        val irLinker: KonanIrLinker,
        override val symbols: BackendNativeSymbols,
        val symbolTable: ReferenceSymbolTable,
) : KonanBackendContext(config) {
    override val configuration get() = config.configuration

    override val optimizeLoopsOverUnsignedArrays = true

    override val innerClassesSupport: NativeInnerClassesSupport by lazy { NativeInnerClassesSupport(irFactory) }
    val bridgesSupport by lazy { BridgesSupport(irBuiltIns, symbols, irFactory) }
    val enumsSupport by lazy { EnumsSupport(irBuiltIns, irFactory) }
    val cachesAbiSupport by lazy { CachesAbiSupport(irFactory) }

    val moduleDeserializerProvider by lazy {
        ModuleDeserializerProvider(config.libraryToCache, config.cachedLibraries, irLinker)
    }

    val externalDeclarationFileNameProvider by lazy {
        ExternalDeclarationFileNameProvider(moduleDeserializerProvider)
    }

    private val inlineFunctionDeserializers = ConcurrentHashMap<KonanPartialModuleDeserializer, InlineFunctionDeserializer>()

    fun getInlineFunctionDeserializer(function: IrFunction): InlineFunctionDeserializer {
        val deserializer = moduleDeserializerProvider.getDeserializerOrNull(function)
                ?: error("No module deserializer for ${function.render()}")
        return inlineFunctionDeserializers.getOrPut(deserializer) {
            InlineFunctionDeserializer(irBuiltIns, deserializer, config.cachedLibraries, irLinker)
        }
    }

    private val trivialGettersDeserializers = mutableMapOf<KonanPartialModuleDeserializer, TrivialGettersDeserializer>()

    /**
     * Cache-aware version of [isTrivialGetter] specialized for `val` properties.
     *
     * For getters declared in the current module we just delegate to [isTrivialGetter].
     * For getters declared in a cached dependency, the IR body may be empty, so we instead consult
     * the cache built when that dependency was first compiled (see `CacheInfoBuilder` and `TrivialGetterSerializer`).
     */
    fun isTrivialGetter(function: IrSimpleFunction): Boolean {
        val deserializer = moduleDeserializerProvider.getDeserializerOrNull(function)
                ?: return function.isTrivialGetter // Function from current module.

        val signature = function.symbol.signature ?: return false
        val trivialGettersDeserializer = trivialGettersDeserializers.getOrPut(deserializer) {
            TrivialGettersDeserializer(config.cachedLibraries, deserializer)
        }
        return signature in trivialGettersDeserializer.trivialGetterSignatures
    }

    fun getLayoutBuilder(irClass: IrClass): ClassLayoutBuilder {
        (irClass.metadata as? KonanMetadata.Class)?.layoutBuilder?.let {
            return it
        }
        synchronized(irClass) {
            return irClass::layoutBuilder.getOrSetIfNull { ClassLayoutBuilder(irClass, this) }
        }
    }

    lateinit var globalHierarchyAnalysisResult: GlobalHierarchyAnalysisResult

    // True once a class hierarchy numbering (DFS intervals) has been computed for this compilation — either
    // the monolithic GHAPhase or the whole-link-set GlobalHierarchyNumberingPhase. Drives the fast class
    // subtype check and the emission of per-class ClassHierarchyIds globals with real values.
    var classHierarchyNumberingComputed: Boolean = false

    // Whole-link-set interface ids (colors) keyed by TypeInfo symbol name, computed by the metadata-driven
    // GlobalHierarchyNumberingPhase. The final link emits strong kifaceid globals for cached interfaces from this.
    var linkSetInterfaceIds: Map<String, Int> = emptyMap()

    // Whole-link-set class DFS intervals [lo, hi] keyed by TypeInfo symbol name. The final link emits strong
    // kclassids globals for cached classes from this.
    var linkSetClassIntervals: Map<String, Pair<Int, Int>> = emptyMap()

    // Hierarchy summaries of classes materialized by consumed caches (loaded from their class_hierarchy sidecar).
    // The final link emits their kclassids/kifaceid globals by name, without deserializing the cached IR.
    var cachedHierarchyRecords: List<org.jetbrains.kotlin.backend.konan.serialization.SerializedClassHierarchy> = emptyList()

    // Fresh + cached hierarchy summaries of the whole link set, used to emit every class's kitable by name.
    var allHierarchyRecords: List<org.jetbrains.kotlin.backend.konan.serialization.SerializedClassHierarchy> = emptyList()

    // True in the final-link build that consumes static caches: the interface itables + ids are provided by
    // the numbering pass via kitable/kifaceid/mask globals, and codegen reads them. Set by
    // GlobalHierarchyNumberingPhase; distinguishes the caches path from monolithic GHA.
    var lateInterfaceScheme: Boolean = false

    // True whenever static caches are involved (building or consuming one), excluding frameworks: interface
    // itables are emitted in the cache style (exported per-interface vtable arrays + an external `kitable`
    // pointer the numbering pass fills in) rather than baked inline. Keep cache-build and consume consistent.
    val emitCacheStyleItables: Boolean
        get() = config.produce != CompilerOutputKind.FRAMEWORK &&
                (config.produce.isCache || config.cachedLibraries.hasStaticCaches)

    override val typeSystem: IrTypeSystemContext
        get() = IrTypeSystemContextImpl(irBuiltIns)

    var cAdapterExportedElements: CAdapterExportedElements? = null
    var objCExportedInterface: ObjCExportedInterface? = null
    var objCExportCodeSpec: ObjCExportCodeSpec? = null

    fun ghaEnabled() = ::globalHierarchyAnalysisResult.isInitialized

    @OptIn(K1Deprecation::class)
    val stdlibModule
        get() = this.builtIns.any.module

    val declaredLocalArrays: MutableMap<String, LLVMTypeRef> = HashMap()

    val targetAbiInfo = config.target.abiInfo

    override fun dispose() {}

    override val partialLinkageSupport = createPartialLinkageSupportForLowerings(
            config.partialLinkageConfig,
            KtDiagnosticReporterWithImplicitIrBasedContext(
                    configuration.diagnosticsCollector,
                    config.languageVersionSettings,
            )
    )
}

internal class ContextLogger(val context: LoggingContext) {
    operator fun String.unaryPlus() = context.log { this }
}

internal fun LoggingContext.logMultiple(messageBuilder: ContextLogger.() -> Unit) {
    if (!inVerbosePhase) return
    with(ContextLogger(this)) { messageBuilder() }
}
