#!/usr/bin/env python3
"""Reduce a private synthetic Reader screenshot to non-reversible color counts."""
from __future__ import annotations

import argparse
import math
from pathlib import Path
import struct
import sys
import zlib


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_PIXELS = 20_000_000
MAX_SAMPLES = 250_000


class ScreenshotDecodeError(ValueError):
    pass


def _paeth(left: int, above: int, upper_left: int) -> int:
    predictor = left + above - upper_left
    left_distance = abs(predictor - left)
    above_distance = abs(predictor - above)
    upper_left_distance = abs(predictor - upper_left)
    if left_distance <= above_distance and left_distance <= upper_left_distance:
        return left
    if above_distance <= upper_left_distance:
        return above
    return upper_left


def _pixel_class(red: int, green: int, blue: int) -> str:
    if red > 80 and red >= 1.4 * green and red >= 1.3 * blue:
        return "RED"
    if blue > 80 and blue >= 1.4 * red and blue >= 1.3 * green:
        return "BLUE"
    if max(red, green, blue) <= 48:
        return "DARK"
    if min(red, green, blue) >= 210:
        return "LIGHT"
    return "OTHER"


def summarize_png(path: Path) -> str:
    data = path.read_bytes()
    if not data.startswith(PNG_SIGNATURE):
        raise ScreenshotDecodeError

    offset = len(PNG_SIGNATURE)
    width = height = bit_depth = color_type = interlace = None
    image_data = bytearray()
    saw_end = False
    while offset + 12 <= len(data):
        chunk_length = struct.unpack_from(">I", data, offset)[0]
        offset += 4
        chunk_type = data[offset:offset + 4]
        offset += 4
        if chunk_length > len(data) - offset - 4:
            raise ScreenshotDecodeError
        chunk = data[offset:offset + chunk_length]
        offset += chunk_length
        expected_crc = struct.unpack_from(">I", data, offset)[0]
        offset += 4
        if zlib.crc32(chunk_type + chunk) & 0xFFFFFFFF != expected_crc:
            raise ScreenshotDecodeError

        if chunk_type == b"IHDR":
            if len(chunk) != 13 or width is not None:
                raise ScreenshotDecodeError
            width, height, bit_depth, color_type, compression, filter_method, interlace = struct.unpack(
                ">IIBBBBB", chunk,
            )
            if compression != 0 or filter_method != 0:
                raise ScreenshotDecodeError
        elif chunk_type == b"IDAT":
            image_data.extend(chunk)
        elif chunk_type == b"IEND":
            saw_end = True
            break

    if not saw_end or width is None or height is None or bit_depth != 8 or interlace != 0:
        raise ScreenshotDecodeError
    if color_type == 2:
        bytes_per_pixel = 3
    elif color_type == 6:
        bytes_per_pixel = 4
    else:
        raise ScreenshotDecodeError
    if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
        raise ScreenshotDecodeError

    row_bytes = width * bytes_per_pixel
    try:
        decoded = zlib.decompress(image_data)
    except zlib.error as error:
        raise ScreenshotDecodeError from error
    if len(decoded) != height * (row_bytes + 1):
        raise ScreenshotDecodeError

    step = max(1, math.ceil(math.sqrt((width * height) / MAX_SAMPLES)))
    counts = {"RED": 0, "BLUE": 0, "DARK": 0, "LIGHT": 0, "OTHER": 0}
    sample_count = 0
    center_class = "OTHER"
    previous = bytearray(row_bytes)
    source_offset = 0
    center_y = height // 2
    center_x = width // 2

    for y in range(height):
        filter_type = decoded[source_offset]
        source_offset += 1
        row = bytearray(decoded[source_offset:source_offset + row_bytes])
        source_offset += row_bytes
        if filter_type > 4:
            raise ScreenshotDecodeError
        for index in range(row_bytes):
            left = row[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            above = previous[index]
            upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            if filter_type == 1:
                row[index] = (row[index] + left) & 0xFF
            elif filter_type == 2:
                row[index] = (row[index] + above) & 0xFF
            elif filter_type == 3:
                row[index] = (row[index] + ((left + above) // 2)) & 0xFF
            elif filter_type == 4:
                row[index] = (row[index] + _paeth(left, above, upper_left)) & 0xFF

        if y == center_y:
            center_offset = center_x * bytes_per_pixel
            center_class = _pixel_class(row[center_offset], row[center_offset + 1], row[center_offset + 2])
        if y % step == 0:
            for x in range(0, width, step):
                pixel_offset = x * bytes_per_pixel
                bucket = _pixel_class(row[pixel_offset], row[pixel_offset + 1], row[pixel_offset + 2])
                counts[bucket] += 1
                sample_count += 1
        previous = row

    basis_points = {key: (value * 10_000) // sample_count for key, value in counts.items()}
    basis_points["OTHER"] = 10_000 - sum(basis_points[key] for key in ("RED", "BLUE", "DARK", "LIGHT"))
    return (
        "ANDROID_SOURCE_SWITCH_SCREEN|capture=PASS|decode=PASS"
        + f"|width={width}|height={height}|samples={sample_count}|center={center_class}"
        + f"|redBp={basis_points['RED']}|blueBp={basis_points['BLUE']}"
        + f"|darkBp={basis_points['DARK']}|lightBp={basis_points['LIGHT']}|otherBp={basis_points['OTHER']}"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("screenshot", type=Path)
    args = parser.parse_args(argv)
    try:
        print(summarize_png(args.screenshot))
    except (OSError, ScreenshotDecodeError):
        print("ANDROID_SOURCE_SWITCH_SCREEN|capture=PASS|decode=UNAVAILABLE")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
