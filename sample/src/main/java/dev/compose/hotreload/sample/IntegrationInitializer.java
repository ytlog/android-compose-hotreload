package dev.compose.hotreload.sample;

public final class IntegrationInitializer {
    public static String value = initialize();
    private static String initialize() {
        int call = IntegrationInitLedger.next();
        if (call < 0) throw new IllegalStateException("integration-init-failure");
        return "init-v1:" + call;
    }
}
