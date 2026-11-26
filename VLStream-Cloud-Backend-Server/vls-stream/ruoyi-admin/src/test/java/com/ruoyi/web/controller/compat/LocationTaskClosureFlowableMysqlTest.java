package com.ruoyi.web.controller.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.flowable.config.GlobalEventListenerConfig;
import com.ruoyi.flowable.listener.GlobalEventListener;
import com.ruoyi.vlstream.test.vlstream.pojo.dto.ActiveSafetyEventReport;
import com.ruoyi.vlstream.test.vlstream.service.DeviceMediaUploadService;
import com.ruoyi.workflow.domain.bo.ProcessStartBo;
import com.ruoyi.workflow.service.IWfProcessService;
import com.ruoyi.workflow.service.LocationTaskWorkflowCallbackService;
import com.ruoyi.workflow.service.LocationTaskWorkflowResultWorker;
import com.ruoyi.workorder.domain.WorkOrder;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Dedicated MySQL schema with the location bootstrap and migrations 084 through 086 applied.
 * The adapter substitutes the application/form directory while using real transactional BPMN execution. */
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "VLS_FLOWABLE_CLOSURE_TEST_JDBC", matches = ".+")
class LocationTaskClosureFlowableMysqlTest {
    private static final String BPMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
        + " xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"closure-test\">"
        + "<process id=\"closureApproval\" isExecutable=\"true\"><startEvent id=\"begin\"/>"
        + "<sequenceFlow id=\"toReview\" sourceRef=\"begin\" targetRef=\"review\"/>"
        + "<userTask id=\"review\" flowable:assignee=\"reviewer\"/>"
        + "<sequenceFlow id=\"toApprove\" sourceRef=\"review\" targetRef=\"approve\"/>"
        + "<userTask id=\"approve\" flowable:assignee=\"approver\"/>"
        + "<sequenceFlow id=\"toEnd\" sourceRef=\"approve\" targetRef=\"end\"/><endEvent id=\"end\"/>"
        + "</process></definitions>";

    @Test
    void deviceReportSurvivesWorkerRestartAndClosesOnlyAfterLastApproval() {
        DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("VLS_FLOWABLE_CLOSURE_TEST_JDBC"),
            System.getenv("VLS_CLOSURE_TEST_USER"), System.getenv("VLS_CLOSURE_TEST_PASSWORD"));
        DataSourceTransactionManager tx = new DataSourceTransactionManager(ds);
        JdbcTemplate db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE IF NOT EXISTS work_order(id VARCHAR(64) PRIMARY KEY,tenant_id VARCHAR(64),"
            + "event_number VARCHAR(128),proc_ins_id VARCHAR(64),workorder_id VARCHAR(64),del_flag CHAR(1))");
        db.execute("CREATE TABLE IF NOT EXISTS sys_user(user_id VARCHAR(64) PRIMARY KEY,tenant_id VARCHAR(64),"
            + "del_flag CHAR(1) DEFAULT '0',nick_name VARCHAR(128),user_name VARCHAR(128))");
        SpringProcessEngineConfiguration cfg = new SpringProcessEngineConfiguration();
        cfg.setDataSource(ds);
        cfg.setTransactionManager(tx);
        cfg.setBeans(java.util.Collections.emptyMap());
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setHistory("audit");
        cfg.setAsyncExecutorActivate(false);
        cfg.setDisableIdmEngine(true);
        cfg.setDisableEventRegistry(true);
        ProcessEngine engine = cfg.buildProcessEngine();
        try {
            GlobalEventListener completionListener = new GlobalEventListener();
            ReflectionTestUtils.setField(completionListener, "runtimeService", engine.getRuntimeService());
            new GlobalEventListenerConfig(completionListener, engine.getRuntimeService()).onApplicationEvent(null);
            ObjectMapper mapper = new ObjectMapper();
            String run = UUID.randomUUID().toString();
            String tenant = run;
            String deployment = engine.getRepositoryService().createDeployment().tenantId(tenant)
                .addString("case.bpmn20.xml", BPMN).deploy().getId();
            String definition = engine.getRepositoryService().createProcessDefinitionQuery()
                .deploymentId(deployment).singleResult().getId();
            db.update("INSERT INTO oort_task_event_item(uid,item,mod_type,tenant_id,data) VALUES (?,?,2,?,"
                    + "JSON_OBJECT('config',JSON_OBJECT('auto_to_work',true,'tenant_id',?,'user_id','operator',"
                    + "'process_id',?,'app_id','fixture-app')))", run, run, tenant, tenant, definition);
            IWfProcessService starts = mock(IWfProcessService.class);
            when(starts.startVerifiedEventWorkOrder(any(ProcessStartBo.class), any(SysUser.class))).thenAnswer(call -> {
                ProcessStartBo req = call.getArgument(0);
                SysUser actor = call.getArgument(1);
                assertEquals(tenant, actor.getTenantId());
                assertEquals("operator", actor.getUserId());
                req.getVariables().put("processStatus", "running");
                String instance = engine.getRuntimeService()
                    .startProcessInstanceById(req.getProcessDefId(), req.getVariables()).getId();
                WorkOrder order = new WorkOrder();
                order.setId(req.getRequestId());
                order.setTenantId(actor.getTenantId());
                order.setWorkorderId("fixture-app");
                order.setProcInsId(instance);
                db.update("INSERT INTO work_order VALUES (?,?,?,?,?,'0')", order.getId(), tenant,
                    req.getWorkOrderBo().getEventNumber(), instance, order.getWorkorderId());
                req.setWorkOrder(order);
                return instance;
            });
            LocationTaskCompatService service = new LocationTaskCompatService(ds, mapper, starts, new LocationTaskWorkflowProperties());
            service.configureBatchTransactions(tx);
            ReflectionTestUtils.setField(service, "singleTenantId", tenant);
            service = transactional(service, tx);
            ActiveSafetyEventReport alarm = ActiveSafetyEventReport.builder().deviceId(run).deviceEventId(run)
                .sourceMessageId(run).mediaId(run).deviceName("fixture").eventType(run).build();
            String event = service.report(alarm).getActiveSafetyEventId();
            assertEquals(event, service.report(alarm).getActiveSafetyEventId());
            assertEquals("PENDING", db.queryForObject("SELECT dispatch_status FROM oort_task_event_dispatch"
                + " WHERE process_definition_id=?", String.class, definition));
            // This worker has never seen the reporting call. Its only input is committed state.
            DeviceMediaUploadService media = mock(DeviceMediaUploadService.class);
            when(media.getPrivateViewUrl(run, 3600)).thenReturn("https://example.invalid/picture");
            LocalEventDispatchWorker worker = new LocalEventDispatchWorker(ds, tx, mapper, starts, media, tenant);
            worker.dispatchDue();
            worker.dispatchDue();
            verify(starts, times(1)).startVerifiedEventWorkOrder(any(), any());
            String instance = db.queryForObject("SELECT proc_inst_id FROM oort_task_event_dispatch"
                + " WHERE process_definition_id=? AND dispatch_status='LINKED'", String.class, definition);
            LocationTaskWorkflowCallbackService results = transactional(new LocationTaskWorkflowCallbackService(db, mapper, tenant), tx);
            Task review = engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
            assertEquals("reviewer", review.getAssignee());
            engine.getTaskService().addComment(review.getId(), instance, "通过");
            engine.getTaskService().complete(review.getId());
            new LocationTaskWorkflowResultWorker(results).reconcile();
            assertEquals(2, db.queryForObject("SELECT status FROM oort_task_event WHERE device_id=?", Integer.class, run).intValue());

            Task approval = engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
            assertEquals("approver", approval.getAssignee());
            engine.getTaskService().addComment(approval.getId(), instance, "现场已处理");
            engine.getTaskService().complete(approval.getId());
            assertNotNull(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instance)
                .finished().singleResult());
            // Recreate the reconciler after the actual engine completion, then replay its scan.
            new LocationTaskWorkflowResultWorker(results).reconcile();
            new LocationTaskWorkflowResultWorker(results).reconcile();
            assertEquals(1, db.queryForObject("SELECT status FROM oort_task_event WHERE device_id=?", Integer.class, run).intValue());
            assertEquals("approver", db.queryForObject("SELECT result_data->>'$.assigneeId' FROM oort_task_event_dispatch"
                + " WHERE proc_inst_id=? AND result_status='COMPLETED'", String.class, instance));
            assertEquals("现场已处理", db.queryForObject("SELECT data->>'$.work_order_call_back.fullMessage'"
                + " FROM oort_task_event WHERE device_id=?", String.class, run));
        } finally {
            engine.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DataSourceTransactionManager tx) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
