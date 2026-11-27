// SPDX-License-Identifier: MIT
.pragma library

function parse(text) {
  if (typeof text !== "string" || text.length > 8192) return ({})
  var raw
  try { raw = JSON.parse(text) } catch (_) { return ({}) }
  if (!raw || raw.schema !== 1 || typeof raw.timestamp !== "number" || !isFinite(raw.timestamp)) return ({})
  var result = {schema: 1, timestamp: raw.timestamp}
  var flags = ["wifiEnabled", "wifiConnected", "bluetoothEnabled", "airplaneMode",
               "rotationLocked", "powerSave", "charging", "full"]
  flags.forEach(function(key) { if (typeof raw[key] === "boolean") result[key] = raw[key] })
  var n = raw.batteryPercent
  if (typeof n === "number" && isFinite(n) && n >= 0 && n <= 100) result.batteryPercent = Math.round(n)
  return result
}

function fresh(state, now) {
  return state.schema === 1 && now >= state.timestamp && now - state.timestamp < 10000
}
