"""Verify ELF LOAD and ZIP alignment of 64-bit native libraries, without an NDK."""
import struct
import sys
import zipfile


def check(path):
    count = 0
    with zipfile.ZipFile(path) as apk, open(path, "rb") as raw:
        for info in apk.infolist():
            if not info.filename.endswith(".so") or not info.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
                continue
            data = apk.read(info)
            if data[:5] != b"\x7fELF\x02" or data[5] != 1:
                raise ValueError(f"Unexpected ELF header: {info.filename}")
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, number = struct.unpack_from("<HH", data, 54)
            segments = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size) for i in range(number)]
            loads = [segment for segment in segments if segment[0] == 1]
            if not loads or any(segment[7] < 16384 or segment[2] % 16384 != segment[3] % 16384 for segment in loads):
                raise ValueError(f"ELF is not 16 KB compatible: {info.filename}")
            if info.compress_type == zipfile.ZIP_STORED:
                raw.seek(info.header_offset + 26)
                name, extra = struct.unpack("<HH", raw.read(4))
                if (info.header_offset + 30 + name + extra) % 16384:
                    raise ValueError(f"ZIP entry is not 16 KB aligned: {info.filename}")
            print(f"16 KB OK: {info.filename}")
            count += 1
    if count == 0:
        raise ValueError("Expected native camera libraries were not found")
    print(f"Verified {count} native libraries")


if __name__ == "__main__":
    check(sys.argv[1])
