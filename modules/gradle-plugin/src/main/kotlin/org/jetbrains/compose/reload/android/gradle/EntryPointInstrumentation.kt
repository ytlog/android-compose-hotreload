package org.jetbrains.compose.reload.android.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Android counterpart of the upstream agent's 'WindowInstrumentation' (window.kt):
 * upstream replaces 'ComposeWindow.setContent' inside Compose Desktop's 'Window' with
 * 'JvmDevelopmentEntryPoint.setContent'. On Android, 'androidx.activity.compose.setContent'
 * calls 'ComposeView.setContent', which is replaced with 'AndroidDevelopmentEntryPoint.setContent'.
 */
abstract class EntryPointInstrumentation : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "androidx.activity.compose.ComponentActivityKt"

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val api = instrumentationContext.apiVersion.get()
        return object : ClassVisitor(api, nextClassVisitor) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?
            ): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(api, next) {
                    override fun visitMethodInsn(
                        opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean
                    ) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner == COMPOSE_VIEW && name == "setContent" &&
                            descriptor == SET_CONTENT_DESCRIPTOR
                        ) {
                            super.visitMethodInsn(
                                Opcodes.INVOKESTATIC, ENTRY_POINT, "setContent",
                                "(L$COMPOSE_VIEW;Lkotlin/jvm/functions/Function2;)V", false
                            )
                            return
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
    }

    private companion object {
        const val COMPOSE_VIEW = "androidx/compose/ui/platform/ComposeView"
        const val SET_CONTENT_DESCRIPTOR = "(Lkotlin/jvm/functions/Function2;)V"
        const val ENTRY_POINT = "org/jetbrains/compose/reload/android/AndroidDevelopmentEntryPoint"
    }
}
