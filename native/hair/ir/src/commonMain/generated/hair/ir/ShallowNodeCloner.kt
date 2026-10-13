package hair.ir
import hair.ir.nodes.*
import hair.sym.*
import hair.sym.Type.*

class ShallowNodeCloner(val nodeBuilder: NodeBuilder): NodeVisitor<Node>() {
    override fun visitNode(node: Node): Node = error("Should not reach here $node")

    override fun visitUse(node: Use): Use = context(nodeBuilder, NoCurrentControl) { Use(null, null) } as Use

    override fun visitNoValue(node: NoValue): NoValue = context(nodeBuilder, NoCurrentControl) { NoValue() }

    override fun visitUnitValue(node: UnitValue): UnitValue = context(nodeBuilder, NoCurrentControl) { UnitValue() }

    override fun visitGlobalInit(node: GlobalInit): GlobalInit = context(nodeBuilder, NoCurrentControl) { GlobalInit(node.initRoutine)(null) } as GlobalInit

    override fun visitThreadLocalInit(node: ThreadLocalInit): ThreadLocalInit = context(nodeBuilder, NoCurrentControl) { ThreadLocalInit(node.initRoutine)(null) } as ThreadLocalInit

    override fun visitStandaloneThreadLocalInit(node: StandaloneThreadLocalInit): StandaloneThreadLocalInit = context(nodeBuilder, NoCurrentControl) { StandaloneThreadLocalInit(node.initRoutine)(null) } as StandaloneThreadLocalInit

    override fun visitUnreachable(node: Unreachable): Unreachable = context(nodeBuilder, NoCurrentControl) { Unreachable() }

    override fun visitBlockEntry(node: BlockEntry): BlockEntry = context(nodeBuilder, NoCurrentControl) { BlockEntry(*Array(node.preds.size) { null }) } as BlockEntry

    override fun visitHalt(node: Halt): Halt = context(nodeBuilder, NoCurrentControl) { Halt(null) } as Halt

    override fun visitReturn(node: Return): Return = context(nodeBuilder, NoCurrentControl) { Return(null, null) } as Return

    override fun visitGoto(node: Goto): Goto = context(nodeBuilder, NoCurrentControl) { Goto(null) } as Goto

    override fun visitIf(node: If): If = context(nodeBuilder, NoCurrentControl) { If(null, null) } as If

    override fun visitTrueExit(node: TrueExit): TrueExit = context(nodeBuilder, NoCurrentControl) { TrueExit(null) } as TrueExit

    override fun visitFalseExit(node: FalseExit): FalseExit = context(nodeBuilder, NoCurrentControl) { FalseExit(null) } as FalseExit

    override fun visitThrow(node: Throw): Throw = context(nodeBuilder, NoCurrentControl) { Throw(null, null) } as Throw

    override fun visitUnwind(node: Unwind): Unwind = context(nodeBuilder, NoCurrentControl) { Unwind(null) } as Unwind

    override fun visitReadVar(node: ReadVar): ReadVar = context(nodeBuilder, NoCurrentControl) { ReadVar(node.variable)(null) } as ReadVar

    override fun visitAssignVar(node: AssignVar): AssignVar = context(nodeBuilder, NoCurrentControl) { AssignVar(node.variable)(null, null) } as AssignVar

    override fun visitPhi(node: Phi): Phi = context(nodeBuilder, NoCurrentControl) { Phi(null, *Array(node.joinedValues.size) { null }) } as Phi

    override fun visitPhiPlaceholder(node: PhiPlaceholder): PhiPlaceholder = context(nodeBuilder, NoCurrentControl) { PhiPlaceholder(node.origin)(null, *Array(node.joinedValues.size) { null }) } as PhiPlaceholder

    override fun visitParam(node: Param): Param = context(nodeBuilder, NoCurrentControl) { Param(node.index) }

    override fun visitCatch(node: Catch): Catch = context(nodeBuilder, NoCurrentControl) { Catch(null) } as Catch

    override fun visitConst(node: Const): Const = context(nodeBuilder, NoCurrentControl) { Const(node.value) }

    override fun visitNull(node: Null): Null = context(nodeBuilder, NoCurrentControl) { Null() }

    override fun visitTrue(node: True): True = context(nodeBuilder, NoCurrentControl) { True() }

    override fun visitFalse(node: False): False = context(nodeBuilder, NoCurrentControl) { False() }

    override fun visitAdd(node: Add): Add = context(nodeBuilder, NoCurrentControl) { Add(node.opType)(null, null) } as Add

    override fun visitSub(node: Sub): Sub = context(nodeBuilder, NoCurrentControl) { Sub(node.opType)(null, null) } as Sub

    override fun visitMul(node: Mul): Mul = context(nodeBuilder, NoCurrentControl) { Mul(node.opType)(null, null) } as Mul

    override fun visitDiv(node: Div): Div = context(nodeBuilder, NoCurrentControl) { Div(node.opType)(null, null) } as Div

    override fun visitRem(node: Rem): Rem = context(nodeBuilder, NoCurrentControl) { Rem(node.opType)(null, null) } as Rem

    override fun visitNeg(node: Neg): Neg = context(nodeBuilder, NoCurrentControl) { Neg(null) } as Neg

    override fun visitAnd(node: And): And = context(nodeBuilder, NoCurrentControl) { And(node.opType)(null, null) } as And

    override fun visitOr(node: Or): Or = context(nodeBuilder, NoCurrentControl) { Or(node.opType)(null, null) } as Or

    override fun visitXor(node: Xor): Xor = context(nodeBuilder, NoCurrentControl) { Xor(node.opType)(null, null) } as Xor

    override fun visitShl(node: Shl): Shl = context(nodeBuilder, NoCurrentControl) { Shl(node.opType)(null, null) } as Shl

    override fun visitShr(node: Shr): Shr = context(nodeBuilder, NoCurrentControl) { Shr(node.opType)(null, null) } as Shr

    override fun visitUshr(node: Ushr): Ushr = context(nodeBuilder, NoCurrentControl) { Ushr(node.opType)(null, null) } as Ushr

    override fun visitInv(node: Inv): Inv = context(nodeBuilder, NoCurrentControl) { Inv(null) } as Inv

    override fun visitCmp(node: Cmp): Cmp = context(nodeBuilder, NoCurrentControl) { Cmp(node.type, node.op)(null, null) } as Cmp

    override fun visitNot(node: Not): Not = context(nodeBuilder, NoCurrentControl) { Not(null) } as Not

    override fun visitSignExtend(node: SignExtend): SignExtend = context(nodeBuilder, NoCurrentControl) { SignExtend(node.targetType)(null) } as SignExtend

    override fun visitZeroExtend(node: ZeroExtend): ZeroExtend = context(nodeBuilder, NoCurrentControl) { ZeroExtend(node.targetType)(null) } as ZeroExtend

    override fun visitTruncate(node: Truncate): Truncate = context(nodeBuilder, NoCurrentControl) { Truncate(node.targetType)(null) } as Truncate

    override fun visitReinterpret(node: Reinterpret): Reinterpret = context(nodeBuilder, NoCurrentControl) { Reinterpret(node.targetType)(null) } as Reinterpret

    override fun visitNew(node: New): New = context(nodeBuilder, NoCurrentControl) { New(node.objectType)(null) } as New

    override fun visitNewArray(node: NewArray): NewArray = context(nodeBuilder, NoCurrentControl) { NewArray(node.elementType)(null, null) } as NewArray

    override fun visitIsInstanceOf(node: IsInstanceOf): IsInstanceOf = context(nodeBuilder, NoCurrentControl) { IsInstanceOf(node.targetType)(null) } as IsInstanceOf

    override fun visitCheckCast(node: CheckCast): CheckCast = context(nodeBuilder, NoCurrentControl) { CheckCast(node.targetType)(null, null) } as CheckCast

    override fun visitTypeInfo(node: TypeInfo): TypeInfo = context(nodeBuilder, NoCurrentControl) { TypeInfo(null) } as TypeInfo

    override fun visitConstTypeInfo(node: ConstTypeInfo): ConstTypeInfo = context(nodeBuilder, NoCurrentControl) { ConstTypeInfo(node.type) }

    override fun visitArraySize(node: ArraySize): ArraySize = context(nodeBuilder, NoCurrentControl) { ArraySize(null) } as ArraySize

    override fun visitArrayIndexCheck(node: ArrayIndexCheck): ArrayIndexCheck = context(nodeBuilder, NoCurrentControl) { ArrayIndexCheck(null, null, null) } as ArrayIndexCheck

    override fun visitLoad(node: Load): Load = context(nodeBuilder, NoCurrentControl) { Load(node.type)(null, null) } as Load

    override fun visitStore(node: Store): Store = context(nodeBuilder, NoCurrentControl) { Store(node.type)(null, null, null) } as Store

    override fun visitLoadField(node: LoadField): LoadField = context(nodeBuilder, NoCurrentControl) { LoadField(node.field)(null, null) } as LoadField

    override fun visitStoreField(node: StoreField): StoreField = context(nodeBuilder, NoCurrentControl) { StoreField(node.field)(null, null, null) } as StoreField

    override fun visitLoadGlobal(node: LoadGlobal): LoadGlobal = context(nodeBuilder, NoCurrentControl) { LoadGlobal(node.field)(null) } as LoadGlobal

    override fun visitStoreGlobal(node: StoreGlobal): StoreGlobal = context(nodeBuilder, NoCurrentControl) { StoreGlobal(node.field)(null, null) } as StoreGlobal

    override fun visitLoadArrayElement(node: LoadArrayElement): LoadArrayElement = context(nodeBuilder, NoCurrentControl) { LoadArrayElement(node.elementType)(null, null, null) } as LoadArrayElement

    override fun visitStoreArrayElement(node: StoreArrayElement): StoreArrayElement = context(nodeBuilder, NoCurrentControl) { StoreArrayElement(node.elementType)(null, null, null, null) } as StoreArrayElement

    override fun visitInvokeStatic(node: InvokeStatic): InvokeStatic = context(nodeBuilder, NoCurrentControl) { InvokeStatic(node.function)(null, *Array(node.callArgs.size) { null }) } as InvokeStatic

    override fun visitInvokeVirtual(node: InvokeVirtual): InvokeVirtual = context(nodeBuilder, NoCurrentControl) { InvokeVirtual(node.function)(null, *Array(node.callArgs.size) { null }) } as InvokeVirtual

}

