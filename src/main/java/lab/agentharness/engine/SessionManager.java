package lab.agentharness.engine;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * SessionManager 用于按会话 ID 隔离多用户、多终端或多个飞书群的上下文历史。
 */
public final class SessionManager {
    public static final SessionManager GLOBAL = new SessionManager();

    private final Map<String, Session> sessions;
    private final ReentrantReadWriteLock lock;

    public SessionManager() {
        this.sessions = new HashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * 获取已有会话，或为新的会话 ID 绑定一个独立工作区。
     */
    public Session getOrCreate(String id, Path workDir) {
        Objects.requireNonNull(workDir, "workDir");

        lock.writeLock().lock();
        try {
            return sessions.computeIfAbsent(id, key -> new Session(key, workDir));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return sessions.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
