package lab.agentharness.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * AppConfig 负责读取运行时配置：优先使用系统环境变量，其次读取项目根目录下不会提交的 .env.local。
 */
public final class AppConfig {
    private static final Map<String, String> LOCAL_VALUES = loadLocalValues();

    private AppConfig() {
    }

    public static String get(String key) {
        String value = System.getenv(key);
        if (hasText(value)) {
            return value;
        }
        return LOCAL_VALUES.get(key);
    }

    public static String get(String key, String defaultValue) {
        String value = get(key);
        return hasText(value) ? value : defaultValue;
    }

    private static Map<String, String> loadLocalValues() {
        Path localEnv = Path.of(".env.local");
        if (!Files.isRegularFile(localEnv)) {
            return Map.of();
        }

        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(localEnv, StandardCharsets.UTF_8)) {
                parseLine(line, values);
            }
            return Map.copyOf(values);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void parseLine(String line, Map<String, String> values) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }

        int split = trimmed.indexOf('=');
        if (split <= 0) {
            return;
        }

        String key = trimmed.substring(0, split).trim();
        String value = stripQuotes(trimmed.substring(split + 1).trim());
        if (!key.isEmpty()) {
            values.put(key, value);
        }
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
