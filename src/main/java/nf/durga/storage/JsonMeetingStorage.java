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
import org.springframework.beans.factory.annotation.Autowired;

import nf.durga.model.ClientMeeting;

@Component
public class JsonMeetingStorage {

    private final Path dataFile;

    @Autowired
    public JsonMeetingStorage() {
        this(Path.of("data", "meetings.json"));
    }

    public JsonMeetingStorage(Path dataFile) {
        this.dataFile = dataFile;
    }

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    public List<ClientMeeting> load() {
        try {
            if (!Files.exists(dataFile)) {
                return Collections.emptyList();
            }
            ClientMeeting[] meetings = objectMapper.readValue(dataFile.toFile(), ClientMeeting[].class);
            return List.of(meetings);
        } catch (IOException exception) {
            throw new MeetingStorageException("Impossible de lire " + dataFile, exception);
        }
    }

    public void save(List<ClientMeeting> meetings) {
        try {
            Files.createDirectories(dataFile.getParent());
            objectMapper.writeValue(dataFile.toFile(), meetings);
        } catch (IOException exception) {
            throw new MeetingStorageException("Impossible d'écrire " + dataFile, exception);
        }
    }

    public static class MeetingStorageException extends RuntimeException {
        public MeetingStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
