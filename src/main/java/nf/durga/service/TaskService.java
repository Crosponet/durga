package nf.durga.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import nf.durga.model.Status;
import nf.durga.model.Task;
import nf.durga.model.WaitingFor;
import nf.durga.model.WorkStatus;
import nf.durga.storage.JsonTaskStorage;

/**
 * Contient toute la logique métier relative aux tâches d'investigation :
 * génération des identifiants, horodatage, et persistance via
 * {@link JsonTaskStorage}.
 */
@Service
public class TaskService {

    /**
     * Journal métier dédié aux actions sur les tâches (création, changement
     * de statut/état de travail, archivage, suppression...). Configuré dans
     * {@code logback-spring.xml} avec un fichier distinct et une rotation
     * quotidienne, afin de pouvoir tracer/estimer les tâches travaillées sur
     * une journée donnée.
     */
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("nf.durga.audit.task");

    private static final DateTimeFormatter MILESTONE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JsonTaskStorage storage;
    private final List<Task> tasks = new ArrayList<>();

    public TaskService(JsonTaskStorage storage) {
        this.storage = storage;
    }

    @PostConstruct
    void init() {
        tasks.addAll(storage.load());
        backfillWorkStatus();
        backfillSortOrder();
    }

    /** Attribue TODO aux tâches issues d'un ancien format JSON. */
    private void backfillWorkStatus() {
        boolean changed = false;
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            if (task.getWorkStatus() == null) {
                tasks.set(i, task.toBuilder().workStatus(WorkStatus.TODO).build());
                changed = true;
            }
        }
        if (changed) {
            storage.save(tasks);
        }
    }

    /** Attribue un sortOrder aux tâches existantes qui n'en ont pas encore (migration douce). */
    private void backfillSortOrder() {
        double next = tasks.stream()
                .map(Task::getSortOrder)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0d);
        boolean changed = false;
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            if (task.getSortOrder() == null) {
                next += 1;
                tasks.set(i, task.toBuilder().sortOrder(next).build());
                changed = true;
            }
        }
        if (changed) {
            storage.save(tasks);
        }
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
                              Boolean archived, Integer priority, WorkStatus workStatus) {
        return tasks.stream()
                .filter(task -> serverKey == null || serverKey.equals(task.getServerKey()))
                .filter(task -> status == null || status == task.getStatus())
                .filter(task -> waitingFor == null || waitingFor == task.getWaitingFor())
                .filter(task -> archived == null || archived.equals(task.getArchived()))
                .filter(task -> priority == null || priority.equals(task.getPriority()))
                .filter(task -> workStatus == null || workStatus == task.getWorkStatus())
                .toList();
    }

    public Task create(Task task) {
        LocalDateTime now = LocalDateTime.now();
        double nextSortOrder = tasks.stream()
                .map(Task::getSortOrder)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0d) + 1;
        boolean becomingArchived = Boolean.TRUE.equals(
                Objects.requireNonNullElse(task.getArchived(), Boolean.FALSE));
        WorkStatus requestedWorkStatus = Objects.requireNonNullElse(task.getWorkStatus(), WorkStatus.TODO);
        WorkStatus finalWorkStatus = becomingArchived ? WorkStatus.FINISHED : requestedWorkStatus;
        if (!becomingArchived) {
            ensureCanTransitionWorkStatus(task.getRequires(), finalWorkStatus);
        }
        Task toCreate = task.toBuilder()
                .id(UUID.randomUUID())
                .archived(becomingArchived)
                .workStatus(finalWorkStatus)
                .sortOrder(Objects.requireNonNullElse(task.getSortOrder(), nextSortOrder))
                .createdAt(now)
                .updatedAt(now)
                .build();
        tasks.add(toCreate);
        storage.save(tasks);
        AUDIT_LOG.info("CREATE task={} subject=\"{}\" status={} workStatus={} serverKey={}",
                toCreate.getId(), toCreate.getSubject(), toCreate.getStatus(),
                toCreate.getWorkStatus(), toCreate.getServerKey());
        return toCreate;
    }

    public Task update(UUID id, Task task) {
        Task existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));

        boolean becomingArchived = Boolean.TRUE.equals(task.getArchived());
        WorkStatus requestedWorkStatus = Objects.requireNonNullElse(task.getWorkStatus(),
                Objects.requireNonNullElse(existing.getWorkStatus(), WorkStatus.TODO));
        WorkStatus finalWorkStatus = becomingArchived ? WorkStatus.FINISHED : requestedWorkStatus;
        if (!becomingArchived && finalWorkStatus != existing.getWorkStatus()) {
            ensureCanTransitionWorkStatus(task.getRequires(), finalWorkStatus);
        }

        Task updated = task.toBuilder()
                .id(existing.getId())
                .createdAt(existing.getCreatedAt())
                .workStatus(finalWorkStatus)
                .updatedAt(LocalDateTime.now())
                .build();

        tasks.set(tasks.indexOf(existing), updated);
        storage.save(tasks);
        AUDIT_LOG.info("UPDATE task={} subject=\"{}\"", updated.getId(), updated.getSubject());
        return updated;
    }

    public Task patch(UUID id, UpdateTaskRequest request) {
        Task existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));

        boolean becomingArchived = Boolean.TRUE.equals(
                request.archived() != null ? request.archived() : existing.getArchived());
        WorkStatus requestedWorkStatus = request.workStatus() != null
                ? request.workStatus() : existing.getWorkStatus();
        WorkStatus finalWorkStatus = becomingArchived ? WorkStatus.FINISHED : requestedWorkStatus;
        List<UUID> finalRequires = request.requires() != null ? request.requires() : existing.getRequires();
        if (!becomingArchived && finalWorkStatus != existing.getWorkStatus()) {
            ensureCanTransitionWorkStatus(finalRequires, finalWorkStatus);
        }

        Task updated = existing.toBuilder()
                .subject(request.subject() != null ? request.subject() : existing.getSubject())
                .description(request.description() != null ? request.description() : existing.getDescription())
                .iteration(request.iteration() != null ? request.iteration() : existing.getIteration())
                .status(request.status() != null ? request.status() : existing.getStatus())
                .workStatus(finalWorkStatus)
                .waitingFor(request.waitingFor() != null ? request.waitingFor() : existing.getWaitingFor())
                .waitingForCustom(request.waitingForCustom() != null
                        ? request.waitingForCustom()
                        : existing.getWaitingForCustom())
                .priority(request.priority() != null ? request.priority() : existing.getPriority())
                .dueDate(request.dueDate() != null ? request.dueDate() : existing.getDueDate())
                .archived(becomingArchived)
                .jiraUrl(request.jiraUrl() != null ? request.jiraUrl() : existing.getJiraUrl())
                .requires(finalRequires)
                .updatedAt(LocalDateTime.now())
                .build();

        tasks.set(tasks.indexOf(existing), updated);
        storage.save(tasks);
        logPatch(existing, updated);
        return updated;
    }

    /** Journalise uniquement les champs qui ont réellement changé lors d'un {@link #patch}. */
    private void logPatch(Task existing, Task updated) {
        List<String> changes = new ArrayList<>();
        addChange(changes, "status", existing.getStatus(), updated.getStatus());
        addChange(changes, "workStatus", existing.getWorkStatus(), updated.getWorkStatus());
        addChange(changes, "waitingFor", existing.getWaitingFor(), updated.getWaitingFor());
        addChange(changes, "priority", existing.getPriority(), updated.getPriority());
        addChange(changes, "dueDate", existing.getDueDate(), updated.getDueDate());
        addChange(changes, "archived", existing.getArchived(), updated.getArchived());
        addChange(changes, "subject", existing.getSubject(), updated.getSubject());

        AUDIT_LOG.info("PATCH task={} subject=\"{}\" changes=[{}]",
                updated.getId(), updated.getSubject(),
                changes.isEmpty() ? "aucun changement de champ suivi" : String.join(", ", changes));
    }

    private void addChange(List<String> changes, String field, Object oldValue, Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.add(field + "=" + oldValue + "->" + newValue);
        }
    }

    public void delete(UUID id) {
        Task existing = get(id).orElse(null);
        boolean removed = tasks.removeIf(task -> task.getId().equals(id));
        if (!removed) {
            throw new NoSuchElementException("Tâche introuvable : " + id);
        }
        storage.save(tasks);
        AUDIT_LOG.info("DELETE task={} subject=\"{}\"",
                id, existing != null ? existing.getSubject() : null);
    }

    /**
     * Marque une tâche comme archivée : elle disparaît de {@link #list()}.
     * Une tâche qui devient archivée passe automatiquement en workStatus
     * FINISHED (sans validation des tâches préalables : l'archivage est une
     * action administrative qui prime sur la règle de blocage).
     */
    public Task archive(UUID id) {
        Task existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));

        Task archived = existing.toBuilder()
                .archived(Boolean.TRUE)
                .workStatus(WorkStatus.FINISHED)
                .updatedAt(LocalDateTime.now())
                .build();

        tasks.set(tasks.indexOf(existing), archived);
        storage.save(tasks);
        AUDIT_LOG.info("ARCHIVE task={} subject=\"{}\"", archived.getId(), archived.getSubject());
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
        AUDIT_LOG.info("STATUS_CHANGE task={} subject=\"{}\" from={} to={}",
                updated.getId(), updated.getSubject(), existing.getStatus(), newStatus);
        return updated;
    }

    /**
     * Ajoute un jalon horodaté ({@code [yyyy-MM-dd HH:mm] texte}) sur une
     * nouvelle ligne à la fin de la description de la tâche.
     */
    public Task addMilestone(UUID id, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Le texte du jalon est obligatoire.");
        }
        Task existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));

        LocalDateTime now = LocalDateTime.now();
        String milestone = "[" + now.format(MILESTONE_TIMESTAMP) + "] " + text.strip();
        String description = existing.getDescription();
        String newDescription = description == null || description.isBlank()
                ? milestone
                : description.stripTrailing() + "\n" + milestone;

        Task updated = existing.toBuilder()
                .description(newDescription)
                .updatedAt(now)
                .build();

        tasks.set(tasks.indexOf(existing), updated);
        storage.save(tasks);
        AUDIT_LOG.info("MILESTONE task={} subject=\"{}\" text=\"{}\"",
                updated.getId(), updated.getSubject(), text.strip());
        return updated;
    }

    public Task changeWorkStatus(UUID id, WorkStatus newWorkStatus) {
        Task existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));

        if (newWorkStatus != existing.getWorkStatus()) {
            ensureCanTransitionWorkStatus(existing.getRequires(), newWorkStatus);
        }

        Task updated = existing.toBuilder()
                .workStatus(newWorkStatus)
                .updatedAt(LocalDateTime.now())
                .build();

        tasks.set(tasks.indexOf(existing), updated);
        storage.save(tasks);
        AUDIT_LOG.info("WORK_STATUS_CHANGE task={} subject=\"{}\" from={} to={}",
                updated.getId(), updated.getSubject(), existing.getWorkStatus(), newWorkStatus);
        return updated;
    }

    /**
     * Empêche de passer une tâche en ON IT ou FINISHED tant qu'une de ses
     * tâches préalables ({@code requires}) n'est pas elle-même FINISHED. Une
     * tâche préalable introuvable (supprimée) n'est pas considérée comme
     * bloquante.
     */
    private void ensureCanTransitionWorkStatus(List<UUID> requires, WorkStatus newWorkStatus) {
        if (newWorkStatus != WorkStatus.ON_IT && newWorkStatus != WorkStatus.FINISHED) {
            return;
        }
        if (requires == null || requires.isEmpty()) {
            return;
        }

        List<String> unfinishedRequirements = requires.stream()
                .map(this::get)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(required -> required.getWorkStatus() != WorkStatus.FINISHED)
                .map(required -> Objects.requireNonNullElse(required.getSubject(), required.getId().toString()))
                .toList();

        if (!unfinishedRequirements.isEmpty()) {
            throw new IllegalStateException(
                    "Impossible de passer cette tâche en " + newWorkStatus.value()
                            + " : tâche(s) préalable(s) non terminée(s) : "
                            + String.join(", ", unfinishedRequirements));
        }
    }

    /**
     * Affecte une même échéance à toutes les tâches indiquées.
     *
     * Les identifiants sont d'abord tous vérifiés afin de ne pas enregistrer
     * une mise à jour partielle si l'une des tâches est introuvable.
     */
    public void setDueDate(List<UUID> taskIds, LocalDate dueDate) {
        List<Task> tasksToUpdate = (taskIds == null ? List.<UUID>of() : taskIds).stream()
                .distinct()
                .map(id -> get(id).orElseThrow(() -> new NoSuchElementException(
                        "Tâche introuvable : " + id)))
                .toList();

        if (tasksToUpdate.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (Task task : tasksToUpdate) {
            Task updated = task.toBuilder()
                    .dueDate(dueDate)
                    .updatedAt(now)
                    .build();
            tasks.set(tasks.indexOf(task), updated);
            AUDIT_LOG.info("DUE_DATE_CHANGE task={} subject=\"{}\" from={} to={}",
                    updated.getId(), updated.getSubject(), task.getDueDate(), dueDate);
        }
        storage.save(tasks);
    }

    /**
     * Déplace une tâche à une position donnée (glisser-déposer côté SPA) :
     * la tâche reçoit temporairement {@code targetSortOrder} (valeur
     * potentiellement intermédiaire entre deux voisines), puis l'ensemble
     * des tâches est retrié et renumboté avec des rangs entiers croissants
     * pour éviter toute dérive numérique au fil des réorganisations.
     */
    public List<Task> reorder(UUID id, Double targetSortOrder) {
        if (targetSortOrder == null) {
            throw new IllegalArgumentException("Le rang cible (sortOrder) est obligatoire.");
        }
        Task existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));

        List<Task> ordered = new ArrayList<>(tasks);
        ordered.set(ordered.indexOf(existing), existing.toBuilder().sortOrder(targetSortOrder).build());
        ordered.sort(Comparator.comparing(Task::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())));

        tasks.clear();
        for (int i = 0; i < ordered.size(); i++) {
            tasks.add(ordered.get(i).toBuilder().sortOrder((double) (i + 1)).build());
        }
        storage.save(tasks);
        return list();
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
