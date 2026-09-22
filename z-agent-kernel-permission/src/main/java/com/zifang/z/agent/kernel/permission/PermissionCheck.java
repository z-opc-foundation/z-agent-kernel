package com.zifang.z.agent.kernel.permission;

import java.util.Set;

/**
 * PermissionCheck SPI — 权限检查.
 *
 * <p>实现方: z-ctc (4A 中心: Authentication / Authorization / Account / Audit).
 *
 * <p>agent 在调敏感工具(写文件 / 调外部 API / 改数据库)前, 先 check 一下.
 */
public interface PermissionCheck {

    /**
     * @param subject 主体(用户 ID / service account / agent ID)
     * @param action  操作(如 "file.write" / "http.call" / "db.write")
     * @param target  操作对象(可选, 如 "/etc/passwd" 或 "https://api.example.com")
     * @return 是否允许
     */
    boolean isAllowed(String subject, String action, String target);

    /**
     * @return 当前主体拥有的所有权限(用于审计/调试)
     */
    Set<String> permissionsOf(String subject);

    /**
     * 权限拒绝异常.
     */
    class PermissionDeniedException extends RuntimeException {
        public final String subject;
        public final String action;
        public final String target;

        public PermissionDeniedException(String subject, String action, String target) {
            super("permission denied: subject=" + subject + " action=" + action + " target=" + target);
            this.subject = subject;
            this.action = action;
            this.target = target;
        }
    }
}