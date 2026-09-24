package nf.durga.mcp;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import nf.durga.model.ClientMeeting;
import nf.durga.service.ClientMeetingService;

@Component
public class ClientMeetingMcpTools {

    private final ClientMeetingService meetingService;

    public ClientMeetingMcpTools(ClientMeetingService meetingService) {
        this.meetingService = meetingService;
    }

    @Tool(name = "create_client_meeting",
            description = "Crée une réunion client avec un serveur, une date/heure et une liste de tâches.")
    public ClientMeeting createClientMeeting(
            @ToolParam(description = "Clé du serveur concerné") String serverKey,
            @ToolParam(description = "Date et heure au format ISO-8601, par exemple 2026-09-24T14:30")
                    String meetingAt,
            @ToolParam(description = "UUID des tâches associées", required = false)
                    List<String> taskIds) {
        return meetingService.create(ClientMeeting.builder()
                .serverKey(serverKey)
                .meetingAt(parseDateTime(meetingAt))
                .taskIds(parseIds(taskIds))
                .build());
    }

    @Tool(name = "list_client_meetings", description = "Retourne toutes les réunions client.")
    public List<ClientMeeting> listClientMeetings() {
        return meetingService.list();
    }

    @Tool(name = "get_client_meeting", description = "Retourne une réunion client par son UUID.")
    public ClientMeeting getClientMeeting(
            @ToolParam(description = "UUID de la réunion") String id) {
        return meetingService.get(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Réunion introuvable : " + id));
    }

    @Tool(name = "update_client_meeting", description = "Met à jour une réunion client.")
    public ClientMeeting updateClientMeeting(
            @ToolParam(description = "UUID de la réunion") String id,
            @ToolParam(description = "Clé du serveur concerné") String serverKey,
            @ToolParam(description = "Date et heure au format ISO-8601") String meetingAt,
            @ToolParam(description = "UUID des tâches associées", required = false)
                    List<String> taskIds) {
        return meetingService.update(parseId(id), ClientMeeting.builder()
                .serverKey(serverKey)
                .meetingAt(parseDateTime(meetingAt))
                .taskIds(parseIds(taskIds))
                .build());
    }

    @Tool(name = "delete_client_meeting", description = "Supprime une réunion client.")
    public void deleteClientMeeting(
            @ToolParam(description = "UUID de la réunion") String id) {
        meetingService.delete(parseId(id));
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("UUID de réunion invalide : " + value, exception);
        }
    }

    private List<UUID> parseIds(List<String> values) {
        return values == null ? List.of() : values.stream().map(this::parseId).toList();
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "Date/heure invalide. Format attendu : YYYY-MM-DDTHH:MM[:SS]", exception);
        }
    }
}
