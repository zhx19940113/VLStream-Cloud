package com.ruoyi.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "token", name = "tenant-type", havingValue = "single", matchIfMissing = true)
public class LocationTaskWorkflowResultWorker {
    private static final Logger LOG = LoggerFactory.getLogger(LocationTaskWorkflowResultWorker.class);
    private final LocationTaskWorkflowCallbackService service;
    private String dispatchCursor = "";
    private String projectionCursor = "";

    public LocationTaskWorkflowResultWorker(LocationTaskWorkflowCallbackService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${vls.event-workflow.result-poll-ms:5000}")
    public void reconcile() {
        dispatchCursor = reconcilePage(true, dispatchCursor);
        projectionCursor = reconcilePage(false, projectionCursor);
    }

    private String reconcilePage(boolean dispatch, String afterOrder) {
        List<Map<String, Object>> orders = service.finishedOrders(dispatch, afterOrder);
        for (Map<String, Object> order : orders) {
            try {
                service.applyFinishedOrder(order);
            } catch (RuntimeException failure) {
                LOG.warn("工单办理结果等待重试，流程实例={}", order.get("proc_ins_id"), failure);
            }
        }
        return orders.size() == LocationTaskWorkflowCallbackService.PAGE_SIZE
            ? orders.get(orders.size() - 1).get("order_id").toString() : "";
    }
}
