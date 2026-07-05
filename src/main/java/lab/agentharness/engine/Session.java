package lab.agentharness.engine;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import lab.agentharness.schema.Schema;

/**
 * Session 代表一次持续的人机交互过程，负责保存该会话下的历史消息与工作区边界。
 */
public final class Session {
    private final String id;
    private final Path workDir;
    private final Instant createdAt;
    private final List<Schema.Message> history;
    private final ReentrantReadWriteLock lock;
    private Instant updatedAt;

    public Session(String id, Path workDir) {
        this.id = requireText(id, "id");
        this.workDir = Objects.requireNonNull(workDir, "workDir").toAbsolutePath().normalize();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.history = new ArrayList<>();
        this.lock = new ReentrantReadWriteLock();
    }

    public String id() {
        return id;
    }

    public Path workDir() {
        return workDir;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        lock.readLock().lock();
        try {
            return updatedAt;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 线程安全地向 Session 追加消息；后续可在这里接入 JSONL 持久化。
     */
    public void append(Schema.Message... messages) {
        if (messages == null || messages.length == 0) {
            return;
        }
        append(Arrays.asList(messages));
    }

    /**
     * 线程安全地批量追加消息，保持工具并发 Observation 的原始顺序。
     */
    public void append(Collection<Schema.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        lock.writeLock().lock();
        try {
            for (Schema.Message message : messages) {
                if (message != null) {
                    history.add(message);
                }
            }
            updatedAt = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 从完整历史尾部提取短期工作记忆，避免每次模型调用都从零开始。
     */
    public List<Schema.Message> getWorkingMemory(int limit) {
        lock.readLock().lock();
        try {
            int total = history.size();
            int fromIndex = limit <= 0 || total <= limit ? 0 : total - limit;
            List<Schema.Message> memory = new ArrayList<>(history.subList(fromIndex, total));

            // 大模型要求 tool observation 必须紧跟对应 tool call；窗口截断后要丢弃孤儿 Observation。
            while (!memory.isEmpty() && isOrphanObservation(memory.get(0))) {
                memory.remove(0);
            }
            return memory;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int historySize() {
        lock.readLock().lock();
        try {
            return history.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private static boolean isOrphanObservation(Schema.Message message) {
        return message.role() == Schema.Role.USER
                && message.toolCallId() != null
                && !message.toolCallId().isBlank();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
