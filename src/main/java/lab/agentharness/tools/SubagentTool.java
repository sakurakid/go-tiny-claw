package lab.agentharness.tools;

import java.util.Objects;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lab.agentharness.schema.Schema;

/**
 * SubagentTool 让主 Agent 能委派一个隔离的只读子智能体执行深度探索任务。
 */
public final class SubagentTool implements BaseTool {
    private static final Logger LOG = Logger.getLogger(SubagentTool.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentRunner runner;
    private final Registry readOnlyRegistry;
    private final Object reporter;

    public SubagentTool(AgentRunner runner, Registry readOnlyRegistry, Object reporter) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.readOnlyRegistry = Objects.requireNonNull(readOnlyRegistry, "readOnlyRegistry");
        this.reporter = reporter;
    }

    @Override
    public String name() {
        return "spawn_subagent";
    }

    @Override
    public Schema.ToolDefinition definition() {
        return new Schema.ToolDefinition(
                name(),
                "派出一个专门用于深度探索的只读子智能体。当你需要阅读大量代码、跨文件查找逻辑或整理遗留项目信息时调用。子智能体探索完成后会返回精炼摘要。",
                Schema.RawJson.of("""
                        {
                          "type": "object",
                          "properties": {
                            "task_prompt": {
                              "type": "string",
                              "description": "交给子智能体的明确探索指令，必须说明要查找什么、最终要汇报什么。"
                            }
                          },
                          "required": ["task_prompt"]
                        }
                        """));
    }

    @Override
    public String execute(Schema.RawJson arguments) throws Exception {
        String taskPrompt = taskPrompt(arguments);
        LOG.info("[Subagent] 主 Agent 发起委派，正在拉起探路者: " + taskPrompt);

        try {
            String summary = runner.runSub(taskPrompt, readOnlyRegistry, reporter);
            LOG.info("[Subagent] 子智能体任务结束，报告返回给主干。");
            return "【子智能体探索报告】:\n" + summary;
        } catch (Exception e) {
            return "【子智能体执行失败】:\n" + e.getMessage();
        }
    }

    private static String taskPrompt(Schema.RawJson arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments.value());
        String taskPrompt = root.path("task_prompt").asText("");
        if (taskPrompt.isBlank()) {
            throw new IllegalArgumentException("参数解析失败: 缺少必填字段 task_prompt");
        }
        return taskPrompt;
    }
}
