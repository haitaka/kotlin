@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class)
import kotlin.native.runtime.GC
import cinterop.*

class ItemWithDict(val dict: Map<String, Any>) {}

fun getItemsWithDict(): List<ItemWithDict> {
    val items = mutableListOf<ItemWithDict>()
    for (i in 1..10000) {
        val properties = mapOf(
            "key1" to "value${i}",
            "key2" to "value${i}",
            "key3" to "value${i}",
            "key4" to "value${i}",
        )
        items.add(ItemWithDict(dict = properties))
    }
    return items
}

fun produce(): List<Any> {
    return List(100_000) { Any() }
}

fun consume(x: Any?) {
    useArray(x as List<Any?>);
}

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
fun main() {
    task(::produce, GC::collect, GC::schedule, ::consume);

}
