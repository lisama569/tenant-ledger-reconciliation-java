package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 请求安全上下文：调用方角色 + 所属租户（COMPENSATION_ADMIN 可为 null）。 */
public record RequestContext(Role role, String tenantId) {
}
