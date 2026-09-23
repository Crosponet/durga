package nf.durga.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import nf.durga.model.Task;
import nf.durga.storage.JsonTaskStorage;

/**
 * Contient toute la logique métier relative aux tâches d'investigation :
 * génération des identifiants, horodatage, et persistance via
 * {@link JsonTaskStorage}.
 */
@Service
public class TaskService {

    private final JsonTaskStorage storage;
    private final List<Task> tasks = new ArrayList<>();

    public TaskService(JsonTaskStorage storage) {
        this.storage = storage;
    }

    @PostConstruct
    void init() {
        tasks.addAll(storage.load());
    }

    public List<Task> list() {
        return List.copyOf(tasks);
    }

    public Optional<Task> get(UUID id) {
        return tasks.stream()
                .filter(task -> task.getId().equals(id))
                .findFirst();
    }

    public Task create(Task task) {
        LocalDateTime now = LocalDateTime.now();
        Task toCreate = task.toBuilder()
                .id(UUID.randomUUID())
                .createdAt(now)
                .updatedAt(now)
                .build();
        tasks.add(toCreate);
        storage.save(tasks);
        return toCreate;
    }

    public Task update(UUID id, Task task) {
        Task existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));

        Task updated = task.toBuilder()
                .id(existing.getId())
                .createdAt(existing.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        tasks.set(tasks.indexOf(existing), updated);
        storage.save(tasks);
        return updated;
    }

    public void delete(UUID id) {
        boolean removed = tasks.removeIf(task -> task.getId().equals(id));
        if (!removed) {
            throw new NoSuchElementException("Tâche introuvable : " + id);
        }
        storage.save(tasks);
    }
}
