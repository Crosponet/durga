package nf.durga.model;

import java.time.LocalDateTime;
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
    private WaitingFor waitingFor;
    private String waitingForCustom;
    private TaskSource source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
