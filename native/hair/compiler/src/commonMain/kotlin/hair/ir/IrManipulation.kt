package hair.ir

import hair.ir.nodes.*
import hair.ir.nodes.Node
import hair.opt.Normalization
import hair.opt.eliminateDead
import hair.utils.isEmpty

fun interface OnNodeBuilt {
    fun onNodeBuilt(node: Node): Node
}

class SessionImpl: Session() {
    val nodeBuilder: NodeBuilder = WiringUpNodeBuilder(this)

    val argUpdater: ArgumentUpdater = ArgumentUpdaterImpl(this)
    val normalization = Normalization(this)

    /** Adds the node to tha graph in its canonical form with uses wired */
    fun wireUp(node: Node): Node = node
        .let { normalization.normalize(it) }
        .let { gvn(it) }
        .also { wireToGraph(it) }
        .let { onNodeBuilt.onNodeBuilt(it) }

    private var onNodeBuilt: OnNodeBuilt = OnNodeBuilt { it }

    fun <T> withOnNodeBuiltHandler(onNodeBuiltProvider: (OnNodeBuilt) -> OnNodeBuilt, block: () -> T): T {
        val previous = onNodeBuilt
        onNodeBuilt = onNodeBuiltProvider(previous)
        try {
            return block()
        } finally {
            onNodeBuilt = previous
        }
    }
}

class WiringUpNodeBuilder internal constructor(override val session: SessionImpl) : NodeBuilder {
    override fun onNodeBuilt(node: Node): Node = session.wireUp(node)
}

val Session.nodeBuilder get() = (this as SessionImpl).nodeBuilder
val Session.argUpdater get() = (this as SessionImpl).argUpdater
val Session.normalization get() = (this as SessionImpl).normalization

class ArgumentUpdaterImpl(val session: SessionImpl) : ArgumentUpdater {
    override fun onArgUpdate(node: Node, index: Int, oldValue: Node?, newValue: Node?) {
        require(newValue != oldValue)
        oldValue?.removeUse(node)
        if (newValue != null) {
            val replacement = node
                .let { session.normalization.normalize(it) }
                .let { session.gvn(it) }

            if (replacement != node) {
                node.foldInto(replacement)
            } else {
                newValue.addUse(replacement)
            }
        }
    }
}

fun <T> Session.withOnNodeBuiltHandler(onNodeBuiltProvider: (OnNodeBuilt) -> OnNodeBuilt, block: () -> T): T =
    (this as SessionImpl).withOnNodeBuiltHandler(onNodeBuiltProvider, block)

fun <T> Session.observingBuiltNodes(observer: (Node) -> Unit, block: () -> T): T = withOnNodeBuiltHandler(
    { outer ->
        { node -> outer.onNodeBuilt(node).also { observer(it) } }
    },
    block
)

fun <T> Session.buildInitialIR(
    builderAction: context(NodeBuilder, ControlFlowBuilder) ArgumentUpdater.() -> T
): T {
    return context(nodeBuilder, ControlFlowBuilder(entry)) {
        argUpdater.builderAction().also {
            eliminateDead()
            verify()
        }
    }
}

fun <T> Session.modifyIR(
    runDCE: Boolean = true,
    builderAction: context(NodeBuilder, NoControlFlowBuilder) ArgumentUpdater.() -> T
): T {
    return context(nodeBuilder, NoControlFlowBuilder) {
        argUpdater.builderAction().also {
            if (runDCE) eliminateDead()
            verify()
        }
    }
}

fun <T> Session.modifyControlFlow(
    at: Controlling,
    builderAction: context(NodeBuilder, ControlFlowBuilder) ArgumentUpdater.() -> T
): T {
    return context(nodeBuilder, ControlFlowBuilder(at)) {
        argUpdater.builderAction().also {
            eliminateDead()
            verify()
        }
    }
}

context(argUpdater: ArgumentUpdater)
fun <N: BlockBody> Session.insertAfter(point: Controlling, createNode: context(NodeBuilder, ControlFlowBuilder) () -> N): N {
    val next = point.next
    val node = modifyControlFlow(point) { createNode() }
    with(argUpdater) { next.control = node }
    return node
}

context(_: ArgumentUpdater)
fun <N: BlockBody> Session.insertBefore(point: Controlled, createNode: context(NodeBuilder, ControlFlowBuilder) () -> N): N {
    return insertAfter(point.control, createNode)
}

/**
 * Replaces this control-spine node with an arbitrary control-flow sub-graph.
 *
 * If the replaced node produced a value, [build] must return its replacement, to which all value uses
 * are redirected; return `null` for non-value nodes.
 */
fun BlockBody.replaceWithSubGraph(
    build: context(NodeBuilder, ControlFlowBuilder) ArgumentUpdater.() -> Node?,
) {
    val original = this
    if (original is Throwing) require(original.unwind == null) {
        "replaceWithSubGraph cannot yet re-link the exception edge of $original"
    }
    // Captured before any rewiring: the spine neighbours the sub-graph is spliced between.
    val predecessor = original.control
    val successor = original.next

    session.modifyControlFlow(predecessor) {
        // Detach the node up front so the predecessor's only live control successor is the sub-graph.
        original.controlOrNull = null

        val replacementValue = build()

        val continuation = contextOf<ControlFlowBuilder>().lastControl
        successor.control = continuation

        replacementValue?.let { original.replaceValueUses(it) }
        original.kill()
    }
}

context(_: ArgumentUpdater)
fun Node.kill() {
    require(uses.isEmpty())
    args.withIndex().forEach { [index, _] -> args[index] = null }
    unwireFromGraphAndDeregister()
}

/**
 * The core of every replacement: re-points the control-spine successor at [control], redirects value uses to
 * [value], then kills this node.
 *
 * TODO require a CFG-modification capability here - modifyIR must not permit spine edits.
 */
context(argUpdater: ArgumentUpdater)
private fun Controlling.rewireAndKill(control: Controlling, value: Node?) {
    // Before anything else, while `nextOrNull` still resolves through this node.
    nextOrNull?.let { with(argUpdater) { it.control = control } }
    if (value != null) replaceValueUses(value)
    require(uses.isEmpty()) { "$this still has uses after rewiring; pass a value replacement" }
    kill()
}

/**
 * Redirects every value use of this node to [value], then kills it.
 *
 * Only for nodes off the control spine - a spine node also has a control edge (and possibly an exception edge)
 * to account for, so it needs [replaceWith], [spliceOut] or [replaceThrowing] instead.
 */
context(_: ArgumentUpdater)
fun Node.replaceWith(value: Node) {
    require(this !is ControlFlow) {
        "$this is part of the control flow; use replaceWith(control, value), spliceOut or replaceThrowing"
    }
    replaceValueUses(value)
    kill()
}

/** Replaces a spine node: the successor's control becomes [control], value uses become [value]. */
context(_: ArgumentUpdater)
fun BlockBody.replaceWith(control: Controlling, value: Node?) {
    requireNoExceptionEdge()
    rewireAndKill(control, value)
}

/** Replaces a spine node with another spine node, which takes over both its control and its value uses. */
context(_: ArgumentUpdater)
fun BlockBody.replaceWith(node: BlockBody) = replaceWith(node, node)

/**
 * Removes this node from the control spine, joining its predecessor straight to its successor.
 * Value uses are redirected to [value], which may be left out only if there are none.
 */
context(_: ArgumentUpdater)
fun BlockBody.spliceOut(value: Node? = null) = replaceWith(control, value)

/**
 * Replaces a throwing spine node with another one, carrying the exception edge over.
 *
 * The explicit counterpart of [replaceWith], which refuses a node that can still unwind.
 */
context(argUpdater: ArgumentUpdater)
fun BlockBodyWithException.replaceThrowing(replacement: BlockBodyWithException) {
    unwind?.let { with(argUpdater) { it.thrower = replacement } }
    rewireAndKill(replacement, replacement)
}

/**
 * Discards a block that turned out to be unreachable: its spine successor is re-pointed at [Session.unreachable]
 * and its value uses - its phis - become [NoValue].
 */
context(_: NodeBuilder, _: ArgumentUpdater)
fun BlockEntry.discardAsUnreachable() = rewireAndKill(session.unreachable, NoValue())

/**
 * Kills every node in [nodes], which must be closed under uses - every user of a node in the set is itself in
 * the set. All outgoing argument edges are severed first, so that use lists come out empty even across cycles,
 * which a node-at-a-time [kill] cannot do.
 */
context(_: ArgumentUpdater)
fun Session.killAll(nodes: Collection<Node>) {
    for (node in nodes) {
        for (arg in node.args) arg?.removeUse(node)
    }
    for (node in nodes) {
        node.unwireFromGraphAndDeregister()
    }
}

/**
 * Redirects this node to the [replacement] normalization or GVN folded it into, then kills it.
 *
 * Internal to [ArgumentUpdaterImpl]: unlike the public replacement ops it takes the replacement to be of the
 * same kind as the original - which normalization guarantees - and so carries the exception edge over silently.
 */
context(_: ArgumentUpdater)
internal fun Node.foldInto(replacement: Node) = when (this) {
    is Controlling -> rewireAndKill(replacement as Controlling, replacement)
    else -> {
        replaceValueUses(replacement)
        kill()
    }
}

private fun BlockBody.requireNoExceptionEdge() {
    if (this is Throwing) require(unwind == null) {
        "$this has an exception edge; use replaceThrowing"
    }
}
