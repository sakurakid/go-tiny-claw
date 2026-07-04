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
 * OpenAI-compatible 协议适配器，可用于 OpenAI、DeepSeek、智谱等兼容 Chat Completions 的服务。
 */
public final class OpenAICompatibleProvider implements LLMProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final String name;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    private OpenAICompatibleProvider(String name, String apiKey, String baseUrl, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 API Key: " + name);
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.name = name;
        this.apiKey = apiKey;
        this.endpoint = endpoint(baseUrl, "/chat/completions");
        this.model = model;
    }

    public static OpenAICompatibleProvider newDeepSeekProvider(String model) {
        return new OpenAICompatibleProvider(
                "deepseek-openai-compatible",
                System.getenv("DEEPSEEK_API_KEY"),
                "https://api.deepseek.com",
                model);
    }

    public static OpenAICompatibleProvider newZhipuProvider(String model) {
        return new OpenAICompatibleProvider(
                "zhipu-openai-compatible",
                System.getenv("ZHIPU_API_KEY"),
                "https://open.bigmodel.cn/api/paas/v4",
                model);
    }

    public static OpenAICompatibleProvider of(String name, String apiKey, String baseUrl, String model) {
        return new OpenAICompatibleProvider(name, apiKey, baseUrl, model);
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
            body.set("messages", toOpenAIMessages(messages));

            if (availableTools != null && !availableTools.isEmpty()) {
                body.set("tools", toOpenAITools(availableTools));
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("OpenAI-compatible API 请求失败: HTTP "
                        + response.statusCode() + " " + response.body());
            }

            return fromOpenAIResponse(MAPPER.readTree(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI-compatible Provider 生成失败: " + e.getMessage(), e);
        }
    }

    private static ArrayNode toOpenAIMessages(List<Schema.Message> messages) {
        ArrayNode result = MAPPER.createArrayNode();
        for (Schema.Message message : messages) {
            ObjectNode node = MAPPER.createObjectNode();

            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                node.put("role", "tool");
                node.put("tool_call_id", message.toolCallId());
                node.put("content", emptyIfNull(message.content()));
                result.add(node);
                continue;
            }

            node.put("role", message.role().value());
            node.put("content", emptyIfNull(message.content()));

            if (message.role() == Schema.Role.ASSISTANT && message.hasToolCalls()) {
                ArrayNode toolCalls = node.putArray("tool_calls");
                for (Schema.ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = toolCalls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments().value());
                }
            }

            result.add(node);
        }
        return result;
    }

    private static ArrayNode toOpenAITools(List<Schema.ToolDefinition> tools) {
        ArrayNode result = MAPPER.createArrayNode();
        for (Schema.ToolDefinition tool : tools) {
            ObjectNode toolNode = result.addObject();
            toolNode.put("type", "function");
            ObjectNode function = toolNode.putObject("function");
            function.put("name", tool.name());
            function.put("description", emptyIfNull(tool.description()));
            function.set("parameters", parseJsonObject(tool.inputSchema()));
        }
        return result;
    }

    private static Schema.Message fromOpenAIResponse(JsonNode response) {
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("OpenAI-compatible API 返回空 choices");
        }

        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").isMissingNode() || message.path("content").isNull()
                ? ""
                : message.path("content").asText();

        List<Schema.ToolCall> calls = new java.util.ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode toolCall : toolCalls) {
                JsonNode function = toolCall.path("function");
                calls.add(new Schema.ToolCall(
                        toolCall.path("id").asText(),
                        function.path("name").asText(),
                        Schema.RawJson.of(function.path("arguments").asText("{}"))));
            }
        }

        return Schema.Message.assistant(content, calls);
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
