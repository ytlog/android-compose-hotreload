package dev.compose.hotreload.sample;

import java.util.UUID;

public final class RefactorService {
    private final String identity = UUID.randomUUID().toString().substring(0, 8);
    public String identity() { return identity; }
    public String format(String value) { return "java-v1:" + value; }
}
