package dev.compose.hotreload.sample;

import java.util.function.Function;
import java.util.function.Supplier;

/** Java lambdas and method references are compiled by javac (not kotlinc) */
public final class JavaGreeting {
    private JavaGreeting() {
    }

    public static String text() {
        Supplier<String> supplier = () -> "Java lambda";
        Function<String, String> upper = String::toUpperCase;
        return upper.apply(supplier.get());
    }
}
