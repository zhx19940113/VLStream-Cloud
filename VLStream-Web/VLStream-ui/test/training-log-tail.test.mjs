import test from 'node:test'
import assert from 'node:assert/strict'
import { incrementalTail, boundWindow } from '../src/utils/trainingLogTail.mjs'

const lines = (from, to) => Array.from({ length: to - from + 1 }, (_, offset) => `line-${from + offset}`)

test('a rolling tail that moves by one line appends only the new line', () => {
  const first = lines(1, 200)
  const second = lines(2, 201)
  assert.deepEqual(incrementalTail(first, second), ['line-201'])
})

test('a window that advances by many lines appends exactly the uncovered suffix', () => {
  const first = lines(1, 200)
  const second = lines(60, 259)
  assert.deepEqual(incrementalTail(first, second), lines(201, 259))
})

test('an unchanged snapshot or a stale snapshot appends nothing', () => {
  const first = lines(1, 200)
  assert.deepEqual(incrementalTail(first, lines(1, 200)), [])
  assert.deepEqual(incrementalTail(first, lines(1, 150)), [])
})

test('the very first snapshot is appended in full', () => {
  assert.deepEqual(incrementalTail([], lines(1, 3)), lines(1, 3))
})

test('a fully replaced window appends everything exactly once', () => {
  assert.deepEqual(incrementalTail(lines(1, 3), lines(900, 902)), lines(900, 902))
})

test('a repeated line does not duplicate the overlap', () => {
  const repeated = ['error', 'error', 'error', 'error']
  assert.deepEqual(incrementalTail(['error', 'error', 'error'], repeated), ['error'])
})

test('the retained window is capped by dropping the oldest lines', () => {
  assert.deepEqual(boundWindow(lines(1, 5), 3), lines(3, 5))
  assert.deepEqual(boundWindow(lines(1, 5), 50), lines(1, 5))
  assert.deepEqual(boundWindow(lines(1, 5), 0), lines(5, 5))
})

test('a long run keeps a bounded window', () => {
  let window = []
  for (let start = 1; start <= 400; start++) {
    window = boundWindow(window.concat(incrementalTail(window, lines(start, start + 199))), 500)
  }
  assert.ok(window.length <= 500)
  assert.equal(window[window.length - 1], 'line-599')
})
