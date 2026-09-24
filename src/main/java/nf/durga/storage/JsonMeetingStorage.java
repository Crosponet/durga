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

import nf.durga.model.ClientMeeting;

@Component
public class JsonMeetingStorage {

    private static final Path DATA_FILE = Path.of("data", "meetings.json");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    public List<ClientMeeting> load() {
        try {
            if (!Files.exists(DATA_FILE)) {
                return Collections.emptyList();
            }
            ClientMeeting[] meetings = objectMapper.readValue(DATA_FILE.toFile(), ClientMeeting[].class);
            return List.of(meetings);
        } catch (IOException exception) {
            throw new MeetingStorageException("Impossible de lire " + DATA_FILE, exception);
        }
    }

    public void save(List<ClientMeeting> meetings) {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            objectMapper.writeValue(DATA_FILE.toFile(), meetings);
        } catch (IOException exception) {
            throw new MeetingStorageException("Impossible d'écrire " + DATA_FILE, exception);
        }
    }

    public static class MeetingStorageException extends RuntimeException {
        public MeetingStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
