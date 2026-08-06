/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.ir

import org.jetbrains.kotlin.backend.konan.serialization.SerializedClassHierarchy

/**
 * Whole-program interface coloring + class DFS intervals, computed from a name-keyed hierarchy summary rather
 * than from IR. This is the caches-consuming counterpart of [GlobalHierarchyAnalysis]: the final link merges the
 * fresh module's classes with every consumed cache's [SerializedClassHierarchy] records (so no cached IR is
 * deserialized) and numbers the union here. The numbering only needs to be internally consistent within this one
 * link (the perfect-hash property), so it can be recomputed freely; cross-compilation identity is carried by the
 * TypeInfo symbol names the records are keyed by.
 *
 * Mirrors [GlobalHierarchyAnalysis.run]:
 *  - interfaces get a color from a greedy proper coloring of the co-implementation graph, and an id
 *    `color or (perColorCounter shl bitsPerColor)` so that `id and mask == color` is a perfect hash slot;
 *  - classes get a DFS `enter/exit` interval, with the root (`kotlin.Any`) entering at -1 (matching GHA).
 */
internal class LinkSetHierarchyNumbering(
        val interfaceIds: Map<String, Int>,
        val classIntervals: Map<String, Pair<Int, Int>>,
        val bitsPerColor: Int,
)

internal fun computeLinkSetHierarchyNumbering(
        records: List<SerializedClassHierarchy>,
        rootName: String,
): LinkSetHierarchyNumbering {
    // --- Interface coloring ---
    // Nodes: every declared interface plus every interface that appears in some class's implemented set.
    val interfaceIndex = LinkedHashMap<String, Int>()
    fun registerInterface(name: String): Int = interfaceIndex.getOrPut(name) { interfaceIndex.size }
    records.forEach { if (it.isInterface) registerInterface(it.typeInfoSymbolName) }
    records.forEach { record -> record.interfaceNames.forEach { registerInterface(it) } }

    val interfaceCount = interfaceIndex.size
    val forbidden = Array(interfaceCount) { mutableSetOf<Int>() }
    records.forEach { record ->
        val ifaces = record.interfaceNames
        for (i in ifaces.indices)
            for (j in i + 1 until ifaces.size) {
                val v = interfaceIndex[ifaces[i]]!!
                val u = interfaceIndex[ifaces[j]]!!
                if (v != u) {
                    forbidden[v].add(u)
                    forbidden[u].add(v)
                }
            }
    }

    val colors = IntArray(interfaceCount) { -1 }
    var numberOfColors = 0
    val usedColors = BooleanArray(interfaceCount)
    for (v in 0 until interfaceCount) {
        for (c in 0 until numberOfColors) usedColors[c] = false
        for (u in forbidden[v]) if (colors[u] >= 0) usedColors[colors[u]] = true
        var found = false
        for (c in 0 until numberOfColors)
            if (!usedColors[c]) {
                colors[v] = c
                found = true
                break
            }
        if (!found) colors[v] = numberOfColors++
    }

    val maxColor = colors.maxOrNull() ?: 0
    var bitsPerColor = 0
    var x = maxColor
    while (x > 0) {
        ++bitsPerColor
        x /= 2
    }
    val colorCounts = IntArray(maxColor + 1)
    val interfaceIds = HashMap<String, Int>(interfaceCount)
    for ([name, index] in interfaceIndex) {
        val color = colors[index]
        // Numerate from 1 (reserve 0 for invalid value), matching GHA.
        val counter = ++colorCounts[color]
        interfaceIds[name] = color or (counter shl bitsPerColor)
    }

    // --- Class DFS intervals ---
    val children = HashMap<String, MutableList<String>>()
    records.forEach { record ->
        if (!record.isInterface && record.typeInfoSymbolName != rootName) {
            val superName = record.superTypeInfoSymbolName.ifEmpty { rootName }
            children.getOrPut(superName) { mutableListOf() }.add(record.typeInfoSymbolName)
        }
    }

    val classIntervals = HashMap<String, Pair<Int, Int>>()
    var time = 0
    fun dfs(name: String) {
        ++time
        // Any's interval left border is -1, matching GHA (needed for ObjC block classes).
        val enterTime = if (name == rootName) -1 else time
        children[name]?.forEach { dfs(it) }
        val exitTime = time
        classIntervals[name] = enterTime to exitTime
    }
    dfs(rootName)

    return LinkSetHierarchyNumbering(interfaceIds, classIntervals, bitsPerColor)
}
