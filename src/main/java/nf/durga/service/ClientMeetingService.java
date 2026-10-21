package nf.durga.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import nf.durga.model.ClientMeeting;
import nf.durga.storage.JsonMeetingStorage;

@Service
public class ClientMeetingService {

    private final JsonMeetingStorage storage;
    private final TaskService taskService;
    private final List<ClientMeeting> meetings = new ArrayList<>();

    public ClientMeetingService(JsonMeetingStorage storage, TaskService taskService) {
        this.storage = storage;
        this.taskService = taskService;
    }

    @PostConstruct
    void init() {
        meetings.addAll(storage.load());
    }

    public synchronized List<ClientMeeting> list() {
        return List.copyOf(meetings);
    }

    public synchronized Optional<ClientMeeting> get(UUID id) {
        return meetings.stream()
                .filter(meeting -> meeting.getId().equals(id))
                .findFirst();
    }

    public synchronized ClientMeeting create(ClientMeeting meeting) {
        ensureNoDuplicate(meeting, null);
        LocalDateTime now = LocalDateTime.now();
        ClientMeeting toCreate = meeting.toBuilder()
                .id(UUID.randomUUID())
                .createdAt(now)
                .updatedAt(now)
                .build();
        taskService.setDueDate(toCreate.getTaskIds(), toCreate.getMeetingAt().toLocalDate());
        List<ClientMeeting> candidate = new ArrayList<>(meetings);
        candidate.add(toCreate);
        saveAfterTaskChanges(candidate, toCreate.getTaskIds());
        return toCreate;
    }

    public synchronized ClientMeeting update(UUID id, ClientMeeting meeting) {
        ClientMeeting existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Réunion introuvable : " + id));
        ensureNoDuplicate(meeting, id);
        ClientMeeting updated = meeting.toBuilder()
                .id(existing.getId())
                .createdAt(existing.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();
        taskService.setDueDate(updated.getTaskIds(), updated.getMeetingAt().toLocalDate());
        List<ClientMeeting> candidate = new ArrayList<>(meetings);
        candidate.set(candidate.indexOf(existing), updated);
        saveAfterTaskChanges(candidate, updated.getTaskIds());
        return updated;
    }

    public synchronized void delete(UUID id) {
        List<ClientMeeting> candidate = new ArrayList<>(meetings);
        boolean removed = candidate.removeIf(meeting -> meeting.getId().equals(id));
        if (!removed) {
            throw new NoSuchElementException("Réunion introuvable : " + id);
        }
        storage.save(candidate);
        meetings.clear();
        meetings.addAll(candidate);
    }

    private void saveAfterTaskChanges(List<ClientMeeting> candidate, List<UUID> taskIds) {
        try {
            storage.save(candidate);
        } catch (JsonMeetingStorage.MeetingStorageException e) {
            throw new JsonMeetingStorage.MeetingStorageException(
                    taskIds == null || taskIds.isEmpty() ? "Local meeting was not saved."
                            : "Task due dates were published, but the local meeting was not saved.", e);
        }
        meetings.clear();
        meetings.addAll(candidate);
    }

    private void ensureNoDuplicate(ClientMeeting meeting, UUID excludedId) {
        boolean duplicate = meetings.stream()
                .filter(existing -> excludedId == null || !excludedId.equals(existing.getId()))
                .anyMatch(existing -> java.util.Objects.equals(existing.getMeetingAt(), meeting.getMeetingAt())
                        && java.util.Objects.equals(existing.getServerKey(), meeting.getServerKey()));
        if (duplicate) {
            throw new IllegalArgumentException(
                    "Une réunion existe déjà pour ce serveur à cette date et cette heure.");
        }
    }
}
