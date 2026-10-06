package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.THeriSetRow;
import com.fc.v2.service.ITHeriSetRowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 单证对齐条目 Controller
 *
 * @author fuce
 * @date 2026-09-12
 */
@Api(value = "单证对齐条目")
@Controller
@RequestMapping("/HeriSetRowController")
public class HeriSetRowController extends BaseController {

    private final String prefix = "admin/heriSetRow";

    @Autowired
    private ITHeriSetRowService heriSetRowService;

    @ApiOperation(value = "分页跳转", notes = "分页跳转")
    @GetMapping("/view")
    @RequiresPermissions("heri:heriSetRow:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "单证对齐条目集合查询", action = "list")
    @ApiOperation(value = "分页查询", notes = "分页查询")
    @GetMapping("/list")
    @RequiresPermissions("heri:heriSetRow:list")
    @ResponseBody
    public ResultTable list(THeriSetRow record) {
        QueryWrapper<THeriSetRow> queryWrapper = new QueryWrapper<THeriSetRow>();
        startPage();
        com.github.pagehelper.PageInfo<THeriSetRow> page =
                new com.github.pagehelper.PageInfo<THeriSetRow>(heriSetRowService.selectTHeriSetRowList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "单证对齐条目新增", action = "add")
    @ApiOperation(value = "新增", notes = "新增")
    @PostMapping("/add")
    @RequiresPermissions("heri:heriSetRow:add")
    @ResponseBody
    public AjaxResult add(THeriSetRow record) {
        return toAjax(heriSetRowService.insertTHeriSetRow(record));
    }

    @Log(title = "单证对齐条目修改", action = "edit")
    @ApiOperation(value = "修改保存", notes = "修改保存")
    @PostMapping("/edit")
    @RequiresPermissions("heri:heriSetRow:edit")
    @ResponseBody
    public AjaxResult editSave(THeriSetRow record) {
        return toAjax(heriSetRowService.updateTHeriSetRow(record));
    }

    @Log(title = "单证对齐条目删除", action = "remove")
    @ApiOperation(value = "删除", notes = "删除")
    @DeleteMapping("/remove")
    @RequiresPermissions("heri:heriSetRow:remove")
    @ResponseBody
    public AjaxResult remove(String ids) {
        return toAjax(heriSetRowService.deleteTHeriSetRowByIds(ids));
    }
}
