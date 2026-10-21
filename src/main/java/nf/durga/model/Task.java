package nf.durga.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    private UUID id;
    private String subject;
    private String description;
    private Integer iteration;
    private String serverKey;
    private Status status;
    private WorkStatus workStatus;
    private WaitingFor waitingFor;
    private String waitingForCustom;
    private TaskSource source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** false par défaut : géré par TaskService à la création. */
    private Boolean archived;
    private LocalDate dueDate;
    private String tags;

    /** 1 = critique, 2 = élevée, 3 = normale, 4 = faible. */
    private Integer priority;
    private String jiraUrl;
    /** Identifiants des tâches qui doivent être terminées avant celle-ci. */
    private List<UUID> requires;

}
