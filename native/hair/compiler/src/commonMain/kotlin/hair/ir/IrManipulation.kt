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

    /** Nesting depth of the IR modification scopes, see [modificationScope]. */
    internal var modificationDepth = 0

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

/**
 * Runs [action] as an IR modification scope. Scopes nest (e.g. [insertAfter] opens one inside the caller's), but
 * DCE and verification run only when the outermost one exits: inner scopes see a graph that is still being
 * transformed, which neither must interfere with.
 */
private inline fun <T> Session.modificationScope(runDCE: Boolean, action: () -> T): T {
    this as SessionImpl
    modificationDepth++
    val result = try {
        action()
    } finally {
        modificationDepth--
    }
    if (modificationDepth == 0) {
        if (runDCE) eliminateDead()
        verify()
    }
    return result
}

fun <T> Session.buildInitialIR(
    builderAction: context(NodeBuilder, ControlFlowBuilder) ArgumentUpdater.() -> T
): T = modificationScope(runDCE = true) {
    context(nodeBuilder, ControlFlowBuilder(entry)) {
        argUpdater.builderAction()
    }
}

/**
 * Modifies the IR without restructuring the control flow: control-flow edits require the [ControlFlowBuilder]
 * that only [buildInitialIR] and [modifyControlFlow] provide.
 */
fun <T> Session.modifyIR(
    runDCE: Boolean = true,
    builderAction: context(NodeBuilder, NoControlFlowBuilder) ArgumentUpdater.() -> T
): T = modificationScope(runDCE) {
    context(nodeBuilder, NoControlFlowBuilder) {
        argUpdater.builderAction()
    }
}

/**
 * Modifies the IR including its control flow. New control-flow nodes are appended [at] the given position;
 * passes that only edit the existing control flow can leave it at [Session.unreachable].
 */
fun <T> Session.modifyControlFlow(
    at: Controlling = unreachable,
    runDCE: Boolean = true,
    builderAction: context(NodeBuilder, ControlFlowBuilder) ArgumentUpdater.() -> T
): T = modificationScope(runDCE) {
    context(nodeBuilder, ControlFlowBuilder(at)) {
        argUpdater.builderAction()
    }
}

// Control-spine insertion

/**
 * Inserts the control-flow nodes built by [build] into the control spine right after this node, and returns the
 * result of [build].
 */
context(_: ControlFlowBuilder, argUpdater: ArgumentUpdater)
fun <N : Node> Controlling.insertAfter(build: context(NodeBuilder, ControlFlowBuilder) () -> N): N {
    // Captured before building: until it is re-pointed, this node has two control successors.
    val successor = next
    val [result, last] = session.modifyControlFlow(this) {
        build() to contextOf<ControlFlowBuilder>().lastControl
    }
    with(argUpdater) { successor.control = last }
    return result
}

/** Inserts the control-flow nodes built by [build] into the control spine right before this node. */
context(_: ControlFlowBuilder, _: ArgumentUpdater)
fun <N : Node> Controlled.insertBefore(build: context(NodeBuilder, ControlFlowBuilder) () -> N): N =
    control.insertAfter(build)

// Node replacement and removal
//
// Every public operation states what the uses of the removed node become, and refuses kinds of nodes it does not
// handle. Operations that restructure the control flow require a [ControlFlowBuilder].

/**
 * Replaces this control-spine node with an arbitrary control-flow sub-graph.
 *
 * If the replaced node produced a value, [build] must return its replacement, to which all value uses
 * are redirected; return `null` for non-value nodes.
 */
context(_: ControlFlowBuilder)
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

/**
 * Removes a node whose uses are all gone.
 *
 * Private: the public operations always say what the uses of the node become.
 */
context(_: ArgumentUpdater)
private fun Node.kill() {
    require(uses.isEmpty())
    args.withIndex().forEach { [index, _] -> args[index] = null }
    unwireFromGraphAndDeregister()
}

/**
 * The core of every replacement: re-points the control-spine successor at [control], redirects value uses to
 * [value], then kills this node.
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
context(_: ControlFlowBuilder, _: ArgumentUpdater)
fun BlockBody.replaceWith(control: Controlling, value: Node?) {
    requireNoExceptionEdge()
    rewireAndKill(control, value)
}

/** Replaces a spine node with another spine node, which takes over both its control and its value uses. */
context(_: ControlFlowBuilder, _: ArgumentUpdater)
fun BlockBody.replaceWith(node: BlockBody) = replaceWith(node, node)

/**
 * Removes this node from the control spine, joining its predecessor straight to its successor.
 * Value uses are redirected to [value], which may be left out only if there are none.
 */
context(_: ControlFlowBuilder, _: ArgumentUpdater)
fun BlockBody.spliceOut(value: Node? = null) = replaceWith(control, value)

/**
 * Replaces a throwing spine node with another one, carrying the exception edge over.
 *
 * The explicit counterpart of [replaceWith], which refuses a node that can still unwind.
 */
context(_: ControlFlowBuilder, argUpdater: ArgumentUpdater)
fun BlockBodyWithException.replaceThrowing(replacement: BlockBodyWithException) {
    unwind?.let { with(argUpdater) { it.thrower = replacement } }
    rewireAndKill(replacement, replacement)
}

/**
 * Replaces this block exit with [exit] in the predecessor list of the block it leads to, then kills it.
 * [Session.unreachable] as [exit] cuts the edge.
 */
context(_: ControlFlowBuilder, argUpdater: ArgumentUpdater)
fun BlockExit.replaceWith(exit: BlockExit) {
    val target = next
    with(argUpdater) {
        for ([index, pred] in target.preds.withIndex()) {
            if (pred == this@replaceWith) target.preds[index] = exit
        }
    }
    kill()
}

/** Folds a branch known to always go to [taken]: control continues through a [Goto] instead. */
context(_: NodeBuilder, _: ControlFlowBuilder, argUpdater: ArgumentUpdater)
fun If.fold(taken: IfProjection) {
    val dropped = if (taken == trueExit) falseExit else trueExit
    val control = control
    // Detach first, so that the Goto is the only control successor of the predecessor.
    with(argUpdater) { controlOrNull = null }
    taken.replaceWith(Goto(control))
    dropped.replaceWith(session.unreachable)
    kill()
}

/**
 * Replaces this block entry with [control]: the spine of the block continues from [control]. The block must have
 * no phis left.
 */
context(_: ControlFlowBuilder, _: ArgumentUpdater)
fun BlockEntry.replaceWith(control: Controlling) = rewireAndKill(control, null)

/**
 * Discards a block that turned out to be unreachable: its spine successor is re-pointed at [Session.unreachable]
 * and its value uses - its phis - become [NoValue].
 */
context(_: NodeBuilder, _: ControlFlowBuilder, _: ArgumentUpdater)
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
