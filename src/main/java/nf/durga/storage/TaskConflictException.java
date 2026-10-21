package nf.durga.storage;

public class TaskConflictException extends IllegalStateException {
    public TaskConflictException(String message) {
        super(message);
    }
}
