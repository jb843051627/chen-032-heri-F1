-- heri 港口集装箱提单与货物放货凭证管理 -- schema (chen-032)
-- 列名与基线实体契约（@TableName/@TableField）逐列对齐，改列必须同步实体。
-- 库：chen_032

CREATE TABLE IF NOT EXISTS t_heri_adv_base (
  id bigint NOT NULL COMMENT '主键',
  grade_code varchar(32) DEFAULT NULL COMMENT '处置档编号',
  grade_name varchar(64) DEFAULT NULL COMMENT '处置档名称',
  th1_max decimal(14,2) DEFAULT NULL COMMENT '直改档上限(元)',
  th2_max decimal(14,2) DEFAULT NULL COMMENT '换单档上限(元)',
  th3_max decimal(14,2) DEFAULT NULL COMMENT '改提单档上限(元)',
  status int DEFAULT NULL COMMENT '处置档状态 0启用 1撤下',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='改单处置档案';

CREATE TABLE IF NOT EXISTS t_heri_adv_row (
  id bigint NOT NULL COMMENT '主键',
  item_no varchar(64) DEFAULT NULL COMMENT '条目编号',
  biz_no varchar(64) DEFAULT NULL COMMENT '业务代号',
  amount decimal(14,2) DEFAULT NULL COMMENT '改单金额(元)',
  settle_type varchar(128) DEFAULT NULL COMMENT '处置方式',
  ship_name varchar(128) DEFAULT NULL COMMENT '船名航次',
  stage int DEFAULT NULL COMMENT '当前环节 0..2',
  content varchar(255) DEFAULT NULL COMMENT '内容备注',
  last_action varchar(32) DEFAULT NULL COMMENT '最近一次流转动作',
  status int DEFAULT NULL COMMENT '条目状态 0在办 1已定下 2已封住',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='改单处置条目';

CREATE TABLE IF NOT EXISTS t_heri_bl_row (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) DEFAULT NULL COMMENT '签发批次码',
  row_no int DEFAULT NULL COMMENT '原始行次',
  item_code varchar(64) DEFAULT NULL COMMENT '提单代号',
  qty decimal(14,2) DEFAULT NULL COMMENT '本单箱数',
  device_kind varchar(128) DEFAULT NULL COMMENT '箱型',
  ship_name varchar(128) DEFAULT NULL COMMENT '船名航次',
  status int DEFAULT NULL COMMENT '行状态 0待签 1已签 2已作废',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提单签发行';

CREATE TABLE IF NOT EXISTS t_heri_bl_rule (
  id bigint NOT NULL COMMENT '主键',
  rule_code varchar(32) DEFAULT NULL COMMENT '规则编号',
  rule_name varchar(64) DEFAULT NULL COMMENT '规则名称',
  th1_max decimal(14,2) DEFAULT NULL COMMENT '本航次直发档上限(件)',
  th2_max decimal(14,2) DEFAULT NULL COMMENT '本航次换单档上限(件)',
  th3_max decimal(14,2) DEFAULT NULL COMMENT '本航次改提单档上限(件)',
  eff_start datetime DEFAULT NULL COMMENT '生效起始时刻',
  eff_end datetime DEFAULT NULL COMMENT '生效截止时刻(不含)',
  priority int DEFAULT NULL COMMENT '优先级(数值越大越优先)',
  status int DEFAULT NULL COMMENT '规则状态 0启用 1撤下',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提单签发判定规则';

CREATE TABLE IF NOT EXISTS t_heri_del_row (
  id bigint NOT NULL COMMENT '主键',
  item_no varchar(64) DEFAULT NULL COMMENT '条目编号',
  due_at datetime DEFAULT NULL COMMENT '到期时刻',
  amount decimal(14,2) DEFAULT NULL COMMENT '滞期费(元)',
  notify_type varchar(128) DEFAULT NULL COMMENT '提醒方式',
  ship_name varchar(128) DEFAULT NULL COMMENT '船名航次',
  status int DEFAULT NULL COMMENT '条目状态 0待发 1已发 2已处理',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='放货凭证条目';

CREATE TABLE IF NOT EXISTS t_heri_del_rule (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '凭证代号',
  node_no int DEFAULT NULL COMMENT '当前关口 0..2',
  sign_mode int DEFAULT NULL COMMENT '落签模式 0或签 1齐签',
  need_count int DEFAULT NULL COMMENT '本关口应落人数',
  sign_count int DEFAULT NULL COMMENT '本关口已落人数',
  status int DEFAULT NULL COMMENT '凭证状态 0在办 1已核发 2已封住',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='放货凭证核发判定规则';

CREATE TABLE IF NOT EXISTS t_heri_job (
  id bigint NOT NULL COMMENT '主键',
  job_no varchar(32) DEFAULT NULL COMMENT '作业编号',
  job_name varchar(64) DEFAULT NULL COMMENT '作业名称',
  status int DEFAULT NULL COMMENT '作业状态(0草稿1待审2生效中3已生效4部分生效5回滚中6已回滚)',
  total_rows int DEFAULT NULL COMMENT '提交总行数',
  done_rows int DEFAULT NULL COMMENT '生效成功行数',
  fail_rows int DEFAULT NULL COMMENT '生效失败行数',
  eff_start datetime DEFAULT NULL COMMENT '作业生效起始时刻',
  eff_end datetime DEFAULT NULL COMMENT '作业生效截止时刻(不含)',
  version varchar(32) DEFAULT NULL COMMENT '判据版本',
  row_no int DEFAULT NULL COMMENT '行次',
  bl_no varchar(64) DEFAULT NULL COMMENT '提单号',
  amount decimal(14,2) DEFAULT NULL COMMENT '金额',
  job_qty int DEFAULT NULL COMMENT '数量',
  version_status int DEFAULT NULL COMMENT '版本状态(1生效中 0未生效)',
  writeback_flag int DEFAULT NULL COMMENT '是否已写回(0否1是)',
  compensate_flag int DEFAULT NULL COMMENT '补偿流水标记(0否1是)',
  compensated int DEFAULT NULL COMMENT '是否已补偿(0否1是)',
  compensate_cnt int DEFAULT NULL COMMENT '补偿累计次数',
  fail_reason varchar(255) DEFAULT NULL COMMENT '失败原因',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_heri_job_no (job_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提单签发作业';

CREATE TABLE IF NOT EXISTS t_heri_job_row (
  id bigint NOT NULL COMMENT '主键',
  job_no varchar(32) DEFAULT NULL COMMENT '作业编号',
  row_no int DEFAULT NULL COMMENT '行次',
  bl_no varchar(64) DEFAULT NULL COMMENT '提单号',
  amount decimal(14,2) DEFAULT NULL COMMENT '金额',
  job_qty int DEFAULT NULL COMMENT '数量',
  version varchar(32) DEFAULT NULL COMMENT '判据版本',
  version_status int DEFAULT NULL COMMENT '版本状态(1生效中 0未生效)',
  writeback_flag int DEFAULT NULL COMMENT '是否已写回(0否1是)',
  compensate_flag int DEFAULT NULL COMMENT '补偿流水标记(0否1是)',
  compensated int DEFAULT NULL COMMENT '是否已补偿(0否1是)',
  fail_reason varchar(255) DEFAULT NULL COMMENT '失败原因',
  status int DEFAULT NULL COMMENT '行状态',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_heri_job_row (job_no, row_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='签发作业行';

CREATE TABLE IF NOT EXISTS t_heri_job_flow (
  id bigint NOT NULL COMMENT '主键',
  job_no varchar(32) NOT NULL COMMENT '作业编号',
  row_no int DEFAULT NULL COMMENT '行次',
  bl_no varchar(64) DEFAULT NULL COMMENT '提单号',
  flow_type int NOT NULL COMMENT '流水类型 1签发费用 2冲账',
  amount decimal(14,2) DEFAULT NULL COMMENT '金额(冲账为红字负数)',
  job_qty int DEFAULT NULL COMMENT '数量',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_heri_job_flow (job_no, row_no, flow_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='签发作业流水';

CREATE TABLE IF NOT EXISTS t_heri_set_row (
  id bigint NOT NULL COMMENT '主键',
  item_no varchar(64) DEFAULT NULL COMMENT '条目编号',
  due_at datetime DEFAULT NULL COMMENT '到期时刻',
  amount decimal(14,2) DEFAULT NULL COMMENT '差异金额(元)',
  base_id bigint DEFAULT NULL COMMENT '所依据的处置档档案主键',
  grade_code varchar(32) DEFAULT NULL COMMENT '处置档编号(冗余自档案)',
  settle_type varchar(128) DEFAULT NULL COMMENT '核销方式',
  ship_name varchar(128) DEFAULT NULL COMMENT '船名航次',
  status int DEFAULT NULL COMMENT '条目状态 0未核销 1已核销 2已退回',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单证对齐条目';

-- 初始档案数据（id=0 示例行 / id=1 在用行 / id=2 停用行）
-- 判定档册与处置档册各留三行，供按 id 取档的各环节使用；
-- 档案表缺行会让 insert 直接判 0
-- ⇒ 合法记录会因为查不到档案而保存失败。
INSERT IGNORE INTO t_heri_bl_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag, create_by, create_time)
VALUES (0, 'NR00', '签发直发档：本航次 500 件以内直发出单（示例行）', 500.00, 2000.00, 5000.00, '2020-01-01 00:00:00', '2099-12-31 23:59:59', 10, 0, 0, 'seed', NOW()),
       (1, 'NR01', '签发直发档：本航次 500 件以内直发出单（在用行）', 500.00, 2000.00, 5000.00, '2020-01-01 00:00:00', '2099-12-31 23:59:59', 10, 0, 0, 'seed', NOW()),
       (2, 'NR02', '签发撤下档：本航次 500 件以内直发出单（停用行）', 500.00, 2000.00, 5000.00, '2020-01-01 00:00:00', '2021-12-31 23:59:59', 10, 1, 0, 'seed', NOW());

INSERT IGNORE INTO t_heri_del_rule (id, bill_no, node_no, sign_mode, need_count, sign_count, status, del_flag, create_by, create_time)
VALUES (0, 'NB00', 0, 0, 1, 0, 0, 0, 'seed', NOW()),
       (1, 'NB01', 1, 1, 2, 0, 0, 0, 'seed', NOW());

INSERT IGNORE INTO t_heri_adv_base (id, grade_code, grade_name, th1_max, th2_max, th3_max, status, del_flag, create_by, create_time)
VALUES (0, 'GB00', '处置直改档：改单金额在 500 元以内（示例行）', 500.00, 2000.00, 5000.00, 0, 0, 'seed', NOW()),
       (1, 'GB01', '处置直改档：改单金额在 500 元以内（在用行）', 500.00, 2000.00, 5000.00, 0, 0, 'seed', NOW()),
       (2, 'GB02', '处置复核档：改单金额 500 元至 2000 元（停用行）', 500.00, 2000.00, 5000.00, 1, 0, 'seed', NOW());

