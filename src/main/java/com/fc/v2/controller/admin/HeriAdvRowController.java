package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.THeriAdvRow;
import com.fc.v2.service.ITHeriAdvRowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 改单处置条目 Controller（state-machine 形状：流转入口）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Api(value = "改单处置条目")
@Controller
@RequestMapping("/heriAdvRow")
public class HeriAdvRowController extends BaseController {

    private final String prefix = "admin/heriAdvRow";

    @Autowired
    private ITHeriAdvRowService heriAdvRowService;

    @ApiOperation(value = "流转台账跳转", notes = "流转台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("heriAdvRow:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "改单处置条目流转台账", action = "list")
    @ApiOperation(value = "流转台账", notes = "流转台账")
    @GetMapping("/list")
    @RequiresPermissions("heriAdvRow:list")
    @ResponseBody
    public ResultTable list(THeriAdvRow record) {
        QueryWrapper<THeriAdvRow> queryWrapper = new QueryWrapper<THeriAdvRow>();
        startPage();
        com.github.pagehelper.PageInfo<THeriAdvRow> page =
                new com.github.pagehelper.PageInfo<THeriAdvRow>(heriAdvRowService.selectTHeriAdvRowList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "改单处置条目推进", action = "advance")
    @ApiOperation(value = "推进一档", notes = "推进一档")
    @PostMapping("/advance")
    @RequiresPermissions("heriAdvRow:advance")
    @ResponseBody
    public AjaxResult advance(Long id, String remark) {
        return toAjax(heriAdvRowService.advance(id, remark) != null ? 1 : 0);
    }

    @Log(title = "改单处置条目回退", action = "rollback")
    @ApiOperation(value = "回退一档", notes = "回退一档")
    @PostMapping("/rollback")
    @RequiresPermissions("heriAdvRow:rollback")
    @ResponseBody
    public AjaxResult rollback(Long id, String remark) {
        return toAjax(heriAdvRowService.rollback(id, remark) != null ? 1 : 0);
    }
}
