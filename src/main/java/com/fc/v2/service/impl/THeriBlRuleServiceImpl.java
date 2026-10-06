package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THeriBlRuleMapper;
import com.fc.v2.model.auto.THeriBlRule;
import com.fc.v2.service.ITHeriBlRuleService;

/**
 * 提单签发判定规则 Service业务层处理（rule-eval 形状）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class THeriBlRuleServiceImpl implements ITHeriBlRuleService {

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
    private static final Comparator<THeriBlRule> PRIORITY_THEN_CODE_DESC = new Comparator<THeriBlRule>() {
        @Override
        public int compare(THeriBlRule a, THeriBlRule b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return cb.compareTo(ca);
        }
    };

    @javax.annotation.Resource
    private THeriBlRuleMapper heriBlRuleMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）+ 字符串绑定：
     * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"切换当日"这类用例上随机错档（实测踩到）。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public THeriBlRule selectTHeriBlRuleById(Long id) {
        return this.heriBlRuleMapper.selectById(id);
    }

    @Override
    public List<THeriBlRule> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<THeriBlRule>();
        }
        // 时段判断放在 Java 侧走墙钟字符串（见 ts 说明），不把 Date 绑进 SQL
        List<THeriBlRule> all = this.heriBlRuleMapper.selectList(new QueryWrapper<THeriBlRule>()
                .eq("del_flag", 0).eq("status", 0));
        List<THeriBlRule> avail = new java.util.ArrayList<THeriBlRule>();
        for (THeriBlRule r : all) {
            if (inEffect(r, at)) {
                avail.add(r);
            }
        }
        java.util.Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public THeriBlRule findEffective(String ruleCode, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || at == null) {
            return null;
        }
        List<THeriBlRule> hit = this.heriBlRuleMapper.selectList(new QueryWrapper<THeriBlRule>()
                .eq("del_flag", 0).eq("status", 0).eq("rule_code", ruleCode));
        THeriBlRule best = null;
        for (THeriBlRule r : hit) {
            if (!inEffect(r, at)) {
                continue;
            }
            if (best == null || PRIORITY_THEN_CODE_DESC.compare(r, best) < 0) {
                best = r;
            }
        }
        return best;
    }

    /** 生效窗口：[eff_start, eff_end)；起/止任一没写就当那一侧不拦 */
    private boolean inEffect(THeriBlRule r, Date at) {
        String now = ts(at);
        if (r.getEffStart() != null && now.compareTo(ts(r.getEffStart())) < 0) {
            return false;
        }
        if (r.getEffEnd() != null && now.compareTo(ts(r.getEffEnd())) >= 0) {
            // 截止时刻不含：到点即过期
            return false;
        }
        return true;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        THeriBlRule rule = findRule(ruleCode, at);
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(new BigDecimal("100")) > 0) {
            return 0;
        }
        List<THeriBlRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        THeriBlRule r = this.heriBlRuleMapper.selectById(id);
        // 口径与 findEffective/listAvailable 一致：在册、未撤、在生效窗口内才算可用
        return r != null
                && (r.getDelFlag() == null || r.getDelFlag() == 0)
                && (r.getStatus() == null || r.getStatus() == 0)
                && inEffect(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        return listAvailable(at).size();
    }

    private THeriBlRule findRule(String ruleCode, Date at) {
        return findEffective(ruleCode, at);
    }

    /** 阈值解析：档案为主、明细覆盖；两者皆缺返回 null（调用方按不可用处理），精度统一 2 位 */
    private BigDecimal resolveLimit(THeriBlRule r, int tier) {
        if (tier == 1) {
            return r.getTh1Max();
        }
        if (tier == 2) {
            return r.getTh2Max();
        }
        return r.getTh3Max();
    }

    /** 档案级默认阈值（明细未维护时的回退来源） */
    private BigDecimal defaultThreshold(THeriBlRule r, int tier) {
        return r.getTh1Max();
    }

    /** 分档：等于上限取高一档 */
    private int levelOf(THeriBlRule rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
