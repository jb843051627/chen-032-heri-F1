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
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private THeriBlRowMapper heriBlRowMapper;

    @Override
    public THeriBlRow selectTHeriBlRowById(Long id) {
        return this.heriBlRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<THeriBlRow> rows) {
        String no = rows.get(0).getBatchNo();
        java.util.List<THeriBlRow> errors = new java.util.ArrayList<THeriBlRow>();
        int seq = 0;
        for (THeriBlRow r : rows) {
            if (r.getItemCode() == null || r.getItemCode().trim().isEmpty()
                    || r.getQty() == null
                    || r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                seq++;
                r.setRowNo(Integer.valueOf(seq));
                r.setBatchNo(no);
                r.setStatus(STATUS_FAIL);
                this.heriBlRowMapper.insert(r);
                errors.add(r);
            }
        }
        if (!errors.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (THeriBlRow r : rows) {
            r.setBatchNo(no);
            r.setStatus(STATUS_OK);
            this.heriBlRowMapper.insert(r);
            ok++;
        }
        return ok;
    }

    @Override
    public List<THeriBlRow> listErrors(String batchNo) {
        return this.heriBlRowMapper.selectList(new QueryWrapper<THeriBlRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL));
    }
}
