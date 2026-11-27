// SPDX-License-Identifier: MIT
.pragma library

function parse(text) {
  if (typeof text !== "string" || text.length > 8192) return ({})
  var raw
  try { raw = JSON.parse(text) } catch (_) { return ({}) }
  if (!raw || raw.schema !== 2 || typeof raw.timestamp !== "number" || !isFinite(raw.timestamp)) return ({})
  if (typeof raw.sessionId !== "string" || !/^[a-f0-9-]{36}$/.test(raw.sessionId)) return ({})
  var result = {schema: 2, sessionId: raw.sessionId, timestamp: raw.timestamp}
  var flags = ["wifiEnabled", "wifiConnected", "bluetoothEnabled", "airplaneMode",
               "rotationLocked", "powerSave", "charging", "full"]
  flags.forEach(function(key) { if (typeof raw[key] === "boolean") result[key] = raw[key] })
  var n = raw.batteryPercent
  if (typeof n === "number" && isFinite(n) && n >= 0 && n <= 100) result.batteryPercent = Math.round(n)
  return result
}

// Timestamp is the last change time, not a heartbeat. The X server and this
// bridge share the Android process; a new guest session always gets a new ID.
function fresh(state, sessionId) {
  return state.schema === 2 && typeof sessionId === "string" && sessionId.length === 36 && state.sessionId === sessionId
}
