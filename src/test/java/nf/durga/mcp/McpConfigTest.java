package nf.durga.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

class McpConfigTest {

    @Test
    void exposesTaskAndClientMeetingTools() {
        ToolCallbackProvider provider = new McpConfig().toolCallbacks(
                mock(TaskMcpTools.class), mock(ClientMeetingMcpTools.class));
        Set<String> toolNames = Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertTrue(toolNames.contains("create_task"));
        assertTrue(toolNames.contains("create_client_meeting"));
    }
}
