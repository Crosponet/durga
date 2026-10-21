package nf.durga.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import nf.durga.GitFixture;
import nf.durga.model.*;
import nf.durga.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TaskServiceTest {
    @TempDir
    Path directory;
    GitFixture fixture;
    GitTaskStorage first;
    GitTaskStorage second;
    TaskService service;
    TaskService peer;

    @BeforeEach
    void setup() throws Exception {
        fixture = new GitFixture(directory);
        first = fixture.open(fixture.first);
        second = fixture.open(fixture.second);
        service = new TaskService(first, new LocalTaskOrderStorage(directory.resolve("order.json").toString()));
        peer = new TaskService(second, new LocalTaskOrderStorage(directory.resolve("peer-order.json").toString()));
    }

    @AfterEach
    void close() {
        first.close();
        second.close();
    }

    TaskView create(String subject) {
        return service.create(Task.builder().subject(subject).status(Status.INVESTIGATION).waitingFor(WaitingFor.NONE).build());
    }

    @Test
    void prerequisiteRulesAndArchiveBypassArePreserved() {
        TaskView requirement = create("Prerequisite");
        TaskView task = service.create(Task.builder().subject("Dependent")
                .requires(List.of(requirement.task().getId())).build());
        assertThatThrownBy(() -> service.changeWorkStatus(task.task().getId(), WorkStatus.ON_IT, task.revision()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Prerequisite");
        assertThatThrownBy(() -> service.changeWorkStatus(task.task().getId(), WorkStatus.FINISHED, task.revision()))
                .isInstanceOf(IllegalStateException.class);
        TaskView archived = service.archive(task.task().getId(), task.revision());
        assertThat(archived.task().getArchived()).isTrue();
        assertThat(archived.task().getWorkStatus()).isEqualTo(WorkStatus.FINISHED);
        assertThat(service.list()).hasSize(1);
    }

    @Test
    void prerequisiteStateIsRefreshedBeforeValidation() {
        TaskView requirement = create("Prerequisite");
        TaskView task = service.create(Task.builder().subject("Dependent")
                .requires(List.of(requirement.task().getId())).build());
        peer.refresh();
        peer.changeWorkStatus(requirement.task().getId(), WorkStatus.FINISHED, requirement.revision());
        TaskView running = service.changeWorkStatus(task.task().getId(), WorkStatus.ON_IT, task.revision());
        assertThat(running.task().getRequires()).containsExactly(requirement.task().getId());
        assertThat(running.task().getWorkStatus()).isEqualTo(WorkStatus.ON_IT);
    }

    @Test
    void manualRequirementRemovalAndPatchNullSemantics() {
        TaskView requirement = create("Prerequisite");
        TaskView task = service.create(Task.builder().subject("Dependent").description("**Markdown**")
                .requires(List.of(requirement.task().getId())).build());
        TaskView patched = service.patch(task.task().getId(), new UpdateTaskRequest(
                null, null, null, null, null, null, null, null, null, null, null, List.of()), task.revision());
        assertThat(patched.task().getDescription()).isEqualTo("**Markdown**");
        assertThat(service.changeWorkStatus(task.task().getId(), WorkStatus.ON_IT, patched.revision())
                .task().getWorkStatus()).isEqualTo(WorkStatus.ON_IT);
    }

    @Test
    void milestonesPreserveMarkdownAndRejectBlank() {
        TaskView task = service.create(Task.builder().subject("Milestone").description("# Description\n").build());
        TaskView updated = service.addMilestone(task.task().getId(), "  Analysis complete  ", task.revision());
        assertThat(updated.task().getDescription()).matches("# Description\\n\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\] Analysis complete");
        assertThatThrownBy(() -> service.addMilestone(task.task().getId(), " ", updated.revision()))
                .isInstanceOf(IllegalArgumentException.class);
        TaskView empty = create("Empty");
        assertThat(service.addMilestone(empty.task().getId(), "First", empty.revision()).task().getDescription())
                .matches("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\] First");
    }

    @Test
    void reorderIsLocalAndDoesNotChangeTaskDocumentsHistoryOrTimestamps() throws Exception {
        TaskView a = create("a");
        TaskView b = create("b");
        String head = new GitClient(fixture.first).require("rev-parse", "HEAD");
        var result = service.reorder(b.task().getId(), 0d);
        assertThat(result.getFirst().task().getId()).isEqualTo(b.task().getId());
        assertThat(service.get(a.task().getId()).orElseThrow().revision()).isEqualTo(a.revision());
        assertThat(service.get(b.task().getId()).orElseThrow().task().getUpdatedAt()).isEqualTo(b.task().getUpdatedAt());
        assertThat(new GitClient(fixture.first).require("rev-parse", "HEAD")).isEqualTo(head);
        assertThat(new GitClient(fixture.first).require("status", "--porcelain")).isEmpty();
        assertThat(Files.readString(directory.resolve("order.json"))).contains(b.task().getId().toString());
        peer.refresh();
        assertThat(Files.exists(fixture.second.resolve("order.json"))).isFalse();
    }

    @Test
    void dueDateBatchIsOneCheckpointAndUnknownIdCausesNoPartialWrite() {
        var a = create("a");
        var b = create("b");
        String count = new GitClient(fixture.first).require("rev-list", "--count", "HEAD");
        assertThatThrownBy(() -> service.setDueDate(List.of(a.task().getId(), UUID.randomUUID()), LocalDate.now()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(service.get(a.task().getId()).orElseThrow().task().getDueDate()).isNull();
        service.setDueDate(List.of(a.task().getId(), b.task().getId()), LocalDate.of(2026, 10, 3));
        assertThat(Integer.parseInt(new GitClient(fixture.first).require("rev-list", "--count", "HEAD")))
                .isEqualTo(Integer.parseInt(count) + 1);
        peer.refresh();
        assertThat(peer.list()).allMatch(v -> v.task().getDueDate().equals(LocalDate.of(2026, 10, 3)));
    }

    @Test
    void staleMutationsRequireRevisionAndCannotOverwrite() {
        TaskView task = create("initial");
        service.changeStatus(task.task().getId(), Status.FIXING, task.revision());
        assertThatThrownBy(() -> service.update(task.task().getId(), task.task(), task.revision()))
                .isInstanceOf(TaskConflictException.class);
        assertThatThrownBy(() -> service.delete(task.task().getId(), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
