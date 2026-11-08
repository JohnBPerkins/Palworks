"""Writes the empty test-area structures the in-game tests use (data/palworks_gametest/structure).

Both are boxes of air: placing one clears that space for a test. Structure .nbt files load on both
Fabric and NeoForge (Fabric's .snbt test structures are Fabric-only). Run from the repo root:
    python3 common/src/gametest/make_structures.py
"""

import gzip
import os
import struct

DATA_VERSION = 3955  # Minecraft 1.21.1
OUT = os.path.join(os.path.dirname(__file__), "resources", "data", "palworks_gametest", "structure")
AREAS = {"empty": (8, 8, 8), "arena": (16, 24, 16)}

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def name(s: str) -> bytes:
    data = s.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def int_tag(key: str, value: int) -> bytes:
    return bytes([TAG_INT]) + name(key) + struct.pack(">i", value)


def string_tag(key: str, value: str) -> bytes:
    return bytes([TAG_STRING]) + name(key) + name(value)


def int_list(key: str, values) -> bytes:
    return bytes([TAG_LIST]) + name(key) + bytes([TAG_INT]) + struct.pack(">i", len(values)) + b"".join(
        struct.pack(">i", v) for v in values)


def compound_list(key: str, compounds) -> bytes:
    body = b"".join(c + bytes([TAG_END]) for c in compounds)
    element = TAG_COMPOUND if compounds else TAG_END
    return bytes([TAG_LIST]) + name(key) + bytes([element]) + struct.pack(">i", len(compounds)) + body


def structure(size) -> bytes:
    sx, sy, sz = size
    blocks = [int_list("pos", [x, y, z]) + int_tag("state", 0)
              for x in range(sx) for y in range(sy) for z in range(sz)]
    root = (int_tag("DataVersion", DATA_VERSION)
            + int_list("size", [sx, sy, sz])
            + compound_list("palette", [string_tag("Name", "minecraft:air")])
            + compound_list("blocks", blocks)
            + compound_list("entities", []))
    return bytes([TAG_COMPOUND]) + name("") + root + bytes([TAG_END])


os.makedirs(OUT, exist_ok=True)
for area, size in AREAS.items():
    with gzip.open(os.path.join(OUT, area + ".nbt"), "wb") as f:
        f.write(structure(size))
    print("wrote", area, size)
