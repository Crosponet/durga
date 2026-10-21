package nf.durga.storage;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nf.durga.GitFixture;
import nf.durga.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitTaskStorageTest {
    @TempDir
    Path directory;
    GitFixture fixture;
    GitTaskStorage first;
    GitTaskStorage second;

    @BeforeEach
    void setup() throws Exception {
        fixture = new GitFixture(directory);
        first = fixture.open(fixture.first);
        second = fixture.open(fixture.second);
    }

    @AfterEach
    void close() {
        first.close();
        second.close();
    }

    TaskStorage.VersionedTask create(String subject) {
        Task task = Task.builder().id(UUID.randomUUID()).subject(subject).build();
        return first.checkpoint(Map.of(), s -> new TaskStorage.Checkpoint(List.of(task), List.of(), "create"))
                .stream().filter(v -> v.task().getId().equals(task.getId())).findFirst().orElseThrow();
    }

    void update(GitTaskStorage storage, TaskStorage.VersionedTask task, String subject) {
        storage.checkpoint(Map.of(task.task().getId(), task.revision()),
                s -> new TaskStorage.Checkpoint(List.of(task.task().toBuilder().subject(subject).build()), List.of(), "update"));
    }

    @Test
    void oneDocumentPerTaskAndCrud() throws Exception {
        var task = create("# Markdown\n\n**text**");
        Path file = fixture.first.resolve("tasks").resolve(task.task().getId() + ".json");
        assertThat(Files.readString(file)).contains("Markdown").doesNotContain("revision", "sortOrder");
        try (var files = Files.list(fixture.first.resolve("tasks"))) {
            assertThat(files.count()).isEqualTo(1);
        }
        update(first, task, "updated");
        var updated = first.load().getFirst();
        assertThat(updated.revision()).isNotEqualTo(task.revision());
        assertThat(updated.task().getSubject()).isEqualTo("updated");
        first.checkpoint(Map.of(updated.task().getId(), updated.revision()),
                s -> new TaskStorage.Checkpoint(List.of(), List.of(updated.task().getId()), "delete"));
        second.refresh();
        assertThat(second.load()).isEmpty();
    }

    @Test
    void independentTaskBlobRevisionsDoNotConflict() {
        var a = create("a");
        var b = create("b");
        second.refresh();
        update(first, a, "a changed");
        update(second, b, "b changed");
        first.refresh();
        assertThat(first.load()).extracting(v -> v.task().getSubject()).containsExactlyInAnyOrder("a changed", "b changed");
    }

    @Test
    void staleSameTaskCannotOverwriteRemote() {
        var task = create("initial");
        second.refresh();
        update(first, task, "winner");
        assertThatThrownBy(() -> update(second, task, "loser")).isInstanceOf(TaskConflictException.class);
        assertThat(second.load().getFirst().task().getSubject()).isEqualTo("winner");
        first.refresh();
        assertThat(first.load().getFirst().task().getSubject()).isEqualTo("winner");
    }

    @Test
    void fetchToPushRaceFailsEvenForDifferentTasksAndRestoresOnlyCandidate() {
        var a = create("a");
        var b = create("b");
        second.refresh();
        assertThatThrownBy(() -> first.checkpoint(Map.of(a.task().getId(), a.revision()), snapshot -> {
            update(second, b, "concurrent b");
            return new TaskStorage.Checkpoint(List.of(a.task().toBuilder().subject("candidate").build()), List.of(), "race");
        })).isInstanceOf(TaskConflictException.class);
        assertThat(new GitClient(fixture.first).require("status", "--porcelain")).isEmpty();
        first.refresh();
        assertThat(first.load()).extracting(v -> v.task().getSubject()).containsExactlyInAnyOrder("a", "concurrent b");
    }

    @Test
    void rejectedPushRestoresCreationAndCommit() throws Exception {
        Files.writeString(fixture.remote.resolve("hooks").resolve("pre-receive"), "#!/bin/sh\nexit 1\n");
        String head = new GitClient(fixture.first).require("rev-parse", "HEAD");
        assertThatThrownBy(() -> create("rejected")).isInstanceOf(TaskConflictException.class);
        assertThat(first.load()).isEmpty();
        assertThat(new GitClient(fixture.first).require("rev-parse", "HEAD")).isEqualTo(head);
        assertThat(new GitClient(fixture.first).require("status", "--porcelain")).isEmpty();
    }

    @Test
    void dirtyCheckoutAndUnreachableRemoteAreExplicitFailures() throws Exception {
        Files.writeString(fixture.first.resolve("unexpected.txt"), "do not delete");
        assertThatThrownBy(() -> create("fail")).isInstanceOf(TaskStorageException.class).hasMessageContaining("clean");
        assertThat(Files.readString(fixture.first.resolve("unexpected.txt"))).isEqualTo("do not delete");
        Files.delete(fixture.first.resolve("unexpected.txt"));
        new GitClient(fixture.first).require("remote", "set-url", "origin", directory.resolve("missing").toString());
        assertThatThrownBy(() -> create("offline")).isInstanceOf(TaskStorageException.class);
        assertThat(first.load()).isEmpty();
    }

    @Test
    void malformedFilenameIsRejected() throws Exception {
        var task = create("a");
        Path file = fixture.first.resolve("tasks").resolve(task.task().getId() + ".json");
        Files.move(file, file.resolveSibling(UUID.randomUUID() + ".json"));
        assertThatThrownBy(first::load).isInstanceOf(TaskStorageException.class).hasMessageContaining("UUID");
    }

    @Test
    void ambiguousPushThatReachedUpstreamIsSuccessful() {
        first.close();
        first = new GitTaskStorage(fixture.first, new GitClient(fixture.first) {
            @Override
            public Result run(String... args) {
                Result result = super.run(args);
                return args[0].equals("push") && result.exitCode() == 0
                        ? new Result(1, "simulated lost push acknowledgement") : result;
            }
        });
        first.init();
        var created = create("published");
        second.refresh();
        assertThat(second.load().getFirst().revision()).isEqualTo(created.revision());
    }

    @Test
    void ambiguousPushConfirmedAbsentRestoresAndReportsInfrastructureFailure() {
        first.close();
        first = new GitTaskStorage(fixture.first, new GitClient(fixture.first) {
            @Override
            public Result run(String... args) {
                return args[0].equals("push") ? new Result(1, "simulated transport error") : super.run(args);
            }
        });
        first.init();
        String base = new GitClient(fixture.first).require("rev-parse", "HEAD");
        assertThatThrownBy(() -> create("not sent")).isInstanceOf(TaskStorageException.class)
                .hasMessageContaining("restored");
        assertThat(first.load()).isEmpty();
        assertThat(new GitClient(fixture.first).require("rev-parse", "HEAD")).isEqualTo(base);
    }

    @Test
    void indeterminatePushReportsRecoveryErrorAndExplicitRefreshCanConfirmPublication() {
        first.close();
        class LostAcknowledgement extends GitClient {
            boolean unreachable;

            LostAcknowledgement() {
                super(fixture.first);
            }

            @Override
            public Result run(String... args) {
                if (unreachable && args[0].equals("fetch")) return new Result(1, "remote unavailable");
                Result result = super.run(args);
                if (args[0].equals("push") && result.exitCode() == 0) {
                    unreachable = true;
                    return new Result(1, "lost acknowledgement");
                }
                return result;
            }
        }
        LostAcknowledgement git = new LostAcknowledgement();
        first = new GitTaskStorage(fixture.first, git);
        first.init();
        assertThatThrownBy(() -> create("uncertain")).isInstanceOf(TaskStorageException.class)
                .hasMessageContaining("Push outcome unknown");
        assertThatThrownBy(() -> create("cannot write offline")).isInstanceOf(TaskStorageException.class);
        git.unreachable = false;
        first.refresh();
        assertThat(first.load()).hasSize(1);
        second.refresh();
        assertThat(second.load().getFirst().task().getSubject()).isEqualTo("uncertain");
    }

    @Test
    void aheadOrDivergedHistoryIsNeverAutomaticallyPublished() {
        new GitClient(fixture.first).require("commit", "--allow-empty", "-m", "unpublished manual work");
        assertThatThrownBy(() -> create("do not publish")).isInstanceOf(TaskStorageException.class)
                .hasMessageContaining("ahead or diverged");
        assertThat(new GitClient(fixture.second).require("rev-list", "--count", "HEAD")).isEqualTo("1");
    }

    @Test
    void validCheckoutRemainsReadableOfflineOnStartup() throws Exception {
        create("cached");
        first.close();
        new GitClient(fixture.first).require("remote", "set-url", "origin", directory.resolve("unavailable").toString());
        first = fixture.open(fixture.first);
        assertThat(first.load().getFirst().task().getSubject()).isEqualTo("cached");
        assertThatThrownBy(first::refresh).isInstanceOf(TaskStorageException.class);
    }

    @Test
    void commitFailureRestoresTaskWithoutChangingRemote() throws Exception {
        var task = create("original");
        String base = new GitClient(fixture.first).require("rev-parse", "HEAD");
        Files.writeString(fixture.first.resolve(".git").resolve("hooks").resolve("pre-commit"), "#!/bin/sh\nexit 1\n");
        assertThatThrownBy(() -> update(first, task, "not committed")).isInstanceOf(TaskStorageException.class);
        assertThat(new GitClient(fixture.first).require("rev-parse", "HEAD")).isEqualTo(base);
        assertThat(new GitClient(fixture.first).require("status", "--porcelain")).isEmpty();
        assertThat(first.load().getFirst().task().getSubject()).isEqualTo("original");
        second.refresh();
        assertThat(second.load().getFirst().revision()).isEqualTo(task.revision());
    }

    @Test
    void missingRepositoryAndMissingIdentityHaveExplicitErrors() {
        assertThatThrownBy(() -> new GitTaskStorage("").init()).isInstanceOf(TaskStorageException.class)
                .hasMessageContaining("durga.tasks.repository");
        assertThatThrownBy(() -> new GitTaskStorage(directory.resolve("missing")).init())
                .isInstanceOf(TaskStorageException.class).hasMessageContaining("existing dedicated Git checkout");
        new GitClient(fixture.first).require("config", "user.name", "");
        assertThatThrownBy(() -> new GitTaskStorage(fixture.first).init())
                .isInstanceOf(TaskStorageException.class).hasMessageContaining("user.name");
    }
}
