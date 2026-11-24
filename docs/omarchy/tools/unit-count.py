#!/usr/bin/env python3
"""Print the unit-test totals from the last :app:test run (JUnit XML)."""
import glob
import os
import xml.etree.ElementTree as ET

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..")
total = failed = 0
for path in glob.glob(os.path.join(root, "app/build/test-results/testDebugUnitTest/*.xml")):
    r = ET.parse(path).getroot()
    total += int(r.get("tests", "0"))
    failed += int(r.get("failures", "0")) + int(r.get("errors", "0"))
print("unit tests", total, "failed", failed)
