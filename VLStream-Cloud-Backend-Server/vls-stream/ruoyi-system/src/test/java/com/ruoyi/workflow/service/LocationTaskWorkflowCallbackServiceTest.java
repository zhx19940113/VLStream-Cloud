/*
 * SPDX-FileCopyrightText: 2021 RuoYi-Flowable-Plus
 * SPDX-FileCopyrightText: 2026 OortCloud (https://vls.oortcloudsmart.com/en/)
 * SPDX-License-Identifier: MIT
 * Created by: RuoYi-Flowable-Plus
 * Updated by: ChaoQun Lei
 */

package com.ruoyi.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.workflow.support.EventNumbers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LocationTaskWorkflowCallbackServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final LocationTaskWorkflowCallbackService service = new LocationTaskWorkflowCallbackService(jdbc, new ObjectMapper(), "tenant");

    @Test void recognizesInternalAndLegacyCallbacks() {
        assertTrue(LocationTaskWorkflowCallbackService.supports("internal://location-task/audit-workorder-callback"));
        assertTrue(LocationTaskWorkflowCallbackService.supports("http://location/v1/audit_workorder_callback"));
        assertFalse(LocationTaskWorkflowCallbackService.supports("https://example.invalid/callback"));
    }

    @Test void preservesBothExternalNumberForms() {
        assertEquals("cloud-event", EventNumbers.externalId("cloud-event", 0L));
        assertEquals("event-20260714-7", EventNumbers.externalId("event-20260714", 7L));
        assertEquals(1, EventNumbers.candidates("cloud-event").size());
        assertEquals(2, EventNumbers.candidates("event-20260714-7").size());
    }

    @Test void completedProjectionRecordsHistoryAndAttemptsGuardedClosure() {
        currentProjection("p", "o", null);
        service.applyFinishedOrder(order("COMPLETED"));
        verify(jdbc).update(contains("SET data=JSON_SET"), contains("\"procInsId\":\"p\""), eq("cloud"), eq(0L), eq("tenant"));
        verify(jdbc).update(contains("NOT EXISTS"), eq("cloud"), eq(0L), eq("tenant"));
    }

    @Test void terminatedProjectionRecordsOutcomeAndLeavesEventOpen() {
        currentProjection("p", "o", null);
        service.applyFinishedOrder(order("TERMINATED"));
        verify(jdbc).update(contains("SET data=JSON_SET"), contains("TERMINATED"), eq("cloud"), eq(0L), eq("tenant"));
        verify(jdbc, never()).update(contains("SET status=1"), any(), any(), any());
    }

    @Test void repeatedProjectionDoesNotReplaceItsFinalResultOrCompletionTime() {
        currentProjection("p", "o", "p");
        service.applyFinishedOrder(order("COMPLETED"));
        verify(jdbc, never()).update(contains("SET data=JSON_SET"), any(), any(), any(), any());
        verify(jdbc, never()).update(contains("SET status=1"), any(), any(), any());
    }

    @Test void staleOrderDoesNotCloseReplacementEvenWithTheSameProcessApplication() {
        currentProjection("replacement-process", "new-order", null);
        service.applyFinishedOrder(order("COMPLETED"));
        verify(jdbc, never()).update(contains("SET data=JSON_SET"), any(), any(), any(), any());
        verify(jdbc, never()).update(contains("SET status=1"), any(), any(), any());
    }

    @Test void interruptedWriteIsRetriedByAFreshWorker() {
        LocationTaskWorkflowCallbackService callback = mock(LocationTaskWorkflowCallbackService.class);
        Map<String, Object> pending = order("COMPLETED");
        when(callback.finishedOrders(true, "")).thenReturn(Collections.singletonList(pending));
        doThrow(new IllegalStateException("transaction rolled back")).doNothing().when(callback).applyFinishedOrder(pending);
        new LocationTaskWorkflowResultWorker(callback).reconcile();
        new LocationTaskWorkflowResultWorker(callback).reconcile();
        verify(callback, times(2)).applyFinishedOrder(pending);
    }

    private void currentProjection(String proc, String order, String recorded) {
        Map<String, Object> event = new HashMap<>();
        event.put("instance_id", proc);
        event.put("order_id", order);
        event.put("recorded", recorded);
        when(jdbc.queryForList(contains("FOR UPDATE"), eq("cloud"), eq(0L), eq("tenant")))
            .thenReturn(Collections.singletonList(event));
    }

    private Map<String, Object> order(String result) {
        Map<String, Object> row = new HashMap<>();
        row.put("tenant_id", "tenant");
        row.put("id", "cloud");
        row.put("no", 0L);
        row.put("order_id", "o");
        row.put("proc_ins_id", "p");
        row.put("workorder_id", "application");
        row.put("end_time", 123L);
        row.put("result_status", result);
        return row;
    }
}
