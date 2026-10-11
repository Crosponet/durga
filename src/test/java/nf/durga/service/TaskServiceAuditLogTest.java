package nf.durga.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import nf.durga.model.Status;
import nf.durga.model.Task;
import nf.durga.model.WorkStatus;
import nf.durga.storage.JsonTaskStorage;

/**
 * Vérifie que les actions métier sur les tâches sont bien tracées dans le
 * fichier de log dédié (voir logback-spring.xml), avec rotation quotidienne.
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceAuditLogTest {

    @Mock
    private JsonTaskStorage taskStorage;

    private TaskService taskService;
    private Path todayLogFile;

    @BeforeEach
    void setUp() throws IOException {
        when(taskStorage.load()).thenReturn(List.of());
        taskService = new TaskService(taskStorage);
        taskService.init();

        String todaySuffix = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        todayLogFile = Path.of("logs", "task-actions-" + todaySuffix + ".log");
        Path activeLogFile = Path.of("logs", "task-actions.log");
        // Le fichier actif (non encore roulé) est celui utilisé tant que la journée n'a pas changé.
        todayLogFile = Files.exists(activeLogFile) ? activeLogFile : todayLogFile;
    }

    @Test
    void changeStatusIsWrittenToTaskActionsLogFile() throws IOException {
        Task task = Task.builder()
                .id(UUID.randomUUID())
                .subject("Investiguer la fuite mémoire")
                .status(Status.INVESTIGATION)
                .workStatus(WorkStatus.TODO)
                .sortOrder(1d)
                .build();
        when(taskStorage.load()).thenReturn(List.of(task));
        taskService.init();

        taskService.changeStatus(task.getId(), Status.FIXING);

        assertThat(Files.exists(todayLogFile)).isTrue();
        String content = Files.readString(todayLogFile);
        assertThat(content).contains("STATUS_CHANGE")
                .contains(task.getId().toString())
                .contains("from=INVESTIGATION to=FIXING");
    }

    @Test
    void addMilestoneIsWrittenToTaskActionsLogFile() throws IOException {
        Task task = Task.builder()
                .id(UUID.randomUUID())
                .subject("Suivi jalon")
                .status(Status.INVESTIGATION)
                .workStatus(WorkStatus.TODO)
                .sortOrder(1d)
                .build();
        when(taskStorage.load()).thenReturn(List.of(task));
        taskService.init();

        taskService.addMilestone(task.getId(), "Logs récupérés");

        String content = Files.readString(todayLogFile);
        assertThat(content).contains("MILESTONE")
                .contains(task.getId().toString())
                .contains("text=\"Logs récupérés\"");
    }
}
