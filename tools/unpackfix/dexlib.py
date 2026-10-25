"""DEX reader: enough structure to classify every method as code / native / empty."""
import struct
import zlib

NO_INDEX = 0xFFFFFFFF
ACC_STATIC = 0x0008
ACC_NATIVE = 0x0100
ACC_ABSTRACT = 0x0400


class Dex:
    def __init__(self, data, name="<memory>"):
        self.d = data
        self.name = name
        if len(data) < 112 or data[:4] != b'dex\n':
            raise ValueError(f"{name}: not a DEX")
        self.version = data[4:7].decode('ascii', 'replace')
        self.file_size = self.u4(0x20)
        self.string_ids_size = self.u4(0x38)
        self.string_ids_off = self.u4(0x3C)
        self.type_ids_size = self.u4(0x40)
        self.type_ids_off = self.u4(0x44)
        self.method_ids_size = self.u4(0x58)
        self.method_ids_off = self.u4(0x5C)
        self.class_defs_size = self.u4(0x60)
        self.class_defs_off = self.u4(0x64)
        self._strings = None
        self._types = None

    # ---- primitives -------------------------------------------------------
    def u2(self, off):
        return struct.unpack_from('<H', self.d, off)[0]

    def u4(self, off):
        return struct.unpack_from('<I', self.d, off)[0]

    def uleb(self, pos):
        result = 0
        shift = 0
        d = self.d
        while True:
            b = d[pos]
            pos += 1
            result |= (b & 0x7F) << shift
            shift += 7
            if not (b & 0x80):
                return result, pos

    # ---- tables -----------------------------------------------------------
    @property
    def strings(self):
        if self._strings is None:
            out = []
            d = self.d
            for i in range(self.string_ids_size):
                p = self.u4(self.string_ids_off + i * 4)
                n = 0
                shift = 0
                while True:
                    b = d[p]
                    p += 1
                    n |= (b & 0x7F) << shift
                    shift += 7
                    if not (b & 0x80):
                        break
                out.append(d[p:p + n].decode('utf-8', 'replace'))
            self._strings = out
        return self._strings

    @property
    def types(self):
        if self._types is None:
            self._types = [self.u4(self.type_ids_off + i * 4)
                           for i in range(self.type_ids_size)]
        return self._types

    def type_name(self, idx):
        if idx >= len(self.types):
            return '?'
        sidx = self.types[idx]
        return self.strings[sidx] if sidx < len(self.strings) else '?'

    def method_name(self, idx):
        if idx >= self.method_ids_size:
            return '?'
        sidx = self.u4(self.method_ids_off + idx * 8 + 4)
        return self.strings[sidx] if sidx < len(self.strings) else '?'

    # ---- analysis ---------------------------------------------------------
    def classes(self):
        """Yield (class_descriptor, class_def_index, [(method_idx, name, acc, code_off)])."""
        d = self.d
        for cd in range(self.class_defs_size):
            base = self.class_defs_off + cd * 32
            type_idx = self.u4(base)
            class_data_off = self.u4(base + 24)
            descriptor = self.type_name(type_idx)
            methods = []
            if class_data_off:
                pos = class_data_off
                sf, pos = self.uleb(pos)
                inf, pos = self.uleb(pos)
                dm, pos = self.uleb(pos)
                vm, pos = self.uleb(pos)
                for _ in range(sf + inf):
                    _, pos = self.uleb(pos)
                    _, pos = self.uleb(pos)
                for count in (dm, vm):
                    idx = 0
                    for _ in range(count):
                        delta, pos = self.uleb(pos)
                        acc, pos = self.uleb(pos)
                        code_off, pos = self.uleb(pos)
                        idx += delta
                        methods.append((idx, self.method_name(idx), acc, code_off))
            yield descriptor, cd, methods

    def stats(self):
        """Classify every declared method. Returns a dict of counters."""
        total = coded = native = abstract = empty = 0
        empty_methods = []
        for descriptor, _cd, methods in self.classes():
            for idx, name, acc, code_off in methods:
                total += 1
                if acc & ACC_NATIVE:
                    native += 1
                    empty_methods.append((descriptor, name, 'native'))
                elif acc & ACC_ABSTRACT:
                    abstract += 1
                elif code_off == 0:
                    empty += 1
                    empty_methods.append((descriptor, name, 'no_code'))
                else:
                    coded += 1
        return {
            'file': self.name,
            'dex_version': self.version,
            'bytes': len(self.d),
            'file_size_ok': self.file_size == len(self.d),
            'checksum_ok': zlib.adler32(self.d[12:]) & 0xFFFFFFFF == self.u4(8),
            'classes': self.class_defs_size,
            'methods': total,
            'coded': coded,
            'native': native,
            'abstract': abstract,
            'empty': empty,
            'empty_methods': empty_methods,
        }
