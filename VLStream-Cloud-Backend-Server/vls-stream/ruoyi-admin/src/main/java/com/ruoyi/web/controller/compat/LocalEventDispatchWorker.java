package com.ruoyi.web.controller.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.helper.TenantContextHolder;
import com.ruoyi.workflow.domain.bo.ProcessStartBo;
import com.ruoyi.workflow.service.IWfProcessService;
import com.ruoyi.vlstream.test.vlstream.service.DeviceMediaUploadService;
import com.ruoyi.workorder.domain.WorkOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/** Starts local Flowable work orders from committed rule snapshots. */
@Component
@ConditionalOnProperty(prefix = "token", name = "tenant-type", havingValue = "single", matchIfMissing = true)
public class LocalEventDispatchWorker {
    private static final Logger LOG = LoggerFactory.getLogger(LocalEventDispatchWorker.class);
    private static final String DUE = "dispatch_status IN ('PENDING','RETRY') AND next_attempt_at<=CURRENT_TIMESTAMP(3)";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final IWfProcessService workflows;
    private final DeviceMediaUploadService media;
    private final String localTenant;

    public LocalEventDispatchWorker(DataSource source, PlatformTransactionManager tx, ObjectMapper json,
                                    IWfProcessService workflows, DeviceMediaUploadService media,
                                    @Value("${vls.tenant.id:000000}") String localTenant) {
        this.jdbc = new JdbcTemplate(source);
        this.transaction = new TransactionTemplate(tx);
        this.json = json;
        this.workflows = workflows;
        this.media = media;
        this.localTenant = localTenant;
    }

    @Scheduled(fixedDelayString = "${vls.event-dispatch.scan-interval-ms:3000}")
    public void dispatchDue() {
        List<Map<String,Object>> jobs = jdbc.queryForList("SELECT request_id,tenant_id,event_id,event_no,attempts"
            + " FROM oort_task_event_dispatch WHERE tenant_id=? AND " + DUE
            + " ORDER BY next_attempt_at,rule_no,request_id LIMIT 20", localTenant);
        for (Map<String,Object> job : jobs) {
            String previousTenant = TenantContextHolder.getTenantId();
            try {
                TenantContextHolder.setTenantId((String) job.get("tenant_id"));
                transaction.executeWithoutResult(status -> dispatch(job));
            } catch (RuntimeException failure) {
                LOG.warn("Local event workflow dispatch failed for {}", job.get("request_id"), failure);
                String error = failure instanceof ServiceException ? failure.getMessage() : "派单事务失败，请按请求编号查询服务日志";
                if (error == null) error = "流程启动失败";
                jdbc.update("UPDATE oort_task_event_dispatch SET dispatch_status='RETRY',"
                        + " next_attempt_at=TIMESTAMPADD(SECOND,LEAST(1800,5*POWER(2,LEAST(attempts,9))),CURRENT_TIMESTAMP(3)),"
                        + " attempts=attempts+1,last_error=? WHERE request_id=? AND attempts=? AND " + DUE,
                    error.substring(0, Math.min(error.length(), 512)), job.get("request_id"), job.get("attempts"));
            } finally {
                TenantContextHolder.setTenantId(previousTenant);
            }
        }
    }

    private void dispatch(Map<String,Object> job) {
        Object tenant = job.get("tenant_id");
        Object id = job.get("event_id");
        Object no = job.get("event_no");
        Object requestId = job.get("request_id");
        // Serialize starts, feedback, deletion and event completion on the same parent row.
        List<Integer> live = jdbc.queryForList("SELECT 1 FROM oort_task_event"
            + " WHERE id=? AND `no`=? AND tenant_id=? AND deleted_at=0 FOR UPDATE", Integer.class, id, no, tenant);
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT actor_id,start_payload FROM oort_task_event_dispatch"
            + " WHERE request_id=? AND tenant_id=? AND " + DUE + " FOR UPDATE", requestId, tenant);
        if (rows.isEmpty()) return;
        if (live.isEmpty()) {
            jdbc.update("UPDATE oort_task_event_dispatch SET dispatch_status='CANCELLED',last_error='原事件已删除'"
                + " WHERE request_id=?", requestId);
            return;
        }

        Map<String,Object> row = rows.get(0);
        try {
            Object payload = row.get("start_payload");
            ProcessStartBo request = payload instanceof byte[] ? json.readValue((byte[]) payload, ProcessStartBo.class)
                : json.readValue((String) payload, ProcessStartBo.class);
            resolvePictures(request);
            SysUser actor = new SysUser();
            actor.setTenantId((String) tenant);
            actor.setUserId((String) row.get("actor_id"));
            actor.setUserName(actor.getUserId());
            actor.setLoginId(actor.getUserId());
            String instance = workflows.startVerifiedEventWorkOrder(request, actor);
            WorkOrder order = request.getWorkOrder();
            if (order == null || instance == null || !instance.equals(order.getProcInsId())
                || !tenant.equals(order.getTenantId()) || order.getId() == null) {
                throw new IllegalStateException("无法核实派单返回的流程实例");
            }
            jdbc.update("UPDATE oort_task_event_dispatch SET dispatch_status='LINKED',attempts=attempts+1,"
                + "last_error=NULL,proc_inst_id=?,work_order_id=? WHERE request_id=?", instance, order.getId(), requestId);
            jdbc.update("UPDATE oort_task_event SET data=JSON_SET(data,'$.work_order_data',CAST(? AS JSON)),"
                + "mod_status=1,work_order_status=1,updated_at=NOW() WHERE id=? AND `no`=? AND tenant_id=?"
                + " AND work_order_status=0 AND deleted_at=0", json.writeValueAsString(order), id, no, tenant);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("无法读取派单记录", error);
        }
    }

    /** Mint URLs from tenant-scoped media only when the work order actually starts. */
    @SuppressWarnings("unchecked")
    private void resolvePictures(ProcessStartBo request) {
        List<Map<String,Object>> pictures = (List<Map<String,Object>>) request.getVariables().get("image");
        if (pictures == null) return;
        for (Map<String,Object> picture : pictures) {
            String url = (String) picture.get("url");
            if (url != null && url.startsWith("vls-media://")) {
                picture.put("url", media.getPrivateViewUrl(url.substring("vls-media://".length()), 3600));
            }
        }
    }
}
