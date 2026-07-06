package lab.agentharness.context;

import java.util.Locale;

/**
 * RecoveryManager 根据工具错误特征注入下一步恢复建议，帮助模型从失败 Observation 中自我修正。
 */
public final class RecoveryManager {
    public static RecoveryManager newRecoveryManager() {
        return new RecoveryManager();
    }

    /**
     * 分析工具原始错误；命中已知模式时，拼接一段面向 Agent 的系统救援指南。
     */
    public String analyzeAndInject(String toolName, String rawError) {
        if (rawError == null || rawError.isBlank()) {
            return rawError == null ? "" : rawError;
        }

        String hint = hintFor(toolName, rawError);
        if (hint.isBlank()) {
            return rawError;
        }

        return rawError + "\n\n[系统救援指南]: " + hint;
    }

    private static String hintFor(String toolName, String rawError) {
        String lowerError = rawError.toLowerCase(Locale.ROOT);
        return switch (toolName) {
            case "edit_file" -> editFileHint(rawError);
            case "read_file", "write_file" -> fileHint(rawError, lowerError);
            case "bash" -> bashHint(rawError, lowerError);
            default -> "";
        };
    }

    private static String editFileHint(String rawError) {
        if (rawError.contains("在文件中未找到 old_text")
                || rawError.contains("找不到该代码片段")
                || rawError.contains("未找到 old_text")) {
            return "你提供的 old_text 与文件当前内容不一致，或者缺少必要缩进。请先使用 `read_file` 工具重新读取该文件，获取最新、准确的内容后，再重新发起编辑。";
        }

        if (rawError.contains("匹配到了多处")
                || rawError.contains("模糊匹配到了")
                || rawError.contains("提供更多")) {
            return "你的 old_text 不够具体，命中了多个相同或相似代码块。请在 old_text 中增加上下相邻的几行代码，以确保替换的唯一性。";
        }

        return "";
    }

    private static String fileHint(String rawError, String lowerError) {
        if (lowerError.contains("no such file or directory")
                || rawError.contains("目标不是可读取文件")
                || rawError.contains("读取文件失败")
                || rawError.contains("路径是否正确")) {
            return "路径似乎不正确。请不要凭空猜测，先使用 `bash` 执行 `dir /s /b`、`ls -la` 或 `find . -name` 命令查找正确的目录结构和文件名。";
        }

        if (lowerError.contains("permission denied")
                || rawError.contains("拒绝读取工作区外文件")
                || rawError.contains("拒绝写入工作区外文件")
                || rawError.contains("拒绝修改工作区外文件")) {
            return "当前操作越过了工作区边界或权限不足。请检查 WorkDir 限制，并改为操作工作区内的正确文件。";
        }

        return "";
    }

    private static String bashHint(String rawError, String lowerError) {
        if (lowerError.contains("command not found")
                || lowerError.contains("is not recognized")
                || rawError.contains("不是内部或外部命令")) {
            return "系统中未安装该命令，或当前 Shell 不支持该命令。请先思考是否可使用 Windows cmd 等价命令，或改用已有工具完成任务。";
        }

        if (rawError.contains("命令执行超时")
                || rawError.contains("超时")
                || rawError.contains("DeadlineExceeded")) {
            return "该命令执行被超时强杀。如果它是常驻服务、server 或 watch，请将其转入后台执行，不要阻塞主线程。";
        }

        if (lowerError.contains("syntax error")
                || lowerError.contains("was unexpected at this time")) {
            return "Shell 语法错误。请检查引号转义、括号和特殊字符，并确保命令能在当前系统 Shell 中直接运行。";
        }

        if (lowerError.contains("exit code")) {
            return "命令返回非 0 退出码。请仔细阅读输出内容，不要重复同一条失败命令；先用更小的检查命令确认当前目录、文件和可用工具。";
        }

        return "";
    }
}
