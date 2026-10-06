package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THeriBlRowMapper;
import com.fc.v2.model.auto.THeriBlRow;
import com.fc.v2.service.ITHeriBlRowService;

/**
 * 提单签发行 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class THeriBlRowServiceImpl implements ITHeriBlRowService {

    private static final int MAX_ROWS = 500;
    /** 行状态：0待签 1已签 2已作废 */
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private THeriBlRowMapper heriBlRowMapper;

    @Override
    public THeriBlRow selectTHeriBlRowById(Long id) {
        return this.heriBlRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<THeriBlRow> rows) {
        // 空批次返回 0
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // 行数超过单批上限返回 -1 且整批不入库
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        // 批次号以入参为准；入参空才回看首行自带的号；没号不入册
        String no = (batchNo == null || batchNo.trim().isEmpty()) ? rows.get(0).getBatchNo() : batchNo;
        if (no == null || no.trim().isEmpty()) {
            return 0;
        }
        // 同一批次重复提交不得重复入库：第二遍只当照面
        Integer exists = this.heriBlRowMapper.selectCount(new QueryWrapper<THeriBlRow>()
                .eq("batch_no", no).eq("del_flag", 0));
        if (exists != null && exists.intValue() > 0) {
            return 0;
        }
        int ok = 0;
        int seq = 0;
        for (THeriBlRow r : rows) {
            seq++;
            // 行次系统顺排（保留原始行号），递交方不填
            r.setRowNo(Integer.valueOf(seq));
            r.setBatchNo(no);
            r.setDelFlag(Integer.valueOf(0));
            if (r.getItemCode() == null || r.getItemCode().trim().isEmpty()
                    || r.getQty() == null
                    || r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                // 非法行记入失败明细留册（提单代号空 / 件数空或不为正），不挡合法行入库
                r.setStatus(STATUS_FAIL);
                this.heriBlRowMapper.insert(r);
                continue;
            }
            r.setStatus(STATUS_WAIT);
            this.heriBlRowMapper.insert(r);
            ok++;
        }
        return ok;
    }

    @Override
    public List<THeriBlRow> listErrors(String batchNo) {
        return this.heriBlRowMapper.selectList(new QueryWrapper<THeriBlRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL).eq("del_flag", 0)
                .orderByAsc("row_no"));
    }
}
