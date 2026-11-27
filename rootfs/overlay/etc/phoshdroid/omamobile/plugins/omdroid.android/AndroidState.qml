// SPDX-License-Identifier: MIT
import QtQuick
import Quickshell
import Quickshell.Io
import "AndroidModel.js" as Model

Item {
  id: root
  readonly property bool hosted: Quickshell.env("OMARCHY_PROOT") === "1"
  property var snapshot: ({})
  property double now: Date.now()
  readonly property bool available: hosted && Model.fresh(snapshot, now)
  // The bundled shell predates Commons.Translations; keep labels Qt-translatable.
  function t(key) {
    switch (key) {
      case "on": return qsTr("On")
      case "off": return qsTr("Off")
      case "connected": return qsTr("Connected")
      case "displaySettings": return qsTr("Display & brightness settings")
      case "volumeSettings": return qsTr("Android volume")
      default: return qsTr("Android settings")
    }
  }
  function value(key) { return available ? snapshot[key] : undefined }
  function status(key) { return value(key) === true ? t("on") : value(key) === false ? t("off") : t("settings") }
  function open(page) {
    if (!hosted) return
    Quickshell.execDetached(["python3", String(Qt.resolvedUrl("android-control.py")).replace(/^file:\/\//, ""), page])
  }
  FileView {
    id: data
    path: root.hosted ? "/tmp/omdroid-android/state.json" : ""
    watchChanges: root.hosted
    onFileChanged: reload()
    onLoaded: { root.now = Date.now(); root.snapshot = Model.parse(text()) }
    onLoadFailed: root.snapshot = ({})
  }
  Timer {
    interval: 3000
    repeat: true
    running: root.hosted
    onTriggered: { root.now = Date.now(); data.reload() }
  }
}
