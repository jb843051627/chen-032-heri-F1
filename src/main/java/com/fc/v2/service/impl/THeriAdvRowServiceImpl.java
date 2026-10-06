package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THeriAdvRowMapper;
import com.fc.v2.model.auto.THeriAdvRow;
import com.fc.v2.service.ITHeriAdvRowService;

/**
 * 改单处置条目 Service业务层处理（state-machine 形状：单据流转）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class THeriAdvRowServiceImpl implements ITHeriAdvRowService {

    private static final int MAX_STAGE = 3;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private THeriAdvRowMapper heriAdvRowMapper;

    @Override
    public THeriAdvRow selectTHeriAdvRowById(Long id) {
        return this.heriAdvRowMapper.selectById(id);
    }

    @Override
    public List<THeriAdvRow> selectTHeriAdvRowList(QueryWrapper<THeriAdvRow> queryWrapper) {
        return this.heriAdvRowMapper.selectList(queryWrapper);
    }

    @Override
    public THeriAdvRow advance(Long id, String remark) {
        THeriAdvRow r = this.heriAdvRowMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        r.setStage(Math.min(st + 2, MAX_STAGE));
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.heriAdvRowMapper.updateById(r);
        return r;
    }

    @Override
    public THeriAdvRow rollback(Long id, String remark) {
        THeriAdvRow r = this.heriAdvRowMapper.selectById(id);
        if (r == null) {
            return null;
        }
        r.setStage(0);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.heriAdvRowMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        THeriAdvRow r = this.heriAdvRowMapper.selectById(id);
        if (r == null) {
            return false;
        }
        r.setContent(remark);
        return this.heriAdvRowMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        THeriAdvRow r = this.heriAdvRowMapper.selectById(id);
        if (r == null) {
            return false;
        }
        return this.heriAdvRowMapper.deleteById(id) > 0;
    }

}
