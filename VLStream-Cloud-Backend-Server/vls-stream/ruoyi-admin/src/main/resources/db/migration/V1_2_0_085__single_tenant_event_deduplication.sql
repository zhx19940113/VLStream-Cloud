-- Legacy rows retain their JSON identifiers and are found through the existing lookup.
-- Nullable keys allow HTTP events without device identifiers and preserve historical duplicates.
ALTER TABLE oort_task_event
  ADD COLUMN mqtt_message_hash BINARY(32) NULL,
  ADD COLUMN device_event_hash BINARY(32) NULL,
  ADD UNIQUE KEY uq_task_event_mqtt (tenant_id,device_id,mqtt_message_hash,deleted_at),
  ADD UNIQUE KEY uq_task_event_device_event (tenant_id,device_id,device_event_hash,deleted_at);
