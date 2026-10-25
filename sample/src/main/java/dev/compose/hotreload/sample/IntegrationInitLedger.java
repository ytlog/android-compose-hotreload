package dev.compose.hotreload.sample;

/** Not edited: preserves observable side effects of a failing user initializer. */
public final class IntegrationInitLedger {
    public static int calls;
    public static int next() { return ++calls; }
}
