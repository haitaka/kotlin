import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.llvm.ContextUtils
import org.jetbrains.kotlin.backend.konan.llvm.StaticData
import org.jetbrains.kotlin.backend.konan.llvm.Struct
import org.jetbrains.kotlin.backend.konan.llvm.classHierarchyIdsSymbolName
import org.jetbrains.kotlin.backend.konan.llvm.isExported
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

internal interface GlobalHierarchyUtils : ContextUtils {

    val IrClass.stableName: String
        get() = classHierarchyIdsSymbolName(generationState.context) // TODO without prefix

    val IrClass.classHierarchyIdsStructName get() = stableName // TODO with prefix

    fun createClassHierarchyIdsStruct(classStableName: String, idLow: Int, idHi: Int) {
        println("createClassHierarchyIdsStruct($classStableName, $idLow, $idHi)")
        val symbolName = classStableName // TODO with prefix
        val global = staticData.getGlobal(symbolName) ?: staticData.createGlobal(runtime.classHierarchyIdsType, symbolName, isExported = true)
        global.setInitializer(Struct(runtime.classHierarchyIdsType, llvm.constInt32(idLow), llvm.constInt32(idHi)))
        global.setConstant(true)
        llvm.usedGlobals += global.llvmGlobal
    }
}
