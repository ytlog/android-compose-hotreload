/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.agent

import javassist.ClassPool
import javassist.LoaderClassPath
import javassist.expr.MethodCall
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MethodId
import java.util.WeakHashMap

private val classPools = WeakHashMap<ClassLoader, ClassPool>()

internal fun getClassPool(loader: ClassLoader): ClassPool {
    return classPools.getOrPut(loader) {
        ClassPool().apply {
            appendClassPath(LoaderClassPath(loader))
        }
    }
}

internal val MethodCall.methodId: MethodId
    get() = MethodId(
        classId = ClassId.fromFqn(this.className),
        methodName = this.methodName,
        methodDescriptor = this.method.signature,
    )
