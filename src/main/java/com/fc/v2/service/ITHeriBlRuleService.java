package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.THeriBlRule;

/**
 * 提单签发判定规则 Service接口（rule-eval 形状：规则求值，无增删改）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITHeriBlRuleService {

    /** 按主键查询规则 */
    THeriBlRule selectTHeriBlRuleById(Long id);

    /** 单规则求值：按 ruleCode 在 at 时刻生效的版本判档（1..4）。
     * 无生效版本 / 输入越界 / 参数缺失一律返回 0。
     */
    int evaluate(String ruleCode, BigDecimal input, Date at);

    /**
     * 按判据版本(rule_code)定位 at 时刻**仍在生效**的判据：在册(del_flag=0)、
     * 未撤下(status=0)、已到生效起始时刻且未过截止时刻(截止不含)。
     * 撤了 / 过期 / 查无一律返回 null——核签前先看判据还在不在的统一口径。
     * 多个版本同时命中取优先级最高（并列取 ruleCode 最大者）。
     */
    THeriBlRule findEffective(String ruleCode, Date at);

    /** 多规则求值：at 时刻可用规则中优先级最高者的档位；无可用规则返回 0 */
    int evaluateTop(BigDecimal input, Date at);

    /** at 时刻可用规则（优先级降序、ruleCode 降序） */
    List<THeriBlRule> listAvailable(Date at);

    /** 单条规则在 at 时刻是否可用（批量导入/报表等旁路复用，口径须与定位/列表一致） */
    boolean usable(Long id, Date at);

    /** at 时刻可用规则条数（列表页角标 / 看板） */
    int countAvailable(Date at);
}
