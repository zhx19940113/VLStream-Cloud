<template>
  <div v-if="item.work_order_dispatches?.length">
    <el-table :data="item.work_order_dispatches" row-key="request_id">
      <el-table-column prop="process_definition_id" :label="$tp('流程定义')" />
      <el-table-column :label="$tp('状态')">
        <template #default="{ row }">{{ $tp(labels[row.result_status || row.dispatch_status] || row.dispatch_status) }}</template>
      </el-table-column>
      <el-table-column prop="proc_inst_id" :label="$tp('流程实例')" />
      <el-table-column :label="$tp('处理人')">
        <template #default="{ row }">{{ row.result_data?.assigneeName || row.result_data?.assigneeId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="updated_at" :label="$tp('更新时间')" />
      <el-table-column prop="last_error" :label="$tp('失败原因')" />
      <el-table-column :label="$tp('操作')">
        <template #default="{ row }">
          <el-button v-if="row.proc_inst_id" link @click="instance = row.proc_inst_id">{{ $tp('查看流程') }}</el-button>
          <el-button v-if="row.can_retry" link :disabled="!!retrying" @click="retry(row.request_id)">{{ $tp('重试') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button link @click="$emit('refresh')">{{ $tp('刷新') }}</el-button>
    <el-button v-if="instance && item.work_order_data?.procInsId && instance !== item.work_order_data.procInsId"
      link @click="instance = ''">{{ $tp('查看当前工单') }}</el-button>
  </div>
  <el-descriptions v-if="outcome" :column="2" border>
    <el-descriptions-item :label="$tp('办理结果')">{{ $tp(labels[outcome.resultStatus] || outcome.fullMessage || '已处理') }}</el-descriptions-item>
    <el-descriptions-item :label="$tp('处理人')">{{ outcome.assigneeName || outcome.assigneeId || '—' }}</el-descriptions-item>
    <el-descriptions-item v-if="outcome.time" :label="$tp('完成时间')">{{ new Date(outcome.time).toLocaleString() }}</el-descriptions-item>
    <el-descriptions-item v-if="outcome.fullMessage" :label="$tp('意见/反馈')">{{ outcome.fullMessage }}</el-descriptions-item>
  </el-descriptions>
  <WorkOrderRecord v-if="order?.procInsId" :key="order.procInsId" :item="{ work_order_data: order }" />
  <el-empty v-else-if="!item.work_order_dispatches?.length" :description="$tp('暂无工单')" />
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { translatePhrase } from '@/i18n'
import { retryEventDispatch } from '@/api/smartCity/events'
import WorkOrderRecord from './workOrderRecord.vue'

const props = defineProps({ item: { type: Object, required: true } })
const emit = defineEmits(['refresh'])
const labels = { PENDING: '待派单', RETRY: '等待重试', LINKED: '已派单', INVALID: '配置无效',
  CANCELLED: '已取消', COMPLETED: '已完成', TERMINATED: '已终止', CANCELED: '已取消' }
const instance = ref('')
const retrying = ref('')
watch(() => props.item.id, () => { instance.value = '' })
const order = computed(() => {
  if (instance.value && props.item.work_order_dispatches?.some(row => row.proc_inst_id === instance.value)) {
    return { procInsId: instance.value }
  }
  return props.item.work_order_data
})
const outcome = computed(() => {
  const id = order.value?.procInsId
  if (!id) return null
  const result = props.item.work_order_dispatches?.find(row => row.proc_inst_id === id)?.result_data
  return result || (props.item.work_order_call_back?.procInsId === id ? props.item.work_order_call_back : null)
})
async function retry(requestId) {
  if (retrying.value) return
  retrying.value = requestId
  try {
    const result = await retryEventDispatch({ id: props.item.id, request_id: requestId })
    if (result?.code !== 200) throw new Error()
    emit('refresh')
  } catch {
    ElMessage.error(translatePhrase('派单重试失败，请刷新后核实'))
  } finally {
    retrying.value = ''
  }
}
</script>
