package com.fc.v2.controller.admin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.THeriJobRow;
import com.fc.v2.service.ITHeriJobService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 提单签发作业 Controller（job-lifecycle 形状：提交/生效/回滚/追溯，纯后端接口，界面保持原样）
 *
 * @author fuce
 * @date 2026-10-06
 */
@Api(value = "提单签发作业")
@Controller
@RequestMapping("/heriJob")
public class HeriJobController extends BaseController {

    @Autowired
    private ITHeriJobService heriJobService;

    /**
     * 提交参数：一航次一叠。行次由系统顺排，递交方不填；
     * 作业号可空——空则由系统发号（一航次一号），往后查这个号就知道是哪一航次的事。
     */
    public static class SubmitParam {
        private String jobNo;
        private List<THeriJobRow> rows;

        public String getJobNo() {
            return jobNo;
        }

        public void setJobNo(String jobNo) {
            this.jobNo = jobNo;
        }

        public List<THeriJobRow> getRows() {
            return rows;
        }

        public void setRows(List<THeriJobRow> rows) {
            this.rows = rows;
        }
    }

    @Log(title = "提单签发作业提交", action = "submit")
    @ApiOperation(value = "提交一批作业行", notes = "同号重复提交幂等照面；号空则系统发号")
    @PostMapping("/submit")
    @RequiresPermissions("heriJob:submit")
    @ResponseBody
    public AjaxResult submit(@RequestBody SubmitParam param) {
        if (param == null) {
            return AjaxResult.error("参数缺失");
        }
        return AjaxResult.success(200, "提交成功（同号重复提交只当照面）",
                heriJobService.submitJob(param.getJobNo(), param.getRows()));
    }

    @Log(title = "提单签发作业生效", action = "effect")
    @ApiOperation(value = "推进到生效", notes = "逐行三段落账，部分行失败不推翻整批；返回本次新签成行数")
    @PostMapping("/effect")
    @RequiresPermissions("heriJob:effect")
    @ResponseBody
    public AjaxResult effect(String jobNo) {
        return AjaxResult.success(200, "本次新签成行数", heriJobService.effectJob(jobNo));
    }

    @Log(title = "提单签发作业回退冲账", action = "rollback")
    @ApiOperation(value = "回滚已生效作业", notes = "补红字冲账流水逐处冲回，已冲过的不重复写；返回本次新冲回行数")
    @PostMapping("/rollback")
    @RequiresPermissions("heriJob:rollback")
    @ResponseBody
    public AjaxResult rollback(String jobNo) {
        return AjaxResult.success(200, "本次新冲回行数", heriJobService.rollbackJob(jobNo));
    }

    @Log(title = "提单签发作业追溯", action = "trace")
    @ApiOperation(value = "按作业号追溯", notes = "走过哪段、签成/没签成/退回各几条、冲账流水写没写重、谁动的何时动的")
    @GetMapping("/trace")
    @RequiresPermissions("heriJob:trace")
    @ResponseBody
    public AjaxResult trace(String jobNo) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("job", heriJobService.selectByJobNo(jobNo));
        data.put("rows", heriJobService.listJobRows(jobNo));
        data.put("flows", heriJobService.listJobFlows(jobNo));
        return AjaxResult.success(200, "追溯", data);
    }
}
