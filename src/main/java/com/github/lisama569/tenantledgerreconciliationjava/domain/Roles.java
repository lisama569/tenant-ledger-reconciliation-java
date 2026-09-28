package com.github.lisama569.tenantledgerreconciliationjava.domain;

/**
 * 调用方角色与权限边界。
 *
 * <ul>
 *   <li>{@link #USER}：开户、提交资金流水；</li>
 *   <li>{@link #AUDITOR}：在 USER 基础上可标记异常流水（隔离，不动余额）；</li>
 *   <li>{@link #ADMIN}：在 AUDITOR 基础上可执行补偿（单边余额调整，只能生效一次）。</li>
 * </ul>
 * 未识别/缺失角色一律按 PERMISSION_DENIED 拒绝。
 */
public final class Roles {

    public static final String USER = "USER";
    public static final String AUDITOR = "AUDITOR";
    public static final String ADMIN = "ADMIN";

    private Roles() {
    }

    public static boolean isKnown(String role) {
        return USER.equals(role) || AUDITOR.equals(role) || ADMIN.equals(role);
    }
}
