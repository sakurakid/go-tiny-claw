package lab.agentharness.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lab.agentharness.schema.Schema;

/**
 * EditFileTool 对现有文件做局部字符串替换，优先要求精确唯一匹配，并逐级降级处理换行和缩进差异。
 */
public final class EditFileTool implements BaseTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workDir;

    public EditFileTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public Schema.ToolDefinition definition() {
        return new Schema.ToolDefinition(
                name(),
                "对现有文件进行局部的字符串替换。这比重写整个文件更安全、更快速。请提供足够的 old_text 上下文以确保匹配的唯一性。",
                Schema.RawJson.of("""
                        {
                          "type": "object",
                          "properties": {
                            "path": {
                              "type": "string",
                              "description": "要修改的文件路径"
                            },
                            "old_text": {
                              "type": "string",
                              "description": "文件中原有的文本。必须包含足够上下文，以确保在文件中的唯一性。"
                            },
                            "new_text": {
                              "type": "string",
                              "description": "要替换成的新文本"
                            }
                          },
                          "required": ["path", "old_text", "new_text"]
                        }
                        """));
    }

    @Override
    public String execute(Schema.RawJson arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments.value());
        String relativePath = requiredText(root, "path");
        String oldText = requiredText(root, "old_text");
        String newText = root.path("new_text").asText("");

        Path target = workDir.resolve(relativePath).normalize();
        if (!target.startsWith(workDir)) {
            throw new IllegalArgumentException("拒绝修改工作区外文件: " + relativePath);
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("读取文件失败，请确认路径是否正确: " + relativePath);
        }

        String originalContent = Files.readString(target, StandardCharsets.UTF_8);
        String newContent = fuzzyReplace(originalContent, oldText, newText);
        Files.writeString(target, newContent, StandardCharsets.UTF_8);

        return "成功修改文件: " + relativePath;
    }

    private static String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("参数解析失败: 缺少必填字段 " + field);
        }
        return value;
    }

    private static String fuzzyReplace(String originalContent, String oldText, String newText) {
        if (oldText.isEmpty()) {
            throw new IllegalArgumentException("old_text 不能为空");
        }

        int count = countOccurrences(originalContent, oldText);
        if (count == 1) {
            return originalContent.replaceFirst(java.util.regex.Pattern.quote(oldText), java.util.regex.Matcher.quoteReplacement(newText));
        }
        if (count > 1) {
            throw new IllegalArgumentException("old_text 匹配到了 " + count + " 处，请提供更多的上下文代码以确保唯一性");
        }

        String normalizedContent = originalContent.replace("\r\n", "\n");
        String normalizedOld = oldText.replace("\r\n", "\n");
        count = countOccurrences(normalizedContent, normalizedOld);
        if (count == 1) {
            return normalizedContent.replaceFirst(java.util.regex.Pattern.quote(normalizedOld), java.util.regex.Matcher.quoteReplacement(newText));
        }

        String trimmedOld = normalizedOld.strip();
        if (!trimmedOld.isEmpty()) {
            count = countOccurrences(normalizedContent, trimmedOld);
            if (count == 1) {
                return normalizedContent.replaceFirst(java.util.regex.Pattern.quote(trimmedOld), java.util.regex.Matcher.quoteReplacement(newText));
            }
        }

        return lineByLineReplace(normalizedContent, normalizedOld, newText);
    }

    private static String lineByLineReplace(String content, String oldText, String newText) {
        String[] contentLines = content.split("\n", -1);
        String[] oldLines = oldText.strip().split("\n", -1);

        if (oldLines.length == 0 || contentLines.length < oldLines.length) {
            throw new IllegalArgumentException("找不到该代码片段");
        }

        for (int i = 0; i < oldLines.length; i++) {
            oldLines[i] = oldLines[i].strip();
        }

        int matchCount = 0;
        int matchStartIndex = -1;
        int matchEndIndex = -1;
        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            boolean isMatch = true;
            for (int j = 0; j < oldLines.length; j++) {
                if (!contentLines[i + j].strip().equals(oldLines[j])) {
                    isMatch = false;
                    break;
                }
            }

            if (isMatch) {
                matchCount++;
                matchStartIndex = i;
                matchEndIndex = i + oldLines.length;
            }
        }

        if (matchCount == 0) {
            throw new IllegalArgumentException("在文件中未找到 old_text，请先调用 read_file 仔细确认文件内容和缩进");
        }
        if (matchCount > 1) {
            throw new IllegalArgumentException("模糊匹配到了 " + matchCount + " 处相似代码，请提供更多上下行代码以精确定位");
        }

        java.util.List<String> newContentLines = new java.util.ArrayList<>();
        newContentLines.addAll(java.util.Arrays.asList(contentLines).subList(0, matchStartIndex));
        newContentLines.add(newText);
        newContentLines.addAll(java.util.Arrays.asList(contentLines).subList(matchEndIndex, contentLines.length));
        return String.join("\n", newContentLines);
    }

    private static int countOccurrences(String content, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }

        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
