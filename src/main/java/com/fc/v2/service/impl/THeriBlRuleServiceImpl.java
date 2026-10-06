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
 * 提单签发判定规则 Service业务层处理（rule-eval 形状：规则求值）
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

    /**
     * at 时刻可用规则：未删除 + 启用中（撤下的不算）+ 生效窗口含 at（过期/未生效的不算），
     * 按优先级降序、ruleCode 降序。老数据窗口列为空按无界处理。
     */
    @Override
    public List<THeriBlRule> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<THeriBlRule>();
        }
        String now = ts(at);
        List<THeriBlRule> list = this.heriBlRuleMapper.selectList(new QueryWrapper<THeriBlRule>()
                .eq("del_flag", 0)
                .eq("status", 0)
                .and(w -> w.isNull("eff_start").or().le("eff_start", now))
                .and(w -> w.isNull("eff_end").or().gt("eff_end", now)));
        list.sort(PRIORITY_THEN_CODE_DESC);
        return list;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0) {
            return 0;
        }
        // 只认 at 时刻生效中的版本：撤下/过期/未生效的一律判 0
        List<THeriBlRule> avail = listAvailable(at);
        for (THeriBlRule r : avail) {
            if (ruleCode.equals(r.getRuleCode())) {
                return levelOf(r, input);
            }
        }
        return 0;
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0) {
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
        if (r == null) {
            return false;
        }
        // 口径与 listAvailable 严格一致：未删除 + 启用中 + 生效窗口含 at；列空视同不在册
        if (r.getDelFlag() == null || r.getDelFlag().intValue() != 0) {
            return false;
        }
        if (r.getStatus() == null || r.getStatus().intValue() != 0) {
            return false;
        }
        String now = ts(at);
        if (r.getEffStart() != null && ts(r.getEffStart()).compareTo(now) > 0) {
            return false;
        }
        if (r.getEffEnd() != null && ts(r.getEffEnd()).compareTo(now) <= 0) {
            return false;
        }
        return true;
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        return listAvailable(at).size();
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

    /** 分档：档上限为含界（"500 件以内"含 500），超三档上限归第 4 档 */
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
