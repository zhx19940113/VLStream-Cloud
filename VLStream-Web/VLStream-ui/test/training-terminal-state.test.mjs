import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { compileScript, parse } from 'vue/compiler-sfc'

const source = readFileSync(new URL('../src/views/AlgorithmTraining/AlgorithmTraining.vue', import.meta.url), 'utf8')

test('the backend stop code is treated as a terminal training state', () => {
  const set = source.match(/const TERMINAL_TRAINING_STATES = new Set\(\[([^\]]+)\]\)/)
  assert.ok(set, 'terminal state set is defined once')
  const states = set[1].split(',').map(value => value.trim().replace(/^'|'$/g, ''))
  assert.ok(states.includes('stop'), 'CloudTrainingWorker maps a confirmed cancel to stop')
  assert.ok(states.includes('stopped'), 'stopped remains accepted for older servers')
  assert.ok(states.includes('completed'))
  assert.ok(states.includes('failed'))
})

test('a localised display label never decides termination', () => {
  assert.ok(!/isFinishedStatus\(displayStatus\)/.test(source), 'status poll must not test the label')
  assert.ok(!/!isCompletedStatus\(displayStatus\)/.test(source), 'finish handler must not test the label')
  assert.ok(!/\|\| isFinishedStatus\(displayStatus\)/.test(source), 'no either-label fallback remains')
})

test('the metrics table reads the same field the row mapping writes', () => {
  const template = source.slice(source.indexOf('<template>'), source.indexOf('<script setup>'))
  assert.ok(!/scope\.row\.mapValue/.test(template), 'template must not read mapValue from a row that stores mAP')
  assert.ok(/scope\.row\.mAP/.test(template), 'template reads mAP which the row mapping writes')
  assert.ok(/metricText\(/.test(template), 'a zero valued metric is rendered, not blanked')
})

test('the row mapping keeps a real zero instead of dropping it', () => {
  assert.ok(!/item\.mapValue \?/.test(source), 'truthiness must not decide metric presence')
  assert.ok(!/item\.precisionValue \?/.test(source))
  assert.ok(!/item\.recallValue \?/.test(source))
})

test('switching tasks drops both delayed responses and stop cancels both current timers', async () => {
  const { descriptor } = parse(source)
  const compiled = compileScript(descriptor, { id: 'training' })
  const names = new Set(['TERMINAL_TRAINING_STATES', 'isFinishedStatus', 'isCompletedStatus',
    'stopLogPolling', 'stopStatusPolling', 'startLogPolling', 'startStatusPolling', 'startPollingForTask',
    'appendLogLines', 'handleTrainingFinished', 'logPollingTicket', 'statusPollingTicket'])
  const code = compiled.scriptSetupAst.filter(node => names.has(node.declarations?.[0]?.id.name))
    .map(node => descriptor.scriptSetup.content.slice(node.start, node.end)).join('\n')
  const requests = { logs: [], status: [] }
  const queue = type => id => new Promise(resolve => requests[type].push({ id, resolve }))
  const lines = []
  const timers = new Set()
  const ref = value => ({ value })
  const state = { ref, getTrainingLogs: queue('logs'), getTrainingStatus: queue('status'),
    setInterval: fn => { timers.add(fn); return fn }, clearInterval: fn => timers.delete(fn),
    getTrainStatusText: String, pushTerminalLine: line => lines.push(line), scrollToBottom() {},
    appendTerminalInfo: line => lines.push(line), loadTrainingData() {}, finalizingTrainingTasks: new Set(),
    tableData: ref([]), isTraining: ref(true), logPollingTimer: ref(null), statusPollingTimer: ref(null) }
  for (const name of ['trainingDialogTaskId', 'lastLogContent', 'lastLogCount', 'lastSnapshotLines', 'lastLogLinesMerged']) {
    state[name] = ref(null)
  }
  const start = runInNewContext(code + '\nstartPollingForTask', state)
  start('A')
  start('B')
  for (const tick of timers) tick()
  for (const queue of Object.values(requests)) assert.deepEqual(queue.map(item => item.id), ['A', 'B'])
  requests.logs[0].resolve({ logContent: 'stale' })
  requests.status[0].resolve({ status: 'completed', message: 'stale' })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(timers.size, 2)
  assert.equal(lines.includes('stale'), false)
  requests.status[1].resolve({ status: 'stop' })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(timers.size, 0)
  assert.equal(state.isTraining.value, false)
  assert.equal(state.finalizingTrainingTasks.has('A'), false)
  assert.equal(state.finalizingTrainingTasks.has('B'), true)
  requests.logs[1].resolve({ logContent: 'late' })
  await new Promise(resolve => setImmediate(resolve))
})

test('the log window appends the incremental tail and is bounded', () => {
  assert.ok(/incrementalTail\(/.test(source), 'overlap-aware append is used')
  assert.ok(!/v-html="terminalOutput"/.test(source), 'the unbounded markup terminal is gone')
  assert.ok(/boundWindow\(/.test(source), 'the retained window is bounded')
  assert.ok(/TERMINAL_WINDOW_LINES = \d+/.test(source))
})
