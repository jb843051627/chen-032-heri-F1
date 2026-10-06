package com.fc.v2.service.impl;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THeriJobMapper;
import com.fc.v2.model.auto.THeriJob;
import com.fc.v2.service.ITHeriJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 提单签发作业 Service实现（job-lifecycle 形状：批量作业提交/生效/回滚，跨模块协调）
 *
 * @author fuce
 * @date 2026-10-03
 */
@Service
public class THeriJobServiceImpl implements ITHeriJobService {

    @Autowired
    private THeriJobMapper heriJobMapper;

    @Override
    public THeriJob selectTHeriJobById(Long id) {
        if (id == null) {
            return null;
        }
        return this.heriJobMapper.selectById(id);
    }

    @Override
    public THeriJob selectByJobNo(String jobNo) {
        if (jobNo == null) {
            return null;
        }
        return this.heriJobMapper.selectOne(new QueryWrapper<THeriJob>()
                .eq("job_no", jobNo).eq("row_no", 0).eq("del_flag", 0).last("limit 1"));
    }

    @Override
    public String submitJob(String jobNo, List<THeriJob> rows) {
        THeriJob job = new THeriJob();
        job.setJobNo(jobNo);
        job.setStatus(Integer.valueOf(ST_PENDING));
        job.setRowNo(Integer.valueOf(0));
        this.heriJobMapper.insert(job);
        int idx = 0;
        for (THeriJob r : rows) {
            idx++;
            r.setJobNo(jobNo);
            r.setRowNo(Integer.valueOf(idx));
            r.setStatus(Integer.valueOf(ST_DRAFT));
            if (r.getBlNo() == null || r.getAmount() == null) {
                throw new IllegalArgumentException("行数据不完整: " + idx);
            }
            this.heriJobMapper.insert(r);
        }
        return jobNo;
    }

    @Override
    public int effectJob(String jobNo) {
        THeriJob job0 = this.heriJobMapper.selectOne(new QueryWrapper<THeriJob>()
                .eq("job_no", jobNo).eq("del_flag", 0));
        if (job0 == null) {
            return 0;
        }
        job0.setStatus(Integer.valueOf(ST_EFFECTED));
        this.heriJobMapper.updateById(job0);
        List<THeriJob> items = this.heriJobMapper.selectList(new QueryWrapper<THeriJob>()
                .eq("job_no", jobNo).eq("del_flag", 0));
        int done = 0;
        for (THeriJob r : items) {
            r.setWritebackFlag(Integer.valueOf(1));
            this.heriJobMapper.updateById(r);
            r.setStatus(Integer.valueOf(ST_EFFECTED));
            this.heriJobMapper.updateById(r);
            done++;
        }
        job0.setDoneRows(Integer.valueOf(done));
        return done;
    }

    @Override
    public int rollbackJob(String jobNo) {
        THeriJob job1 = this.heriJobMapper.selectOne(new QueryWrapper<THeriJob>()
                .eq("job_no", jobNo).eq("del_flag", 0));
        if (job1 == null) {
            return 0;
        }
        List<THeriJob> items = this.heriJobMapper.selectList(new QueryWrapper<THeriJob>()
                .eq("job_no", jobNo).eq("del_flag", 0));
        int back = 0;
        for (THeriJob r : items) {
            int cc = r.getCompensateCnt() == null ? 0 : r.getCompensateCnt().intValue();
            r.setCompensateCnt(Integer.valueOf(cc + 1));
            r.setCompensateFlag(Integer.valueOf(1));
            r.setStatus(Integer.valueOf(ST_ROLLED));
            this.heriJobMapper.updateById(r);
            back++;
        }
        job1.setStatus(Integer.valueOf(ST_ROLLED));
        this.heriJobMapper.updateById(job1);
        return back;
    }

    // 作业状态机常量（草稿→待审→生效中→已生效/部分生效→回滚中→已回滚）
    private static final int ST_DRAFT = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_EFFECTING = 2;
    private static final int ST_EFFECTED = 3;
    private static final int ST_PARTIAL = 4;
    private static final int ST_ROLLING = 5;
    private static final int ST_ROLLED = 6;

    private static java.text.SimpleDateFormat _fmt() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    }

    private static String ts(java.util.Date d) {
        return d == null ? "" : _fmt().format(d);
    }

    /** 跨模块写回 */
    private void writeback(THeriJob r) {
        if (r == null) {
            return;
        }
        r.setWritebackFlag(Integer.valueOf(1));
        this.heriJobMapper.updateById(r);
    }

    /** 写补偿流水 */
    private void compensate(THeriJob r) {
        if (r == null) {
            return;
        }
        int c = r.getCompensateCnt() == null ? 0 : r.getCompensateCnt().intValue();
        r.setCompensateCnt(Integer.valueOf(c + 1));
        r.setCompensateFlag(Integer.valueOf(1));
        this.heriJobMapper.updateById(r);
    }
}
