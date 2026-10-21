package nf.durga.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Path;

import nf.durga.GitFixture;
import nf.durga.model.Task;
import nf.durga.service.*;
import nf.durga.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskControllerTest {
    @TempDir
    Path directory;
    GitTaskStorage storage;
    TaskService service;
    MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        GitFixture fixture = new GitFixture(directory);
        storage = fixture.open(fixture.first);
        service = new TaskService(storage, new LocalTaskOrderStorage(directory.resolve("order.json").toString()));
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void close() {
        storage.close();
    }

    @Test
    void flatTaskReadAndMutationResponsesUseQuotedStrongEtags() throws Exception {
        var created = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"REST\",\"description\":\"**Markdown**\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subject").value("REST"))
                .andExpect(jsonPath("$.task").doesNotExist()).andExpect(jsonPath("$.sortOrder").isNumber())
                .andExpect(jsonPath("$.revision").isString()).andReturn();
        TaskView task = service.list().getFirst();
        String id = task.task().getId().toString();
        String etag = "\"" + task.revision() + "\"";
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo(etag);
        mvc.perform(get("/api/tasks/" + id)).andExpect(status().isOk()).andExpect(header().string("ETag", etag));
        mvc.perform(post("/api/tasks/" + id + "/status").header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FIXING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FIXING"));
        mvc.perform(delete("/api/tasks/" + id).header("If-Match", etag)).andExpect(status().isConflict());
    }

    @Test
    void missingWeakWildcardAndUnquotedIfMatchAreRejected() throws Exception {
        var task = service.create(Task.builder().subject("test").build());
        String path = "/api/tasks/" + task.task().getId() + "/archive";
        mvc.perform(post(path)).andExpect(status().isBadRequest());
        for (String invalid : new String[]{"*", task.revision(), "W/\"" + task.revision() + "\""}) {
            mvc.perform(post(path).header("If-Match", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/tasks/" + java.util.UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void infrastructureFailuresAre503AndLocalReadsRemainAvailable() throws Exception {
        service.create(Task.builder().subject("Local").build());
        java.nio.file.Files.writeString(directory.resolve("first").resolve("manual.txt"), "dirty");
        mvc.perform(post("/api/tasks/refresh")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/tasks")).andExpect(status().isOk()).andExpect(jsonPath("$[0].subject").value("Local"));
    }
}
