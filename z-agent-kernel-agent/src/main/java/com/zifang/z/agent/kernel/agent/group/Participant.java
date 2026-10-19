package com.zifang.z.agent.kernel.agent.group;

/**
 * 群成员. 群聊里的一个发言主体 —— 真人或 agent.
 *
 * <p>{@link Kind#AGENT} 的成员必须能在 {@link GroupChatRunner} 的 agent 表里按 {@link #getId()} 查到,
 * 否则本轮点名到它会被跳过（不静默丢, 结果里有 skipped 记录）。
 */
public final class Participant {

    public enum Kind {
        HUMAN,
        AGENT
    }

    private final String id;
    private final String displayName;
    private final Kind kind;
    private final String role;

    public Participant(String id, String displayName, Kind kind, String role) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("participant id must not be empty");
        }
        this.id = id;
        this.displayName = displayName == null || displayName.isEmpty() ? id : displayName;
        this.kind = kind == null ? Kind.HUMAN : kind;
        this.role = role;
    }

    public static Participant human(String id, String displayName) {
        return new Participant(id, displayName, Kind.HUMAN, null);
    }

    public static Participant agent(String id, String displayName, String role) {
        return new Participant(id, displayName, Kind.AGENT, role);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Kind getKind() {
        return kind;
    }

    public String getRole() {
        return role;
    }

    public boolean isAgent() {
        return kind == Kind.AGENT;
    }

    @Override
    public String toString() {
        return "Participant{" + id + "/" + kind + "}";
    }
}
