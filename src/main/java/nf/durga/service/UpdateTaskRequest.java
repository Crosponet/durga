package nf.durga.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import nf.durga.model.Status;
import nf.durga.model.WaitingFor;

/**
 * Requête de mise à jour partielle d'une tâche.
 * Une valeur {@code null} signifie que le champ ne doit pas être modifié.
 */
public record UpdateTaskRequest(
        String subject,
        String description,
        Integer iteration,
        Status status,
        WaitingFor waitingFor,
        String waitingForCustom,
        Integer priority,
        LocalDate dueDate,
        Boolean archived,
        String jiraUrl,
        List<UUID> requires) {
}
