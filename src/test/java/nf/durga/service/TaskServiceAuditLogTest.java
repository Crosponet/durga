package nf.durga.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import nf.durga.GitFixture;
import nf.durga.model.*;
import nf.durga.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskServiceAuditLogTest {
    @TempDir
    Path directory;

    @Test
    void successfulBusinessActionsAreAuditedButFailedWritesAreNot() throws Exception {
        GitFixture fixture = new GitFixture(directory);
        GitTaskStorage storage = fixture.open(fixture.first);
        try {
            TaskService service = new TaskService(storage, new LocalTaskOrderStorage(directory.resolve("order.json").toString()));
            TaskView task = service.create(Task.builder().subject("Audit").status(Status.INVESTIGATION).build());
            TaskView updated = service.changeStatus(task.task().getId(), Status.FIXING, task.revision());
            service.addMilestone(task.task().getId(), "Logs received", updated.revision());
            String content = Files.readString(Path.of("logs", "task-actions.log")).lines()
                    .filter(line -> line.contains(task.task().getId().toString())).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(content).contains(task.task().getId().toString(), "from=INVESTIGATION to=FIXING",
                    "MILESTONE", "text=\"Logs received\"", "actor=\"Durga Test <durga@example.test>\"");
            Files.writeString(fixture.remote.resolve("hooks").resolve("pre-receive"), "#!/bin/sh\nexit 1\n");
            TaskView current = service.get(task.task().getId()).orElseThrow();
            assertThatThrownBy(() -> service.changeStatus(task.task().getId(), Status.REPORT, current.revision()))
                    .isInstanceOf(TaskConflictException.class);
            assertThat(Files.readString(Path.of("logs", "task-actions.log")).lines()
                    .filter(line -> line.contains(task.task().getId().toString())))
                    .noneMatch(line -> line.contains("from=FIXING to=REPORT"));
        } finally {
            storage.close();
        }
    }
}
