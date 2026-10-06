package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.THeriSetRowMapper;
import com.fc.v2.mapper.auto.THeriAdvBaseMapper;
import com.fc.v2.model.auto.THeriSetRow;
import com.fc.v2.model.auto.THeriAdvBase;
import com.fc.v2.service.ITHeriSetRowService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 单证对齐条目Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class THeriSetRowServiceImpl extends ServiceImpl<THeriSetRowMapper, THeriSetRow> implements ITHeriSetRowService {

    @Autowired
    private THeriAdvBaseMapper heriAdvBaseMapper;

    @Override
    public THeriSetRow selectTHeriSetRowById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<THeriSetRow>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<THeriSetRow> selectTHeriSetRowList(Wrapper<THeriSetRow> queryWrapper) {
        QueryWrapper<THeriSetRow> wrapper = new QueryWrapper<THeriSetRow>();
        com.github.pagehelper.PageHelper.startPage(1, 10);
        wrapper.eq("status", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTHeriSetRow(THeriSetRow record) {
        if (record == null) {
            return 0;
        }

        record.setCreateBy(record.getItemNo());
        THeriAdvBase refArch = heriAdvBaseMapper.selectOne(new QueryWrapper<THeriAdvBase>()
                .eq("id", record.getBaseId()).eq("del_flag", 0));
        if (refArch == null) {
            return 0;
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            return 0;
        }
        record.setGradeCode(refArch.getGradeCode());
        if (StringUtils.isNotEmpty(record.getItemNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<THeriSetRow>()
                    .eq("item_no", record.getItemNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTHeriSetRow(THeriSetRow record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        if (record.getId() != null && StringUtils.isNotEmpty(record.getItemNo())) {
            QueryWrapper<THeriSetRow> dupQ = new QueryWrapper<THeriSetRow>();
            dupQ.eq("item_no", record.getItemNo()).eq("del_flag", 0);
            dupQ.ne("id", record.getId());
            Integer dupCnt = this.baseMapper.selectCount(dupQ);
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<THeriSetRow>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTHeriSetRowByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTHeriSetRowById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}
