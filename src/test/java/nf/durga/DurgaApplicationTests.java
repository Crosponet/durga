package nf.durga;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DurgaApplicationTests {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void repository(DynamicPropertyRegistry registry) throws Exception {
        GitFixture fixture = new GitFixture(directory);
        registry.add("durga.tasks.repository", () -> fixture.first.toString());
    }

    @Test
    void contextLoads() {
    }

}
