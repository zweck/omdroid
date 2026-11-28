#!/usr/bin/env python3
"""DeX task selection and recovery checks; no device or Android SDK needed."""
import json
import unittest

import fullscreen


def activity(task=173, mode="freeform", bounds="0, 0 - 2800, 1752",
             component=fullscreen.COMPONENT):
    return f"""Display #0 (activities from top to bottom):
  * Task{{aaa #7 type=undefined mode=freeform sz=2}}
    * Task{{bbb #{task} type=standard mode={mode} sz=1}}
      topResumedActivity=ActivityRecord{{ccc u0 {component} t{task}}}
      mGlobalConfig={{winConfig={{mMaxBounds=Rect({bounds})}}}}
    * Task{{ddd #174 type=standard mode=freeform sz=1}}
      topResumedActivity=ActivityRecord{{eee u0 com.android.settings/.Settings t174}}
      mGlobalConfig={{winConfig={{mMaxBounds=Rect(0, 0 - 1920, 1080)}}}}
  mCurrentFocus=Window{{fff u0 {component}}}
"""


class Android:
    def __init__(self, dump, help_text="moveTaskOutOfDesk <taskId>", after_help=None):
        self.dump = dump
        self.help_text = help_text
        self.after_help = after_help
        self.calls = []

    def __call__(self, *args):
        self.calls.append(args)
        if args == ("dumpsys", "activity", "activities"):
            return self.dump
        if args == (*fullscreen.WM_SHELL, "help"):
            if self.after_help is not None:
                self.dump = self.after_help
            return self.help_text
        return ""

    def moves(self):
        return [args for args in self.calls if "moveTaskOutOfDesk" in args]


class FullscreenTest(unittest.TestCase):
    def test_moves_only_the_focused_omdroid_task(self):
        android = Android(activity())
        self.assertEqual(fullscreen.request_fullscreen(android), "173")
        self.assertEqual(android.moves(), [(*fullscreen.WM_SHELL, "desktopmode", "moveTaskOutOfDesk", "173")])

    def test_already_fullscreen_needs_no_shell_mutation(self):
        android = Android(activity(mode="fullscreen"))
        fullscreen.request_fullscreen(android)
        self.assertEqual(android.calls, [("dumpsys", "activity", "activities")])

    def test_foreign_focus_cannot_move_a_background_omdroid_task(self):
        dump = activity().replace("mCurrentFocus=Window{fff u0 " + fullscreen.COMPONENT,
                                  "mCurrentFocus=Window{fff u0 com.android.settings/.Settings")
        android = Android(dump)
        with self.assertRaisesRegex(RuntimeError, "foreground"):
            fullscreen.request_fullscreen(android)
        self.assertFalse(android.moves())

    def test_split_screen_is_not_changed(self):
        android = Android(activity(mode="multi-window"))
        with self.assertRaises(RuntimeError):
            fullscreen.request_fullscreen(android)
        self.assertFalse(android.moves())

    def test_unsupported_dex_does_not_attempt_a_move(self):
        android = Android(activity(), help_text="help dump")
        with self.assertRaisesRegex(RuntimeError, "fullscreen button"):
            fullscreen.request_fullscreen(android)
        self.assertFalse(android.moves())

    def test_focus_and_task_are_rechecked_before_moving(self):
        for next_dump in (activity(task=175), activity(component="com.android.settings/.Settings")):
            android = Android(activity(), after_help=next_dump)
            with self.assertRaises(RuntimeError):
                fullscreen.request_fullscreen(android)
            self.assertFalse(android.moves())

    def test_task_id_prefix_does_not_match_another_task(self):
        dump = activity().replace("#173 type", "#1730 type")
        with self.assertRaisesRegex(RuntimeError, "window mode"):
            fullscreen.display_task(dump)

    def test_ambiguous_tasks_are_rejected(self):
        dump = activity() + f"\ntopResumedActivity=ActivityRecord{{abc u0 {fullscreen.COMPONENT} t175}}\n"
        with self.assertRaisesRegex(RuntimeError, "single"):
            fullscreen.display_task(dump)

    def test_uses_own_display_bounds_and_rounds_down_to_even(self):
        for bounds, expected in (("0, 0 - 3200, 1971", (3200, 1970)),
                                 ("0, 0 - 1752, 2800", (1752, 2800)),
                                 ("-1920, 0 - 0, 1080", (1920, 1080))):
            self.assertEqual(fullscreen.display_task(activity(bounds=bounds))[2], expected)

    def test_missing_own_bounds_cannot_use_another_tasks_bounds(self):
        dump = activity().replace("mMaxBounds=Rect(0, 0 - 2800, 1752)", "")
        with self.assertRaisesRegex(RuntimeError, "display bounds"):
            fullscreen.display_task(dump)

    def test_waits_for_actual_guest_size_not_android_window_size(self):
        for width, height, expected in ((2800, 1752, True), (1512, 1138, False),
                                        (2800, 1592, False), (1752, 2800, False)):
            output = "# control socket\n## j/monitors\n" + json.dumps([{"width": width, "height": height}])
            self.assertEqual(fullscreen.guest_fits(output, (2800, 1752)), expected)
        for output in ("NO-IPC", "## j/monitors\n[]", "## j/monitors\nnot json"):
            self.assertFalse(fullscreen.guest_fits(output, (2800, 1752)))


if __name__ == "__main__":
    unittest.main()
