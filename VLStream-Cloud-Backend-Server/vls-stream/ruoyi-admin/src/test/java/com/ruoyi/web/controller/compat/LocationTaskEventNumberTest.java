/*
 * SPDX-FileCopyrightText: 2021 RuoYi-Flowable-Plus
 * SPDX-FileCopyrightText: 2026 OortCloud (https://vls.oortcloudsmart.com/en/)
 * SPDX-License-Identifier: MIT
 * Created by: RuoYi-Flowable-Plus
 * Updated by: ChaoQun Lei
 */

package com.ruoyi.web.controller.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.vlstream.test.vlstream.service.DeviceMediaUploadService;
import com.ruoyi.workflow.service.IWfProcessService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Concurrent allocation and historical-series continuation for oort_task_event numbers. */
@Tag("dev")
class LocationTaskEventNumberTest {

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private LocationTaskCompatService service;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:eventnumber_" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_MODE=3");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE oort_task_event_number_scope (scope VARCHAR(64) PRIMARY KEY, "
            + "last_no INT NOT NULL DEFAULT 0, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        LocationTaskCompatService target = new LocationTaskCompatService(source, new ObjectMapper(),
            org.mockito.Mockito.mock(IWfProcessService.class),
            org.mockito.Mockito.mock(LocationTaskWorkflowProperties.class));
        ReflectionTestUtils.setField(target, "deviceMediaUploadService",
            org.mockito.Mockito.mock(DeviceMediaUploadService.class));
        service = target;
    }

    @Test
    void aFreshScopeStartsWithOne() {
        assertEquals(1L, allocate("zhangsan-20260714"));
        assertEquals(2L, allocate("zhangsan-20260714"));
    }

    @Test
    void separateScopesDoNotShareACounter() {
        assertEquals(1L, allocate("zhangsan-20260714"));
        assertEquals(1L, allocate("lisi-20260714"));
        assertEquals(1L, allocate("zhangsan-20260715"));
    }

    @Test
    void allocationContinuesAfterASeededHistoricalMaximum() {
        jdbc.update("INSERT INTO oort_task_event_number_scope(scope,last_no) VALUES('seeded-20260714',5)");
        assertEquals(6L, allocate("seeded-20260714"));
    }

    @Test
    void concurrentCallersNeverShareANumber() throws Exception {
        int callers = 20;
        int each = 5;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<Future<List<Long>>>();
        for (int index = 0; index < callers; index++) {
            futures.add(pool.submit(() -> {
                start.await();
                List<Long> values = new ArrayList<Long>();
                for (int round = 0; round < each; round++) {
                    values.add(allocate("shared-20260714"));
                }
                return values;
            }));
        }
        start.countDown();
        List<Long> all = new ArrayList<Long>();
        for (Future<List<Long>> future : futures) all.addAll(future.get(60, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(hashSetOf(all).size(), all.size(), "every allocated number is distinct");
        assertEquals(callers * each, all.size());
        Collections.sort(all);
        assertEquals(1L, all.get(0).longValue(), "numbers form one continuous series");
        assertEquals((long) all.size(), all.get(all.size() - 1).longValue());
    }

    private long allocate(String scope) {
        return transaction.execute(status ->
            ReflectionTestUtils.<Long>invokeMethod(service, "allocateEventNumber", scope));
    }

    private static Set<Long> hashSetOf(List<Long> values) {
        return new HashSet<Long>(values);
    }
}
