package nf.durga.service;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import nf.durga.model.Task;

/**
 * Technical response metadata, never serialized into shared Task documents.
 */
public record TaskView(@JsonUnwrapped Task task, String revision, Double sortOrder) {
}
