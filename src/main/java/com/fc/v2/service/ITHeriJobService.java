package com.fc.v2.service;

import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.THeriJob;
import com.fc.v2.model.auto.THeriJobRow;
import com.fc.v2.model.auto.TSysOperLog;

/**
 * 提单签发作业 Service 接口（job-lifecycle 形状：航次批量签发/退回，跨模块协调）
 *
 * <p>规矩（六方口径）：</p>
 * <ol>
 *   <li>一个航次给一个作业号，号由册上发；头表一个号只占一行，行次由册上排（1..N），交单方不填。</li>
 *   <li>同号重复递交按幂等，第二遍只当照面；号换、内容也换才算新一航次。</li>
 *   <li>签发前先核判据：判据撤了、过期了、版本没写的，该行不签；件数/提单号不齐的行留在册上写明原因，其余照签。</li>
 *   <li>签一行落三处：放货凭据、费用流水、经手留痕；三处对得上才算签成。某行某处没落，
 *       只把该行记成它自己没落成，不推翻整航次。</li>
 *   <li>退回不划掉凭据，按落过的地方一处一处补冲账流水；同一条重试只补缺的，冲账不写重。</li>
 *   <li>两个航次同时被核，各自锁自己那一段，锁不住就等，不许互相踩。</li>
 * </ol>
 *
 * @author fuce
 * @date 2026-10-06
 */
public interface ITHeriJobService {

    /** 按主键回查作业头 */
    THeriJob selectTHeriJobById(Long id);

    /** 按作业号回查作业头（一个号在册上只有一行） */
    THeriJob selectByJobNo(String jobNo);

    /** 回查作业行（按行次升序） */
    List<THeriJobRow> listRows(String jobNo);

    /**
     * 开号：册上发一个新作业号，头表落一行（row_no=0），明细行次由册上按递交顺序排 1..N。
     * 提单号/件数等不齐的行同样留在册上，标成待签失败并写明原因，不影响其余行。
     *
     * @param shipName 船名航次
     * @param rows     本航次一叠提单行（rowNo 由册上排，交单方填了也不认）
     * @param operator 开号经手人
     * @return 册上发的作业号
     */
    String openJob(String shipName, List<THeriJobRow> rows, String operator);

    /**
     * 同号再次递交：已在册上就照面，不另落头行、不重排行次，原样返回已发的号。
     */
    String resubmit(String jobNo, List<THeriJobRow> rows, String operator);

    /**
     * 核签整个航次（at 时刻为判据生效口径）。逐行先核判据再落三处：
     * 放货凭据(t_heri_del_row)、费用流水(t_heri_adv_row)、经手留痕(t_sys_oper_log)。
     * 单行/单处失败只记该行，不回滚已签成的行。
     * 终态：全部签成=已生效(3)，有没签成的=部分生效(4)。
     *
     * @return 作业头（totalRows/doneRows/failRows 可倒查）
     */
    THeriJob effectJob(String jobNo, Date at, String operator);

    /**
     * 退回：只对签成的行，按落过的地方一处一处补冲账流水（不划凭据）。
     * 重试只补此前没落的冲账流水，已冲过的不写重；compensateCnt 记退回重试遍数。
     *
     * @return 本次补完冲账、达到已退回的行数
     */
    int rollbackJob(String jobNo, String operator);

    /**
     * 倒查这个航次走过的每一段：开号/核签/退回，谁动的、什么时候、签成几条没签成几条，
     * 按时刻升序。
     */
    List<TSysOperLog> listTrail(String jobNo);
}
