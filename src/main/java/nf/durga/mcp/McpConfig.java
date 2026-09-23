package nf.durga.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    ToolCallbackProvider taskTools(TaskMcpTools taskMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(taskMcpTools)
                .build();
    }
}
