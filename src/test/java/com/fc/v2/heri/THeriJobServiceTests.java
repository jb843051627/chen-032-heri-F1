package com.fc.v2.heri;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.THeriAdvRow;
import com.fc.v2.model.auto.THeriBlRule;
import com.fc.v2.model.auto.THeriDelRow;
import com.fc.v2.model.auto.THeriJob;
import com.fc.v2.model.auto.THeriJobRow;
import com.fc.v2.model.auto.TSysOperLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航次批量签发规矩集成测试（H2 + 真实 mapper）。
 *
 * @author fuce
 * @date 2026-10-06
 */
class THeriJobServiceTests extends HeriJobITSupport {

    private static final String SHIP = "远春河/2609E";
    private static final String OP = "agent_wang";
    private static final String RULE_CODE = "NR01";

    private Date at;

    @BeforeEach
    void init() throws Exception {
        setUp();
        this.at = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-10-06 09:00:00");
        seedRule(RULE_CODE, 0, "2020-01-01 00:00:00", "2099-12-31 23:59:59", 10);
    }

    // ---------------- 船代：给号、行次册上排 ----------------

    @Test
    @DisplayName("开号：头发一行、号唯一、行次册上按递交顺序排 1..N，交单方填的行次不认")
    void openJob_assignsNoAndServerSideRowNo() {
        List<THeriJobRow> rows = rows(
                row(null, "BL1", 10, "100", RULE_CODE),   // 交单方行次留空
                row(99, "BL2", 20, "200", RULE_CODE));   // 交单方乱填 99，册上不认
        String jobNo = jobService.openJob(SHIP, rows, OP);

        assertNotNull(jobNo);
        assertTrue(jobNo.startsWith("V"), "号应以 V 打头: " + jobNo);

        THeriJob head = jobService.selectByJobNo(jobNo);
        assertEquals(1, headCount(jobNo), "一个号在册上只占一行");
        assertEquals(0, head.getRowNo(), "头行行次固定 0");
        assertEquals(2, head.getTotalRows());

        List<THeriJobRow> saved = jobService.listRows(jobNo);
        assertEquals(2, saved.size());
        assertEquals(Integer.valueOf(1), saved.get(0).getRowNo());
        assertEquals(Integer.valueOf(2), saved.get(1).getRowNo());
        assertEquals("BL1", saved.get(0).getBlNo());
        assertEquals("BL2", saved.get(1).getBlNo());
    }

    @Test
    @DisplayName("开空航次不允许")
    void openJob_emptyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> jobService.openJob(SHIP, new ArrayList<THeriJobRow>(), OP));
        assertThrows(IllegalArgumentException.class,
                () -> jobService.openJob(SHIP, null, OP));
    }

    // ---------------- 港务：同号幂等、号换才算新 ----------------

    @Test
    @DisplayName("同号再交一遍：照面，回同一个号，头不重落、行不重排")
    void resubmit_sameNo_isIdempotent() {
        String jobNo = jobService.openJob(SHIP, rows(row(null, "BL1", 10, "100", RULE_CODE)), OP);

        String again = jobService.resubmit(jobNo,
                rows(row(null, "BL9", 99, "999", RULE_CODE)), OP);

        assertEquals(jobNo, again);
        assertEquals(1, headCount(jobNo), "头仍只有一行");
        List<THeriJobRow> saved = jobService.listRows(jobNo);
        assertEquals(1, saved.size(), "第二遍的内容不落册");
        assertEquals("BL1", saved.get(0).getBlNo(), "只当照面，内容不覆盖");
        assertEquals(Integer.valueOf(1), saved.get(0).getRowNo());
    }

    @Test
    @DisplayName("号没在册上：resubmit 按新号落册（号换、内容换才算新的）")
    void resubmit_newNo_registers() {
        String jobNo = jobService.resubmit("V202610060001",
                rows(row(null, "BL1", 10, "100", RULE_CODE)), OP);
        assertEquals("V202610060001", jobNo);
        assertNotNull(jobService.selectByJobNo(jobNo));
    }

    // ---------------- 船代+理货：不齐的行留册写明，不连累其余 ----------------

    @Test
    @DisplayName("提单号空、件数空的行留在册上写明原因；其余齐的行待签")
    void incompleteRows_stayOnBook() {
        String jobNo = jobService.openJob(SHIP, rows(
                row(null, "BL1", 10, "100", RULE_CODE),
                row(null, null, 10, "100", RULE_CODE),       // 提单号空
                row(null, "BL3", null, "300", RULE_CODE),    // 件数空
                row(null, "BL4", 0, "400", RULE_CODE)), OP); // 件数不是正数

        List<THeriJobRow> saved = jobService.listRows(jobNo);
        assertEquals(0, saved.get(0).getStatus(), "齐的行待签");
        assertNull(saved.get(0).getFailReason());
        assertEquals(2, saved.get(1).getStatus());
        assertTrue(saved.get(1).getFailReason().contains("提单号"));
        assertEquals(2, saved.get(2).getStatus());
        assertTrue(saved.get(2).getFailReason().contains("件数"));
        assertEquals(2, saved.get(3).getStatus());
        assertTrue(saved.get(3).getFailReason().contains("件数"));
        // 不齐的行开号时就计入 failRows，不进待签
        assertEquals(Integer.valueOf(3), jobService.selectByJobNo(jobNo).getFailRows());
    }

    // ---------------- 船代：核之前先看判据还在不在 ----------------

    @Test
    @DisplayName("判据在有效期内：签成")
    void effect_ruleEffective_signed() {
        String jobNo = jobService.openJob(SHIP, rows(row(null, "BL1", 10, "100", RULE_CODE)), OP);
        THeriJob head = jobService.effectJob(jobNo, at, OP);

        assertEquals(3, head.getStatus(), "全部签成=已生效");
        assertEquals(1, head.getDoneRows());
        assertEquals(0, head.getFailRows());
        assertEquals(1, jobService.listRows(jobNo).get(0).getStatus());
    }

    @Test
    @DisplayName("判据撤下了：不签，写明原因")
    void effect_ruleWithdrawn_rejected() {
        seedRule("NR99", 1, "2020-01-01 00:00:00", "2099-12-31 23:59:59", 10);
        String jobNo = jobService.openJob(SHIP, rows(row(null, "BL1", 10, "100", "NR99")), OP);
        THeriJob head = jobService.effectJob(jobNo, at, OP);

        assertEquals(4, head.getStatus(), "有没签成=部分生效");
        THeriJobRow r = jobService.listRows(jobNo).get(0);
        assertEquals(2, r.getStatus());
        assertTrue(r.getFailReason().contains("撤") || r.getFailReason().contains("过期"));
        assertEquals(0, r.getWritebackFlag(), "没签成就一处都不落");
    }

    @Test
    @DisplayName("判据过期了（截止不含，到点即过期）：不签")
    void effect_ruleExpired_rejected() throws Exception {
        seedRule("NR88", 0, "2020-01-01 00:00:00", "2026-10-06 09:00:00", 10);
        String jobNo = jobService.openJob(SHIP, rows(row(null, "BL1", 10, "100", "NR88")), OP);
        jobService.effectJob(jobNo, at, OP); // at 恰好等于截止时刻
        THeriJobRow r = jobService.listRows(jobNo).get(0);
        assertEquals(2, r.getStatus());
        assertTrue(r.getFailReason().contains("过期"));
    }

    @Test
    @DisplayName("判据版本没写：不签（老数据版本格空着）")
    void effect_versionMissing_rejected() {
        String jobNo = jobService.openJob(SHIP, rows(row(null, "BL1", 10, "100", null)), OP);
        jobService.effectJob(jobNo, at, OP);
        THeriJobRow r = jobService.listRows(jobNo).get(0);
        assertEquals(2, r.getStatus());
        assertTrue(r.getFailReason().contains("版本"));
    }

    // ---------------- 理货：三处写回对得上、部分失败不推翻 ----------------

    @Test
    @DisplayName("签成一行：放货凭据、费用流水、经手留痕三处都落，且对得上同一个号/行")
    void effect_threeWritebacks_allLanded() {
        String jobNo = jobService.openJob(SHIP, rows(row(null, "BL1", 10, "100", RULE_CODE)), OP);
        jobService.effectJob(jobNo, at, OP);

        List<THeriDelRow> dels = delRowMapper.selectList(new QueryWrapper<THeriDelRow>()
                .likeRight("item_no", "D:" + jobNo + ":"));
        List<THeriAdvRow> advs = advRowMapper.selectList(new QueryWrapper<THeriAdvRow>()
                .likeRight("item_no", "A:" + jobNo + ":"));
        List<TSysOperLog> trail = jobService.listTrail(jobNo);

        assertEquals(1, dels.size(), "放货凭据一条");
        assertEquals(1, advs.size(), "费用流水一条(签发入账)");
        assertEquals(Integer.valueOf(1), dels.get(0).getStatus());
        assertEquals(Integer.valueOf(1), advs.get(0).getStatus());
        assertEquals(0, new BigDecimal("100").compareTo(advs.get(0).getAmount()));
        // 留痕：开号 + 核签汇总 + 签发行，至少三笔且都记得是谁
        assertTrue(trail.size() >= 3, "留痕笔数=" + trail.size());
        for (TSysOperLog l : trail) {
            assertEquals(OP, l.getOperName());
            assertNotNull(l.getOperTime());
        }
        assertEquals(7, jobService.listRows(jobNo).get(0).getWritebackFlag(), "三处掩码齐=7");
    }

    @Test
    @DisplayName("同航次重复核签：已签成的行不重落，三处不写重")
    void effect_twice_noDuplicateWritebacks() {
        String jobNo = jobService.openJob(SHIP, rows(
                row(null, "BL1", 10, "100", RULE_CODE),
                row(null, "BL2", 20, "200", RULE_CODE)), OP);
        jobService.effectJob(jobNo, at, OP);
        THeriJob second = jobService.effectJob(jobNo, at, OP);

        assertEquals(3, second.getStatus());
        assertEquals(2, delRowMapper.selectCount(new QueryWrapper<THeriDelRow>()
                .likeRight("item_no", "D:" + jobNo + ":")));
        assertEquals(2, advRowMapper.selectCount(new QueryWrapper<THeriAdvRow>()
                .likeRight("item_no", "A:" + jobNo + ":")));
    }

    @Test
    @DisplayName("某行判据没签成：只记它自己，同行次其余照签，不推翻整航次")
    void effect_partialKeepsOthers() {
        String jobNo = jobService.openJob(SHIP, rows(
                row(null, "BL1", 10, "100", RULE_CODE),
                row(null, "BL2", 20, "200", "NR99"),   // 判据撤下
                row(null, "BL3", 30, "300", RULE_CODE)), OP);
        THeriJob head = jobService.effectJob(jobNo, at, OP);

        assertEquals(4, head.getStatus(), "部分生效");
        assertEquals(2, head.getDoneRows());
        assertEquals(1, head.getFailRows());
        List<THeriJobRow> rs = jobService.listRows(jobNo);
        assertEquals(1, rs.get(0).getStatus());
        assertEquals(2, rs.get(1).getStatus());
        assertEquals(1, rs.get(2).getStatus());
    }

    // ---------------- 理货：退回补冲账流水，重试不写重 ----------------

    @Test
    @DisplayName("退回：不划凭据，补冲账凭据条+负数冲账流水+退回留痕；行转已退回")
    void rollback_writesReversals() {
        String jobNo = jobService.openJob(SHIP, rows(
                row(null, "BL1", 10, "100", RULE_CODE),
                row(null, "BL2", 20, "200", RULE_CODE)), OP);
        jobService.effectJob(jobNo, at, OP);

        int back = jobService.rollbackJob(jobNo, OP);
        assertEquals(2, back);
        assertEquals(6, jobService.selectByJobNo(jobNo).getStatus(), "全冲平=已回滚");

        // 原凭据没划，另补了冲账凭据条（负向）
        List<THeriDelRow> delRev = delRowMapper.selectList(new QueryWrapper<THeriDelRow>()
                .likeRight("item_no", "DR:" + jobNo + ":"));
        List<THeriAdvRow> advRev = advRowMapper.selectList(new QueryWrapper<THeriAdvRow>()
                .likeRight("item_no", "AR:" + jobNo + ":"));
        assertEquals(2, delRev.size());
        assertEquals(2, advRev.size());
        assertTrue(delRev.get(0).getAmount().signum() < 0, "冲账金额为负");
        assertEquals(Integer.valueOf(2), delRev.get(0).getStatus());
        assertEquals(Integer.valueOf(2), advRev.get(0).getStatus());
        // 原凭据、原流水仍在
        assertEquals(2, delRowMapper.selectCount(new QueryWrapper<THeriDelRow>()
                .likeRight("item_no", "D:" + jobNo + ":")));
        assertEquals(2, advRowMapper.selectCount(new QueryWrapper<THeriAdvRow>()
                .likeRight("item_no", "A:" + jobNo + ":")));
        for (THeriJobRow r : jobService.listRows(jobNo)) {
            assertEquals(3, r.getStatus());
            assertEquals(1, r.getCompensated());
            assertEquals(7, r.getCompensateFlag(), "落过的三处全冲回");
        }
    }

    @Test
    @DisplayName("退回重试：冲过的不写重，compensateCnt 只在真补了流水时累加")
    void rollback_retry_doesNotDuplicate() {
        String jobNo = jobService.openJob(SHIP, rows(row(null, "BL1", 10, "100", RULE_CODE)), OP);
        jobService.effectJob(jobNo, at, OP);

        assertEquals(1, jobService.rollbackJob(jobNo, OP));
        int second = jobService.rollbackJob(jobNo, OP);
        assertEquals(0, second, "已冲平，第二遍一道都不补");

        assertEquals(1, delRowMapper.selectCount(new QueryWrapper<THeriDelRow>()
                .likeRight("item_no", "DR:" + jobNo + ":")));
        assertEquals(1, advRowMapper.selectCount(new QueryWrapper<THeriAdvRow>()
                .likeRight("item_no", "AR:" + jobNo + ":")));
        THeriJobRow r = jobService.listRows(jobNo).get(0);
        assertEquals(Integer.valueOf(1), r.getCompensateCnt(), "只在第一次真冲时记一遍");
    }

    @Test
    @DisplayName("未签成的航次不能退回；退回后不能再核签")
    void lifecycle_terminalGuards() {
        String no1 = jobService.openJob(SHIP, rows(row(null, "BL1", 10, "100", RULE_CODE)), OP);
        assertThrows(IllegalStateException.class, () -> jobService.rollbackJob(no1, OP));

        String no2 = jobService.openJob(SHIP, rows(row(null, "BL2", 10, "100", RULE_CODE)), OP);
        jobService.effectJob(no2, at, OP);
        jobService.rollbackJob(no2, OP);
        assertThrows(IllegalStateException.class, () -> jobService.effectJob(no2, at, OP));
    }

    // ---------------- 港务：倒查 ----------------

    @Test
    @DisplayName("倒查：走过哪几段、谁动的、什么时候，按时刻升序都查得出")
    void trail_recordsEveryStage() {
        String jobNo = jobService.openJob(SHIP, rows(row(null, "BL1", 10, "100", RULE_CODE)), "agent_wang");
        jobService.effectJob(jobNo, at, "clerk_li");
        jobService.rollbackJob(jobNo, "tally_zhao");

        List<TSysOperLog> trail = jobService.listTrail(jobNo);
        List<String> actions = new ArrayList<String>();
        Set<String> operators = new HashSet<String>();
        Date prev = null;
        for (TSysOperLog l : trail) {
            actions.add(l.getMethod());
            operators.add(l.getOperName());
            if (prev != null) {
                assertFalse(l.getOperTime().before(prev), "留痕按时刻升序");
            }
            prev = l.getOperTime();
        }
        assertTrue(actions.contains("开号"));
        assertTrue(actions.contains("核签"));
        assertTrue(actions.contains("退回"));
        assertTrue(operators.contains("agent_wang"));
        assertTrue(operators.contains("clerk_li"));
        assertTrue(operators.contains("tally_zhao"));

        THeriJob head = jobService.selectByJobNo(jobNo);
        assertEquals(1, head.getTotalRows());
        assertEquals(1, head.getDoneRows(), "签成 1 条");
        assertEquals(0, head.getFailRows(), "没签成 0 条");
        assertEquals(3, jobService.listRows(jobNo).get(0).getStatus(), "退回 1 条");
    }

    // ---------------- 港务：两册同核各锁各段，不许互相踩 ----------------

    @Test
    @DisplayName("两个航次同时被核：各锁各的段，都能签成且互不串账")
    void concurrentDifferentJobs_doNotBlockEachOther() throws Exception {
        String j1 = jobService.openJob(SHIP, rows(row(null, "BL-A", 10, "100", RULE_CODE)), OP);
        String j2 = jobService.openJob(SHIP, rows(row(null, "BL-B", 20, "200", RULE_CODE)), OP);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger ok = new AtomicInteger();
        for (final String jno : new String[]{j1, j2}) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (jobService.effectJob(jno, at, OP).getDoneRows() == 1) {
                        ok.incrementAndGet();
                    }
                } catch (Exception e) {
                    // 记失败
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(2, ok.get(), "两个航次各自签成，互不踩");

        assertEquals(1, delRowMapper.selectCount(new QueryWrapper<THeriDelRow>()
                .likeRight("item_no", "D:" + j1 + ":")));
        assertEquals(1, delRowMapper.selectCount(new QueryWrapper<THeriDelRow>()
                .likeRight("item_no", "D:" + j2 + ":")));
    }

    @Test
    @DisplayName("同一航次重复并发递交：只有一册先落，另一册照面，头行只有一条、行不重")
    void concurrentSameJob_noDuplicateHead() throws Exception {
        final String jobNo = "V20261006CONC1";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(8);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    jobService.resubmit(jobNo, rows(row(null, "BL1", 10, "100", RULE_CODE)), OP);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    allDone.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(allDone.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(errors.isEmpty(), "并发同号不应抛唯一约束异常: " + errors);
        assertEquals(1, headCount(jobNo));
        assertEquals(1, jobService.listRows(jobNo).size());
    }

    // ---------------- 判据服务口径旁路测试 ----------------

    @Test
    @DisplayName("判据可用性口径：撤下/过期/未到生效点不可用，有效期内可用")
    void ruleUsable_windowAndStatus() throws Exception {
        Date inside = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-10-06 09:00:00");
        THeriBlRule active = blRuleMapper.selectOne(new QueryWrapper<THeriBlRule>()
                .eq("rule_code", RULE_CODE));
        assertTrue(blRuleService.usable(active.getId(), inside));

        Date atStart = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2020-01-01 00:00:00");
        assertTrue(blRuleService.usable(active.getId(), atStart), "起始时刻含");
        Date beforeEnd = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2099-12-31 23:59:58");
        assertTrue(blRuleService.usable(active.getId(), beforeEnd), "截止前一秒含");
        Date atEnd = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2099-12-31 23:59:59");
        assertFalse(blRuleService.usable(active.getId(), atEnd), "截止时刻不含，正点即过期");
    }

    // ---------------- helpers ----------------

    private int headCount(String jobNo) {
        return jobMapper.selectCount(new QueryWrapper<THeriJob>()
                .eq("job_no", jobNo).eq("del_flag", 0));
    }

    private static THeriJobRow row(Integer rowNo, String blNo, Integer qty, String amount, String version) {
        THeriJobRow r = new THeriJobRow();
        r.setRowNo(rowNo);
        r.setBlNo(blNo);
        r.setJobQty(qty);
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setVersion(version);
        return r;
    }

    private static List<THeriJobRow> rows(THeriJobRow... rs) {
        List<THeriJobRow> list = new ArrayList<THeriJobRow>();
        for (THeriJobRow r : rs) {
            list.add(r);
        }
        return list;
    }

    private void seedRule(String code, int status, String start, String end, int priority) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            THeriBlRule r = new THeriBlRule();
            r.setRuleCode(code);
            r.setRuleName(code + "-测试判据");
            r.setTh1Max(new BigDecimal("500"));
            r.setTh2Max(new BigDecimal("2000"));
            r.setTh3Max(new BigDecimal("5000"));
            r.setEffStart(f.parse(start));
            r.setEffEnd(f.parse(end));
            r.setPriority(priority);
            r.setStatus(status);
            r.setDelFlag(0);
            blRuleMapper.insert(r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
