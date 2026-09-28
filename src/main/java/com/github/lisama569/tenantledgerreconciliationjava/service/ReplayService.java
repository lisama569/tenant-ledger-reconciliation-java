package com.github.lisama569.tenantledgerreconciliationjava.service;

import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ReplayFlag;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ReplayReport;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LedgerRepository;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LedgerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 重放服务：启动时从本地操作日志确定性重建账户与流水。
 *
 * 识别规则：
 * - 正常：seq 从 1 连续、哈希链完整 —— 全部 APPLIED 记录重新生效，余额与重启前一致；
 * - 重复：同一 requestId 的 APPLIED 记录出现多次 —— 只生效第一条，其余标记 DUPLICATE_SKIPPED；
 * - 乱序：存储顺序与 seq 顺序不一致 —— 按 seq 排序后安全重放，标记 OUT_OF_ORDER（不影响结果）；
 * - 缺号/中断：seq 不连续 —— 无法确定被删记录的账务影响，healthy=false，账本只读保护；
 * - 篡改：记录哈希或链不吻合 —— 同上，healthy=false 并定位篡改序号。
 */
@Service
public class ReplayService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final LedgerRepository repo;
    private final LedgerService ledgerService;
    private volatile ReplayReport lastReport;

    public ReplayService(LedgerRepository repository, LedgerService ledgerService) {
        this.repo = repository;
        this.ledgerService = ledgerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        replayOnStartup();
    }

    public void replayOnStartup() {
        ReplayReport report = replay();
        log.info("启动重放完成: 总记录={} 生效={} 跳过重复={} 乱序={} 缺号={} 篡改={} healthy={}",
                report.totalRecords(), report.applied(), report.skippedDuplicates(),
                report.outOfOrder(), report.gapDetected(), report.tampered(), report.healthy());
        for (String note : report.notes()) {
            log.info("重放判定依据: {}", note);
        }
    }

    public ReplayReport replay() {
        return repo.mutate(() -> {
            LedgerState raw = repo.rawSnapshot();
            List<OperationRecord> stored = new ArrayList<>(raw.getOperations());

            repo.replaceRuntimeState(raw);
            repo.resetDerivedState();

            List<String> notes = new ArrayList<>();
            int total = stored.size();

            // 1) 乱序识别：存储顺序是否已按 seq 单调
            boolean disordered = false;
            for (int i = 1; i < stored.size(); i++) {
                if (stored.get(i).getSeq() < stored.get(i - 1).getSeq()) {
                    disordered = true;
                    break;
                }
            }
            List<OperationRecord> ordered = new ArrayList<>(stored);
            ordered.sort(Comparator.comparingLong(OperationRecord::getSeq));
            int outOfOrder = disordered ? 1 : 0;
            if (disordered) {
                notes.add("检测到存储顺序乱序，已按 seq 升序重放");
            }

            // 2) 识别物理重复副本：同 seq 且 hash 完全相同（日志被重复写入/重复投递）。
            //    这类副本不参与连续性与哈希链校验，重放时安全跳过；
            //    同 seq 但 hash 不同不是重复，而是篡改，留给链校验识别。
            List<OperationRecord> canonical = new ArrayList<>();
            java.util.Map<Long, OperationRecord> firstBySeq = new java.util.HashMap<>();
            int physicalDuplicates = 0;
            for (OperationRecord r : ordered) {
                OperationRecord first = firstBySeq.get(r.getSeq());
                if (first != null && java.util.Objects.equals(first.getHash(), r.getHash())) {
                    r.setReplayFlag(ReplayFlag.DUPLICATE_SKIPPED);
                    physicalDuplicates++;
                    notes.add("物理重复副本安全跳过: seq=" + r.getSeq()
                            + " requestId=" + r.getRequestId());
                    continue;
                }
                firstBySeq.putIfAbsent(r.getSeq(), r);
                canonical.add(r);
            }

            // 3) 对去重后的规范链做完整性检查：seq 连续 + 哈希链
            long gapAfter = -1;
            long tamperedSeq = -1;
            String expectedHash = HashChains.GENESIS_HASH;
            Long prevSeq = null;
            for (OperationRecord r : canonical) {
                if (prevSeq != null && r.getSeq() != prevSeq + 1) {
                    gapAfter = prevSeq;
                    notes.add("检测到序号缺口: seq=" + prevSeq + " 之后缺失，下一记录 seq=" + r.getSeq());
                    break;
                }
                if (!expectedHash.equals(r.getPrevHash())
                        || !HashChains.hash(r, expectedHash).equals(r.getHash())) {
                    tamperedSeq = r.getSeq();
                    notes.add("哈希链校验失败: seq=" + r.getSeq() + " 记录被篡改或链路断裂");
                    break;
                }
                prevSeq = r.getSeq();
                expectedHash = r.getHash();
            }

            boolean gap = gapAfter > 0;
            boolean tampered = tamperedSeq > 0;
            if (gap || tampered) {
                repo.setHealthy(false, gap ? ("操作日志缺号: seq=" + gapAfter + " 之后中断")
                        : ("操作日志哈希链断裂: seq=" + tamperedSeq));
                lastReport = new ReplayReport(total, 0, physicalDuplicates, outOfOrder, gap, tampered,
                        gap ? gapAfter : null, tampered ? tamperedSeq : null, false, notes);
                log.warn("重放中止并进入只读保护: {}", notes);
                return lastReport;
            }

            // 4) 安全重放：物理副本已跳过；不同 seq 但 requestId 重复的 APPLIED 记录也只生效一次
            Set<String> seen = new HashSet<>();
            int applied = 0;
            int duplicates = physicalDuplicates;
            for (OperationRecord r : canonical) {
                boolean appliedRecord = r.getStatus() == OperationStatus.APPLIED;
                if (appliedRecord) {
                    if (r.getRequestId() != null && !seen.add(r.getRequestId())) {
                        r.setReplayFlag(ReplayFlag.DUPLICATE_SKIPPED);
                        duplicates++;
                        notes.add("重复幂等键安全跳过: seq=" + r.getSeq() + " requestId=" + r.getRequestId());
                        continue;
                    }
                    ledgerService.applyRecordEffects(r);
                    r.setReplayFlag(ReplayFlag.REPLAYED);
                    applied++;
                } else {
                    // 被拒绝的记录无账务效果，仅保留为审计事实
                    r.setReplayFlag(ReplayFlag.REPLAYED);
                }
            }

            repo.setHealthy(true, null);
            if (canonical.isEmpty()) {
                notes.add("操作日志为空，从零状态启动");
            } else {
                notes.add("重放后账户余额与流水均由操作日志确定性重建，最终余额不变");
            }
            lastReport = new ReplayReport(total, applied, duplicates, outOfOrder, false, false,
                    null, null, true, notes);
            return lastReport;
        });
    }

    public boolean isHealthy() {
        return repo.isHealthy();
    }

    public ReplayReport lastReport() {
        return lastReport;
    }
}
