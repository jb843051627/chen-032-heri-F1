-- 三张判定/档案表的初始数据：id=0 为示例行、id=1 为在用行、id=2 为停用行，
-- 供按 id 取档的各环节使用；档案表缺行会让 insert 直接判 0
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
