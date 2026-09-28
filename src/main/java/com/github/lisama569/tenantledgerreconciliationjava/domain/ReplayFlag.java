package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 重放阶段对每条记录的判定标记。 */
public enum ReplayFlag {
    /** 首次写入，尚未经过重放。 */
    NONE,
    /** 重启后正常重放生效。 */
    REPLAYED,
    /** 重复的幂等键，重放时跳过。 */
    DUPLICATE_SKIPPED,
    /** 存储顺序乱序，已按 seq 排序后重放。 */
    OUT_OF_ORDER
}
