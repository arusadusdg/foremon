"""Converts a vanilla structure template (.nbt, for example a village house from the Minecraft jar) into a
.litematic file that Litematica can load. Jigsaw and structure-void blocks become air, and block entity
contents are dropped (workers place blocks empty anyway).

Usage: structure_to_litematic.py <minecraft jar> <structure path inside the jar> <out .litematic> [name]"""
import gzip, io, math, struct, sys, time, zipfile

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


# ------------------------------------------------------------------ reading NBT
class R:
    def __init__(self, b): self.b = b; self.i = 0
    def take(self, n): v = self.b[self.i:self.i + n]; self.i += n; return v
    def u(self, fmt): return struct.unpack('>' + fmt, self.take(struct.calcsize('>' + fmt)))[0]
    def s(self): return self.take(self.u('H')).decode('utf-8')


def read_payload(r, t):
    if t == BYTE: return r.u('b')
    if t == SHORT: return r.u('h')
    if t == INT: return r.u('i')
    if t == LONG: return r.u('q')
    if t == FLOAT: return r.u('f')
    if t == DOUBLE: return r.u('d')
    if t == BYTE_ARRAY: return r.take(r.u('i'))
    if t == STRING: return r.s()
    if t == LIST:
        et = r.u('b'); n = r.u('i')
        return [read_payload(r, et) for _ in range(n)]
    if t == COMPOUND:
        d = {}
        while True:
            ct = r.u('b')
            if ct == END: return d
            name = r.s(); d[name] = read_payload(r, ct)
    if t == INT_ARRAY: return [r.u('i') for _ in range(r.u('i'))]
    if t == LONG_ARRAY: return [r.u('q') for _ in range(r.u('i'))]
    raise ValueError(t)


def read_nbt(data):
    r = R(gzip.decompress(data))
    assert r.u('b') == COMPOUND
    r.s()
    return read_payload(r, COMPOUND)


# ------------------------------------------------------------------ writing NBT
def ws(b, text):
    data = text.encode('utf-8'); b.write(struct.pack('>H', len(data))); b.write(data)


def payload(b, kind, v):
    if kind == INT: b.write(struct.pack('>i', v))
    elif kind == LONG: b.write(struct.pack('>q', v))
    elif kind == STRING: ws(b, v)
    elif kind == LONG_ARRAY:
        b.write(struct.pack('>i', len(v)))
        for x in v: b.write(struct.pack('>q', x))
    elif kind == LIST:
        el, items = v
        b.write(struct.pack('>bi', el, len(items)))
        for it in items: payload(b, el, it)
    elif kind == COMPOUND:
        for name, (k, val) in v.items():
            b.write(struct.pack('>b', k)); ws(b, name); payload(b, k, val)
        b.write(struct.pack('>b', END))


def vec(x, y, z): return (COMPOUND, {'x': (INT, x), 'y': (INT, y), 'z': (INT, z)})


jar, path, out = sys.argv[1], sys.argv[2], sys.argv[3]
title = sys.argv[4] if len(sys.argv) > 4 else path.rsplit('/', 1)[-1].replace('.nbt', '')
with zipfile.ZipFile(jar) as z:
    src = read_nbt(z.read(path))

SX, SY, SZ = src['size']
pal_src = src['palette'] if 'palette' in src else src['palettes'][0]
AIRISH = {'minecraft:jigsaw', 'minecraft:structure_void', 'minecraft:air', 'minecraft:cave_air'}

palette = [('minecraft:air', {})]
index = {('minecraft:air', ()): 0}
grid = {}
for blk in src['blocks']:
    st = pal_src[blk['state']]
    name = st['Name']
    if name in AIRISH:
        continue
    props = st.get('Properties', {})
    key = (name, tuple(sorted(props.items())))
    if key not in index:
        index[key] = len(palette); palette.append((name, props))
    x, y, zz = blk['pos']
    grid[(x, y, zz)] = index[key]

bits = max(2, math.ceil(math.log2(len(palette))))
volume = SX * SY * SZ
longs = [0] * math.ceil(volume * bits / 64)
for (x, y, zz), v in grid.items():
    i = (y * SX * SZ + zz * SX + x) * bits
    li, off = i // 64, i % 64
    longs[li] = (longs[li] | (v << off)) & 0xFFFFFFFFFFFFFFFF
    if off + bits > 64:
        longs[li + 1] |= v >> (64 - off)
signed = [x - (1 << 64) if x >= (1 << 63) else x for x in longs]

pal_nbt = []
for name, props in palette:
    c = {'Name': (STRING, name)}
    if props: c['Properties'] = (COMPOUND, {k: (STRING, str(v)) for k, v in props.items()})
    pal_nbt.append(c)

now = int(time.time() * 1000)
root = {
    'MinecraftDataVersion': (INT, src.get('DataVersion', 3955)),
    'Version': (INT, 7), 'SubVersion': (INT, 1),
    'Metadata': (COMPOUND, {
        'Name': (STRING, title), 'Author': (STRING, 'Mojang (village structure), converted for Foremon'),
        'Description': (STRING, ''), 'RegionCount': (INT, 1), 'TotalVolume': (INT, volume),
        'TotalBlocks': (INT, len(grid)), 'TimeCreated': (LONG, now), 'TimeModified': (LONG, now),
        'EnclosingSize': vec(SX, SY, SZ),
    }),
    'Regions': (COMPOUND, {'Main': (COMPOUND, {
        'Position': vec(0, 0, 0), 'Size': vec(SX, SY, SZ),
        'BlockStatePalette': (LIST, (COMPOUND, pal_nbt)), 'BlockStates': (LONG_ARRAY, signed),
        'TileEntities': (LIST, (COMPOUND, [])), 'Entities': (LIST, (COMPOUND, [])),
        'PendingBlockTicks': (LIST, (COMPOUND, [])), 'PendingFluidTicks': (LIST, (COMPOUND, [])),
    })}),
}
b = io.BytesIO()
b.write(struct.pack('>b', COMPOUND)); ws(b, ''); payload(b, COMPOUND, root)
with gzip.open(out, 'wb') as f:
    f.write(b.getvalue())
kinds = sorted({n for n, _ in palette[1:]})
print(f'{title}: size {SX}x{SY}x{SZ}, {len(grid)} blocks, {len(kinds)} block kinds')
