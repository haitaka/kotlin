package hair.ir

import hair.ir.nodes.*
import hair.sym.ArithmeticType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModificationScopeTest : IrTest {

    /**
     * Nested scopes see a graph that is still being transformed: dead code is removed only when the outermost scope
     * exits, so an inner scope must not sweep away nodes the outer one has just built and is about to use.
     */
    @Test
    fun testDeadCodeIsEliminatedOnlyByTheOutermostScope() = withTestSession {
        buildInitialIR {
            ReturnVoid()
        }

        lateinit var pending: Node
        modifyIR {
            pending = Add(ArithmeticType.INT)(Param(0), Param(1))
            modifyIR { }
            assertTrue(pending.inGraph, "an inner scope must not eliminate a node the outer scope still holds")
        }
        assertFalse(pending.inGraph, "the outermost scope must eliminate the node left unused")
    }
}
