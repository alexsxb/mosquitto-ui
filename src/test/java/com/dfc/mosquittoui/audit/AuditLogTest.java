package com.dfc.mosquittoui.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dfc.mosquittoui.api.Dtos.AuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogTest {

    @TempDir
    Path directory;

    private AuditLog log() {
        AuditLog log = new AuditLog();
        log.mapper = new ObjectMapper().findAndRegisterModules();
        log.stateDir = directory;
        return log;
    }

    private static List<String> actions(AuditLog log) {
        return log.latest(100).stream().map(AuditEntry::action).toList();
    }

    @Test
    void theNewestComesFirstAndEntriesSurviveARestart() {
        AuditLog log = log();
        log.record("alex", "first", Map.of(), null);
        log.record("alex", "second", Map.of("username", "a"), "failed");
        assertEquals(List.of("second", "first"), actions(log));
        assertEquals(List.of("second", "first"), actions(log()));
    }

    @Test
    void restoringAddsWhatIsMissingAndKeepsWhatIsNewer() throws IOException {
        AuditLog log = log();
        log.record("alex", "before the backup", Map.of(), null);
        String backup = log.export();
        Files.delete(directory.resolve("audit-log.jsonl"));

        AuditLog lost = log();
        lost.record("alex", "after the backup", Map.of(), null);
        lost.restore(backup);
        assertEquals(List.of("after the backup", "before the backup"), actions(lost));
        lost.restore(backup);
        assertEquals(2, actions(log()).size(), "nothing twice, also on the disk");
    }

    @Test
    void aLineThatCannotBeReadCostsOnlyThatLine() throws IOException {
        AuditLog log = log();
        log.record("alex", "good", Map.of(), null);
        log.restore("not json\n");
        assertEquals(List.of("good"), actions(log));
    }
}
