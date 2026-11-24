#!/usr/bin/env python3
"""uitap.py <regex> [--all]  -- read a uiautomator XML dump on stdin, print "x y" (the centre)
of the first node whose text or content-desc matches the regex. Prints nothing if none does.
--all prints every node that has a text or content-desc, as `x y | text | desc | class`, so you
can see what is on screen without a screenshot.
"""
import re
import sys
import xml.etree.ElementTree as ET

args = sys.argv[1:]
show_all = "--all" in args
args = [a for a in args if a != "--all"]
pattern = re.compile(args[0]) if args else None
data = sys.stdin.read()
start = data.find("<?xml")
if start < 0:
    start = data.find("<hierarchy")
if start < 0:
    sys.exit(0)
try:
    root = ET.fromstring(data[start:])
except ET.ParseError:
    sys.exit(0)
bounds_re = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
for node in root.iter("node"):
    text = node.get("text", "")
    desc = node.get("content-desc", "")
    m = bounds_re.match(node.get("bounds", ""))
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    if show_all:
        if text or desc:
            print(f"{cx} {cy} | {text} | {desc} | {node.get('class', '')}")
        continue
    if pattern and (pattern.search(text) or pattern.search(desc)):
        print(cx, cy)
        break
