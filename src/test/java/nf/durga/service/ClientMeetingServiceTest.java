package nf.durga.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.anyList;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import nf.durga.model.ClientMeeting;
import nf.durga.storage.JsonMeetingStorage;

@ExtendWith(MockitoExtension.class)
class ClientMeetingServiceTest {

    @Mock
    private JsonMeetingStorage meetingStorage;

    @Mock
    private TaskService taskService;

    private ClientMeetingService meetingService;

    @BeforeEach
    void setUp() {
        when(meetingStorage.load()).thenReturn(List.of());
        meetingService = new ClientMeetingService(meetingStorage, taskService);
        meetingService.init();
    }

    @Test
    void createSetsAssociatedTasksDueDateToMeetingDate() {
        UUID taskId = UUID.randomUUID();
        LocalDateTime meetingAt = LocalDateTime.of(2026, 10, 3, 14, 30);

        meetingService.create(ClientMeeting.builder()
                .serverKey("server-a")
                .meetingAt(meetingAt)
                .taskIds(List.of(taskId))
                .build());

        verify(taskService).setDueDate(List.of(taskId), LocalDate.of(2026, 10, 3));
    }

    @Test
    void updateSetsAssociatedTasksDueDateToUpdatedMeetingDate() {
        UUID taskId = UUID.randomUUID();
        ClientMeeting created = meetingService.create(ClientMeeting.builder()
                .serverKey("server-a")
                .meetingAt(LocalDateTime.of(2026, 10, 3, 14, 30))
                .taskIds(List.of(taskId))
                .build());
        LocalDateTime updatedMeetingAt = LocalDateTime.of(2026, 10, 10, 9, 0);

        meetingService.update(created.getId(), ClientMeeting.builder()
                .serverKey("server-a")
                .meetingAt(updatedMeetingAt)
                .taskIds(List.of(taskId))
                .build());

        verify(taskService).setDueDate(List.of(taskId), LocalDate.of(2026, 10, 10));
    }

    @Test
    void localSaveFailureExplicitlyReportsAlreadyPublishedTaskChanges() {
        UUID taskId = UUID.randomUUID();
        doThrow(new JsonMeetingStorage.MeetingStorageException("disk full", new java.io.IOException()))
                .when(meetingStorage).save(anyList());
        assertThatThrownBy(() -> meetingService.create(ClientMeeting.builder().serverKey("server")
                .meetingAt(LocalDateTime.of(2026, 10, 3, 14, 30)).taskIds(List.of(taskId)).build()))
                .isInstanceOf(JsonMeetingStorage.MeetingStorageException.class)
                .hasMessageContaining("Task due dates were published");
        assertThat(meetingService.list()).isEmpty();
    }
}
