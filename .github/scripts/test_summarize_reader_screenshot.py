#!/usr/bin/env python3
"""Unit tests for private offline Reader screenshot reduction."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import struct
from contextlib import redirect_stdout
import io
import tempfile
import unittest
import zlib


MODULE = Path(__file__).with_name("summarize_reader_screenshot.py")
SPEC = importlib.util.spec_from_file_location("summarize_reader_screenshot", MODULE)
assert SPEC is not None and SPEC.loader is not None
summarizer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summarizer)


def _chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def _png(width: int, height: int, pixels: list[tuple[int, int, int]]) -> bytes:
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for red, green, blue in pixels[y * width:(y + 1) * width]:
            rows.extend((red, green, blue))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        summarizer.PNG_SIGNATURE
        + _chunk(b"IHDR", header)
        + _chunk(b"IDAT", zlib.compress(rows))
        + _chunk(b"IEND", b"")
    )


class SummarizeReaderScreenshotTest(unittest.TestCase):
    def test_reports_only_aggregate_color_buckets_and_center_class(self):
        pixels = [(220, 10, 10)] * 6
        pixels[4] = (10, 20, 230)
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "private.png"
            screenshot.write_bytes(_png(3, 2, pixels))
            result = summarizer.summarize_png(screenshot)

        self.assertIn("|width=3|height=2|samples=6|center=BLUE", result)
        self.assertIn("|redBp=8333|blueBp=1666", result)
        self.assertTrue(result.startswith("ANDROID_SOURCE_SWITCH_SCREEN|capture=PASS|decode=PASS"))
        self.assertNotIn("220", result)
        self.assertNotIn("private.png", result)

    def test_blank_reader_screen_is_reported_as_light_without_image_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "private.png"
            screenshot.write_bytes(_png(2, 2, [(240, 240, 240)] * 4))
            result = summarizer.summarize_png(screenshot)
        self.assertIn("center=LIGHT", result)
        self.assertIn("lightBp=10000", result)
        self.assertNotIn("240", result)

    def test_invalid_png_returns_fixed_unavailable_record(self):
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "secret-provider-title.png"
            screenshot.write_bytes(b"not a png")
            output = io.StringIO()
            with redirect_stdout(output):
                result = summarizer.main([str(screenshot)])
        self.assertEqual(1, result)
        self.assertEqual(
            "ANDROID_SOURCE_SWITCH_SCREEN|capture=PASS|decode=UNAVAILABLE\n",
            output.getvalue(),
        )
        self.assertNotIn("secret-provider-title", output.getvalue())


if __name__ == "__main__":
    unittest.main()
