package nf.durga.storage;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import nf.durga.model.Task;

public interface TaskStorage {
    record VersionedTask(Task task, String revision) {
    }

    record Checkpoint(List<Task> writes, List<UUID> deletes, String message) {
    }

    List<VersionedTask> load();

    void refresh();

    String actor();

    /**
     * The callback validates business rules against the refreshed snapshot before any write.
     */
    List<VersionedTask> checkpoint(Map<UUID, String> expected,
                                   Function<List<VersionedTask>, Checkpoint> operation);
}
