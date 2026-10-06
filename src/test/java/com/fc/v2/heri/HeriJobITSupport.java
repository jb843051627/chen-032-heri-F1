package com.fc.v2.heri;

import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fc.v2.mapper.auto.THeriAdvRowMapper;
import com.fc.v2.mapper.auto.THeriBlRuleMapper;
import com.fc.v2.mapper.auto.THeriDelRowMapper;
import com.fc.v2.mapper.auto.THeriJobMapper;
import com.fc.v2.mapper.auto.THeriJobRowMapper;
import com.fc.v2.mapper.auto.TSysOperLogMapper;
import com.fc.v2.service.impl.THeriBlRuleServiceImpl;
import com.fc.v2.service.impl.THeriJobServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 签发作业链路测试夹具：H2 内存库(MySQL 兼容) + 真实 MyBatis-Plus mapper，
 * 不启 Spring 容器（避开 Shiro/Redis/Druid 自动装配）。
 * mapper 走 SqlSessionTemplate：线程安全、无事务时每条操作自动提交，可跨线程共用同一 service。
 *
 * @author fuce
 * @date 2026-10-06
 */
public abstract class HeriJobITSupport {

    protected JdbcDataSource dataSource;
    protected SqlSessionTemplate session;

    protected THeriJobServiceImpl jobService;
    protected THeriBlRuleServiceImpl blRuleService;

    protected THeriJobMapper jobMapper;
    protected THeriJobRowMapper jobRowMapper;
    protected THeriDelRowMapper delRowMapper;
    protected THeriAdvRowMapper advRowMapper;
    protected TSysOperLogMapper operLogMapper;
    protected THeriBlRuleMapper blRuleMapper;

    protected void setUp() throws Exception {
        this.dataSource = new JdbcDataSource();
        this.dataSource.setURL("jdbc:h2:mem:jobit_" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        this.dataSource.setUser("sa");
        runScript(this.dataSource);

        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(THeriJobMapper.class);
        cfg.addMapper(THeriJobRowMapper.class);
        cfg.addMapper(THeriDelRowMapper.class);
        cfg.addMapper(THeriAdvRowMapper.class);
        cfg.addMapper(TSysOperLogMapper.class);
        cfg.addMapper(THeriBlRuleMapper.class);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(this.dataSource);
        factoryBean.setConfiguration(cfg);
        SqlSessionFactory factory = factoryBean.getObject();

        this.session = new SqlSessionTemplate(factory);
        this.jobMapper = this.session.getMapper(THeriJobMapper.class);
        this.jobRowMapper = this.session.getMapper(THeriJobRowMapper.class);
        this.delRowMapper = this.session.getMapper(THeriDelRowMapper.class);
        this.advRowMapper = this.session.getMapper(THeriAdvRowMapper.class);
        this.operLogMapper = this.session.getMapper(TSysOperLogMapper.class);
        this.blRuleMapper = this.session.getMapper(THeriBlRuleMapper.class);

        this.blRuleService = new THeriBlRuleServiceImpl();
        ReflectionTestUtils.setField(this.blRuleService, "heriBlRuleMapper", this.blRuleMapper);

        this.jobService = new THeriJobServiceImpl();
        ReflectionTestUtils.setField(this.jobService, "heriJobMapper", this.jobMapper);
        ReflectionTestUtils.setField(this.jobService, "heriJobRowMapper", this.jobRowMapper);
        ReflectionTestUtils.setField(this.jobService, "heriDelRowMapper", this.delRowMapper);
        ReflectionTestUtils.setField(this.jobService, "heriAdvRowMapper", this.advRowMapper);
        ReflectionTestUtils.setField(this.jobService, "sysOperLogMapper", this.operLogMapper);
        ReflectionTestUtils.setField(this.jobService, "heriBlRuleService", this.blRuleService);
    }

    private static void runScript(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             Reader r = new InputStreamReader(
                     HeriJobITSupport.class.getResourceAsStream("/schema-h2.sql"), "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            for (String sql : sb.toString().split(";")) {
                if (!sql.trim().isEmpty()) {
                    st.execute(sql);
                }
            }
        }
    }
}
