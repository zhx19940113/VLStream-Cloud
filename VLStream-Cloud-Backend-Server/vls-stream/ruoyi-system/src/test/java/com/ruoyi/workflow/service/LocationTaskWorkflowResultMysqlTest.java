package com.ruoyi.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
@EnabledIfEnvironmentVariable(named = "VLS_CLOSURE_TEST_JDBC", matches = "jdbc:mysql:.*")
class LocationTaskWorkflowResultMysqlTest {
    @Test void terminalHistoryResolvesEveryRuleAndManualLinkExactlyOnce() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(System.getenv("VLS_CLOSURE_TEST_JDBC"),
            System.getenv("VLS_CLOSURE_TEST_USER"), System.getenv("VLS_CLOSURE_TEST_PASSWORD"), true);
        JdbcTemplate db = new JdbcTemplate(source);
        try {
            tables(db);
            db.update("INSERT INTO oort_task_event VALUES ('evt',7,'t',0,2,1,"
                + "JSON_OBJECT('work_order_data',JSON_OBJECT('id','manual','procInsId','p3')),NOW())");
            db.update("INSERT INTO oort_task_event_dispatch VALUES ('evt',7,'t','one','p1','LINKED',NULL,NULL,NOW()),"
                + "('evt',7,'t','two','p2','LINKED',NULL,NULL,NOW())");
            for (int i = 1; i <= 3; i++) {
                String proc = "p" + i;
                db.update("INSERT INTO work_order VALUES (?,?,?,?,'t','0','evt-7')",
                    new String[]{"one","two","manual"}[i-1], proc, "app", "unused");
                db.update("INSERT INTO ACT_HI_PROCINST VALUES (?,'t',NULL,NULL)", proc);
                db.update("INSERT INTO ACT_HI_VARINST VALUES (?,?,NULL,'processStatus','running')", proc, proc);
            }
            LocationTaskWorkflowCallbackService service = new LocationTaskWorkflowCallbackService(db, new ObjectMapper(), "t");
            LocationTaskWorkflowResultWorker worker = new LocationTaskWorkflowResultWorker(service);
            db.update("UPDATE ACT_HI_VARINST SET TEXT_='completed'");
            worker.reconcile(); // Intermediate approval without a process end must not write.
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM oort_task_event_dispatch WHERE result_status IS NOT NULL", Integer.class));
            db.update("UPDATE ACT_HI_PROCINST SET END_TIME_=NOW() WHERE PROC_INST_ID_ IN ('p1','p2')");
            db.update("UPDATE ACT_HI_PROCINST SET TENANT_ID_='other' WHERE PROC_INST_ID_='p2'");
            worker.reconcile();
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM oort_task_event_dispatch WHERE result_status='COMPLETED'", Integer.class));
            db.update("UPDATE ACT_HI_PROCINST SET TENANT_ID_='t' WHERE PROC_INST_ID_='p2'");
            worker.reconcile();
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM oort_task_event_dispatch WHERE result_status='COMPLETED'", Integer.class));
            assertEquals(2, eventStatus(db));
            db.update("UPDATE ACT_HI_PROCINST SET END_TIME_=NOW() WHERE PROC_INST_ID_='p3'");
            List<Map<String,Object>> finished = service.finishedOrders(false, "");
            assertEquals(1, finished.size());
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
                service.applyFinishedOrder(finished.get(0));
                assertEquals(1, eventStatus(db));
                tx.setRollbackOnly();
            });
            assertEquals(2, eventStatus(db));
            assertEquals(1, service.finishedOrders(false, "").size());
            service.applyFinishedOrder(finished.get(0));
            assertEquals(1, eventStatus(db));
            String first = db.queryForObject("SELECT CAST(data AS CHAR) FROM oort_task_event", String.class);
            service.applyFinishedOrder(finished.get(0));
            assertEquals(first, db.queryForObject("SELECT CAST(data AS CHAR) FROM oort_task_event", String.class));
            assertTrue(service.finishedOrders(false, "").isEmpty());

            // A cloud event's bare ID reconciles a returned/cancelled work order without closing it.
            db.update("UPDATE oort_task_event SET id='cloud-event',`no`=0,status=2,data=JSON_REMOVE(data,'$.work_order_call_back')");
            db.update("UPDATE work_order SET event_number='cloud-event' WHERE id='manual'");
            db.update("UPDATE ACT_HI_VARINST SET TEXT_='terminated' WHERE PROC_INST_ID_='p3'");
            worker.reconcile();
            assertEquals(2, eventStatus(db));
            assertEquals("TERMINATED", db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(data,"
                + "'$.work_order_call_back.resultStatus')) FROM oort_task_event", String.class));
        } finally {
            source.destroy();
        }
    }

    private int eventStatus(JdbcTemplate db) {
        return db.queryForObject("SELECT status FROM oort_task_event", Integer.class);
    }

    private void tables(JdbcTemplate db) {
        db.execute("CREATE TEMPORARY TABLE oort_task_event(id VARCHAR(40),`no` BIGINT,tenant_id VARCHAR(40),"
            + "deleted_at INT,status INT,work_order_status INT,data JSON,updated_at DATETIME)");
        db.execute("CREATE TEMPORARY TABLE oort_task_event_dispatch(event_id VARCHAR(40),event_no BIGINT,tenant_id VARCHAR(40),"
            + "work_order_id VARCHAR(40),proc_inst_id VARCHAR(64),dispatch_status VARCHAR(16),result_status VARCHAR(16),"
            + "result_data JSON,updated_at DATETIME)");
        db.execute("CREATE TEMPORARY TABLE work_order(id VARCHAR(40),proc_ins_id VARCHAR(64),workorder_id VARCHAR(40),"
            + "workorder_number VARCHAR(40),tenant_id VARCHAR(40),del_flag CHAR(1),event_number VARCHAR(100))");
        db.execute("CREATE TEMPORARY TABLE ACT_HI_PROCINST(PROC_INST_ID_ VARCHAR(64),TENANT_ID_ VARCHAR(40),END_TIME_ DATETIME,DELETE_REASON_ VARCHAR(100))");
        db.execute("CREATE TEMPORARY TABLE ACT_HI_VARINST(PROC_INST_ID_ VARCHAR(64),EXECUTION_ID_ VARCHAR(64),TASK_ID_ VARCHAR(64),NAME_ VARCHAR(40),TEXT_ VARCHAR(100))");
        db.execute("CREATE TEMPORARY TABLE ACT_HI_TASKINST(PROC_INST_ID_ VARCHAR(64),TENANT_ID_ VARCHAR(40),END_TIME_ DATETIME,ID_ VARCHAR(64),ASSIGNEE_ VARCHAR(64))");
        db.execute("CREATE TEMPORARY TABLE ACT_HI_COMMENT(TASK_ID_ VARCHAR(64),ACTION_ VARCHAR(64),TIME_ DATETIME,ID_ VARCHAR(64),MESSAGE_ VARCHAR(100))");
        db.execute("CREATE TEMPORARY TABLE sys_user(user_id VARCHAR(64),tenant_id VARCHAR(40),del_flag CHAR(1),nick_name VARCHAR(64),user_name VARCHAR(64))");
    }
}
