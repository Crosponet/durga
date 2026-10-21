package nf.durga.mcp;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import nf.durga.model.Status;
import nf.durga.model.Task;
import nf.durga.model.TaskSource;
import nf.durga.model.WaitingFor;
import nf.durga.model.WorkStatus;
import nf.durga.service.TaskService;
import nf.durga.service.UpdateTaskRequest;
import nf.durga.service.TaskView;

@Component
public class TaskMcpTools {

    private final TaskService taskService;

    public TaskMcpTools(TaskService taskService) {
        this.taskService = taskService;
    }

    @Tool(name = "create_task", description = "Crée une nouvelle tâche d'investigation dans Durga.")
    public TaskView createTask(
            @ToolParam(description = "Sujet de la tâche") String subject,
            @ToolParam(description = "Description détaillée", required = false) String description,
            @ToolParam(description = "Clé du serveur concerné", required = false) String serverKey,
            @ToolParam(description = "Statut: DATA_GRABBING, INVESTIGATION, FIXING ou REPORT") String status,
            @ToolParam(description = "État de travail: TODO, ON IT, PAUSE ou FINISHED",
                    required = false) String workStatus,
            @ToolParam(description = "Attente: NONE, CLOUDOPS, RND ou CUSTOM") String waitingFor,
            @ToolParam(description = "Libellé d'attente personnalisé", required = false) String waitingForCustom,
            @ToolParam(description = "Priorité: 1 critique, 2 élevée, 3 normale, 4 faible",
                    required = false) Integer priority,
            @ToolParam(description = "Échéance au format YYYY-MM-DD", required = false) String dueDate,
            @ToolParam(description = "Lien vers la demande Jira", required = false) String jiraUrl,
            @ToolParam(description = "UUID des tâches préalables", required = false) List<String> requires) {
        Task task = Task.builder()
                .subject(subject)
                .description(description)
                .serverKey(serverKey)
                .status(parseStatus(status))
                .workStatus(parseOptionalWorkStatus(workStatus))
                .waitingFor(parseWaitingFor(waitingFor))
                .waitingForCustom(waitingForCustom)
                .priority(priority)
                .dueDate(parseDate(dueDate))
                .jiraUrl(jiraUrl)
                .requires(parseIds(requires))
                .source(TaskSource.COPILOT)
                .build();
        return taskService.create(task);
    }

    @Tool(name = "list_tasks", description = "Retourne toutes les tâches actives non archivées.")
    public List<TaskView> listTasks() {
        return taskService.getActiveTasks();
    }

    @Tool(name = "search_tasks", description = "Recherche des tâches avec des filtres tous optionnels.")
    public List<TaskView> searchTasks(
            @ToolParam(description = "Clé serveur", required = false) String serverKey,
            @ToolParam(description = "Statut", required = false) String status,
            @ToolParam(description = "État de travail: TODO, ON IT, PAUSE ou FINISHED",
                    required = false) String workStatus,
            @ToolParam(description = "Attente", required = false) String waitingFor,
            @ToolParam(description = "Filtre d'archivage", required = false) Boolean archived,
            @ToolParam(description = "Priorité de 1 à 4", required = false) Integer priority) {
        return taskService.search(
                serverKey,
                parseOptionalStatus(status),
                parseOptionalWaitingFor(waitingFor),
                archived,
                priority,
                parseOptionalWorkStatus(workStatus));
    }

    @Tool(name = "update_task",
            description = "Met à jour uniquement les champs non nuls fournis pour une tâche existante.")
    public TaskView updateTask(
            @ToolParam(description = "UUID de la tâche") String id,
            @ToolParam(description = "Révision retournée par la lecture de la tâche") String expectedRevision,
            @ToolParam(description = "Nouveau sujet", required = false) String subject,
            @ToolParam(description = "Nouvelle description", required = false) String description,
            @ToolParam(description = "Nouvelle itération", required = false) Integer iteration,
            @ToolParam(description = "Nouveau statut", required = false) String status,
            @ToolParam(description = "Nouvel état de travail: TODO, ON IT, PAUSE ou FINISHED",
                    required = false) String workStatus,
            @ToolParam(description = "Nouvelle attente", required = false) String waitingFor,
            @ToolParam(description = "Nouveau libellé d'attente personnalisé",
                    required = false) String waitingForCustom,
            @ToolParam(description = "Nouvelle priorité de 1 à 4", required = false) Integer priority,
            @ToolParam(description = "Nouvelle échéance au format YYYY-MM-DD",
                    required = false) String dueDate,
            @ToolParam(description = "Nouvel état d'archivage", required = false) Boolean archived,
            @ToolParam(description = "Nouveau lien vers la demande Jira", required = false) String jiraUrl,
            @ToolParam(description = "Nouvelles tâches préalables par UUID", required = false)
            List<String> requires) {
        UpdateTaskRequest request = new UpdateTaskRequest(
                subject,
                description,
                iteration,
                parseOptionalStatus(status),
                parseOptionalWorkStatus(workStatus),
                parseOptionalWaitingFor(waitingFor),
                waitingForCustom,
                priority,
                parseDate(dueDate),
                archived,
                jiraUrl,
                parseIds(requires));
        return taskService.patch(parseId(id), request, expectedRevision);
    }

    @Tool(name = "archive_task", description = "Archive une tâche existante.")
    public TaskView archiveTask(@ToolParam(description = "UUID de la tâche") String id,
                                @ToolParam(description = "Révision retournée par la lecture de la tâche") String expectedRevision) {
        return taskService.archive(parseId(id), expectedRevision);
    }

    @Tool(name = "change_status", description = "Change uniquement le statut d'une tâche existante.")
    public TaskView changeStatus(
            @ToolParam(description = "UUID de la tâche") String id,
            @ToolParam(description = "Révision retournée par la lecture de la tâche") String expectedRevision,
            @ToolParam(description = "Nouveau statut") String status) {
        return taskService.changeStatus(parseId(id), parseStatus(status), expectedRevision);
    }

    @Tool(name = "change_work_status", description = "Change uniquement l'état de travail d'une tâche existante.")
    public TaskView changeWorkStatus(
            @ToolParam(description = "UUID de la tâche") String id,
            @ToolParam(description = "Révision retournée par la lecture de la tâche") String expectedRevision,
            @ToolParam(description = "Nouvel état de travail: TODO, ON IT, PAUSE ou FINISHED")
            String workStatus) {
        return taskService.changeWorkStatus(parseId(id), parseWorkStatus(workStatus), expectedRevision);
    }

    @Tool(name = "get_blocked_tasks",
            description = "Retourne les tâches non archivées dont l'attente est différente de NONE.")
    public List<TaskView> getBlockedTasks() {
        return taskService.getBlockedTasks();
    }

    @Tool(name = "get_tasks_by_server", description = "Retourne les tâches actives d'un serveur.")
    public List<TaskView> getTasksByServer(
            @ToolParam(description = "Clé du serveur") String serverKey) {
        return taskService.getTasksByServer(serverKey);
    }

    @Tool(name = "refresh_tasks", description = "Synchronise explicitement les tâches avec le dépôt partagé, sans fusion.")
    public List<TaskView> refreshTasks() {
        return taskService.refresh();
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("UUID de tâche invalide : " + value, exception);
        }
    }

    private List<UUID> parseIds(List<String> values) {
        return values == null ? null : values.stream().map(this::parseId).toList();
    }

    private Status parseOptionalStatus(String value) {
        return value == null ? null : parseStatus(value);
    }

    private Status parseStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Le statut est obligatoire. Valeurs valides : "
                    + enumValues(Status.values()));
        }

        try {
            return Status.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Statut invalide '" + value + "'. Valeurs valides : "
                    + enumValues(Status.values()), exception);
        }
    }

    private WorkStatus parseOptionalWorkStatus(String value) {
        return value == null ? null : parseWorkStatus(value);
    }

    private WorkStatus parseWorkStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("L'état de travail est obligatoire. Valeurs valides : "
                    + enumValues(WorkStatus.values()));
        }
        try {
            return WorkStatus.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("État de travail invalide '" + value + "'. Valeurs valides : "
                    + enumValues(WorkStatus.values()), exception);
        }
    }

    private WaitingFor parseOptionalWaitingFor(String value) {
        return value == null ? null : parseWaitingFor(value);
    }

    private WaitingFor parseWaitingFor(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("L'attente est obligatoire. Valeurs valides : "
                    + enumValues(WaitingFor.values()));
        }
        try {
            return WaitingFor.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Attente invalide '" + value + "'. Valeurs valides : "
                    + enumValues(WaitingFor.values()), exception);
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Date invalide '" + value + "'. Format attendu : YYYY-MM-DD", exception);
        }
    }

    private String enumValues(Enum<?>[] values) {
        return Arrays.toString(values);
    }
}
