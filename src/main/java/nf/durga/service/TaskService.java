package nf.durga.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import nf.durga.model.Status;
import nf.durga.model.Task;
import nf.durga.model.WaitingFor;
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
        return tasks.stream()
                .filter(task -> !Boolean.TRUE.equals(task.getArchived()))
                .toList();
    }

    public Optional<Task> get(UUID id) {
        return tasks.stream()
                .filter(task -> task.getId().equals(id))
                .findFirst();
    }

    /**
     * Recherche des tâches selon des critères tous optionnels (les
     * paramètres null sont ignorés). Contrairement à {@link #list()}, les
     * tâches archivées sont incluses sauf si {@code archived} est précisé.
     */
    public List<Task> search(String serverKey, Status status, WaitingFor waitingFor,
                              Boolean archived, Integer priority) {
        return tasks.stream()
                .filter(task -> serverKey == null || serverKey.equals(task.getServerKey()))
                .filter(task -> status == null || status == task.getStatus())
                .filter(task -> waitingFor == null || waitingFor == task.getWaitingFor())
                .filter(task -> archived == null || archived.equals(task.getArchived()))
                .filter(task -> priority == null || priority.equals(task.getPriority()))
                .toList();
    }

    public Task create(Task task) {
        LocalDateTime now = LocalDateTime.now();
        Task toCreate = task.toBuilder()
                .id(UUID.randomUUID())
                .archived(Objects.requireNonNullElse(task.getArchived(), Boolean.FALSE))
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

    /** Marque une tâche comme archivée : elle disparaît de {@link #list()}. */
    public Task archive(UUID id) {
        Task existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));

        Task archived = existing.toBuilder()
                .archived(Boolean.TRUE)
                .updatedAt(LocalDateTime.now())
                .build();

        tasks.set(tasks.indexOf(existing), archived);
        storage.save(tasks);
        return archived;
    }

    public Task changeStatus(UUID id, Status newStatus) {
        Task existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));

        Task updated = existing.toBuilder()
                .status(newStatus)
                .updatedAt(LocalDateTime.now())
                .build();

        tasks.set(tasks.indexOf(existing), updated);
        storage.save(tasks);
        return updated;
    }

    /** Tâches non archivées. */
    public List<Task> getActiveTasks() {
        return list();
    }

    /** Tâches actives en attente d'un tiers (waitingFor != NONE). */
    public List<Task> getBlockedTasks() {
        return tasks.stream()
                .filter(task -> !Boolean.TRUE.equals(task.getArchived()))
                .filter(task -> task.getWaitingFor() != null && task.getWaitingFor() != WaitingFor.NONE)
                .toList();
    }

    /** Tâches actives d'un serveur donné. */
    public List<Task> getTasksByServer(String serverKey) {
        return tasks.stream()
                .filter(task -> !Boolean.TRUE.equals(task.getArchived()))
                .filter(task -> serverKey == null || serverKey.equals(task.getServerKey()))
                .toList();
    }
}
