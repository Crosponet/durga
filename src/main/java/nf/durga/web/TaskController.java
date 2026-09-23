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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import nf.durga.model.Status;
import nf.durga.model.Task;
import nf.durga.model.WaitingFor;
import nf.durga.service.TaskService;

/**
 * Expose les tâches via HTTP. Ne contient aucune logique métier : chaque
 * méthode se contente de déléguer à {@link TaskService}.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public List<Task> list() {
        return taskService.list();
    }

    @GetMapping("/search")
    public List<Task> search(@RequestParam(required = false) String serverKey,
                              @RequestParam(required = false) Status status,
                              @RequestParam(required = false) WaitingFor waitingFor) {
        return taskService.search(serverKey, status, waitingFor, null, null);
    }

    @GetMapping("/blocked")
    public List<Task> getBlockedTasks() {
        return taskService.getBlockedTasks();
    }

    @GetMapping("/server/{serverKey}")
    public List<Task> getTasksByServer(@PathVariable String serverKey) {
        return taskService.getTasksByServer(serverKey);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> get(@PathVariable UUID id) {
        return taskService.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Task> create(@RequestBody Task task) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskService.create(task));
    }

    @PutMapping("/{id}")
    public Task update(@PathVariable UUID id, @RequestBody Task task) {
        return taskService.update(id, task);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        taskService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    public Task archive(@PathVariable UUID id) {
        return taskService.archive(id);
    }

    @PostMapping("/{id}/status")
    public Task changeStatus(@PathVariable UUID id, @RequestBody ChangeStatusRequest request) {
        return taskService.changeStatus(id, request.status());
    }

    public record ChangeStatusRequest(Status status) {
    }
}
