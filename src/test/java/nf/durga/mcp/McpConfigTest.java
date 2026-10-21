package nf.durga.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

class McpConfigTest {

    @Test
    void actualToolSerializationKeepsFlatTaskMetadata() {
        nf.durga.service.TaskService service = mock(nf.durga.service.TaskService.class);
        var task = nf.durga.model.Task.builder().id(java.util.UUID.randomUUID()).subject("MCP Task").build();
        when(service.getActiveTasks()).thenReturn(java.util.List.of(
                new nf.durga.service.TaskView(task, "a".repeat(40), 1d)));
        ToolCallbackProvider provider = new McpConfig().toolCallbacks(
                new TaskMcpTools(service), mock(ClientMeetingMcpTools.class));
        var tool = Arrays.stream(provider.getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("list_tasks")).findFirst().orElseThrow();
        String json = tool.call("{}");
        org.assertj.core.api.Assertions.assertThat(json)
                .contains("\"subject\":\"MCP Task\"", "\"revision\":\"" + "a".repeat(40) + "\"")
                .doesNotContain("\"task\":");
    }

    @Test
    void exposesTaskAndClientMeetingTools() {
        ToolCallbackProvider provider = new McpConfig().toolCallbacks(
                mock(TaskMcpTools.class), mock(ClientMeetingMcpTools.class));
        Set<String> toolNames = Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertTrue(toolNames.contains("create_task"));
        assertTrue(toolNames.contains("create_client_meeting"));
        assertTrue(toolNames.contains("refresh_tasks"));
        String schema = Arrays.stream(provider.getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("archive_task"))
                .findFirst().orElseThrow().getToolDefinition().inputSchema();
        org.assertj.core.api.Assertions.assertThat(schema).contains("expectedRevision");
    }
}
