package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fc.v2.mapper.auto.THeriBlRowMapper;
import com.fc.v2.mapper.auto.THeriJobFlowMapper;
import com.fc.v2.mapper.auto.THeriJobMapper;
import com.fc.v2.mapper.auto.THeriJobRowMapper;
import com.fc.v2.model.auto.THeriBlRow;
import com.fc.v2.model.auto.THeriBlRule;
import com.fc.v2.model.auto.THeriJob;
import com.fc.v2.model.auto.THeriJobFlow;
import com.fc.v2.model.auto.THeriJobRow;
import com.fc.v2.service.ITHeriBlRuleService;
import com.fc.v2.service.ITHeriJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 提单签发作业 Service实现（job-lifecycle 形状：批量作业提交/生效/回滚，跨模块协调）
 *
 * <p>口径要点：
 * <ul>
 * <li>一航次一号：号在 t_heri_job 上只占一行（表头 row_no=0），明细落 t_heri_job_row，行次系统顺排；</li>
 * <li>同号重复递交幂等照面；号换内容换才算新；</li>
 * <li>生效逐行三段落账（凭据写回 + 费用流水 + 留痕）同事务，哪行没落成留册写明，整航次不推翻；</li>
 * <li>回滚补红字冲账流水逐处冲回，已冲过的重试不重复写；</li>
 * <li>并发各自锁自己那一段（表头 FOR UPDATE，锁不住就等），先落者胜。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-10-03
 */
@Service
public class THeriJobServiceImpl implements ITHeriJobService {

    // 作业状态机常量（0草稿→1待审→2生效中→3已生效/4部分生效→5回滚中→6已回滚）
    private static final int ST_DRAFT = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_EFFECTING = 2;
    private static final int ST_EFFECTED = 3;
    private static final int ST_PARTIAL = 4;
    private static final int ST_ROLLING = 5;
    private static final int ST_ROLLED = 6;

    // 行状态（0待生效 1已生效 2已作废 3生效失败 6已回滚）
    private static final int ROW_PENDING = 0;
    private static final int ROW_DONE = 1;
    private static final int ROW_INVALID = 2;
    private static final int ROW_FAILED = 3;
    private static final int ROW_ROLLED = 6;

    // 流水类型（1签发费用 2冲账）
    private static final int FLOW_FEE = 1;
    private static final int FLOW_COMPENSATE = 2;

    // 放货凭据行状态（0待签 1已签 2已作废）
    private static final int BL_WAIT = 0;
    private static final int BL_SIGNED = 1;

    /** 失败原因列宽 */
    private static final int FAIL_REASON_MAX = 255;

    @Autowired
    private THeriJobMapper heriJobMapper;

    @Autowired
    private THeriJobRowMapper heriJobRowMapper;

    @Autowired
    private THeriJobFlowMapper heriJobFlowMapper;

    @Autowired
    private THeriBlRowMapper heriBlRowMapper;

    @Autowired
    private ITHeriBlRuleService heriBlRuleService;

    @Autowired
    private PlatformTransactionManager txManager;

    @Override
    public THeriJob selectTHeriJobById(Long id) {
        if (id == null) {
            return null;
        }
        return this.heriJobMapper.selectById(id);
    }

    @Override
    public THeriJob selectByJobNo(String jobNo) {
        if (jobNo == null) {
            return null;
        }
        return this.heriJobMapper.selectOne(new QueryWrapper<THeriJob>()
                .eq("job_no", jobNo).eq("row_no", 0).eq("del_flag", 0).last("limit 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submitJob(String jobNo, List<THeriJobRow> rows) {
        if (isBlank(jobNo)) {
            // 系统发号：一航次一号，往后查这个号就知道是哪一航次的事
            jobNo = "HJ" + IdWorker.getIdStr();
        }
        // 号已在册：再交一遍只当照面，返回原号，不再落行
        if (selectByJobNo(jobNo) != null) {
            return jobNo;
        }
        THeriJob header = new THeriJob();
        header.setJobNo(jobNo);
        header.setRowNo(Integer.valueOf(0));
        header.setStatus(Integer.valueOf(ST_PENDING));
        header.setTotalRows(Integer.valueOf(rows == null ? 0 : rows.size()));
        header.setDoneRows(Integer.valueOf(0));
        header.setFailRows(Integer.valueOf(0));
        header.setDelFlag(Integer.valueOf(0));
        try {
            this.heriJobMapper.insert(header);
        } catch (DuplicateKeyException dup) {
            // 并发下同号已被先落：照册上的来，照面返回原号
            return jobNo;
        }
        int idx = 0;
        int invalid = 0;
        if (rows != null) {
            for (THeriJobRow r : rows) {
                idx++;
                // 行次系统顺排，递交方不填、不插手
                r.setJobNo(jobNo);
                r.setRowNo(Integer.valueOf(idx));
                r.setDelFlag(Integer.valueOf(0));
                r.setWritebackFlag(Integer.valueOf(0));
                r.setCompensateFlag(Integer.valueOf(0));
                r.setCompensated(Integer.valueOf(0));
                if (isBlank(r.getBlNo()) || r.getAmount() == null || r.getJobQty() == null) {
                    // 册上口径：提单号/金额/件数不齐的行作废留册写明，不挡整批
                    r.setStatus(Integer.valueOf(ROW_INVALID));
                    r.setFailReason("行数据不完整：提单号/金额/件数有缺");
                    invalid++;
                } else {
                    r.setStatus(Integer.valueOf(ROW_PENDING));
                }
                this.heriJobRowMapper.insert(r);
            }
        }
        if (invalid > 0) {
            header.setFailRows(Integer.valueOf(invalid));
            this.heriJobMapper.updateById(header);
        }
        return jobNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int effectJob(String jobNo) {
        if (isBlank(jobNo)) {
            return 0;
        }
        // 锁自己那一段：表头行 FOR UPDATE，锁不住就等，不许互相踩
        THeriJob header = lockHeader(jobNo);
        if (header == null) {
            return 0;
        }
        Integer st = header.getStatus();
        // 终态（已生效/已回滚）与回滚中再核只当照面，第二遍不重复落账
        if (st != null && (st.intValue() == ST_EFFECTED || st.intValue() == ST_ROLLED
                || st.intValue() == ST_ROLLING)) {
            return 0;
        }
        header.setStatus(Integer.valueOf(ST_EFFECTING));
        this.heriJobMapper.updateById(header);

        // 核之前先看判据还在不在：本次核定时刻的可用判据一次取齐（撤下/过期的不签）
        Set<String> usableCodes = new HashSet<String>();
        List<THeriBlRule> usableRules = this.heriBlRuleService.listAvailable(new Date());
        for (THeriBlRule rule : usableRules) {
            if (rule.getRuleCode() != null) {
                usableCodes.add(rule.getRuleCode());
            }
        }

        // 只取待生效/生效失败的行：已生效、已作废的行不重碰（行级幂等）
        List<THeriJobRow> rows = this.heriJobRowMapper.selectList(new QueryWrapper<THeriJobRow>()
                .eq("job_no", jobNo).eq("del_flag", 0)
                .in("status", ROW_PENDING, ROW_FAILED)
                .orderByAsc("row_no"));
        int done = 0;
        for (THeriJobRow row : rows) {
            String reason;
            try {
                reason = rowTx().execute(status -> {
                    try {
                        effectOneRow(jobNo, row, usableCodes);
                        return null;
                    } catch (RowFailException e) {
                        // 三段对不上：整行回滚，三处一处都不落
                        status.setRollbackOnly();
                        return e.getReason();
                    }
                });
            } catch (RuntimeException e) {
                reason = cut("系统异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            if (reason == null) {
                done++;
            } else {
                // 哪一条没落成，留在册上写明白是它自己没落成，其余照签
                markRowFailed(row.getId(), reason);
            }
        }

        // 汇总回写表头：签成几条、没签成几条，倒着查得出来
        Integer doneCnt = this.heriJobRowMapper.selectCount(new QueryWrapper<THeriJobRow>()
                .eq("job_no", jobNo).eq("del_flag", 0).eq("status", ROW_DONE));
        Integer failCnt = this.heriJobRowMapper.selectCount(new QueryWrapper<THeriJobRow>()
                .eq("job_no", jobNo).eq("del_flag", 0).in("status", ROW_INVALID, ROW_FAILED));
        header.setDoneRows(doneCnt);
        header.setFailRows(failCnt);
        header.setStatus(Integer.valueOf(failCnt != null && failCnt.intValue() > 0 ? ST_PARTIAL : ST_EFFECTED));
        this.heriJobMapper.updateById(header);
        return done;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rollbackJob(String jobNo) {
        if (isBlank(jobNo)) {
            return 0;
        }
        THeriJob header = lockHeader(jobNo);
        if (header == null) {
            return 0;
        }
        Integer st = header.getStatus();
        // 只有已生效/部分生效（及上次回滚中断的回滚中）有物可退；已回滚再退只当照面
        if (st == null || (st.intValue() != ST_EFFECTED && st.intValue() != ST_PARTIAL
                && st.intValue() != ST_ROLLING)) {
            return 0;
        }
        header.setStatus(Integer.valueOf(ST_ROLLING));
        this.heriJobMapper.updateById(header);

        // 只冲已生效的行：落过的地方一处一处冲回来
        List<THeriJobRow> rows = this.heriJobRowMapper.selectList(new QueryWrapper<THeriJobRow>()
                .eq("job_no", jobNo).eq("del_flag", 0).eq("status", ROW_DONE)
                .orderByAsc("row_no"));
        int back = 0;
        for (THeriJobRow row : rows) {
            try {
                rowTx().execute(status -> {
                    compensateOneRow(jobNo, row);
                    return null;
                });
                back++;
            } catch (RuntimeException e) {
                // 单行冲不动不挡其余行；行保持已生效，下次重试接着冲
            }
        }

        Integer remain = this.heriJobRowMapper.selectCount(new QueryWrapper<THeriJobRow>()
                .eq("job_no", jobNo).eq("del_flag", 0).eq("status", ROW_DONE));
        Integer compCnt = this.heriJobRowMapper.selectCount(new QueryWrapper<THeriJobRow>()
                .eq("job_no", jobNo).eq("del_flag", 0).eq("compensated", 1));
        boolean allBack = remain == null || remain.intValue() == 0;
        header.setCompensateCnt(compCnt);
        header.setCompensateFlag(Integer.valueOf(compCnt != null && compCnt.intValue() > 0 ? 1 : 0));
        header.setCompensated(Integer.valueOf(allBack ? 1 : 0));
        header.setStatus(Integer.valueOf(allBack ? ST_ROLLED : ST_ROLLING));
        this.heriJobMapper.updateById(header);
        return back;
    }

    @Override
    public List<THeriJobRow> listJobRows(String jobNo) {
        if (isBlank(jobNo)) {
            return new ArrayList<THeriJobRow>();
        }
        return this.heriJobRowMapper.selectList(new QueryWrapper<THeriJobRow>()
                .eq("job_no", jobNo).eq("del_flag", 0).orderByAsc("row_no"));
    }

    @Override
    public List<THeriJobFlow> listJobFlows(String jobNo) {
        if (isBlank(jobNo)) {
            return new ArrayList<THeriJobFlow>();
        }
        return this.heriJobFlowMapper.selectList(new QueryWrapper<THeriJobFlow>()
                .eq("job_no", jobNo).eq("del_flag", 0).orderByAsc("id"));
    }

    /**
     * 单行生效：三段落账（放货凭据写回 + 费用流水 + 操作留痕）。
     * 任一段落不下抛 {@link RowFailException}，由调用方整行回滚——三处要么都对上，要么一处都不落。
     */
    private void effectOneRow(String jobNo, THeriJobRow row, Set<String> usableCodes) {
        // 一、核之前先看判据还在不在：撤了、过期了的还照签，签出来的东西船代不认
        if (isBlank(row.getVersion())) {
            throw new RowFailException("判据版本缺失");
        }
        if (!usableCodes.contains(row.getVersion())) {
            throw new RowFailException("判据不存在、已撤下或已过期");
        }
        // 二、放货凭据上那几条得改（先落者胜：只翻 待签→已签，已被别的批次先落的翻不动）
        THeriBlRow blUp = new THeriBlRow();
        blUp.setStatus(Integer.valueOf(BL_SIGNED));
        int hit = this.heriBlRowMapper.update(blUp, new UpdateWrapper<THeriBlRow>()
                .eq("batch_no", jobNo)
                .eq("item_code", row.getBlNo())
                .eq("status", BL_WAIT)
                .eq("del_flag", 0));
        if (hit == 0) {
            throw new RowFailException("放货凭据行不存在或已签");
        }
        // 三、这航次的费用流水落一条
        THeriJobFlow flow = new THeriJobFlow();
        flow.setJobNo(jobNo);
        flow.setRowNo(row.getRowNo());
        flow.setBlNo(row.getBlNo());
        flow.setFlowType(Integer.valueOf(FLOW_FEE));
        flow.setAmount(row.getAmount());
        flow.setJobQty(row.getJobQty());
        flow.setDelFlag(Integer.valueOf(0));
        flow.setRemark("签发费用");
        this.heriJobFlowMapper.insert(flow);
        // 四、谁动的手上留一笔（update_by/update_time 由框架自动留痕）
        THeriJobRow up = new THeriJobRow();
        up.setId(row.getId());
        up.setStatus(Integer.valueOf(ROW_DONE));
        up.setWritebackFlag(Integer.valueOf(1));
        this.heriJobRowMapper.update(up, new UpdateWrapper<THeriJobRow>()
                .eq("id", row.getId())
                .set("fail_reason", null));
    }

    /**
     * 单行冲回：补一道红字冲账流水 + 凭据冲回待签 + 行留痕。
     * 同一条已冲过的（流水已在）不再重复写流水——该写几道写几道，重试只当照面。
     */
    private void compensateOneRow(String jobNo, THeriJobRow row) {
        Integer dup = this.heriJobFlowMapper.selectCount(new QueryWrapper<THeriJobFlow>()
                .eq("job_no", jobNo)
                .eq("row_no", row.getRowNo())
                .eq("flow_type", FLOW_COMPENSATE)
                .eq("del_flag", 0));
        if (dup == null || dup.intValue() == 0) {
            THeriJobFlow flow = new THeriJobFlow();
            flow.setJobNo(jobNo);
            flow.setRowNo(row.getRowNo());
            flow.setBlNo(row.getBlNo());
            flow.setFlowType(Integer.valueOf(FLOW_COMPENSATE));
            // 红字冲账：与原费用流水正负相抵，账才平得了
            flow.setAmount(row.getAmount() == null ? null : row.getAmount().negate());
            flow.setJobQty(row.getJobQty());
            flow.setDelFlag(Integer.valueOf(0));
            flow.setRemark("冲账");
            this.heriJobFlowMapper.insert(flow);
        }
        // 凭据上落过的冲回来（已签→待签）
        THeriBlRow blUp = new THeriBlRow();
        blUp.setStatus(Integer.valueOf(BL_WAIT));
        this.heriBlRowMapper.update(blUp, new UpdateWrapper<THeriBlRow>()
                .eq("batch_no", jobNo)
                .eq("item_code", row.getBlNo())
                .eq("status", BL_SIGNED)
                .eq("del_flag", 0));
        // 行留痕
        THeriJobRow up = new THeriJobRow();
        up.setId(row.getId());
        up.setStatus(Integer.valueOf(ROW_ROLLED));
        up.setCompensateFlag(Integer.valueOf(1));
        up.setCompensated(Integer.valueOf(1));
        this.heriJobRowMapper.updateById(up);
    }

    /** 锁表头行：FOR UPDATE，锁不住就等 */
    private THeriJob lockHeader(String jobNo) {
        return this.heriJobMapper.selectOne(new QueryWrapper<THeriJob>()
                .eq("job_no", jobNo).eq("row_no", 0).eq("del_flag", 0)
                .last("limit 1 for update"));
    }

    /** 每行一个独立事务（REQUIRES_NEW）：行与行互不牵连，哪行没落成不推翻整航次 */
    private TransactionTemplate rowTx() {
        TransactionTemplate tx = new TransactionTemplate(this.txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    /** 失败行留册写明原因（独立事务提交，不随后续行成败） */
    private void markRowFailed(Long rowId, String reason) {
        rowTx().execute(status -> {
            THeriJobRow up = new THeriJobRow();
            up.setId(rowId);
            up.setStatus(Integer.valueOf(ROW_FAILED));
            this.heriJobRowMapper.update(up, new UpdateWrapper<THeriJobRow>()
                    .eq("id", rowId)
                    .set("fail_reason", cut(reason)));
            return null;
        });
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= FAIL_REASON_MAX ? s : s.substring(0, FAIL_REASON_MAX);
    }

    /** 行级业务失败：携带留册原因，触发整行回滚 */
    private static final class RowFailException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        RowFailException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String getReason() {
            return reason;
        }
    }
}
