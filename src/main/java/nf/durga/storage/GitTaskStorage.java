package nf.durga.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import nf.durga.model.Task;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GitTaskStorage implements TaskStorage {
    private final Path root;
    private final GitClient git;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);
    private FileChannel lockChannel;
    private FileLock lock;
    private String remote;
    private String remoteBranch;
    private String branch;

    @org.springframework.beans.factory.annotation.Autowired
    public GitTaskStorage(@Value("${durga.tasks.repository}") String repository) {
        this(repositoryPath(repository));
    }

    private static Path repositoryPath(String repository) {
        if (repository == null || repository.isBlank())
            throw new TaskStorageException("Set durga.tasks.repository to an existing dedicated Git checkout");
        return Path.of(repository);
    }

    public GitTaskStorage(Path repository) {
        this(repository, new GitClient(repository.toAbsolutePath().normalize()));
    }

    GitTaskStorage(Path repository, GitClient git) {
        this.root = repository.toAbsolutePath().normalize();
        this.git = git;
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.isDirectory(root) || !"true".equals(git.require("rev-parse", "--is-inside-work-tree"))
                || !root.equals(Path.of(git.require("rev-parse", "--show-toplevel")).toAbsolutePath().normalize())) {
            throw new TaskStorageException("durga.tasks.repository must be an existing dedicated Git checkout: " + root);
        }
        git.require("rev-parse", "--verify", "HEAD");
        branch = git.require("symbolic-ref", "--short", "HEAD");
        remote = git.require("config", "--get", "branch." + branch + ".remote");
        remoteBranch = git.require("config", "--get", "branch." + branch + ".merge");
        if (remote.equals(".") || !remoteBranch.startsWith("refs/heads/")) {
            throw new TaskStorageException("Configure a remote upstream branch for the Task checkout");
        }
        git.require("rev-parse", "--verify", "@{upstream}");
        actor();
        clean();
        try {
            Path metadata = Path.of(git.require("rev-parse", "--absolute-git-dir"));
            lockChannel = FileChannel.open(metadata.resolve("durga.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) {
                close();
                throw new TaskStorageException("Another Durga instance is using " + root);
            }
        } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
            close();
            throw new TaskStorageException("Cannot lock Task checkout " + root, e);
        }
        try {
            // Validate documents even when the initial fetch is unavailable.
            load();
            try {
                refresh();
            } catch (TaskStorageException e) {
                LoggerFactory.getLogger(getClass()).warn("Task synchronization unavailable; local reads only: {}", e.getMessage());
                load();
            }
        } catch (TaskStorageException e) {
            close();
            throw e;
        }
    }

    @PreDestroy
    public synchronized void close() {
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
            lock = null;
            lockChannel = null;
        } catch (IOException e) {
            throw new TaskStorageException("Cannot release Task checkout lock", e);
        }
    }

    private void clean() {
        if (!git.require("status", "--porcelain", "--untracked-files=all").isBlank()) {
            throw new TaskStorageException("Task checkout must be clean; inspect Git status before retrying");
        }
        for (String name : List.of("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply")) {
            Path path = Path.of(git.require("rev-parse", "--git-path", name));
            if (Files.exists(path.isAbsolute() ? path : root.resolve(path))) {
                throw new TaskStorageException("Unfinished Git operation: " + name);
            }
        }
    }

    @Override
    public synchronized void refresh() {
        if (!Objects.equals(branch, git.require("symbolic-ref", "--short", "HEAD")))
            throw new TaskStorageException("Task checkout branch changed; restart Durga with the intended branch");
        clean();
        git.require("fetch", "--no-tags", remote, remoteBranch);
        String upstream = git.require("rev-parse", "FETCH_HEAD");
        if (!git.isAncestor("HEAD", upstream)) {
            throw new TaskStorageException("Local Task history is ahead or diverged. Inspect Git history and reconcile manually; Durga will not merge or publish it.");
        }
        git.require("merge", "--ff-only", upstream);
        load();
    }

    @Override
    public synchronized String actor() {
        String name = git.require("config", "user.name");
        String email = git.require("config", "user.email");
        if (name.isBlank() || email.isBlank()) throw new TaskStorageException("Configure Git user.name and user.email");
        return name + " <" + email + ">";
    }

    @Override
    public synchronized List<VersionedTask> load() {
        Path directory = root.resolve("tasks");
        if (Files.isSymbolicLink(directory)) throw new TaskStorageException("Task directory cannot be a symlink");
        if (!Files.exists(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            List<VersionedTask> result = new ArrayList<>();
            for (Path file : paths.sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".json")) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new TaskStorageException("Task document must be a regular file: " + file);
                }
                Task task = mapper.readValue(file.toFile(), Task.class);
                if (task.getId() == null || !file.getFileName().toString().equals(task.getId() + ".json")) {
                    throw new TaskStorageException("Task UUID must match filename: " + file);
                }
                String revision = git.require("rev-parse", "--verify", "HEAD:tasks/" + task.getId() + ".json");
                result.add(new VersionedTask(task, revision));
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new TaskStorageException("Cannot read Task documents in " + directory, e);
        }
    }

    @Override
    public synchronized List<VersionedTask> checkpoint(Map<UUID, String> expected,
                                                       Function<List<VersionedTask>, Checkpoint> operation) {
        refresh();
        List<VersionedTask> snapshot = load();
        for (var entry : expected.entrySet()) {
            String actual = snapshot.stream().filter(v -> v.task().getId().equals(entry.getKey()))
                    .map(VersionedTask::revision).findFirst().orElse(null);
            if (!Objects.equals(actual, entry.getValue())) {
                throw new TaskConflictException("Task " + entry.getKey() + " changed remotely. Reload before editing.");
            }
        }
        Checkpoint change = operation.apply(snapshot);
        List<String> paths = new ArrayList<>();
        for (Task task : change.writes()) paths.add("tasks/" + Objects.requireNonNull(task.getId()) + ".json");
        for (UUID id : change.deletes()) paths.add("tasks/" + id + ".json");
        paths = paths.stream().distinct().toList();
        if (paths.isEmpty()) return snapshot;
        String base = git.require("rev-parse", "HEAD");
        try {
            Files.createDirectories(root.resolve("tasks"));
            for (Task task : change.writes())
                mapper.writeValue(root.resolve("tasks").resolve(task.getId() + ".json").toFile(), task);
            for (UUID id : change.deletes()) Files.delete(root.resolve("tasks").resolve(id + ".json"));
            List<String> add = new ArrayList<>(List.of("add", "--"));
            add.addAll(paths);
            git.require(add.toArray(String[]::new));
            GitClient.Result diff = git.run("diff", "--cached", "--quiet");
            if (diff.exitCode() > 1)
                throw new TaskStorageException("Cannot inspect staged Task changes: " + diff.output());
            if (diff.exitCode() == 0) return load();
            git.require("commit", "-m", change.message());
        } catch (IOException | RuntimeException e) {
            restore(base, paths, e);
            throw new TaskStorageException("Task checkpoint failed before publication", e);
        }
        String commit = git.require("rev-parse", "HEAD");
        GitClient.Result push;
        try {
            push = git.run("push", "--porcelain", remote, "HEAD:" + remoteBranch);
        } catch (TaskStorageException e) {
            return verifyPush(base, paths, commit, e);
        }
        if (push.exitCode() == 0) return load();
        if (push.output().lines().anyMatch(line -> line.startsWith("!\t"))) {
            TaskStorageException failure = new TaskStorageException("Git push rejected: " + push.output());
            restore(base, paths, failure);
            throw new TaskConflictException("Task checkpoint was rejected. Refresh and retry. " + push.output());
        }
        return verifyPush(base, paths, commit, new TaskStorageException("Git push failed: " + push.output()));
    }

    private List<VersionedTask> verifyPush(String base, List<String> paths, String commit, RuntimeException failure) {
        try {
            git.require("fetch", "--no-tags", remote, remoteBranch);
        } catch (TaskStorageException verification) {
            failure.addSuppressed(verification);
            throw new TaskStorageException("Push outcome unknown. Local commit " + commit
                    + " retained; explicit refresh or manual Git reconciliation required before writing.", failure);
        }
        if (git.isAncestor(commit, "FETCH_HEAD")) return load();
        restore(base, paths, failure);
        throw new TaskStorageException("Task checkpoint was not published and has been restored. " + failure.getMessage(), failure);
    }

    private void restore(String base, List<String> paths, Exception failure) {
        try {
            // Restore only checkpoint paths. New files are removed individually.
            for (String path : paths) {
                if (!git.require("ls-tree", "--name-only", base, "--", path).isBlank()) {
                    git.require("restore", "--source=" + base, "--staged", "--worktree", "--", path);
                } else {
                    git.require("rm", "--cached", "--ignore-unmatch", "--", path);
                    Files.deleteIfExists(root.resolve(path));
                }
            }
            git.require("update-ref", "HEAD", base, git.require("rev-parse", "HEAD"));
        } catch (IOException | RuntimeException recovery) {
            failure.addSuppressed(recovery);
            throw new TaskStorageException("Cannot restore Task checkpoint; inspect checkout before retrying", failure);
        }
    }
}
