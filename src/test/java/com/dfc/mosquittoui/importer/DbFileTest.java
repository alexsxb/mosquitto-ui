package com.dfc.mosquittoui.importer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** With files written by real brokers of these versions. */
class DbFileTest {

    private static byte[] file(String version) throws IOException {
        try (var in = DbFileTest.class.getResourceAsStream("/db/" + version + ".db")) {
            return in.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.6.15", "2.0.22", "2.1.2"})
    void readsRetainedMessages(String version) throws IOException {
        DbFile file = DbFile.parse(file(version));

        assertEquals(6, file.version);
        Map<String, DbFile.Message> messages = file.retained.stream().collect(Collectors.toMap(DbFile.Message::topic, m -> m));
        assertEquals(2, messages.size());
        assertArrayEquals("21.5".getBytes(StandardCharsets.UTF_8), messages.get("home/kitchen/temp").payload());
        assertEquals(1, messages.get("home/kitchen/temp").qos());
        assertArrayEquals("{\"on\":true}".getBytes(StandardCharsets.UTF_8), messages.get("home/hall/state").payload());
        assertEquals(0, messages.get("home/hall/state").qos());
        assertFalse(messages.get("home/hall/state").hasProperties());
    }

    @Test
    void rejectsOtherFiles() throws IOException {
        assertThrows(InvalidFileException.class, () -> DbFile.parse("SQLite format 3 and more".getBytes(StandardCharsets.UTF_8)));
        assertThrows(InvalidFileException.class, () -> DbFile.parse(new byte[0]));
        byte[] data = file("2.1.2");
        assertThrows(InvalidFileException.class, () -> DbFile.parse(Arrays.copyOf(data, data.length - 100)));
    }
}
