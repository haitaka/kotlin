package hair.ir.nodes

import hair.utils.listIterator
import kotlin.jvm.JvmInline
import kotlin.reflect.KClass
import kotlin.reflect.cast

@JvmInline
value class ArgsList(val host: NodeBase) : Iterable<Node?> {
    internal val elements: Array<Node?> get() = host.args_
    fun getOrNull(index: Int): Node? = elements[index]
    operator fun get(index: Int): Node = getOrNull(index)!!
    fun iterator(starting: Int) = elements.listIterator(starting)
    override fun iterator() = iterator(0)
    operator fun contains(element: Node) = elements.contains(element)
}

@Suppress("UNCHECKED_CAST")
class VarArgsList<N: Node>(val argsList: ArgsList, val firstVarargIndex: Int, val type: KClass<N>) : Iterable<N> {
    val size: Int = argsList.elements.size - firstVarargIndex // TODO make list expandable?
    fun isEmpty(): Boolean = size == 0
    fun isNotEmpty(): Boolean = !isEmpty()
    fun getOrNull(index: Int): N? = argsList.getOrNull(index + firstVarargIndex) as N?
    operator fun get(index: Int): N = getOrNull(index)!!
    override fun iterator() = object : Iterator<N> {
        val baseIter = argsList.iterator(firstVarargIndex)
        // TODO replace with unckecked cast?
        override fun next(): N = type.cast(baseIter.next()!!)
        override fun hasNext(): Boolean = baseIter.hasNext()
    }
    val withNulls: Iterable<N?> get() = object : Iterable<N?> {
        override fun iterator(): Iterator<N?> = object : Iterator<N?> {
            val baseIter = argsList.iterator(firstVarargIndex)
            override fun next(): N? = baseIter.next() as N?
            override fun hasNext(): Boolean = baseIter.hasNext()
        }
    }
}

inline fun <reified N: Node> VarArgsList<N>.toTypedArray(): Array<N> = toList().toTypedArray()

interface ArgumentUpdaterBase {
    fun onArgUpdate(node: Node, index: Int, oldValue: Node?, newValue: Node?)
}

context(argsUpdater: ArgumentUpdaterBase)
fun Node.replaceValueUses(by: Node) {
    // TODO optimize, perhaps introduce abstraction of edge?
    for (use in this.uses.toList()) {
        val drop = if (use is Controlled) 1 else 0
        val useEdges = use.args.withIndex().drop(drop).filter { it.value == this }
        for ([argIndex, _] in useEdges) {
            use.args[argIndex] = by
        }
    }
}

context(argsUpdater: ArgumentUpdaterBase)
private fun Node.updateArg(index: Int, oldValue: Node?, newValue: Node?, update: () -> Unit) {
    if (oldValue == newValue) return

    if (this is Phi) {
        require(newValue !is BlockExit && newValue !is BlockEntry )
    }

    form.deregister(this)

    update()

    argsUpdater.onArgUpdate(this, index, oldValue, newValue)
}

/**
 * Sets a single argument edge.
 *
 * The update is reported to the [ArgumentUpdaterBase], which may normalize the host node or find an equal one
 * via GVN, and then fold the host into that replacement. So after `host.args[i] = x` the host itself may be gone:
 * do not keep using a reference to it, but re-read the node from its use or from the returned structure.
 */
context(argsUpdater: ArgumentUpdaterBase)
operator fun ArgsList.set(index: Int, element: Node?): Node? {
    val old = elements[index]
    host.updateArg(index, old, element) {
        elements[index] = element
    }
    return old
}

/** Appends [exits] to the predecessors of this block, which must not have phis yet. */
context(_: ControlFlowBuilder, _: ArgumentUpdaterBase)
fun BlockEntry.addPreds(vararg exits: BlockExit) {
    require(uses.none { it is Phi }) { "Can't add predecessors to $this: its phis would miss the joined values" }
    this as NodeBase
    val oldSize = args_.size
    args_ = args_.copyOf(oldSize + exits.size)
    // Fill the new slots through the regular single-edge path, so that every use is wired exactly once.
    for ([offset, exit] in exits.withIndex()) {
        args[oldSize + offset] = exit
    }
}

context(_: ArgumentUpdaterBase)
fun ArgsList.erase(index: Int): Node? = set(index, null)

context(argsUpdater: ArgumentUpdaterBase)
operator fun <N : Node> VarArgsList<N>.set(index: Int, value: N?) {
    argsList[index + firstVarargIndex] = value
}
