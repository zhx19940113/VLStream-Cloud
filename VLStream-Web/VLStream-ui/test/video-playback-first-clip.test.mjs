import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/views/VideoAggregation/VideoPlayback.vue', import.meta.url), 'utf8')

test('the first clip is not prefetched again by the poster warmer', () => {
  const warmer = source.slice(source.indexOf('async function warmPosters'), source.indexOf('const key = item'))
  assert.ok(warmer.includes('items.slice(1, 3)'), 'prefetch starts at the second clip; the player owns the first')
  assert.ok(!warmer.includes('items.slice(0, 3)'), 'no duplicate download of the clip the player selected')
})

test('the player and the poster warmer share one in-flight file request per clip', () => {
  assert.ok(/const fileRequests = new Map\(\)/.test(source), 'a per-clip request table exists')
  assert.ok(/function requestFile\(id\)/.test(source), 'requests go through the shared helper')
  const player = source.slice(source.indexOf('async function play('), source.indexOf('async function play(') + 800)
  assert.ok(player.includes('await requestFile(segment.id)'), 'the player uses the shared request')
  assert.ok(!/await api\.file\(segment\.id\)/.test(player), 'the player does not bypass the shared request')
})

test('the cover of the first clip is taken from the decoded player frame', () => {
  assert.ok(/function mediaReady\(\) \{ capturePoster\(\);/.test(source), 'metadata-ready captures the first frame')
  assert.ok(source.includes('function capturePoster()'))
})

test('changing date, source or closing the player drops stale in-flight requests', () => {
  const close = source.slice(source.indexOf('function closePlayer()'), source.indexOf('function closePlayer()') + 200)
  const sources = source.slice(source.indexOf('async function loadSources()'), source.indexOf('async function loadSources()') + 400)
  const segments = source.slice(source.indexOf('async function loadSegments()'), source.indexOf('async function loadSegments()') + 400)
  assert.ok(close.includes('fileRequests.clear()'), 'closing the player drops in-flight requests')
  assert.ok(sources.includes('fileRequests.clear()'), 'changing the source drops in-flight requests')
  assert.ok(segments.includes('fileRequests.clear()'), 'changing the date drops in-flight requests')
  // Playing another clip of the SAME selection must not clear the table, or the player and the
  // warmer would each download a shared clip again.
  const play = source.slice(source.indexOf('async function play('), source.indexOf('async function play(') + 800)
  assert.ok(!play.includes('fileRequests.clear()'), 'selecting another clip keeps the shared request')
})
