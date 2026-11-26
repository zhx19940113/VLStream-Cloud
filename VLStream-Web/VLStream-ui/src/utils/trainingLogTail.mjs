/**
 * Incremental merge for a rolling log tail.
 *
 * The training backend returns the newest N lines (`tail -n`), not a cursor-based stream.
 * Two consecutive snapshots therefore usually overlap: snapshot B starts somewhere inside A.
 * Appending all of B duplicates that overlap, and appending only "the part not already a prefix
 * of A" duplicates too once the window itself has moved.
 *
 * This finds the longest suffix of the previous window that is also a prefix of the next one and
 * returns only the lines after it. If nothing overlaps the whole snapshot is new. The result is
 * order-preserving and never repeats a line that was already shown.
 *
 * @param {string[]} previous lines shown from the previous snapshot
 * @param {string[]} incoming lines of the newest snapshot
 * @returns {string[]} lines to append, in order
 */
export function incrementalTail(previous, incoming) {
  if (!Array.isArray(incoming) || incoming.length === 0) return []
  if (!Array.isArray(previous) || previous.length === 0) return incoming.slice()

  // A shorter snapshot that is the prefix of the previous one is an older window (the log did
  // not move): nothing new arrived, so nothing may be appended again.
  if (incoming.length < previous.length) {
    let prefix = true
    for (let offset = 0; offset < incoming.length; offset++) {
      if (previous[offset] !== incoming[offset]) {
        prefix = false
        break
      }
    }
    if (prefix) return []
  }

  const maximum = Math.min(previous.length, incoming.length)
  for (let overlap = maximum; overlap > 0; overlap--) {
    let same = true
    const from = previous.length - overlap
    for (let offset = 0; offset < overlap; offset++) {
      if (previous[from + offset] !== incoming[offset]) {
        same = false
        break
      }
    }
    if (same) return incoming.slice(overlap)
  }
  return incoming.slice()
}

/**
 * Keep at most {@code limit} lines, dropping the oldest first.
 *
 * @param {string[]} lines current window
 * @param {number} limit maximum retained lines
 * @returns {string[]} bounded window
 */
export function boundWindow(lines, limit) {
  const maximum = Math.max(1, Math.floor(Number(limit) || 1))
  return lines.length > maximum ? lines.slice(lines.length - maximum) : lines
}
