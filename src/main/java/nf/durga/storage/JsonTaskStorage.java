package nf.durga.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import nf.durga.model.Task;

/**
 * Stockage fichier (JSON) des tâches. Aucune logique métier ici : cette
 * classe se contente de charger/sauvegarder la liste des tâches depuis/vers
 * le fichier data/tasks.json. L'annotation @Component ne sert qu'au câblage
 * Spring (injection dans TaskService) ; aucune logique Spring n'est utilisée
 * à l'intérieur de la classe.
 */
@Component
public class JsonTaskStorage {

    private static final Path DATA_FILE = Path.of("data", "tasks.json");

    private final ObjectMapper objectMapper;

    public JsonTaskStorage() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Relit le fichier data/tasks.json et retourne son contenu.
     * Si le fichier n'existe pas encore, retourne une liste vide.
     */
    public List<Task> load() {
        try {
            if (!Files.exists(DATA_FILE)) {
                return Collections.emptyList();
            }
            Task[] tasks = objectMapper.readValue(DATA_FILE.toFile(), Task[].class);
            return List.of(tasks);
        } catch (IOException e) {
            throw new TaskStorageException("Impossible de lire " + DATA_FILE, e);
        }
    }

    /**
     * Écrit la liste des tâches dans data/tasks.json, en créant le dossier
     * parent si nécessaire.
     */
    public void save(List<Task> tasks) {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            objectMapper.writeValue(DATA_FILE.toFile(), tasks);
        } catch (IOException e) {
            throw new TaskStorageException("Impossible d'écrire " + DATA_FILE, e);
        }
    }

    public static class TaskStorageException extends RuntimeException {
        public TaskStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
