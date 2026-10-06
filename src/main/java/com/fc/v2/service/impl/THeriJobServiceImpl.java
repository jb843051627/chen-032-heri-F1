package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THeriAdvRowMapper;
import com.fc.v2.mapper.auto.THeriDelRowMapper;
import com.fc.v2.mapper.auto.THeriJobMapper;
import com.fc.v2.mapper.auto.THeriJobRowMapper;
import com.fc.v2.mapper.auto.TSysOperLogMapper;
import com.fc.v2.model.auto.THeriAdvRow;
import com.fc.v2.model.auto.THeriBlRule;
import com.fc.v2.model.auto.THeriDelRow;
import com.fc.v2.model.auto.THeriJob;
import com.fc.v2.model.auto.THeriJobRow;
import com.fc.v2.model.auto.TSysOperLog;
import com.fc.v2.service.ITHeriBlRuleService;
import com.fc.v2.service.ITHeriJobService;
import com.fc.v2.util.SnowflakeIdWorker;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 提单签发作业 Service实现（job-lifecycle 形状：航次批量签发/退回，跨模块协调）
 *
 * <p>落账结构：</p>
 * <ul>
 *   <li>t_heri_job：头表，一个作业号只占一行（row_no=0），total/done/fail 汇总倒查；</li>
 *   <li>t_heri_job_row：明细，行次由册上按递交顺序排 1..N，交单方填的 rowNo 不认；</li>
 *   <li>三处写回各有占位：放货凭据=t_heri_del_row、费用流水=t_heri_adv_row、经手留痕=t_sys_oper_log。</li>
 * </ul>
 *
 * <p>writeback_flag / compensate_flag 用三位掩码逐处记账（三处对得上才算签成，
 * 退回时按落过的位一处一处补冲账）：位1放货凭据、位2费用流水、位4经手留痕，三处齐=7。</p>
 *
 * @author fuce
 * @date 2026-10-06
 */
@Service
public class THeriJobServiceImpl implements ITHeriJobService {

    // ---- 头表状态机（0草稿 1待审 2生效中 3已生效 4部分生效 5回滚中 6已回滚） ----
    private static final int ST_DRAFT = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_EFFECTING = 2;
    private static final int ST_EFFECTED = 3;
    private static final int ST_PARTIAL = 4;
    private static final int ST_ROLLING = 5;
    private static final int ST_ROLLED = 6;

    // ---- 行状态：0待签 1已签 2没签成 3已退回 ----
    private static final int ROW_PENDING = 0;
    private static final int ROW_SIGNED = 1;
    private static final int ROW_REJECTED = 2;
    private static final int ROW_RETURNED = 3;

    // ---- 三处写回占位掩码 ----
    private static final int PLACE_DELIVERY = 1;
    private static final int PLACE_LEDGER = 2;
    private static final int PLACE_TRAIL = 4;
    private static final int PLACE_ALL = 7;

    /** 放货凭据条目状态 0待发 1已发 2已处理；费用流水状态 0在办 1已定下 2已封住 */
    private static final int DEL_ISSUED = 1;
    private static final int DEL_REVERSED = 2;
    private static final int ADV_POSTED = 1;
    private static final int ADV_REVERSED = 2;

    /** 每一航次一段锁：同号核签排队等，异号各锁各的、互不踩 */
    private final ConcurrentHashMap<String, Object> segmentLocks = new ConcurrentHashMap<String, Object>();

    /** 册上发号（航次号） */
    private static final SnowflakeIdWorker ID_WORKER = new SnowflakeIdWorker(1, 1);

    @Resource
    private THeriJobMapper heriJobMapper;
    @Resource
    private THeriJobRowMapper heriJobRowMapper;
    @Resource
    private THeriDelRowMapper heriDelRowMapper;
    @Resource
    private THeriAdvRowMapper heriAdvRowMapper;
    @Resource
    private TSysOperLogMapper sysOperLogMapper;
    @Resource
    private ITHeriBlRuleService heriBlRuleService;

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
    public List<THeriJobRow> listRows(String jobNo) {
        if (jobNo == null) {
            return new ArrayList<THeriJobRow>();
        }
        return this.heriJobRowMapper.selectList(new QueryWrapper<THeriJobRow>()
                .eq("job_no", jobNo).eq("del_flag", 0).orderByAsc("row_no"));
    }

    @Override
    public String openJob(String shipName, List<THeriJobRow> rows, String operator) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("本航次没有可签发的提单行");
        }
        // 册上发号：号是航次的号，往后凭这个号倒查整航次（V+日期+雪花号，唯一）
        String jobNo = "V" + new java.text.SimpleDateFormat("yyyyMMdd").format(new Date())
                + ID_WORKER.nextId();
        return registerJob(jobNo, shipName, rows, operator);
    }

    @Override
    public String resubmit(String jobNo, List<THeriJobRow> rows, String operator) {
        if (jobNo == null || jobNo.trim().isEmpty()) {
            throw new IllegalArgumentException("作业号不能为空");
        }
        // 同一个号已在册上：再交一遍照册上的来——照面，原样回号，不另落头行、不重排
        if (selectByJobNo(jobNo) != null) {
            return jobNo;
        }
        // 册上没有（号换、内容也换）才算新的
        return registerJob(jobNo, null, rows == null ? new ArrayList<THeriJobRow>() : rows, operator);
    }

    /**
     * 落头表一行（row_no=0，一个号在册上只占一行）+ 明细行；行次册上排，交单方不插手。
     * 不齐的行也留在册上，标没签成、写明原因，不把整叠顶回去。
     */
    private String registerJob(String jobNo, String shipName, List<THeriJobRow> rows, String operator) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("本航次没有可签发的提单行");
        }
        synchronized (lockOf(jobNo)) {
            if (selectByJobNo(jobNo) != null) {
                // 并发下同一刻两册同号递交：先落的在册，后来的照面
                return jobNo;
            }
            Date now = new Date();
            THeriJob head = new THeriJob();
            head.setJobNo(jobNo);
            head.setJobName(shipName);
            head.setRowNo(0);
            head.setStatus(ST_PENDING);
            head.setTotalRows(rows.size());
            head.setDoneRows(0);
            head.setFailRows(0);
            head.setDelFlag(0);
            head.setCreateBy(operator);
            head.setCreateTime(now);
            this.heriJobMapper.insert(head);

            int seq = 0;
            int rejected = 0;
            for (THeriJobRow src : rows) {
                seq++;
                THeriJobRow r = new THeriJobRow();
                r.setJobNo(jobNo);
                r.setRowNo(seq); // 行次册上排，交单方填的一律覆盖
                r.setBlNo(trim(src.getBlNo()));
                r.setAmount(src.getAmount());
                r.setJobQty(src.getJobQty());
                r.setVersion(trim(src.getVersion()));
                r.setVersionStatus(0);
                r.setWritebackFlag(0);
                r.setCompensateFlag(0);
                r.setCompensated(0);
                r.setCompensateCnt(0);
                r.setDelFlag(0);
                r.setCreateBy(operator);
                r.setCreateTime(now);
                String incomplete = checkIncomplete(r);
                if (incomplete != null) {
                    // 老数据/不齐行：留在册上写明白是它自己不齐，不连累同航次其余行
                    r.setStatus(ROW_REJECTED);
                    r.setFailReason(incomplete);
                    rejected++;
                } else {
                    r.setStatus(ROW_PENDING);
                }
                this.heriJobRowMapper.insert(r);
            }
            // 不齐的行开号即算"没签成"，头表汇总当场对得上
            head.setFailRows(rejected);
            this.heriJobMapper.updateById(head);
            writeTrail(jobNo, "开号", operator,
                    "航次开号，船名航次=" + shipName + "，递交行数=" + rows.size()
                            + "，不齐留册=" + rejected);
            return jobNo;
        }
    }

    @Override
    public THeriJob effectJob(String jobNo, Date at, String operator) {
        THeriJob head = selectByJobNo(jobNo);
        if (head == null) {
            return null;
        }
        Integer st = head.getStatus();
        if (st != null && (st == ST_ROLLING || st == ST_ROLLED)) {
            throw new IllegalStateException("航次已进入退回流程，不能再核签: " + jobNo);
        }
        if (st != null && st == ST_EFFECTING) {
            throw new IllegalStateException("航次正在核签中，请勿重复核: " + jobNo);
        }
        final Date now = at != null ? at : new Date();
        synchronized (lockOf(jobNo)) {
            head.setStatus(ST_EFFECTING);
            head.setUpdateBy(operator);
            head.setUpdateTime(new Date());
            this.heriJobMapper.updateById(head);

            List<THeriJobRow> rows = listRows(jobNo);
            int done = 0;
            int fail = 0;
            for (THeriJobRow r : rows) {
                Integer rs = r.getStatus();
                if (rs != null && rs == ROW_SIGNED) {
                    // 已签成的行不重落
                    done++;
                    continue;
                }
                if (rs != null && rs == ROW_RETURNED) {
                    continue;
                }
                EffectOutcome out = effectOneRow(head, r, now, operator);
                if (out == EffectOutcome.SIGNED) {
                    done++;
                } else {
                    fail++;
                }
            }

            head.setDoneRows(done);
            head.setFailRows(fail);
            // 一条没签成的都没有才算已生效；有没签成的就是部分生效，不推翻重来
            head.setStatus(fail == 0 ? ST_EFFECTED : ST_PARTIAL);
            head.setUpdateBy(operator);
            head.setUpdateTime(new Date());
            this.heriJobMapper.updateById(head);
            writeTrail(jobNo, "核签", operator,
                    "核签完成，签成=" + done + "，没签成=" + fail);
            return head;
        }
    }

    /** 核签单行：先看判据还在不在，再逐处落三处；某一处没落只记本行。 */
    private EffectOutcome effectOneRow(THeriJob head, THeriJobRow r, Date at, String operator) {
        List<String> missing = new ArrayList<String>();

        // 1) 核之前先看一眼：件数/提单号/金额不齐，照册上的来——留册不签
        String incomplete = checkIncomplete(r);
        if (incomplete != null) {
            return rejectRow(r, incomplete, 0);
        }

        // 2) 判据还在不在：版本没写 / 判据撤了 / 过期了，签出来船代不认
        if (r.getVersion() == null) {
            return rejectRow(r, "判据版本没写，不签", 0);
        }
        THeriBlRule rule = this.heriBlRuleService.findEffective(r.getVersion(), at);
        if (rule == null) {
            return rejectRow(r, "判据已撤下或过期(版本=" + r.getVersion() + ")，不签", 0);
        }
        r.setVersionStatus(1);

        // 3) 三处写回，逐处独立成败；先查后落，重试不写重
        int mask = r.getWritebackFlag() == null ? 0 : r.getWritebackFlag();
        if ((mask & PLACE_DELIVERY) == 0) {
            if (landDelivery(head, r, operator)) {
                mask |= PLACE_DELIVERY;
            } else {
                missing.add("放货凭据");
            }
        }
        if ((mask & PLACE_LEDGER) == 0) {
            if (landLedger(head, r, operator)) {
                mask |= PLACE_LEDGER;
            } else {
                missing.add("费用流水");
            }
        }
        if ((mask & PLACE_TRAIL) == 0) {
            if (landRowTrail(head, r, "签发行", operator, null)) {
                mask |= PLACE_TRAIL;
            } else {
                missing.add("经手留痕");
            }
        }

        r.setWritebackFlag(mask);
        if (mask == PLACE_ALL) {
            r.setStatus(ROW_SIGNED);
            r.setFailReason(null);
            r.setCompensated(0);
            r.setCompensateFlag(0);
            persistRow(r, operator);
            return EffectOutcome.SIGNED;
        }
        // 某处没落：把这条留在册上写明白是它自己没落，其余几条照签
        return rejectRow(r, "三处没落齐，缺：" + join(missing), mask);
    }

    private EffectOutcome rejectRow(THeriJobRow r, String reason, int landedMask) {
        r.setStatus(ROW_REJECTED);
        r.setFailReason(reason);
        if (landedMask > 0) {
            r.setWritebackFlag(landedMask);
        }
        r.setUpdateTime(new Date());
        this.heriJobRowMapper.updateById(r);
        return EffectOutcome.REJECTED;
    }

    private void persistRow(THeriJobRow r, String operator) {
        r.setUpdateBy(operator);
        r.setUpdateTime(new Date());
        this.heriJobRowMapper.updateById(r);
    }

    /** 放货凭据：签发一条已发凭据（退回时不划它，另补冲账条） */
    private boolean landDelivery(THeriJob head, THeriJobRow r, String operator) {
        try {
            String itemNo = deliveryItemNo(r);
            if (existsDelRow(itemNo)) {
                return true;
            }
            THeriDelRow d = new THeriDelRow();
            d.setItemNo(itemNo);
            d.setAmount(r.getAmount());
            d.setShipName(head.getJobName());
            d.setNotifyType("签发放货");
            d.setStatus(DEL_ISSUED);
            d.setDelFlag(0);
            d.setCreateBy(operator);
            d.setCreateTime(new Date());
            return this.heriDelRowMapper.insert(d) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** 费用流水：签发货一条入账流水 */
    private boolean landLedger(THeriJob head, THeriJobRow r, String operator) {
        try {
            String itemNo = ledgerItemNo(r);
            if (existsAdvRow(itemNo)) {
                return true;
            }
            THeriAdvRow a = new THeriAdvRow();
            a.setItemNo(itemNo);
            a.setBizNo(r.getBlNo());
            a.setAmount(r.getAmount());
            a.setSettleType("签发入账");
            a.setShipName(head.getJobName());
            a.setStage(1);
            a.setContent("航次" + r.getJobNo() + "第" + r.getRowNo() + "行签发");
            a.setLastAction("签发");
            a.setStatus(ADV_POSTED);
            a.setDelFlag(0);
            a.setCreateBy(operator);
            a.setCreateTime(new Date());
            return this.heriAdvRowMapper.insert(a) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** 经手留痕：每签一行留一笔（谁动的、什么时候、哪一行） */
    private boolean landRowTrail(THeriJob head, THeriJobRow r, String action,
                                 String operator, String extra) {
        try {
            TSysOperLog log = newTrail(r.getJobNo(), action, operator,
                    "行次=" + r.getRowNo() + "，提单号=" + r.getBlNo()
                            + (extra == null ? "" : "，" + extra));
            return this.sysOperLogMapper.insert(log) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int rollbackJob(String jobNo, String operator) {
        THeriJob head = selectByJobNo(jobNo);
        if (head == null) {
            return 0;
        }
        Integer st = head.getStatus();
        if (st != null && st == ST_ROLLED) {
            // 全冲过了再退一遍：照面，一道流水都不重写
            return 0;
        }
        if (st != null && (st == ST_DRAFT || st == ST_PENDING || st == ST_EFFECTING)) {
            throw new IllegalStateException("航次尚未签成，不能退回: " + jobNo);
        }
        synchronized (lockOf(jobNo)) {
            head.setStatus(ST_ROLLING);
            this.heriJobMapper.updateById(head);

            int returnedThisTime = 0;
            boolean anySignedLeft = false;
            for (THeriJobRow r : listRows(jobNo)) {
                Integer rs = r.getStatus();
                if (rs == null || rs != ROW_SIGNED) {
                    continue;
                }
                int before = r.getCompensateFlag() == null ? 0 : r.getCompensateFlag();
                int after = compensateOneRow(head, r, operator);
                if (after == (r.getWritebackFlag() == null ? 0 : r.getWritebackFlag())) {
                    // 落过的几处全冲平了，本行才算退回；冲账不划凭据
                    r.setStatus(ROW_RETURNED);
                    r.setCompensated(1);
                    r.setFailReason(null);
                    returnedThisTime++;
                } else {
                    anySignedLeft = true;
                }
                // 重试只补缺的：compensateCnt 记退回重试遍数
                if (after != before) {
                    int cnt = r.getCompensateCnt() == null ? 0 : r.getCompensateCnt();
                    r.setCompensateCnt(cnt + 1);
                }
                r.setCompensateFlag(after);
                persistRow(r, operator);
            }

            head.setStatus(anySignedLeft ? ST_ROLLING : ST_ROLLED);
            head.setUpdateBy(operator);
            head.setUpdateTime(new Date());
            this.heriJobMapper.updateById(head);
            writeTrail(jobNo, "退回", operator,
                    "本次冲平退回 " + returnedThisTime + " 行，冲账按落过的处补缺、不写重");
            return returnedThisTime;
        }
    }

    /**
     * 按落过的地方一处一处冲回来：凭据补冲账凭据条（不划原条）、流水补负数冲账条、
     * 留痕补退回笔。已冲过的处先查后跳——重试也不写重。
     */
    private int compensateOneRow(THeriJob head, THeriJobRow r, String operator) {
        int landed = r.getWritebackFlag() == null ? 0 : r.getWritebackFlag();
        int done = r.getCompensateFlag() == null ? 0 : r.getCompensateFlag();

        if ((landed & PLACE_DELIVERY) != 0 && (done & PLACE_DELIVERY) == 0
                && reverseDelivery(head, r, operator)) {
            done |= PLACE_DELIVERY;
        }
        if ((landed & PLACE_LEDGER) != 0 && (done & PLACE_LEDGER) == 0
                && reverseLedger(head, r, operator)) {
            done |= PLACE_LEDGER;
        }
        if ((landed & PLACE_TRAIL) != 0 && (done & PLACE_TRAIL) == 0
                && landRowTrail(head, r, "退回冲账", operator, "按落过的处冲回")) {
            done |= PLACE_TRAIL;
        }
        return done;
    }

    private boolean reverseDelivery(THeriJob head, THeriJobRow r, String operator) {
        try {
            String itemNo = deliveryRevItemNo(r);
            if (existsDelRow(itemNo)) {
                return true;
            }
            THeriDelRow d = new THeriDelRow();
            d.setItemNo(itemNo);
            d.setAmount(r.getAmount() == null ? null : r.getAmount().negate());
            d.setShipName(head.getJobName());
            d.setNotifyType("退回冲账");
            d.setStatus(DEL_REVERSED);
            d.setDelFlag(0);
            d.setCreateBy(operator);
            d.setCreateTime(new Date());
            d.setRemark("冲销凭据 " + deliveryItemNo(r) + "，原条不划");
            return this.heriDelRowMapper.insert(d) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean reverseLedger(THeriJob head, THeriJobRow r, String operator) {
        try {
            String itemNo = ledgerRevItemNo(r);
            if (existsAdvRow(itemNo)) {
                return true;
            }
            THeriAdvRow a = new THeriAdvRow();
            a.setItemNo(itemNo);
            a.setBizNo(r.getBlNo());
            a.setAmount(r.getAmount() == null ? null : r.getAmount().negate());
            a.setSettleType("退回冲账");
            a.setShipName(head.getJobName());
            a.setStage(2);
            a.setContent("航次" + r.getJobNo() + "第" + r.getRowNo() + "行退回冲账");
            a.setLastAction("冲账");
            a.setStatus(ADV_REVERSED);
            a.setDelFlag(0);
            a.setCreateBy(operator);
            a.setCreateTime(new Date());
            return this.heriAdvRowMapper.insert(a) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<TSysOperLog> listTrail(String jobNo) {
        if (jobNo == null) {
            return new ArrayList<TSysOperLog>();
        }
        return this.sysOperLogMapper.selectList(new QueryWrapper<TSysOperLog>()
                .eq("oper_url", trailKey(jobNo)).orderByAsc("oper_time"));
    }

    // ---------------- 零碎口径 ----------------

    /** 件数、提单号、金额、判据版本任一不齐：照册上的来——留册写明，不顶回整批 */
    private String checkIncomplete(THeriJobRow r) {
        if (r.getBlNo() == null || r.getBlNo().trim().isEmpty()) {
            return "提单号空着，不齐";
        }
        if (r.getJobQty() == null || r.getJobQty().intValue() <= 0) {
            return "件数空着或不是正数，不齐";
        }
        if (r.getAmount() == null) {
            return "金额空着，不齐";
        }
        return null;
    }

    private Object lockOf(String jobNo) {
        // putIfAbsent 保证同号同一把锁；锁对象常驻（航次号数量有限），异号各拿各的
        Object exist = this.segmentLocks.get(jobNo);
        if (exist != null) {
            return exist;
        }
        Object fresh = new Object();
        Object old = this.segmentLocks.putIfAbsent(jobNo, fresh);
        return old == null ? fresh : old;
    }

    private boolean existsDelRow(String itemNo) {
        Integer cnt = this.heriDelRowMapper.selectCount(new QueryWrapper<THeriDelRow>()
                .eq("item_no", itemNo).eq("del_flag", 0));
        return cnt != null && cnt > 0;
    }

    private boolean existsAdvRow(String itemNo) {
        Integer cnt = this.heriAdvRowMapper.selectCount(new QueryWrapper<THeriAdvRow>()
                .eq("item_no", itemNo).eq("del_flag", 0));
        return cnt != null && cnt > 0;
    }

    private String deliveryItemNo(THeriJobRow r) {
        return "D:" + r.getJobNo() + ":" + r.getRowNo();
    }

    private String deliveryRevItemNo(THeriJobRow r) {
        return "DR:" + r.getJobNo() + ":" + r.getRowNo();
    }

    private String ledgerItemNo(THeriJobRow r) {
        return "A:" + r.getJobNo() + ":" + r.getRowNo();
    }

    private String ledgerRevItemNo(THeriJobRow r) {
        return "AR:" + r.getJobNo() + ":" + r.getRowNo();
    }

    private String trailKey(String jobNo) {
        return "/heri/job/" + jobNo;
    }

    private void writeTrail(String jobNo, String action, String operator, String param) {
        this.sysOperLogMapper.insert(newTrail(jobNo, action, operator, param));
    }

    private TSysOperLog newTrail(String jobNo, String action, String operator, String param) {
        TSysOperLog log = new TSysOperLog();
        log.setTitle("提单签发");
        log.setMethod(action);
        log.setOperName(operator);
        log.setOperUrl(trailKey(jobNo));
        log.setOperParam(param);
        log.setOperTime(new Date());
        return log;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(x);
        }
        return sb.toString();
    }

    /** 单行核签结果 */
    private enum EffectOutcome {
        SIGNED, REJECTED
    }
}
