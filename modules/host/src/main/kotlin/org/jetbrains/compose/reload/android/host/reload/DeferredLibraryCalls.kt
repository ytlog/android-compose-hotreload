package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MethodId
import org.objectweb.asm.Type

/**
 * Public API execution contracts for lazy State/Flow/Lazy factories excluded from upstream tracking.
 * Exact owner/name/descriptor matching matters: terminal operations and unknown overloads must
 * retain conservative evaluation. These are dependency rules, not invalidation/reset exceptions.
 *
 * derivedStateOf/snapshotFlow: developer.android.com/develop/ui/compose/side-effects
 * Flow intermediates: kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-flow/
 */
internal object DeferredLibraryCalls {
    private const val state = "androidx/compose/runtime/State"
    private const val policy = "androidx/compose/runtime/SnapshotMutationPolicy"
    private const val flow = "kotlinx/coroutines/flow/Flow"
    private const val snapshot = "androidx/compose/runtime/SnapshotStateKt"
    private const val flowKt = "kotlinx/coroutines/flow/FlowKt"
    private const val function = "kotlin/jvm/functions/Function"
    private const val continuation = "Lkotlin/coroutines/Continuation;"

    private val factories = setOf(
        MethodId(ClassId("kotlin/LazyKt"), "lazy", "(L${function}0;)Lkotlin/Lazy;"),
        MethodId(ClassId("kotlin/LazyKt"), "lazy", "(Lkotlin/LazyThreadSafetyMode;L${function}0;)Lkotlin/Lazy;"),
        MethodId(ClassId("kotlin/LazyKt"), "lazy", "(Ljava/lang/Object;L${function}0;)Lkotlin/Lazy;"),
        MethodId(ClassId(snapshot), "derivedStateOf", "(L${function}0;)L$state;"),
        MethodId(ClassId(snapshot), "derivedStateOf", "(L$policy;L${function}0;)L$state;"),
        MethodId(ClassId(snapshot), "snapshotFlow", "(L${function}0;)L$flow;"),
        MethodId(ClassId(flowKt), "flow", "(L${function}2;)L$flow;"),
        MethodId(ClassId(flowKt), "onStart", "(L$flow;L${function}2;)L$flow;"),
        MethodId(ClassId(flowKt), "onEach", "(L$flow;L${function}2;)L$flow;"),
        MethodId(ClassId(flowKt), "onCompletion", "(L$flow;L${function}3;)L$flow;"),
    )

    fun defers(id: MethodId): Boolean = id in factories

    /** Reading a lazy wrapper during remember is evaluation, even without an explicit lambda arg. */
    fun evaluatedTypes(id: MethodId, nullArguments: Set<Int> = emptySet()): Set<ClassId> {
        val arguments = Type.getArgumentTypes(id.methodDescriptor).filterIndexed { index, _ -> index !in nullArguments }.filter {
            it.sort == Type.OBJECT && it.internalName != "java/lang/Object"
        }.mapTo(mutableSetOf()) { ClassId(it.internalName) }
        if (id == MethodId(ClassId(state), "getValue", "()Ljava/lang/Object;")) arguments += ClassId(state)
        if (id == MethodId(ClassId("kotlin/Lazy"), "getValue", "()Ljava/lang/Object;")) arguments += ClassId("${function}0")
        if (id == MethodId(ClassId(flow), "collect", "(Lkotlinx/coroutines/flow/FlowCollector;$continuation)Ljava/lang/Object;")) {
            arguments += ClassId(flow)
        }
        // Unknown APIs accepting wrappers may consume them synchronously. Include the deferred
        // calculations, not just app-defined implementations of the State/Flow/Lazy interface.
        if (ClassId(state) in arguments) arguments += setOf(ClassId("${function}0"), ClassId(policy))
        if (ClassId("kotlin/Lazy") in arguments) arguments += ClassId("${function}0")
        if (ClassId(flow) in arguments) {
            arguments += ClassId("${function}0") // snapshotFlow calculation
            (1..22).forEach { arguments += ClassId("$function$it") } // suspend operators/combine arities
        }
        return arguments
    }
}
