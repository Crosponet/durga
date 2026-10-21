package nf.durga.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Small non-interactive CLI adapter. No business rules.
 */
public class GitClient {
    public record Result(int exitCode, String output) {
    }

    private final Path repository;

    public GitClient(Path repository) {
        this.repository = repository;
    }

    public Result run(String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "--no-pager", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GCM_INTERACTIVE", "never");
            for (String key : List.of("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL"))
                builder.environment().remove(key);
            process = builder.start();
            Process running = process;
            var output = new java.io.ByteArrayOutputStream();
            var readFailure = new java.util.concurrent.atomic.AtomicReference<IOException>();
            Thread reader = Thread.startVirtualThread(() -> {
                try (var input = running.getInputStream()) {
                    input.transferTo(output);
                } catch (IOException e) {
                    readFailure.set(e);
                }
            });
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new TaskStorageException("Git timed out: " + arguments[0]);
            }
            reader.join(5000);
            if (reader.isAlive()) {
                throw new TaskStorageException("Git output did not close: " + arguments[0]);
            }
            if (readFailure.get() != null)
                throw new TaskStorageException("Cannot read Git output: " + arguments[0], readFailure.get());
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            throw new TaskStorageException("Cannot execute Git in " + repository, e);
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new TaskStorageException("Git interrupted", e);
        }
    }

    public String require(String... arguments) {
        Result result = run(arguments);
        if (result.exitCode() != 0) {
            throw new TaskStorageException("Git " + String.join(" ", arguments) + " failed: " + result.output());
        }
        return result.output();
    }

    public boolean isAncestor(String ancestor, String descendant) {
        Result result = run("merge-base", "--is-ancestor", ancestor, descendant);
        if (result.exitCode() > 1)
            throw new TaskStorageException("Cannot inspect Git ancestry: " + result.output());
        return result.exitCode() == 0;
    }
}
