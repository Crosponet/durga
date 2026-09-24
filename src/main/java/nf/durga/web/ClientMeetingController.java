package nf.durga.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nf.durga.model.ClientMeeting;
import nf.durga.service.ClientMeetingService;

@RestController
@RequestMapping("/api/meetings")
public class ClientMeetingController {

    private final ClientMeetingService meetingService;

    public ClientMeetingController(ClientMeetingService meetingService) {
        this.meetingService = meetingService;
    }

    @GetMapping
    public List<ClientMeeting> list() {
        return meetingService.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClientMeeting> get(@PathVariable UUID id) {
        return meetingService.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ClientMeeting> create(@RequestBody ClientMeeting meeting) {
        return ResponseEntity.status(HttpStatus.CREATED).body(meetingService.create(meeting));
    }

    @PutMapping("/{id}")
    public ClientMeeting update(@PathVariable UUID id, @RequestBody ClientMeeting meeting) {
        return meetingService.update(id, meeting);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        meetingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
