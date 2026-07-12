package lab.agentharness.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Trace 提供一个极轻量的链路追踪实现，用于把 Agent 的 Run / Turn / LLM / Tool 调用串成一棵树。
 *
 * <p>Go 版可以用 context.Context 传递当前 Span；Java 版这里采用 ThreadLocal 保存当前 Span。
 * 对于并发工具执行，调用方需要用 {@link #callWithParent(Span, Supplier)} 把父 Span 显式传入工作线程，
 * 这样多个工具 Span 就能平行挂在同一个 Turn 下面。</p>
 */
public final class Trace {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ThreadLocal<Span> CURRENT = new ThreadLocal<>();

    private Trace() {
    }

    /**
     * 开启一个新 Span。若当前线程已有父 Span，新 Span 会自动挂到父 Span 的 children 下。
     */
    public static Span startSpan(String name) {
        Span parent = CURRENT.get();
        Span span = new Span(name, parent);
        if (parent != null) {
            parent.addChild(span);
        }
        CURRENT.set(span);
        return span;
    }

    /**
     * 获取当前线程正在执行的 Span；没有追踪上下文时返回 null。
     */
    public static Span currentSpan() {
        return CURRENT.get();
    }

    /**
     * 在线程池任务中恢复父 Span。没有这个方法时，ThreadLocal 不会自动跨线程传播。
     */
    public static <T> T callWithParent(Span parent, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Span previous = CURRENT.get();
        CURRENT.set(parent);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * 将根 Span 导出为便于人类阅读的 JSON 文件。
     */
    public static Path exportTraceToFile(Span rootSpan, Path workDir, String sessionId) throws IOException {
        Objects.requireNonNull(rootSpan, "rootSpan");
        Objects.requireNonNull(workDir, "workDir");

        Path traceDir = workDir.toAbsolutePath().normalize().resolve(".claw").resolve("traces");
        Files.createDirectories(traceDir);

        String safeSessionId = safeFileName(sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId);
        Path traceFile = traceDir.resolve("trace_" + safeSessionId + "_" + Instant.now().toEpochMilli() + ".json");
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rootSpan);
        Files.writeString(traceFile, json, StandardCharsets.UTF_8);
        return traceFile;
    }

    private static void restore(Span span) {
        if (span == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(span);
        }
    }

    private static String safeFileName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Span 是一次可计时的操作节点。它实现 AutoCloseable，方便使用 try-with-resources 自动结束。
     */
    public static final class Span implements AutoCloseable {
        private final String name;
        private final Instant startedAt;
        private final String startTime;
        private final Span previousSpan;
        private final Map<String, Object> attributes;
        private final List<Span> children;

        private String endTime;
        private long durationMs;
        private boolean closed;

        private Span(String name, Span previousSpan) {
            this.name = Objects.requireNonNull(name, "name");
            this.previousSpan = previousSpan;
            this.startedAt = Instant.now();
            this.startTime = startedAt.toString();
            this.attributes = Collections.synchronizedMap(new LinkedHashMap<>());
            this.children = Collections.synchronizedList(new ArrayList<>());
            this.endTime = null;
            this.durationMs = 0L;
            this.closed = false;
        }

        @JsonProperty("name")
        public String name() {
            return name;
        }

        @JsonProperty("start_time")
        public String startTime() {
            return startTime;
        }

        @JsonProperty("end_time")
        public String endTime() {
            return endTime;
        }

        @JsonProperty("duration_ms")
        public long durationMs() {
            return durationMs;
        }

        @JsonProperty("attributes")
        public Map<String, Object> attributes() {
            synchronized (attributes) {
                return new LinkedHashMap<>(attributes);
            }
        }

        @JsonProperty("children")
        public List<Span> children() {
            synchronized (children) {
                return List.copyOf(children);
            }
        }

        @JsonIgnore
        public boolean closed() {
            return closed;
        }

        /**
         * 给 Span 追加调试元数据，例如模型 token、工具参数、输出摘要等。
         */
        public void addAttribute(String key, Object value) {
            if (key == null || key.isBlank()) {
                return;
            }
            attributes.put(key, value);
        }

        private void addChild(Span child) {
            children.add(child);
        }

        /**
         * 结束 Span 并恢复进入该 Span 前的 ThreadLocal 上下文。
         */
        @Override
        public void close() {
            if (!closed) {
                Instant endedAt = Instant.now();
                this.endTime = endedAt.toString();
                this.durationMs = Duration.between(startedAt, endedAt).toMillis();
                this.closed = true;
            }
            restore(previousSpan);
        }
    }
}
