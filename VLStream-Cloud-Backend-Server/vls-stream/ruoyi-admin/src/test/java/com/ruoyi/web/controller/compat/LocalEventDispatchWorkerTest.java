package com.ruoyi.web.controller.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.helper.TenantContextHolder;
import com.ruoyi.workflow.domain.bo.ProcessStartBo;
import com.ruoyi.workflow.service.IWfProcessService;
import com.ruoyi.workorder.domain.WorkOrder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
public class LocalEventDispatchWorkerTest {
    // H2 has no MySQL JSON_SET. This test exercises the transaction boundary around the projection.
    public static String setJsonField(String data, String path, String value) {
        return value;
    }

    @Test
    void restartAfterStartFailureRecoversBothRulesWithoutDuplicateOrders() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate db = new JdbcTemplate(source);
        db.execute("CREATE ALIAS JSON_SET FOR \"com.ruoyi.web.controller.compat.LocalEventDispatchWorkerTest.setJsonField\"");
        db.execute("CREATE TABLE oort_task_event(id VARCHAR, `no` INT,tenant_id VARCHAR, deleted_at INT,"
            + " data VARCHAR,mod_status INT,work_order_status INT,updated_at TIMESTAMP)");
        db.execute("CREATE TABLE oort_task_event_dispatch(request_id VARCHAR PRIMARY KEY, tenant_id VARCHAR,"
            + " event_id VARCHAR,event_no INT,rule_no INT,actor_id VARCHAR,start_payload VARCHAR,"
            + " dispatch_status VARCHAR,attempts INT,next_attempt_at TIMESTAMP,proc_inst_id VARCHAR,"
            + " work_order_id VARCHAR,last_error VARCHAR)");
        db.execute("CREATE TABLE created_orders(id VARCHAR PRIMARY KEY)");
        db.update("INSERT INTO oort_task_event VALUES ('evt',0,'tenant-a',0,'{}',0,0,NOW())");
        ObjectMapper mapper = new ObjectMapper();
        for (int rule = 1; rule <= 2; rule++) {
            ProcessStartBo request = new ProcessStartBo();
            request.setRequestId("rule-" + rule);
            db.update("INSERT INTO oort_task_event_dispatch VALUES (?,'tenant-a','evt',0,?,'actor',?,"
                    + "'PENDING',0,NOW(),NULL,NULL,NULL)", request.getRequestId(), rule, mapper.writeValueAsString(request));
        }

        IWfProcessService workflows = mock(IWfProcessService.class);
        AtomicBoolean fail = new AtomicBoolean(true);
        when(workflows.startVerifiedEventWorkOrder(any(ProcessStartBo.class), any(SysUser.class))).thenAnswer(call -> {
            ProcessStartBo request = call.getArgument(0);
            SysUser actor = call.getArgument(1);
            assertEquals("tenant-a", TenantContextHolder.getTenantId());
            assertEquals("actor", actor.getUserId());
            String id = request.getRequestId();
            db.update("INSERT INTO created_orders VALUES (?)", id);
            if (fail.getAndSet(false)) throw new IllegalStateException("injected crash after order insert");
            WorkOrder order = new WorkOrder();
            order.setId("order-" + id);
            order.setTenantId("tenant-a");
            order.setProcInsId("flow-" + id);
            request.setWorkOrder(order);
            return order.getProcInsId();
        });

        DataSourceTransactionManager tx = new DataSourceTransactionManager(source);
        new LocalEventDispatchWorker(source, tx, mapper, workflows, null, "tenant-a").dispatchDue();
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM created_orders", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM oort_task_event_dispatch WHERE dispatch_status='RETRY'", Integer.class));
        db.update("UPDATE oort_task_event_dispatch SET next_attempt_at=NOW()");
        LocalEventDispatchWorker restarted = new LocalEventDispatchWorker(source, tx, mapper, workflows, null, "tenant-a");
        restarted.dispatchDue();
        restarted.dispatchDue();
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM created_orders", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM oort_task_event_dispatch WHERE dispatch_status='LINKED'"
            + " AND proc_inst_id IS NOT NULL AND work_order_id IS NOT NULL", Integer.class));
        assertEquals(1, db.queryForObject("SELECT work_order_status FROM oort_task_event", Integer.class));
        verify(workflows, times(3)).startVerifiedEventWorkOrder(any(), any());
    }
}
