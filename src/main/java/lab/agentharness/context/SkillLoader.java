package lab.agentharness.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * SkillLoader 负责扫描工作区内的 .claw/skills 目录，并解析所有标准 SKILL.md。
 */
public final class SkillLoader {
    private final Path workDir;

    public SkillLoader(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    /**
     * 加载所有技能并格式化为可直接注入 System Prompt 的 Markdown 片段。
     */
    public String loadAll() {
        Path skillBaseDir = workDir.resolve(".claw").resolve("skills").normalize();
        if (!Files.isDirectory(skillBaseDir)) {
            return "";
        }

        List<Path> skillFiles;
        try (Stream<Path> paths = Files.walk(skillBaseDir)) {
            skillFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> "SKILL.md".equals(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> skillBaseDir.relativize(path).toString()))
                    .toList();
        } catch (IOException e) {
            return "";
        }

        if (skillFiles.isEmpty()) {
            return "";
        }

        StringBuilder skillsBuilder = new StringBuilder();
        skillsBuilder.append("\n### 可用专业技能 (Agent Skills)\n");
        skillsBuilder.append("以下是你拥有的标准化外挂技能，请在符合 description 描述的场景下严格遵循其正文指令：\n\n");

        int loadedCount = 0;
        for (Path skillFile : skillFiles) {
            if (readSkill(skillFile, skillsBuilder)) {
                loadedCount++;
            }
        }

        return loadedCount == 0 ? "" : skillsBuilder.toString();
    }

    private static boolean readSkill(Path skillFile, StringBuilder skillsBuilder) {
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            Skill skill = parseSkillMd(content);

            skillsBuilder.append("#### 技能名称: ").append(skill.name()).append('\n');
            skillsBuilder.append("**触发条件**: ").append(skill.description()).append("\n\n");
            skillsBuilder.append("**执行指南**:\n");
            skillsBuilder.append(skill.body()).append("\n\n---\n");
            return true;
        } catch (IOException ignored) {
            // 单个技能读取失败不应阻断整个 Prompt 组装，保持加载器对坏文件的容错。
            return false;
        }
    }

    /**
     * 解析带 YAML Frontmatter 的 Markdown。当前只识别 name 和 description 两个元信息字段。
     */
    static Skill parseSkillMd(String content) {
        if (content == null || content.isBlank()) {
            return Skill.unknown("");
        }

        String normalized = content.replace("\r\n", "\n");
        Skill fallback = Skill.unknown(normalized.strip());
        if (!normalized.startsWith("---\n")) {
            return fallback;
        }

        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return fallback;
        }

        String frontmatter = normalized.substring(4, end);
        String body = normalized.substring(end + 4).strip();
        String name = fallback.name();
        String description = fallback.description();

        for (String rawLine : frontmatter.split("\n")) {
            String line = rawLine.strip();
            int split = line.indexOf(':');
            if (split <= 0) {
                continue;
            }

            String key = line.substring(0, split).strip().toLowerCase(Locale.ROOT);
            String value = stripQuotes(line.substring(split + 1).strip());
            if ("name".equals(key) && !value.isBlank()) {
                name = value;
            } else if ("description".equals(key) && !value.isBlank()) {
                description = value;
            }
        }

        return new Skill(name, description, body);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
