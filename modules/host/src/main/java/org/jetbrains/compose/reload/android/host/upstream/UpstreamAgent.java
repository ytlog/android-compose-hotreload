package org.jetbrains.compose.reload.android.host.upstream;

import javassist.CtClass;

/**
 * Entry points of the upstream 'hot-reload-agent' that are not tied to {@code java.lang.instrument}.
 * <p>
 * They are Kotlin {@code internal} top-level functions, compiled to public static methods.
 * Calling them from Java lets the host reuse the agent unmodified instead of re-implementing it.
 */
public final class UpstreamAgent {
    private UpstreamAgent() {
    }

    /** Same order as the agent's {@code premain}: devtools, orchestration, pid file, logs */
    public static void start() {
        org.jetbrains.compose.reload.agent.DevToolsKt.startDevTools();
        org.jetbrains.compose.reload.agent.OrchestrationKt.startOrchestration();
        org.jetbrains.compose.reload.agent.PidfileKt.createPidfile();
        org.jetbrains.compose.reload.agent.LoggingKt.startWritingLogs();
    }

    /**
     * Upstream only checks that the original class is loaded (non-null).
     * On Android, the original class lives on the device: callers only pass classes known to the installed app.
     */
    public static void transformForStaticsInitialization(CtClass clazz) {
        org.jetbrains.compose.reload.agent.StaticsInitializationKt.transformForStaticsInitialization(clazz, Object.class);
    }
}
