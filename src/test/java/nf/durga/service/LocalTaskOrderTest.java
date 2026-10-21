package nf.durga.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import nf.durga.model.Task;
import nf.durga.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalTaskOrderTest {
    @TempDir
    Path directory;

    @Test
    void staleIdsAreIgnoredAndUnknownTasksUseDeterministicFallback() {
        TaskStorage storage = mock(TaskStorage.class);
        LocalTaskOrderStorage order = new LocalTaskOrderStorage(directory.resolve("order.json").toString());
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID stale = UUID.randomUUID();
        order.save(List.of(stale, b));
        when(storage.load()).thenReturn(List.of(
                new TaskStorage.VersionedTask(Task.builder().id(a).build(), "a".repeat(40)),
                new TaskStorage.VersionedTask(Task.builder().id(b).build(), "b".repeat(40))));
        TaskService service = new TaskService(storage, order);
        assertThat(service.list()).extracting(v -> v.task().getId()).containsExactly(b, a);
        order.save(List.of(stale));
        assertThat(service.list()).extracting(v -> v.task().getId()).containsExactly(a, b);
        service.reorder(b, 0d);
        assertThat(order.load()).containsExactly(b, a);
        verify(storage, never()).refresh();
        verify(storage, never()).checkpoint(anyMap(), any());
    }
}
