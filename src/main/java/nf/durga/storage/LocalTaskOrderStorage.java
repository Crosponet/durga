package nf.durga.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalTaskOrderStorage {
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public LocalTaskOrderStorage(@Value("${durga.tasks.order-file:data/task-order.json}") String file) {
        this.file = Path.of(file).toAbsolutePath().normalize();
    }

    public synchronized List<UUID> load() {
        try {
            return Files.exists(file) ? List.of(mapper.readValue(file.toFile(), UUID[].class)) : List.of();
        } catch (IOException e) {
            throw new TaskStorageException("Cannot read local Task ordering " + file, e);
        }
    }

    public synchronized void save(List<UUID> ids) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(temporary.toFile(), ids);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new TaskStorageException("Cannot save local Task ordering " + file, e);
        }
    }
}
