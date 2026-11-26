/*
 * SPDX-FileCopyrightText: 2021 RuoYi-Flowable-Plus
 * SPDX-FileCopyrightText: 2026 OortCloud (https://vls.oortcloudsmart.com/en/)
 * SPDX-License-Identifier: MIT
 * Created by: RuoYi-Flowable-Plus
 * Updated by: ChaoQun Lei
 */

package com.ruoyi.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.workflow.support.EventNumbers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reconciles committed Flowable history with the linked event and its dispatches. */
@Service
public class LocationTaskWorkflowCallbackService {
    static final int PAGE_SIZE = 50;
    private static final String PROJECTED_INSTANCE = "JSON_UNQUOTE(JSON_EXTRACT(e.data,'$.work_order_data.procInsId'))";
    private static final String PROJECTED_ORDER = "JSON_UNQUOTE(JSON_EXTRACT(e.data,'$.work_order_data.id'))";
    private static final String RECORDED_INSTANCE = "JSON_UNQUOTE(JSON_EXTRACT(e.data,'$.work_order_call_back.procInsId'))";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    private final String tenantId;

    public LocationTaskWorkflowCallbackService(JdbcTemplate jdbc, ObjectMapper json,
        @Value("${vls.tenant.id:000000}") String tenantId) {
        this.tenantId = tenantId;
        this.jdbc = jdbc;
        this.json = json;
    }

    public static boolean supports(String url) {
        return url != null && ("internal://location-task/audit-workorder-callback".equals(url)
            || url.contains("/v1/audit_workorder_callback"));
    }

    // Read only durable, finished instances. A process which ends before its link is saved
    // becomes eligible on the next pass, including after a platform restart.
    public List<Map<String, Object>> finishedOrders(boolean dispatch, String afterOrder) {
        String links = dispatch
            ? "FROM oort_task_event_dispatch d JOIN oort_task_event e ON e.id=d.event_id AND e.`no`=d.event_no"
              + " AND BINARY e.tenant_id=BINARY d.tenant_id JOIN work_order w ON w.id=d.work_order_id"
              + " AND BINARY w.proc_ins_id=BINARY d.proc_inst_id "
            : "FROM oort_task_event e JOIN work_order w ON w.proc_ins_id=" + PROJECTED_INSTANCE
              + " AND (" + PROJECTED_ORDER + " IS NULL OR BINARY w.id=BINARY " + PROJECTED_ORDER + ") ";
        String pending = dispatch
            ? "d.dispatch_status='LINKED' AND d.result_status IS NULL"
            : "e.work_order_status=1 AND NOT (BINARY " + RECORDED_INSTANCE + " <=> BINARY w.proc_ins_id)";
        return jdbc.queryForList("SELECT e.id,e.`no`,e.tenant_id,w.id AS order_id,w.proc_ins_id,w.workorder_id,"
            + "ROUND(UNIX_TIMESTAMP(p.END_TIME_) * 1000) AS end_time,UPPER(v.TEXT_) AS result_status " + links
            + "JOIN ACT_HI_PROCINST p ON p.PROC_INST_ID_=BINARY w.proc_ins_id AND p.END_TIME_ IS NOT NULL"
            + " AND BINARY p.TENANT_ID_=BINARY w.tenant_id JOIN ACT_HI_VARINST v ON v.PROC_INST_ID_=p.PROC_INST_ID_"
            + " AND v.EXECUTION_ID_=p.PROC_INST_ID_ AND v.TASK_ID_ IS NULL AND v.NAME_='processStatus'"
            + " WHERE v.TEXT_ IN ('completed','terminated','canceled')"
            + " AND (v.TEXT_<>'completed' OR p.DELETE_REASON_ IS NULL)"
            + " AND BINARY e.tenant_id=BINARY ? AND e.tenant_id<>'' AND BINARY w.tenant_id=BINARY e.tenant_id"
            + " AND BINARY w.event_number=BINARY IF(e.`no`=0,e.id,CONCAT(e.id,'-',e.`no`))"
            + " AND e.deleted_at=0 AND w.del_flag='0' AND " + pending
            + " AND w.id>? ORDER BY w.id LIMIT " + PAGE_SIZE, tenantId, afterOrder);
    }

    @Transactional(rollbackFor = Exception.class)
    public void applyFinishedOrder(Map<String, Object> order) {
        Object id = order.get("id");
        Object no = order.get("no");
        Object tenant = order.get("tenant_id");
        if (!tenantId.equals(tenant)) return;
        Object instance = order.get("proc_ins_id");
        Object orderId = order.get("order_id");
        String status = order.get("result_status").toString();
        List<Map<String, Object>> events = jdbc.queryForList("SELECT " + PROJECTED_INSTANCE + " AS instance_id,"
            + PROJECTED_ORDER + " AS order_id," + RECORDED_INSTANCE + " AS recorded FROM oort_task_event e"
            + " WHERE id=? AND `no`=? AND BINARY tenant_id=BINARY ? AND deleted_at=0 FOR UPDATE", id, no, tenant);
        if (events.isEmpty()) {
            return;
        }
        Map<String, Object> event = events.get(0);
        boolean projected = instance.equals(event.get("instance_id"))
            && (event.get("order_id") == null || orderId.equals(event.get("order_id")));
        String callback = callbackData(order);
        int saved = jdbc.update("UPDATE oort_task_event_dispatch SET result_status=?, result_data=CAST(? AS JSON),"
            + "updated_at=NOW() WHERE tenant_id=? AND event_id=? AND event_no=? AND work_order_id=?"
            + " AND proc_inst_id=? AND dispatch_status='LINKED' AND result_status IS NULL",
            status, callback, tenant, id, no, orderId, instance);
        if (projected && !instance.equals(event.get("recorded"))) {
            jdbc.update("UPDATE oort_task_event SET data=JSON_SET(data,'$.work_order_call_back',CAST(? AS JSON)),"
                + " updated_at=NOW() WHERE id=? AND `no`=? AND tenant_id=?", callback, id, no, tenant);
        } else if (saved == 0) {
            return;
        }
        if ("COMPLETED".equals(status)) {
            jdbc.update("UPDATE oort_task_event e SET status=1,data=JSON_SET(data,'$.finish_at',"
                + "DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s')),updated_at=NOW()"
                + " WHERE e.id=? AND e.`no`=? AND e.tenant_id=? AND e.status=2 AND ("
                + PROJECTED_INSTANCE + " IS NULL OR (BINARY " + PROJECTED_INSTANCE + "=BINARY " + RECORDED_INSTANCE
                + " AND JSON_UNQUOTE(JSON_EXTRACT(e.data,'$.work_order_call_back.resultStatus'))='COMPLETED'))"
                + " AND NOT EXISTS (SELECT 1 FROM oort_task_event_dispatch d WHERE d.event_id=e.id"
                + " AND d.event_no=e.`no` AND BINARY d.tenant_id=BINARY e.tenant_id"
                + " AND (d.result_status IS NULL OR d.result_status<>'COMPLETED'))", id, no, tenant);
        }
    }

    private String callbackData(Map<String, Object> order) {
        List<Map<String, Object>> tasks = jdbc.queryForList("SELECT t.ASSIGNEE_ AS actor,"
            + "COALESCE(NULLIF(u.nick_name,''),u.user_name) AS actor_name,"
            + "(SELECT c.MESSAGE_ FROM ACT_HI_COMMENT c WHERE c.TASK_ID_=t.ID_ AND c.ACTION_='AddComment'"
            + " ORDER BY c.TIME_ DESC,c.ID_ DESC LIMIT 1) AS message FROM ACT_HI_TASKINST t"
            + " LEFT JOIN sys_user u ON u.user_id=BINARY t.ASSIGNEE_ AND BINARY u.tenant_id=BINARY t.TENANT_ID_ AND u.del_flag='0'"
            + " WHERE t.PROC_INST_ID_=? AND BINARY t.TENANT_ID_=BINARY ? AND t.END_TIME_ IS NOT NULL"
            + " ORDER BY t.END_TIME_ DESC,t.ID_ DESC LIMIT 1", order.get("proc_ins_id"), order.get("tenant_id"));
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("no", EventNumbers.externalId(order.get("id").toString(), ((Number) order.get("no")).longValue()));
        callback.put("workorderId", order.get("workorder_id"));
        callback.put("orderId", order.get("order_id"));
        callback.put("procInsId", order.get("proc_ins_id"));
        callback.put("resultStatus", order.get("result_status"));
        callback.put("time", ((Number) order.get("end_time")).longValue());
        if (!tasks.isEmpty()) {
            callback.put("assigneeId", tasks.get(0).get("actor"));
            callback.put("assigneeName", tasks.get(0).get("actor_name"));
            callback.put("fullMessage", tasks.get(0).get("message"));
        }
        try {
            return json.writeValueAsString(callback);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化事件工单办理结果", e);
        }
    }
}
