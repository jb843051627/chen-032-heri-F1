package com.fc.v2.service;

import java.util.List;

import com.fc.v2.model.auto.THeriJob;
import com.fc.v2.model.auto.THeriJobFlow;
import com.fc.v2.model.auto.THeriJobRow;

/**
 * 提单签发作业 Service 接口（job-lifecycle 形状：批量作业提交/生效/回滚，无增删改查入口）
 *
 * @author fuce
 * @date 2026-10-03
 */
public interface ITHeriJobService {

    /** 按主键回查作业 */
    THeriJob selectTHeriJobById(Long id);

    /** 按作业号回查作业（号在册上只占一行：表头行） */
    THeriJob selectByJobNo(String jobNo);

    /**
     * 提交一批作业行：落作业主表一行 + 逐行明细；行次由系统顺排，递交方不填。
     * 同作业号重复提交幂等（第二遍只当照面，不再落行），返回同一作业号；
     * jobNo 为空时由系统发号（一航次一号）。
     */
    String submitJob(String jobNo, List<THeriJobRow> rows);

    /**
     * 推进到生效：逐行三段落账（放货凭据写回 + 费用流水 + 操作留痕）在同一行事务内，对不上整行回滚；
     * 部分行失败不回滚整批，失败行留册写明原因；已终态（已生效/已回滚）再调幂等照面返回 0。
     * 返回本次新签成行数。
     */
    int effectJob(String jobNo);

    /**
     * 回滚已生效作业：逐行补冲账流水（红字）把落过的地方一处一处冲回来；
     * 同一条已冲过的重试不重复写流水；终态（已回滚）不可再推进，再调幂等照面返回 0。
     * 返回本次新冲回行数。
     */
    int rollbackJob(String jobNo);

    /** 追溯：该作业全部行（按行次升序） */
    List<THeriJobRow> listJobRows(String jobNo);

    /** 追溯：该作业全部流水（签发费用/冲账，按主键升序，写没写重可查） */
    List<THeriJobFlow> listJobFlows(String jobNo);
}
