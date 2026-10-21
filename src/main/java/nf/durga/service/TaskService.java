package nf.durga.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import nf.durga.model.Status;
import nf.durga.model.Task;
import nf.durga.model.WaitingFor;
import nf.durga.model.WorkStatus;
import nf.durga.storage.LocalTaskOrderStorage;
import nf.durga.storage.TaskStorage;
import nf.durga.storage.TaskStorage.Checkpoint;
import nf.durga.storage.TaskStorage.VersionedTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("nf.durga.audit.task");
    private static final DateTimeFormatter MILESTONE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final TaskStorage storage;
    private final LocalTaskOrderStorage order;

    public TaskService(TaskStorage storage, LocalTaskOrderStorage order) {
        this.storage = storage;
        this.order = order;
    }

    public synchronized List<TaskView> refresh() {
        storage.refresh();
        return list();
    }

    private List<TaskView> views(List<VersionedTask> tasks) {
        List<UUID> localOrder = order.load();
        List<VersionedTask> sorted = tasks.stream().sorted(Comparator
                .comparingInt((VersionedTask v) -> {
                    int rank = localOrder.indexOf(v.task().getId());
                    return rank < 0 ? Integer.MAX_VALUE : rank;
                }).thenComparing(v -> v.task().getId().toString())).toList();
        List<TaskView> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            VersionedTask v = sorted.get(i);
            Task task = v.task().toBuilder()
                    .workStatus(Objects.requireNonNullElse(v.task().getWorkStatus(), WorkStatus.TODO)).build();
            result.add(new TaskView(task, v.revision(), (double) i + 1));
        }
        return List.copyOf(result);
    }

    public synchronized List<TaskView> list() {
        return search(null, null, null, false, null, null);
    }

    public synchronized Optional<TaskView> get(UUID id) {
        return views(storage.load()).stream().filter(v -> v.task().getId().equals(id)).findFirst();
    }

    public synchronized List<TaskView> search(String serverKey, Status status, WaitingFor waitingFor,
                                              Boolean archived, Integer priority, WorkStatus workStatus) {
        return views(storage.load()).stream()
                .filter(v -> serverKey == null || serverKey.equals(v.task().getServerKey()))
                .filter(v -> status == null || status == v.task().getStatus())
                .filter(v -> waitingFor == null || waitingFor == v.task().getWaitingFor())
                .filter(v -> archived == null || archived == Boolean.TRUE.equals(v.task().getArchived()))
                .filter(v -> priority == null || priority.equals(v.task().getPriority()))
                .filter(v -> workStatus == null || workStatus == v.task().getWorkStatus()).toList();
    }

    public synchronized TaskView create(Task input) {
        UUID id = UUID.randomUUID();
        String actor = storage.actor();
        List<VersionedTask> saved = storage.checkpoint(Map.of(), snapshot -> {
            LocalDateTime now = LocalDateTime.now();
            boolean archived = Boolean.TRUE.equals(input.getArchived());
            WorkStatus workStatus = archived ? WorkStatus.FINISHED
                    : Objects.requireNonNullElse(input.getWorkStatus(), WorkStatus.TODO);
            if (!archived) ensureCanTransitionWorkStatus(snapshot, input.getRequires(), workStatus);
            Task task = input.toBuilder().id(id).createdAt(now).updatedAt(now)
                    .archived(archived).workStatus(workStatus).build();
            return new Checkpoint(List.of(task), List.of(), "task " + id + ": create");
        });
        TaskView result = find(saved, id);
        AUDIT_LOG.info("CREATE task={} subject=\"{}\" status={} workStatus={} serverKey={} actor=\"{}\"",
                id, result.task().getSubject(), result.task().getStatus(), result.task().getWorkStatus(),
                result.task().getServerKey(), actor);
        return result;
    }

    public synchronized TaskView update(UUID id, Task input, String revision) {
        return mutate(id, revision, "update", "UPDATE", null, (existing, snapshot) -> {
            boolean archived = Boolean.TRUE.equals(input.getArchived());
            WorkStatus workStatus = archived ? WorkStatus.FINISHED
                    : Objects.requireNonNullElse(input.getWorkStatus(), existing.getWorkStatus());
            if (!archived && workStatus != existing.getWorkStatus())
                ensureCanTransitionWorkStatus(snapshot, input.getRequires(), workStatus);
            return input.toBuilder().id(id).createdAt(existing.getCreatedAt()).workStatus(workStatus)
                    .updatedAt(LocalDateTime.now()).build();
        });
    }

    public synchronized TaskView patch(UUID id, UpdateTaskRequest request, String revision) {
        return mutate(id, revision, "update", "PATCH", null, (existing, snapshot) -> {
            boolean archived = Boolean.TRUE.equals(request.archived() != null ? request.archived() : existing.getArchived());
            WorkStatus workStatus = archived ? WorkStatus.FINISHED
                    : request.workStatus() != null ? request.workStatus() : existing.getWorkStatus();
            List<UUID> requires = request.requires() != null ? request.requires() : existing.getRequires();
            if (!archived && workStatus != existing.getWorkStatus())
                ensureCanTransitionWorkStatus(snapshot, requires, workStatus);
            return existing.toBuilder()
                    .subject(request.subject() != null ? request.subject() : existing.getSubject())
                    .description(request.description() != null ? request.description() : existing.getDescription())
                    .iteration(request.iteration() != null ? request.iteration() : existing.getIteration())
                    .status(request.status() != null ? request.status() : existing.getStatus())
                    .workStatus(workStatus)
                    .waitingFor(request.waitingFor() != null ? request.waitingFor() : existing.getWaitingFor())
                    .waitingForCustom(request.waitingForCustom() != null ? request.waitingForCustom() : existing.getWaitingForCustom())
                    .priority(request.priority() != null ? request.priority() : existing.getPriority())
                    .dueDate(request.dueDate() != null ? request.dueDate() : existing.getDueDate())
                    .archived(archived).jiraUrl(request.jiraUrl() != null ? request.jiraUrl() : existing.getJiraUrl())
                    .requires(requires).updatedAt(LocalDateTime.now()).build();
        });
    }

    public synchronized void delete(UUID id, String revision) {
        requireRevision(revision);
        String actor = storage.actor();
        List<Task> removed = new ArrayList<>();
        storage.checkpoint(Map.of(id, revision), snapshot -> {
            removed.add(existing(snapshot, id));
            return new Checkpoint(List.of(), List.of(id), "task " + id + ": delete");
        });
        AUDIT_LOG.info("DELETE task={} subject=\"{}\" actor=\"{}\"", id, removed.getFirst().getSubject(), actor);
    }

    public synchronized TaskView archive(UUID id, String revision) {
        return mutate(id, revision, "archive", "ARCHIVE", null, (task, snapshot) -> task.toBuilder()
                .archived(true).workStatus(WorkStatus.FINISHED).updatedAt(LocalDateTime.now()).build());
    }

    public synchronized TaskView changeStatus(UUID id, Status status, String revision) {
        return mutate(id, revision, "status", "STATUS_CHANGE", null, (task, snapshot) -> task.toBuilder()
                .status(status).updatedAt(LocalDateTime.now()).build());
    }

    public synchronized TaskView changeWorkStatus(UUID id, WorkStatus status, String revision) {
        return mutate(id, revision, "workStatus", "WORK_STATUS_CHANGE", null, (task, snapshot) -> {
            if (status != task.getWorkStatus()) ensureCanTransitionWorkStatus(snapshot, task.getRequires(), status);
            return task.toBuilder().workStatus(status).updatedAt(LocalDateTime.now()).build();
        });
    }

    public synchronized TaskView addMilestone(UUID id, String text, String revision) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Le texte du jalon est obligatoire.");
        return mutate(id, revision, "milestone", "MILESTONE", "text=\"" + text.strip() + "\"", (task, snapshot) -> {
            LocalDateTime now = LocalDateTime.now();
            String line = "[" + now.format(MILESTONE_TIMESTAMP) + "] " + text.strip();
            String description = task.getDescription();
            return task.toBuilder().description(description == null || description.isBlank()
                    ? line : description.stripTrailing() + "\n" + line).updatedAt(now).build();
        });
    }

    private TaskView mutate(UUID id, String revision, String action, String audit, String auditDetail,
                            BiFunction<Task, List<VersionedTask>, Task> operation) {
        requireRevision(revision);
        String actor = storage.actor();
        List<Task> before = new ArrayList<>();
        List<VersionedTask> saved = storage.checkpoint(Map.of(id, revision), snapshot -> {
            Task existing = existing(snapshot, id);
            before.add(existing);
            Task updated = operation.apply(existing, snapshot);
            String detail = switch (action) {
                case "status" -> "status " + existing.getStatus() + " -> " + updated.getStatus();
                case "workStatus" -> "workStatus " + existing.getWorkStatus() + " -> " + updated.getWorkStatus();
                default -> action;
            };
            return new Checkpoint(List.of(updated), List.of(), "task " + id + ": " + detail);
        });
        TaskView result = find(saved, id);
        Task old = before.getFirst();
        String detail = auditDetail != null ? auditDetail : switch (audit) {
            case "STATUS_CHANGE" -> "from=" + old.getStatus() + " to=" + result.task().getStatus();
            case "WORK_STATUS_CHANGE" ->
                    "from=" + workStatusLabel(old.getWorkStatus()) + " to=" + workStatusLabel(result.task().getWorkStatus());
            default -> changes(old, result.task());
        };
        AUDIT_LOG.info("{} task={} subject=\"{}\" {} actor=\"{}\"", audit, id, result.task().getSubject(), detail, actor);
        return result;
    }

    private String workStatusLabel(WorkStatus status) {
        return status == null ? "null" : status.value();
    }

    private String changes(Task old, Task updated) {
        List<String> changes = new ArrayList<>();
        change(changes, "status", old.getStatus(), updated.getStatus());
        change(changes, "workStatus", old.getWorkStatus(), updated.getWorkStatus());
        change(changes, "waitingFor", old.getWaitingFor(), updated.getWaitingFor());
        change(changes, "priority", old.getPriority(), updated.getPriority());
        change(changes, "dueDate", old.getDueDate(), updated.getDueDate());
        change(changes, "archived", old.getArchived(), updated.getArchived());
        change(changes, "subject", old.getSubject(), updated.getSubject());
        return "changes=[" + String.join(", ", changes) + "]";
    }

    private void change(List<String> changes, String field, Object old, Object updated) {
        if (!Objects.equals(old, updated)) changes.add(field + "=" + old + "->" + updated);
    }

    private TaskView find(List<VersionedTask> saved, UUID id) {
        return views(saved).stream().filter(v -> v.task().getId().equals(id)).findFirst().orElseThrow();
    }

    private Task existing(List<VersionedTask> tasks, UUID id) {
        Task task = tasks.stream().map(VersionedTask::task).filter(t -> t.getId().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Tâche introuvable : " + id));
        return task.toBuilder().workStatus(Objects.requireNonNullElse(task.getWorkStatus(), WorkStatus.TODO)).build();
    }

    private static void requireRevision(String revision) {
        if (revision == null || !revision.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"))
            throw new IllegalArgumentException("A read-derived Task revision is required. Reload before editing.");
    }

    private void ensureCanTransitionWorkStatus(List<VersionedTask> tasks, List<UUID> requires, WorkStatus status) {
        if ((status != WorkStatus.ON_IT && status != WorkStatus.FINISHED) || requires == null) return;
        List<String> unfinished = tasks.stream().map(VersionedTask::task)
                .filter(t -> requires.contains(t.getId()) && t.getWorkStatus() != WorkStatus.FINISHED)
                .map(t -> Objects.requireNonNullElse(t.getSubject(), t.getId().toString())).toList();
        if (!unfinished.isEmpty())
            throw new IllegalStateException("Impossible de passer cette tâche en " + status.value()
                    + " : tâche(s) préalable(s) non terminée(s) : " + String.join(", ", unfinished));
    }

    public synchronized void setDueDate(List<UUID> taskIds, LocalDate dueDate) {
        if (taskIds == null || taskIds.isEmpty()) return;
        String actor = storage.actor();
        List<Task> before = new ArrayList<>();
        storage.checkpoint(Map.of(), snapshot -> {
            List<Task> affected = taskIds.stream().distinct().map(id -> existing(snapshot, id)).toList();
            before.addAll(affected);
            LocalDateTime now = LocalDateTime.now();
            return new Checkpoint(affected.stream().map(t -> t.toBuilder().dueDate(dueDate).updatedAt(now).build()).toList(),
                    List.of(), "tasks: meeting dueDate " + dueDate);
        });
        before.forEach(t -> AUDIT_LOG.info("DUE_DATE_CHANGE task={} subject=\"{}\" from={} to={} actor=\"{}\"",
                t.getId(), t.getSubject(), t.getDueDate(), dueDate, actor));
    }

    public synchronized List<TaskView> reorder(UUID id, Double targetSortOrder) {
        if (targetSortOrder == null || !Double.isFinite(targetSortOrder))
            throw new IllegalArgumentException("Le rang cible (sortOrder) est obligatoire et fini.");
        List<TaskView> tasks = new ArrayList<>(views(storage.load()));
        if (tasks.stream().noneMatch(v -> v.task().getId().equals(id)))
            throw new NoSuchElementException("Tâche introuvable : " + id);
        tasks.sort(Comparator.comparingDouble(v -> v.task().getId().equals(id) ? targetSortOrder : v.sortOrder()));
        order.save(tasks.stream().map(v -> v.task().getId()).toList());
        return list();
    }

    public List<TaskView> getActiveTasks() {
        return list();
    }

    public synchronized List<TaskView> getBlockedTasks() {
        return list().stream().filter(v -> v.task().getWaitingFor() != null && v.task().getWaitingFor() != WaitingFor.NONE).toList();
    }

    public List<TaskView> getTasksByServer(String serverKey) {
        return search(serverKey, null, null, false, null, null);
    }
}
