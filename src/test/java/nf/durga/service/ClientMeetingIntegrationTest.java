package nf.durga.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import nf.durga.GitFixture;
import nf.durga.model.*;
import nf.durga.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientMeetingIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void meetingRemainsLocalAndDueDatesUseOneSharedCheckpoint() throws Exception {
        GitFixture fixture = new GitFixture(directory);
        GitTaskStorage storage = fixture.open(fixture.first);
        try {
            TaskService tasks = new TaskService(storage, new LocalTaskOrderStorage(directory.resolve("order.json").toString()));
            TaskView task = tasks.create(Task.builder().subject("Meeting task").build());
            JsonMeetingStorage local = new JsonMeetingStorage(directory.resolve("meetings.json"));
            ClientMeetingService meetings = new ClientMeetingService(local, tasks);
            meetings.init();
            LocalDateTime date = LocalDateTime.of(2026, 10, 3, 14, 30);
            ClientMeeting meeting = meetings.create(ClientMeeting.builder().serverKey("server")
                    .meetingAt(date).taskIds(List.of(task.task().getId())).build());
            assertThat(local.load()).hasSize(1);
            assertThat(Files.readString(directory.resolve("meetings.json"))).contains(meeting.getId().toString());
            GitClient git = new GitClient(fixture.first);
            assertThat(git.require("show", "--pretty=format:", "--name-only", "HEAD"))
                    .isEqualTo("tasks/" + task.task().getId() + ".json");
            assertThat(tasks.get(task.task().getId()).orElseThrow().task().getDueDate()).isEqualTo(date.toLocalDate());
            Files.writeString(fixture.remote.resolve("hooks").resolve("pre-receive"), "#!/bin/sh\nexit 1\n");
            assertThatThrownBy(() -> meetings.update(meeting.getId(), meeting.toBuilder().meetingAt(date.plusDays(1)).build()))
                    .isInstanceOf(TaskConflictException.class);
            assertThat(meetings.get(meeting.getId()).orElseThrow().getMeetingAt()).isEqualTo(date);
            assertThat(local.load().getFirst().getMeetingAt()).isEqualTo(date);
        } finally {
            storage.close();
        }
    }
}
