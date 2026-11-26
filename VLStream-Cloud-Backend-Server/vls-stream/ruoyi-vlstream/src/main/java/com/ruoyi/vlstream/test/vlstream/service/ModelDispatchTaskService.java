/*
 * SPDX-FileCopyrightText: 2021 RuoYi-Flowable-Plus
 * SPDX-FileCopyrightText: 2026 OortCloud (https://vls.oortcloudsmart.com/en/)
 * SPDX-License-Identifier: MIT
 * Created by: RuoYi-Flowable-Plus
 * Updated by: ChaoQun Lei
 */

package com.ruoyi.vlstream.test.vlstream.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ruoyi.vlstream.test.vlstream.mapper.VlsModelDispatchTaskMapper;
import com.ruoyi.vlstream.test.vlstream.pojo.entity.ModelDispatchTask;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * Stores dispatch progress reported by the platform and field devices.
 */
@Service
public class ModelDispatchTaskService {

	@Resource
	private VlsModelDispatchTaskMapper taskMapper;

	public void create(ModelDispatchTask task) {
		task.setRetryCount(0);
		taskMapper.insert(task);
	}

	public ModelDispatchTask getByRequestId(String requestId) {
		if (StringUtils.isBlank(requestId)) {
			return null;
		}
		return taskMapper.selectOne(new LambdaQueryWrapper<ModelDispatchTask>()
			.eq(ModelDispatchTask::getRequestId, requestId)
			.last("limit 1"));
	}

	public List<ModelDispatchTask> listRecent(String deviceId, String dispatchStatus, int limit) {
		LambdaQueryWrapper<ModelDispatchTask> query = new LambdaQueryWrapper<ModelDispatchTask>()
			.eq(StringUtils.isNotBlank(deviceId), ModelDispatchTask::getDeviceId, deviceId)
			.eq(StringUtils.isNotBlank(dispatchStatus), ModelDispatchTask::getDispatchStatus, dispatchStatus)
			.orderByDesc(ModelDispatchTask::getCreateTime)
			.last("limit " + Math.max(1, Math.min(limit, 200)));
		return taskMapper.selectList(query);
	}

	public void markPublished(String requestId, String topic) {
		updateStatus(requestId, "PUBLISHED", null, null, new Date(), null, null);
		taskMapper.update(null, new LambdaUpdateWrapper<ModelDispatchTask>()
			.eq(ModelDispatchTask::getRequestId, requestId)
			.set(ModelDispatchTask::getMqttTopic, topic));
	}

	public void markDownloadStarted(String requestId) {
		updateStatus(requestId, "DOWNLOADING", null, null, null, new Date(), null);
	}

	public void markDownloadCompleted(String requestId) {
		updateStatus(requestId, "DOWNLOADED", null, null, null, null, new Date());
	}

	public void markFailed(String requestId, String reason) {
		updateStatus(requestId, "FAILED", reason, null, null, null, null);
	}

    public void markUncertain(String requestId, String reason) {
        updateStatus(requestId, "UNKNOWN", reason, null, null, null, null);
    }

    public void requireNoActiveTask(String deviceId) {
        Long count = taskMapper.selectCount(new LambdaQueryWrapper<ModelDispatchTask>()
            .eq(ModelDispatchTask::getDeviceId, deviceId)
            .notIn(ModelDispatchTask::getDispatchStatus, "SUCCESS", "FAILED"));
        if (count != null && count > 0) throw new com.ruoyi.common.exception.ServiceException("设备存在进行中或结果未确认的模型任务，请先核实");
    }

	public boolean applyHardwareReply(String sourceMsgId, String requestId, String deviceId,
									 String status, String fileSha256,
									 String message, String rawPayload) {
		return applyHardwareReply(sourceMsgId, requestId, deviceId, status, fileSha256, null, message, rawPayload);
	}

	public boolean applyHardwareReply(String sourceMsgId, String requestId, String deviceId,
		String status, String fileSha256, String classFileSha256, String message, String rawPayload) {
		String normalizedStatus = normalizeHardwareStatus(status);
		if (normalizedStatus == null) {
			return false;
		}
		// A reply can lose the race against a concurrent progress update: both read the same old
		// status, so a conditional update on that stale value affects zero rows. Re-read the row and
		// let the state machine decide again from the newest status. Identity and digest checks run
		// against every fresh copy, so a retry can never accept a mismatched message.
		for (int attempt = 0; attempt < 3; attempt++) {
			ModelDispatchTask task = getByRequestId(requestId);
			if (task == null
				|| !StringUtils.equals(task.getMqttMessageId(), sourceMsgId)
				|| !StringUtils.equals(task.getDeviceId(), deviceId)) {
				return false;
			}
			String outcome = normalizedStatus;
			String outcomeMessage = message;
			if ("SUCCESS".equals(outcome)
				&& !StringUtils.equalsIgnoreCase(task.getSha256(), StringUtils.trimToEmpty(fileSha256))) {
				outcome = "FAILED";
				outcomeMessage = "Device model SHA-256 mismatch: expected=" + task.getSha256()
					+ ", actual=" + StringUtils.defaultIfBlank(fileSha256, "empty");
			} else if ("SUCCESS".equals(outcome) && StringUtils.isNotBlank(task.getClassFileSha256())
				&& !StringUtils.equalsIgnoreCase(task.getClassFileSha256(), StringUtils.trimToEmpty(classFileSha256))) {
				outcome = "FAILED";
				outcomeMessage = "Device class file SHA-256 mismatch or missing";
			}
			if (!canApplyHardwareStatus(task.getDispatchStatus(), outcome)) {
				return true;
			}
			if ("SUCCESS".equals(task.getDispatchStatus()) || "FAILED".equals(task.getDispatchStatus())) return true;
			Date now = new Date();
			LambdaUpdateWrapper<ModelDispatchTask> update = new LambdaUpdateWrapper<ModelDispatchTask>()
				.eq(ModelDispatchTask::getRequestId, requestId)
				.eq(ModelDispatchTask::getDispatchStatus, task.getDispatchStatus())
				.set(ModelDispatchTask::getDispatchStatus, outcome)
				.set(ModelDispatchTask::getLastReplyAt, now)
				.set(ModelDispatchTask::getReplyPayload, StringUtils.abbreviate(rawPayload, 8000))
				.set(ModelDispatchTask::getFailureReason,
					"FAILED".equals(outcome) ? StringUtils.abbreviate(outcomeMessage, 2000) : null);
			if ("FAILED".equals(outcome)) {
				update.ne(ModelDispatchTask::getDispatchStatus, "SUCCESS");
			} else if ("SUCCESS".equals(outcome)) {
				update.ne(ModelDispatchTask::getDispatchStatus, "FAILED");
			} else {
				update.notIn(ModelDispatchTask::getDispatchStatus, "SUCCESS", "FAILED");
			}
			if ("SUCCESS".equals(outcome)) {
				update.set(ModelDispatchTask::getDeployedAt, now);
			}
			if (taskMapper.update(null, update) > 0) {
				return true;
			}
		}
		return false;
	}

	private void updateStatus(String requestId, String status, String failureReason, String replyPayload,
							  Date publishedAt, Date downloadStartedAt, Date downloadCompletedAt) {
		LambdaUpdateWrapper<ModelDispatchTask> update = new LambdaUpdateWrapper<ModelDispatchTask>()
			.eq(ModelDispatchTask::getRequestId, requestId)
			.set(ModelDispatchTask::getDispatchStatus, status)
			.set(ModelDispatchTask::getFailureReason,
				failureReason == null ? null : StringUtils.abbreviate(failureReason, 2000));
		if ("FAILED".equals(status)) {
			update.ne(ModelDispatchTask::getDispatchStatus, "SUCCESS");
		} else {
			update.notIn(ModelDispatchTask::getDispatchStatus, "SUCCESS", "FAILED");
		}
        if ("PUBLISHED".equals(status) || "UNKNOWN".equals(status)) {
            update.in(ModelDispatchTask::getDispatchStatus, "CREATED", "PUBLISHED", "UNKNOWN");
        } else if ("DOWNLOADING".equals(status)) {
            update.in(ModelDispatchTask::getDispatchStatus, "CREATED", "PUBLISHED", "UNKNOWN", "RECEIVED", "DOWNLOADING");
        } else if ("DOWNLOADED".equals(status)) {
            update.in(ModelDispatchTask::getDispatchStatus, "CREATED", "PUBLISHED", "UNKNOWN", "RECEIVED", "DOWNLOADING", "DOWNLOADED");
        }
		if (replyPayload != null) {
			update.set(ModelDispatchTask::getReplyPayload, StringUtils.abbreviate(replyPayload, 8000));
		}
		if (publishedAt != null) {
			update.set(ModelDispatchTask::getPublishedAt, publishedAt);
		}
		if (downloadStartedAt != null) {
			update.set(ModelDispatchTask::getDownloadStartedAt, downloadStartedAt);
		}
		if (downloadCompletedAt != null) {
			update.set(ModelDispatchTask::getDownloadCompletedAt, downloadCompletedAt);
		}
		taskMapper.update(null, update);
	}

	private boolean canApplyHardwareStatus(String currentStatus, String incomingStatus) {
		String current = StringUtils.upperCase(StringUtils.trimToEmpty(currentStatus));
		if ("SUCCESS".equals(current) || "FAILED".equals(current)) {
			return current.equals(incomingStatus);
		}
		if ("FAILED".equals(incomingStatus)) {
			return true;
		}
		return statusOrder(incomingStatus) >= statusOrder(current);
	}

	private int statusOrder(String status) {
		switch (StringUtils.upperCase(StringUtils.trimToEmpty(status))) {
			case "RECEIVED":
				return 1;
			case "DOWNLOADING":
				return 2;
			case "DOWNLOADED":
				return 3;
			case "VERIFYING":
				return 4;
			case "DEPLOYING":
				return 5;
			case "SUCCESS":
				return 6;
			default:
				return 0;
		}
	}

	private String normalizeHardwareStatus(String status) {
		String normalized = StringUtils.upperCase(StringUtils.trimToEmpty(status));
		switch (normalized) {
			case "RECEIVED":
			case "DOWNLOADING":
			case "DOWNLOADED":
			case "VERIFYING":
			case "DEPLOYING":
			case "SUCCESS":
			case "FAILED":
				return normalized;
			default:
				return null;
		}
	}
}
