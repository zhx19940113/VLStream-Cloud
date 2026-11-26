-- Atomic per-day, per-user event numbering for oort_task_event.
-- The previous COUNT(*)+1 allocation let two concurrent writers receive the same `no`;
-- the composite primary key (id, no) then rejected one of two legitimate events.
CREATE TABLE IF NOT EXISTS `oort_task_event_number_scope` (
  `scope` char(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'id prefix, e.g. zhangsan-20260714',
  `last_no` int NOT NULL DEFAULT '0' COMMENT 'Highest number already handed out by this scope',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Event number allocator scope';

-- Seed from existing rows so allocation continues after the current maximum instead of
-- colliding with historical numbers. The event `id` column already stores the allocator
-- scope (user name plus day, for example "zhangsan-20260714"), so it is used unchanged.
-- Scopes without rows start at one on first allocation.
INSERT INTO `oort_task_event_number_scope` (`scope`, `last_no`)
SELECT t.id, MAX(t.`no`)
FROM `oort_task_event` t
GROUP BY t.id
ON DUPLICATE KEY UPDATE `last_no` = GREATEST(`last_no`, VALUES(`last_no`));
