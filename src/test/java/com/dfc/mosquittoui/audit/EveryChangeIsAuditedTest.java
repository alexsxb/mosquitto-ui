package com.dfc.mosquittoui.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** A new resource that changes something and forgets the log should not get through. */
class EveryChangeIsAuditedTest {

    @Test
    void noResourceThatChangesSomethingIsLeftOut() throws IOException, ClassNotFoundException {
        List<String> missing = new ArrayList<>();
        try (Stream<java.nio.file.Path> files = java.nio.file.Files.list(java.nio.file.Path.of("src/main/java/com/dfc/mosquittoui/api"))) {
            for (java.nio.file.Path file : files.toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                Class<?> type = Class.forName("com.dfc.mosquittoui.api." + name);
                boolean changes = type.isAnnotationPresent(Path.class) && Stream.of(type.getDeclaredMethods()).anyMatch(EveryChangeIsAuditedTest::changes);
                if (changes && !type.isAnnotationPresent(Audited.class)) {
                    missing.add(name);
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    private static boolean changes(Method method) {
        return method.isAnnotationPresent(POST.class) || method.isAnnotationPresent(PUT.class) || method.isAnnotationPresent(DELETE.class);
    }
}
