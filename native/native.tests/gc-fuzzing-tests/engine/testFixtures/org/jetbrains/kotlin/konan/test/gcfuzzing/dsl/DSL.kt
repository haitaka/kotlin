/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.gcfuzzing.dsl

import java.io.File

data class Program(
    val definitions: List<Definition>,
    val mainBody: Body,
)

fun Program.save(root: File) {
    root.mkdirs()
    root.resolve("dsl").writeText(this.toString())
}

typealias EntityId = Int

sealed interface Definition {
    val targetLanguage: TargetLanguage

    data class Class(
        override val targetLanguage: TargetLanguage,
        val fields: List<Field>,
    ) : Definition

    data class Function(
        override val targetLanguage: TargetLanguage,
        val parameters: List<Parameter>,
        val body: BodyWithReturn,
    ) : Definition

    data class Global(
        override val targetLanguage: TargetLanguage,
        val field: Field,
    ) : Definition
}

sealed interface TargetLanguage {
    data object Kotlin : TargetLanguage
    data object ObjC : TargetLanguage
}

data object Field

data object Parameter

data class Body(
    val statements: List<BodyStatement>,
)

sealed interface BodyStatement {
    data class Alloc(val classId: EntityId, val args: List<LoadExpression>) : BodyStatement
    data class Load(val from: LoadExpression) : BodyStatement
    data class Store(val to: StoreExpression, val from: LoadExpression) : BodyStatement
    data class Call(val functionId: EntityId, val args: List<LoadExpression>) : BodyStatement
    data class SpawnThread(val functionId: EntityId, val args: List<LoadExpression>) : BodyStatement
}

sealed interface LoadExpression {
    data object Default : LoadExpression
    data class Local(val localId: EntityId, val path: Path) : LoadExpression
    data class Global(val globalId: EntityId, val path: Path) : LoadExpression
}

data class Path(val accessors: List<EntityId>)

sealed interface StoreExpression {
    data class Local(val localId: EntityId, val path: Path) : StoreExpression
    data class Global(val globalId: EntityId, val path: Path) : StoreExpression
}

class BodyWithReturn(
    val body: Body,
    val returnExpression: LoadExpression,
)