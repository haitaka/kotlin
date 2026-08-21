package hair.ir

import hair.ir.nodes.Catch
import hair.ir.nodes.Node
import hair.ir.nodes.Throwing
import hair.sym.HairClass
import hair.test.Fun
import hair.utils.printGraphviz
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class BuildersTest : IrTest {

    @Test
    fun testTryCatch() = withTestSession {
        val f = Fun("f")
        val t1 = object : HairClass {}
        val t2 = object : HairClass {}

        buildInitialIR {
            InvokeStatic(f)(callArgs = arrayOf<Node>())
            tryCatch(
                tryBody = {
                    InvokeStatic(f)(callArgs = arrayOf<Node>())
                    InvokeStatic(f)(callArgs = arrayOf<Node>())
                },
                catches = listOf(
                    t1 to {
                        Use(it)
                    },
                    t2 to {
                        Throw(it)
                    }
                )
            )
            ReturnVoid()
        }

        printGraphviz()
    }

    /**
     * Reproduces the shape [hair.ir.tryCatch] sees from IrToHair: the try body is not built by a lambda written
     * inside the `tryCatch` call, but by a long-lived visitor-like object that captured the enclosing builder
     * context earlier. The nodes it builds must still reach `tryCatch`, otherwise no exception edges are created.
     */
    @Test
    fun testTryCatchWithCapturedBuilderContext() = withTestSession {
        val f = Fun("f")
        val t1 = object : HairClass {}

        lateinit var bodyCall: Node

        buildInitialIR {
            val bodyGenerator = object {
                fun generate(): Node = InvokeStatic(f)(callArgs = arrayOf<Node>())
            }

            tryCatch(
                tryBody = { bodyGenerator.generate().also { bodyCall = it } },
                catches = listOf(t1 to { exception -> Use(exception) })
            )
            ReturnVoid()
        }

        val call = bodyCall
        assertIs<Throwing>(call)
        assertNotNull(call.unwind, "the call built through the captured context got no exception edge")
        assertEquals(1, allNodes<Catch>().count())
    }
}
