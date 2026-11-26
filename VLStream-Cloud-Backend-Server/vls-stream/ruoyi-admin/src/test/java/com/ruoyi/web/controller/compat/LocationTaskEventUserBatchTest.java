/*
 * SPDX-FileCopyrightText: 2021 RuoYi-Flowable-Plus
 * SPDX-FileCopyrightText: 2026 OortCloud (https://vls.oortcloudsmart.com/en/)
 * SPDX-License-Identifier: MIT
 * Created by: RuoYi-Flowable-Plus
 * Updated by: ChaoQun Lei
 */

package com.ruoyi.web.controller.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The page executor loader seeds every key, issues one IN query, and keeps created_at order.
 *
 * <p>Pure grouping is exercised on a stand-in JdbcTemplate so the assertion is about the shape of
 * the batched lookup, not about a database dialect.
 */
@Tag("dev")
class LocationTaskEventUserBatchTest {

    @Test
    void oneQueryCoversTheWholePageAndMissingExecutorsStayEmpty() {
        final List<String> issued = new ArrayList<String>();
        final List<Object> bound = new ArrayList<Object>();
        List<Map<String, Object>> executors = new ArrayList<Map<String, Object>>();
        Map<String, Object> first = new HashMap<String, Object>();
        first.put("task_event_id", "event-a-1"); first.put("uuid", "operator"); first.put("u_type", 1);
        Map<String, Object> second = new HashMap<String, Object>();
        second.put("task_event_id", "event-a-1"); second.put("uuid", "vehicle"); second.put("u_type", 2);
        executors.add(first); executors.add(second);

        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                issued.add(sql);
                bound.addAll(Arrays.asList(args));
                return executors;
            }
        };
        LocationTaskCompatService service = new LocationTaskCompatService(mock(DataSource.class),
            new ObjectMapper(), mock(com.ruoyi.workflow.service.IWfProcessService.class),
            new LocationTaskWorkflowProperties());
        ReflectionTestUtils.setField(service, "jdbc", jdbc);

        Map<String, Object> pageEvent = new HashMap<String, Object>();
        pageEvent.put("id", "event-a"); pageEvent.put("no", 1);
        Map<String, Object> silentEvent = new HashMap<String, Object>();
        silentEvent.put("id", "event-b"); silentEvent.put("no", 2);

        Map<String, List<Map<String, Object>>> grouped = ReflectionTestUtils.invokeMethod(
            service, "eventUsers", Arrays.asList(pageEvent, silentEvent));

        assertEquals(1, issued.size(), "the page issues exactly one executor query");
        assertTrue(issued.get(0).contains("IN (?,?)"), "the query binds every event of the page");
        for (Object key : bound) assertTrue(key instanceof String && ((String) key).startsWith("event-"));
        assertEquals(2, grouped.get("event-a-1").size());
        assertEquals("operator", grouped.get("event-a-1").get(0).get("uuid"));
        assertEquals(1, grouped.get("event-a-1").get(0).get("u_type"));
        assertEquals("vehicle", grouped.get("event-a-1").get(1).get("uuid"));
        assertEquals(2, grouped.get("event-a-1").get(1).get("u_type"));
        // Event B has no executor rows; its key still exists with an empty array.
        assertEquals(Collections.emptyList(), grouped.get("event-b-2"));
    }
}
