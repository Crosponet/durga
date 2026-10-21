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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import nf.durga.model.Status;
import nf.durga.model.Task;
import nf.durga.model.WaitingFor;
import nf.durga.model.WorkStatus;
import nf.durga.service.TaskService;
import nf.durga.service.TaskView;

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
    public List<TaskView> list() {
        return taskService.list();
    }

    @GetMapping("/search")
    public List<TaskView> search(@RequestParam(required = false) String serverKey,
                                 @RequestParam(required = false) Status status,
                                 @RequestParam(required = false) WaitingFor waitingFor,
                                 @RequestParam(required = false) String workStatus) {
        return taskService.search(serverKey, status, waitingFor, null, null,
                workStatus == null ? null : WorkStatus.fromValue(workStatus));
    }

    @GetMapping("/blocked")
    public List<TaskView> getBlockedTasks() {
        return taskService.getBlockedTasks();
    }

    @GetMapping("/server/{serverKey}")
    public List<TaskView> getTasksByServer(@PathVariable String serverKey) {
        return taskService.getTasksByServer(serverKey);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskView> get(@PathVariable UUID id) {
        return taskService.get(id)
                .map(task -> response(task, HttpStatus.OK))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<TaskView> create(@RequestBody Task task) {
        return response(taskService.create(task), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TaskView> update(@PathVariable UUID id, @RequestBody Task task,
                                           @RequestHeader(value = "If-Match", required = false) String etag) {
        return response(taskService.update(id, task, revision(etag)), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String etag) {
        taskService.delete(id, revision(etag));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<TaskView> archive(@PathVariable UUID id,
                                            @RequestHeader(value = "If-Match", required = false) String etag) {
        return response(taskService.archive(id, revision(etag)), HttpStatus.OK);
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<TaskView> changeStatus(@PathVariable UUID id, @RequestBody ChangeStatusRequest request,
                                                 @RequestHeader(value = "If-Match", required = false) String etag) {
        return response(taskService.changeStatus(id, request.status(), revision(etag)), HttpStatus.OK);
    }

    @PostMapping("/{id}/work-status")
    public ResponseEntity<TaskView> changeWorkStatus(@PathVariable UUID id, @RequestBody ChangeWorkStatusRequest request,
                                                     @RequestHeader(value = "If-Match", required = false) String etag) {
        return response(taskService.changeWorkStatus(id, request.workStatus(), revision(etag)), HttpStatus.OK);
    }

    @PostMapping("/{id}/milestone")
    public ResponseEntity<TaskView> addMilestone(@PathVariable UUID id, @RequestBody AddMilestoneRequest request,
                                                 @RequestHeader(value = "If-Match", required = false) String etag) {
        return response(taskService.addMilestone(id, request.text(), revision(etag)), HttpStatus.OK);
    }

    @PostMapping("/{id}/reorder")
    public List<TaskView> reorder(@PathVariable UUID id, @RequestBody ReorderRequest request) {
        return taskService.reorder(id, request.sortOrder());
    }

    @PostMapping("/refresh")
    public List<TaskView> refresh() {
        return taskService.refresh();
    }

    private ResponseEntity<TaskView> response(TaskView task, HttpStatus status) {
        return ResponseEntity.status(status).eTag("\"" + task.revision() + "\"").body(task);
    }

    private String revision(String etag) {
        if (etag == null || !etag.matches("\"(?:[0-9a-f]{40}|[0-9a-f]{64})\""))
            throw new IllegalArgumentException("If-Match must contain the quoted Task ETag returned by a read.");
        return etag.substring(1, etag.length() - 1);
    }

    public record ChangeStatusRequest(Status status) {
    }

    public record ChangeWorkStatusRequest(WorkStatus workStatus) {
    }

    public record ReorderRequest(Double sortOrder) {
    }

    public record AddMilestoneRequest(String text) {
    }
}
