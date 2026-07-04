package lab.agentharness.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lab.agentharness.schema.Schema;

/**
 * Anthropic-compatible 协议适配器，用 Messages API 形态适配 Claude 或兼容服务。
 */
public final class AnthropicCompatibleProvider implements LLMProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final String name;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    private AnthropicCompatibleProvider(String name, String apiKey, String baseUrl, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 API Key: " + name);
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.name = name;
        this.apiKey = apiKey;
        this.endpoint = endpoint(baseUrl, "/messages");
        this.model = model;
    }

    public static AnthropicCompatibleProvider newClaudeProvider(String model) {
        return new AnthropicCompatibleProvider(
                "anthropic-compatible",
                System.getenv("ANTHROPIC_API_KEY"),
                "https://api.anthropic.com/v1",
                model);
    }

    public static AnthropicCompatibleProvider newZhipuClaudeProvider(String model) {
        return new AnthropicCompatibleProvider(
                "zhipu-anthropic-compatible",
                System.getenv("ZHIPU_API_KEY"),
                "https://open.bigmodel.cn/api/paas/v4",
                model);
    }

    public static AnthropicCompatibleProvider of(String name, String apiKey, String baseUrl, String model) {
        return new AnthropicCompatibleProvider(name, apiKey, baseUrl, model);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Schema.Message generate(List<Schema.Message> messages, List<Schema.ToolDefinition> availableTools) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 4096);

            String system = extractSystem(messages);
            if (!system.isBlank()) {
                body.put("system", system);
            }

            body.set("messages", toAnthropicMessages(messages));
            if (availableTools != null && !availableTools.isEmpty()) {
                body.set("tools", toAnthropicTools(availableTools));
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(90))
                    .header("x-api-key", apiKey)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Anthropic-compatible API 请求失败: HTTP "
                        + response.statusCode() + " " + response.body());
            }

            return fromAnthropicResponse(MAPPER.readTree(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException("Anthropic-compatible Provider 生成失败: " + e.getMessage(), e);
        }
    }

    private static String extractSystem(List<Schema.Message> messages) {
        StringBuilder system = new StringBuilder();
        for (Schema.Message message : messages) {
            if (message.role() == Schema.Role.SYSTEM && message.content() != null) {
                system.append(message.content()).append(System.lineSeparator());
            }
        }
        return system.toString().strip();
    }

    private static ArrayNode toAnthropicMessages(List<Schema.Message> messages) {
        ArrayNode result = MAPPER.createArrayNode();
        for (Schema.Message message : messages) {
            if (message.role() == Schema.Role.SYSTEM) {
                continue;
            }

            ObjectNode node = result.addObject();
            ArrayNode content = node.putArray("content");

            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                node.put("role", "user");
                ObjectNode toolResult = content.addObject();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", message.toolCallId());
                toolResult.put("content", emptyIfNull(message.content()));
                toolResult.put("is_error", false);
                continue;
            }

            node.put("role", message.role() == Schema.Role.ASSISTANT ? "assistant" : "user");
            if (message.content() != null && !message.content().isBlank()) {
                ObjectNode text = content.addObject();
                text.put("type", "text");
                text.put("text", message.content());
            }

            if (message.role() == Schema.Role.ASSISTANT && message.hasToolCalls()) {
                for (Schema.ToolCall call : message.toolCalls()) {
                    ObjectNode toolUse = content.addObject();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", call.id());
                    toolUse.put("name", call.name());
                    toolUse.set("input", parseJsonObject(call.arguments()));
                }
            }
        }
        return result;
    }

    private static ArrayNode toAnthropicTools(List<Schema.ToolDefinition> tools) {
        ArrayNode result = MAPPER.createArrayNode();
        for (Schema.ToolDefinition tool : tools) {
            ObjectNode toolNode = result.addObject();
            toolNode.put("name", tool.name());
            toolNode.put("description", emptyIfNull(tool.description()));
            toolNode.set("input_schema", parseJsonObject(tool.inputSchema()));
        }
        return result;
    }

    private static Schema.Message fromAnthropicResponse(JsonNode response) {
        StringBuilder content = new StringBuilder();
        List<Schema.ToolCall> calls = new java.util.ArrayList<>();

        JsonNode blocks = response.path("content");
        if (blocks.isArray()) {
            for (JsonNode block : blocks) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    content.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    calls.add(new Schema.ToolCall(
                            block.path("id").asText(),
                            block.path("name").asText(),
                            Schema.RawJson.of(block.path("input").toString())));
                }
            }
        }

        return Schema.Message.assistant(content.toString(), calls);
    }

    private static JsonNode parseJsonObject(Schema.RawJson rawJson) {
        try {
            JsonNode node = MAPPER.readTree(rawJson == null ? "{}" : rawJson.value());
            return node.isObject() ? node : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String endpoint(String baseUrl, String path) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith(path) ? normalized : normalized + path;
    }

    private static String emptyIfNull(String text) {
        return text == null ? "" : text;
    }
}
