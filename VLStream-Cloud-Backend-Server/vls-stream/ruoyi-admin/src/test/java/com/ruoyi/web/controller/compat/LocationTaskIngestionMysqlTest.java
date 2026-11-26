/* SPDX-License-Identifier: MIT */
package com.ruoyi.web.controller.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.vlstream.test.vlstream.pojo.dto.ActiveSafetyEventReport;
import com.ruoyi.vlstream.test.vlstream.pojo.dto.ActiveSafetyEventReportResult;
import com.ruoyi.vlstream.test.vlstream.service.VlsDeviceEventReportService;
import com.ruoyi.workflow.service.IWfProcessService;
import com.ruoyi.workflow.mapper.WorkflowOwnershipMapper;
import com.ruoyi.workflow.service.WorkflowOwnershipService;
import com.ruoyi.workorder.domain.WorkOrder;
import com.ruoyi.common.exception.ServiceException;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Run against a disposable database initialized with the single-tenant event schema and its migrations. */
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "VLS_CLOSURE_TEST_JDBC", matches = ".+")
class LocationTaskIngestionMysqlTest {
    private final String device = UUID.randomUUID().toString();
    private final ObjectMapper json = new ObjectMapper();
    private LocationTaskCompatService service;
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private TransactionTemplate transaction;
    private DriverManagerDataSource db;

    @BeforeEach
    void setUp() {
        db = new DriverManagerDataSource(System.getenv("VLS_CLOSURE_TEST_JDBC"),
            System.getenv("VLS_CLOSURE_TEST_USER"), System.getenv("VLS_CLOSURE_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(db);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(db);
        transaction = new TransactionTemplate(tx);
        LocationTaskCompatService target = new LocationTaskCompatService(db, json, mock(IWfProcessService.class),
            new LocationTaskWorkflowProperties());
        target.configureBatchTransactions(tx);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        service = (LocationTaskCompatService) proxy.getProxy();
        mvc = MockMvcBuilders.standaloneSetup(new LocationTaskCompatController(service, null,
            new CameraDeviceEventReportService(service, mock(VlsDeviceEventReportService.class)))).build();
    }

    @Test
    void overlappingSingleAndBatchRequestsAllPersist() throws Exception {
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(() -> postReport(false, eventJson(device)));
            requests.add(() -> postReport(true, eventJson(device)));
        }
        for (int code : runTogether(requests)) assertEquals(200, code);
        assertEquals(40, eventCount());
        assertEquals(40, jdbc.queryForObject("SELECT COUNT(DISTINCT `no`) FROM oort_task_event WHERE device_id=?",
            Integer.class, device).intValue());
    }

    @Test
    void oneDatabaseFailureDoesNotRollBackOtherBatchMembers() throws Exception {
        // The middle report passes request validation but overflows the persisted device_id.
        assertEquals(200, postReport(true, eventJson(device) + "," + eventJson(device + device) + "," + eventJson(device)));
        assertEquals(2, eventCount());
    }

    @Test
    void concurrentMqttRetriesReturnTheOriginalEventAndMedia() throws Exception {
        jdbc.update("INSERT INTO oort_task_event_item (uid,item,mod_type,tenant_id,data) VALUES (?,?,2,'000000',"
            + "JSON_OBJECT('config',JSON_OBJECT('auto_to_work',1,'tenant_id','000000','user_id','operator',"
            + "'process_id','process','app_id','app')))", device, device);
        List<Callable<ActiveSafetyEventReportResult>> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final String media = "media-" + i;
            requests.add(() -> service.report(ActiveSafetyEventReport.builder().deviceId(device).deviceName("camera")
                .sourceMessageId("message").deviceEventId("event").mediaId(media).eventType(device).build()));
        }
        List<ActiveSafetyEventReportResult> replies = runTogether(requests);
        assertEquals(1, replies.stream().filter(r -> !r.isDuplicate()).count());
        assertEquals(1, replies.stream().map(ActiveSafetyEventReportResult::getActiveSafetyEventId).distinct().count());
        assertEquals(1, replies.stream().map(ActiveSafetyEventReportResult::getMediaId).distinct().count());
        assertEquals(1, eventCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oort_task_event_dispatch d JOIN oort_task_event e"
            + " ON d.tenant_id=e.tenant_id AND d.event_id=e.id AND d.event_no=e.`no` WHERE e.device_id=?"
            + " AND d.dispatch_status='PENDING'", Integer.class, device).intValue());
        assertNotNull(service.findDuplicate("message", device, "another-event"));
        assertNotNull(service.findDuplicate("another-message", device, "event"));
    }

    @Test
    void legacyEventWithoutDigestColumnsStillReturnsItsOriginalReceipt() {
        jdbc.update("INSERT INTO oort_task_event (id,`no`,tenant_id,uuid,device_id,data)"
            + " VALUES (?,1,'000000',?,?,JSON_OBJECT('pics',JSON_ARRAY(),'mqtt_message_id','old-message','device_event_id','old-event',"
            + "'media_id','original-media'))", device, device, device);
        ActiveSafetyEventReportResult reply = service.report(ActiveSafetyEventReport.builder().deviceId(device)
            .sourceMessageId("new-message").deviceEventId("old-event").mediaId("new-media").build());
        assertTrue(reply.isDuplicate());
        assertEquals(device + "-1", reply.getActiveSafetyEventId());
        assertEquals("original-media", reply.getMediaId());
        assertEquals(1, eventCount());
    }

    private int postReport(boolean batch, String events) throws Exception {
        String body = batch ? "{\"event_report\":[" + events + "]}" : events;
        String path = "/task/v1/event_report_camera" + (batch ? "s" : "");
        return json.readTree(mvc.perform(post(path).contentType("application/json").content(body))
            .andReturn().getResponse().getContentAsByteArray()).get("code").asInt();
    }

    @Test
    void manualReceiptRecoveryAndFeedbackRespectThePersistedLink() throws Exception {
        String event = "e" + device.replace("-", "");
        jdbc.update("INSERT INTO oort_task_event (id,`no`,tenant_id,uuid,device_id,mod_type,data)"
            + " VALUES (?,0,'tenant-A',?,?,2,JSON_OBJECT('pics',JSON_ARRAY()))", event, device, device);
        WorkflowOwnershipService ownership = mysqlOwnershipService();
        WorkOrder order = new WorkOrder();
        order.setTenantId("tenant-A");
        order.setEventNumber(event);
        order.setId("real-order");
        order.setProcInsId("real-instance");
        // A previously committed start receipt can repair a missing link, then replay unchanged.
        transaction.executeWithoutResult(tx -> ownership.linkManualOrder(order, true));
        transaction.executeWithoutResult(tx -> ownership.linkManualOrder(order, true));
        assertEquals("real-instance", linkedInstance(event));

        LocationTaskCompatService.UserContext user =
            new LocationTaskCompatService.UserContext("session", "tenant-A", "reviewer", "reviewer", "web");
        Map<String,Object> feedback = new LinkedHashMap<>();
        feedback.put("id", event);
        feedback.put("status", 1);
        feedback.put("mod_status", 2);
        feedback.put("work_order_status", 0);
        assertEquals(200, service.eventBackAdd(feedback, user).getCode());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM oort_task_event WHERE id=?", Integer.class, event).intValue());
        assertEquals(2, jdbc.queryForObject("SELECT mod_status FROM oort_task_event WHERE id=?", Integer.class, event).intValue());
        feedback.put("work_order_status", 1);
        feedback.put("work_order_data", Collections.singletonMap("procInsId", "old-instance"));
        assertEquals(4101, service.eventBackAdd(feedback, user).getCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oort_task_event_back WHERE task_event_id=?", Integer.class, event).intValue());
        assertEquals("real-instance", linkedInstance(event));

        order.setProcInsId("old-instance");
        order.setId("old-order");
        assertThrows(ServiceException.class, () -> transaction.executeWithoutResult(tx -> ownership.linkManualOrder(order, true)));
        assertEquals("real-instance", linkedInstance(event));
        // Historical associations may have persisted the order primary key before storing its workflow instance.
        jdbc.update("UPDATE oort_task_event SET data=JSON_SET(data,'$.work_order_data',JSON_OBJECT('id','real-order')) WHERE id=?", event);
        assertThrows(ServiceException.class, () -> transaction.executeWithoutResult(tx -> ownership.linkManualOrder(order, true)));
        order.setId("real-order");
        order.setProcInsId("real-instance");
        order.setTenantId("tenant-a");
        assertThrows(ServiceException.class, () -> transaction.executeWithoutResult(tx -> ownership.linkManualOrder(order, true)));
        order.setTenantId("tenant-A");
        transaction.executeWithoutResult(tx -> ownership.linkManualOrder(order, true));
        assertEquals("real-instance", linkedInstance(event));
    }

    private String linkedInstance(String event) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(data,'$.work_order_data.procInsId'))"
            + " FROM oort_task_event WHERE id=?", String.class, event);
    }

    @Test
    void onlyConfiguredActorCanRetryAndAnotherTenantCannotReadDispatchDetails() {
        String event = device.replace("-", "");
        String request = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO oort_task_event (id,`no`,tenant_id,uuid,device_id,data)"
            + " VALUES (?,0,'tenant-A',?,?,JSON_OBJECT('pics',JSON_ARRAY()))", event, device, device);
        jdbc.update("INSERT INTO oort_task_event_dispatch"
            + " (request_id,tenant_id,event_id,event_no,rule_no,actor_id,process_definition_id,start_payload,dispatch_status,"
            + " attempts,last_error) VALUES (?,'tenant-A',?,0,1,'operator','definition',JSON_OBJECT(),'RETRY',3,'unavailable')",
            request, event);
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("id", event);
        body.put("request_id", request);
        LocationTaskCompatService.UserContext actor = new LocationTaskCompatService.UserContext(
            "session", "tenant-A", "operator", "operator", "web");
        LocationTaskCompatService.UserContext colleague = new LocationTaskCompatService.UserContext(
            "session", "tenant-A", "viewer", "viewer", "web");
        LocationTaskCompatService.UserContext outsider = new LocationTaskCompatService.UserContext(
            "session", "tenant-a", "operator", "operator", "web");
        com.fasterxml.jackson.databind.JsonNode dispatch = json.valueToTree(
            service.eventInfo(body, actor).getData()).path("work_order_dispatches").get(0);
        assertEquals(request, dispatch.path("request_id").asText());
        assertEquals("unavailable", dispatch.path("last_error").asText());
        assertTrue(dispatch.path("updated_at").asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"));
        assertTrue(dispatch.path("can_retry").asBoolean());
        assertFalse(json.valueToTree(service.eventInfo(body, colleague).getData())
            .at("/work_order_dispatches/0/can_retry").asBoolean(true));
        assertEquals(40001, service.eventInfo(body, outsider).getCode());
        assertEquals(40001, service.retryDispatch(body, outsider).getCode());
        assertEquals(40001, service.retryDispatch(body, colleague).getCode());
        assertEquals(200, service.retryDispatch(body, actor).getCode());
        assertEquals(40001, service.retryDispatch(body, actor).getCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM oort_task_event_dispatch WHERE event_id=?"
            + " AND request_id=? AND dispatch_status='PENDING' AND attempts=3", Integer.class, event, request).intValue());
    }

    private WorkflowOwnershipService mysqlOwnershipService() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(WorkflowOwnershipMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(db);
        factory.setConfiguration(configuration);
        WorkflowOwnershipMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WorkflowOwnershipMapper.class);
        WorkflowOwnershipService ownership = new WorkflowOwnershipService(mapper, null, null, null, null, json);
        ReflectionTestUtils.setField(ownership, "tenantType", "single");
        return ownership;
    }

    private String eventJson(String id) {
        return "{\"device_id\":\"" + id + "\",\"device_name\":\"camera\",\"item\":\"helmet\",\"point\":{}}";
    }

    private int eventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM oort_task_event WHERE device_id=?", Integer.class, device);
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
            start.countDown();
            List<T> values = new ArrayList<>();
            for (Future<T> future : futures) values.add(future.get(60, TimeUnit.SECONDS));
            return values;
        } finally {
            pool.shutdownNow();
        }
    }
}
