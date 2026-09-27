package nf.durga.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import nf.durga.model.Task;
import nf.durga.model.WorkStatus;
import nf.durga.storage.JsonTaskStorage;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private JsonTaskStorage taskStorage;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(taskStorage);
    }

    @Test
    void setDueDateUpdatesEachAssociatedTask() {
        UUID firstTaskId = UUID.randomUUID();
        UUID secondTaskId = UUID.randomUUID();
        when(taskStorage.load()).thenReturn(List.of(
                Task.builder().id(firstTaskId).subject("First").sortOrder(1d).build(),
                Task.builder().id(secondTaskId).subject("Second").sortOrder(2d).build()));
        taskService.init();
        LocalDate dueDate = LocalDate.of(2026, 10, 3);

        taskService.setDueDate(List.of(firstTaskId, secondTaskId), dueDate);

        assertEquals(dueDate, taskService.get(firstTaskId).orElseThrow().getDueDate());
        assertEquals(dueDate, taskService.get(secondTaskId).orElseThrow().getDueDate());
    }

    @Test
    void cannotSwitchToOnItWhilePrerequisiteIsNotFinished() {
        UUID prerequisiteId = UUID.randomUUID();
        UUID dependentId = UUID.randomUUID();
        when(taskStorage.load()).thenReturn(List.of(
                Task.builder().id(prerequisiteId).subject("Prérequis").sortOrder(1d)
                        .workStatus(WorkStatus.TODO).build(),
                Task.builder().id(dependentId).subject("Dépendante").sortOrder(2d)
                        .workStatus(WorkStatus.TODO).requires(List.of(prerequisiteId)).build()));
        taskService.init();

        assertThatThrownBy(() -> taskService.changeWorkStatus(dependentId, WorkStatus.ON_IT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Prérequis");

        assertThatThrownBy(() -> taskService.changeWorkStatus(dependentId, WorkStatus.FINISHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Prérequis");
    }

    @Test
    void canSwitchToOnItOnceThePrerequisiteIsFinished() {
        UUID prerequisiteId = UUID.randomUUID();
        UUID dependentId = UUID.randomUUID();
        when(taskStorage.load()).thenReturn(List.of(
                Task.builder().id(prerequisiteId).subject("Prérequis").sortOrder(1d)
                        .workStatus(WorkStatus.FINISHED).build(),
                Task.builder().id(dependentId).subject("Dépendante").sortOrder(2d)
                        .workStatus(WorkStatus.TODO).requires(List.of(prerequisiteId)).build()));
        taskService.init();

        Task updated = taskService.changeWorkStatus(dependentId, WorkStatus.ON_IT);

        assertEquals(WorkStatus.ON_IT, updated.getWorkStatus());
        assertEquals(List.of(prerequisiteId), updated.getRequires());
    }

    @Test
    void canSwitchToOnItOnceTheRequirementLinkIsManuallyRemoved() {
        UUID prerequisiteId = UUID.randomUUID();
        UUID dependentId = UUID.randomUUID();
        when(taskStorage.load()).thenReturn(List.of(
                Task.builder().id(prerequisiteId).subject("Prérequis").sortOrder(1d)
                        .workStatus(WorkStatus.TODO).build(),
                Task.builder().id(dependentId).subject("Dépendante").sortOrder(2d)
                        .workStatus(WorkStatus.TODO).requires(List.of(prerequisiteId)).build()));
        taskService.init();

        taskService.patch(dependentId, new UpdateTaskRequest(
                null, null, null, null, null, null, null, null, null, null, null, List.of()));

        Task updated = taskService.changeWorkStatus(dependentId, WorkStatus.ON_IT);
        assertEquals(WorkStatus.ON_IT, updated.getWorkStatus());
    }

    @Test
    void archivingATaskAutomaticallySetsItFinished() {
        UUID taskId = UUID.randomUUID();
        when(taskStorage.load()).thenReturn(List.of(
                Task.builder().id(taskId).subject("À clore").sortOrder(1d).workStatus(WorkStatus.ON_IT).build()));
        taskService.init();

        Task archived = taskService.archive(taskId);

        assertEquals(WorkStatus.FINISHED, archived.getWorkStatus());
        assertThat(archived.getArchived()).isTrue();
    }

    @Test
    void archivingATaskBypassesTheUnfinishedPrerequisiteBlock() {
        UUID prerequisiteId = UUID.randomUUID();
        UUID dependentId = UUID.randomUUID();
        when(taskStorage.load()).thenReturn(List.of(
                Task.builder().id(prerequisiteId).subject("Prérequis").sortOrder(1d)
                        .workStatus(WorkStatus.TODO).build(),
                Task.builder().id(dependentId).subject("Dépendante").sortOrder(2d)
                        .workStatus(WorkStatus.TODO).requires(List.of(prerequisiteId)).build()));
        taskService.init();

        Task archived = taskService.archive(dependentId);

        assertEquals(WorkStatus.FINISHED, archived.getWorkStatus());
    }
}

