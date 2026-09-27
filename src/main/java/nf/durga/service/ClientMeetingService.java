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

    public List<ClientMeeting> list() {
        return List.copyOf(meetings);
    }

    public Optional<ClientMeeting> get(UUID id) {
        return meetings.stream()
                .filter(meeting -> meeting.getId().equals(id))
                .findFirst();
    }

    public ClientMeeting create(ClientMeeting meeting) {
        ensureNoDuplicate(meeting, null);
        LocalDateTime now = LocalDateTime.now();
        ClientMeeting toCreate = meeting.toBuilder()
                .id(UUID.randomUUID())
                .createdAt(now)
                .updatedAt(now)
                .build();
        taskService.setDueDate(toCreate.getTaskIds(), toCreate.getMeetingAt().toLocalDate());
        meetings.add(toCreate);
        storage.save(meetings);
        return toCreate;
    }

    public ClientMeeting update(UUID id, ClientMeeting meeting) {
        ClientMeeting existing = get(id)
                .orElseThrow(() -> new NoSuchElementException("Réunion introuvable : " + id));
        ensureNoDuplicate(meeting, id);
        ClientMeeting updated = meeting.toBuilder()
                .id(existing.getId())
                .createdAt(existing.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();
        taskService.setDueDate(updated.getTaskIds(), updated.getMeetingAt().toLocalDate());
        meetings.set(meetings.indexOf(existing), updated);
        storage.save(meetings);
        return updated;
    }

    public void delete(UUID id) {
        boolean removed = meetings.removeIf(meeting -> meeting.getId().equals(id));
        if (!removed) {
            throw new NoSuchElementException("Réunion introuvable : " + id);
        }
        storage.save(meetings);
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
