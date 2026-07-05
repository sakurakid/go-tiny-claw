package lab.agentharness.context;

/**
 * Skill 是从 SKILL.md 解析出的标准化技能结构，包含触发元信息和 Markdown 执行指南。
 */
public record Skill(String name, String description, String body) {
    public static Skill unknown(String body) {
        return new Skill("Unknown Skill", "No description provided.", body == null ? "" : body);
    }
}
