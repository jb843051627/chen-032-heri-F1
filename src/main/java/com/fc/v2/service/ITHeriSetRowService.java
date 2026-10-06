package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.THeriSetRow;

import java.util.List;

/**
 * 单证对齐条目 Service接口
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface ITHeriSetRowService {

    /** 按主键查询 */
    THeriSetRow selectTHeriSetRowById(Long id);

    /** 按条件查询列表（分页由调用方统一处理） */
    List<THeriSetRow> selectTHeriSetRowList(Wrapper<THeriSetRow> queryWrapper);

    /** 新增 */
    int insertTHeriSetRow(THeriSetRow record);

    /** 修改 */
    int updateTHeriSetRow(THeriSetRow record);

    /** 批量删除 */
    int deleteTHeriSetRowByIds(String ids);

    /** 按主键删除 */
    int deleteTHeriSetRowById(Long id);
}
