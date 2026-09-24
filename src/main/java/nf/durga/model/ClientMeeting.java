package nf.durga.model;

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
public class ClientMeeting {

    private UUID id;
    private String serverKey;
    private LocalDateTime meetingAt;
    private List<UUID> taskIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
