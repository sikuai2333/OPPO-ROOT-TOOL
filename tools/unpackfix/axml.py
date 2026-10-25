"""Minimal Android binary XML (AXML) rewriter.

Only what repacking needs: read the string pool, substitute strings, and write a
file back with every chunk offset corrected. Attribute values and resource IDs
are indices into the pool, so replacing a string does not disturb anything else.
"""
import struct

RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003

UTF8_FLAG = 0x00000100


class StringPool:
    def __init__(self, data, off):
        self.data = data
        self.off = off
        (_type, header_size, size, count, style_count, flags,
         strings_start, styles_start) = struct.unpack_from('<HHIIIIII', data, off)
        self.header_size = header_size
        self.size = size
        self.count = count
        self.flags = flags
        self.strings_start = strings_start
        self.styles_start = styles_start
        self.offsets = [struct.unpack_from('<I', data, off + header_size + i * 4)[0]
                        for i in range(count)]
        self.strings = [self._read(i) for i in range(count)]

    def _read(self, index):
        base = self.off + self.strings_start + self.offsets[index]
        d = self.data
        if self.flags & UTF8_FLAG:
            # utf16 length, then utf8 length, both uleb-ish; Android uses 1-2 bytes
            _u16_len, p = _read_len8(d, base)
            u8_len, p = _read_len8(d, p)
            return d[p:p + u8_len].decode('utf-8', 'replace')
        n, p = _read_len16(d, base)
        return d[p:p + n * 2].decode('utf-16-le', 'replace')

    def index_of(self, value):
        try:
            return self.strings.index(value)
        except ValueError:
            return -1


def _read_len8(d, p):
    n = d[p]
    if n & 0x80:
        return ((n & 0x7F) << 8) | d[p + 1], p + 2
    return n, p + 1


def _read_len16(d, p):
    n = struct.unpack_from('<H', d, p)[0]
    if n & 0x8000:
        n = ((n & 0x7FFF) << 16) | struct.unpack_from('<H', d, p + 2)[0]
        return n, p + 4
    return n, p + 2


def _write_len8(value):
    if value < 0x80:
        return bytes([value])
    return bytes([0x80 | (value >> 8), value & 0xFF])


def _write_len16(value):
    if value < 0x8000:
        return struct.pack('<H', value)
    return struct.pack('<HH', 0x8000 | (value >> 16), value & 0xFFFF)


def build_pool(strings, flags):
    """Emit a string pool chunk for the given strings."""
    offsets, blob = [], bytearray()
    if flags & UTF8_FLAG:
        for s in strings:
            encoded = s.encode('utf-8')
            offsets.append(len(blob))
            blob += _write_len8(len(s))          # utf-16 length in code units
            blob += _write_len8(len(encoded))    # utf-8 byte length
            blob += encoded
            blob += b'\x00'
    else:
        for s in strings:
            offsets.append(len(blob))
            blob += _write_len16(len(s))
            blob += s.encode('utf-16-le')
            blob += b'\x00\x00'
    while len(blob) % 4:
        blob += b'\x00'

    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(blob)
    out = bytearray()
    out += struct.pack('<HHIIIIII', RES_STRING_POOL_TYPE, header_size, size,
                       len(strings), 0, flags, strings_start, 0)
    for off in offsets:
        out += struct.pack('<I', off)
    out += blob
    return bytes(out)


class Axml:
    def __init__(self, data):
        self.raw = data
        _type, header_size, total = struct.unpack_from('<HHI', data, 0)
        if _type != RES_XML_TYPE:
            raise ValueError('not AXML')
        self.total = total
        self.pool = StringPool(data, header_size)
        self.pool_start = header_size
        self.rest = data[self.pool_start + self.pool.size:total]

    def rewrite(self, replacements):
        """replacements: {old_string: new_string}. Returns new AXML bytes."""
        strings = list(self.pool.strings)
        for old, new in replacements.items():
            for i, s in enumerate(strings):
                if s == old:
                    strings[i] = new
        pool = build_pool(strings, self.pool.flags)
        head = struct.pack('<HHI', RES_XML_TYPE, 8, 8 + len(pool) + len(self.rest))
        return head + pool + self.rest

    def find(self, needle):
        return [s for s in self.pool.strings if needle in s]
