package lab.agentharness.tools;

import lab.agentharness.schema.Schema;

/**
 * BaseTool 是所有本地工具的统一契约：工具自己描述能力、声明参数 Schema，并解析模型传入的 RawJson 参数。
 */
public interface BaseTool {
    /**
     * 返回工具的全局唯一名称，大模型会通过这个名字发起 ToolCall。
     */
    String name();

    /**
     * 返回提交给大模型的工具描述和参数 JSON Schema。
     */
    Schema.ToolDefinition definition();

    /**
     * 执行具体工具逻辑。参数保持 RawJson 原文，解析责任留给具体工具。
     */
    String execute(Schema.RawJson arguments) throws Exception;
}
