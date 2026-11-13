"""Writes a small .litematic file for the self-driving Litematica test: a 5x5 plank floor, a cobblestone ring
on top of it and a torch in the middle (an attached block, to check the build order). Usage: make_test_litematic.py <out file>"""
import gzip, io, math, struct, sys, time

END, BYTE, SHORT, INT, LONG, STRING, LIST, COMPOUND, LONG_ARRAY = 0, 1, 2, 3, 4, 8, 9, 10, 12


def s(b, text):
    data = text.encode('utf-8'); b.write(struct.pack('>H', len(data))); b.write(data)


def payload(b, kind, v):
    if kind == INT: b.write(struct.pack('>i', v))
    elif kind == LONG: b.write(struct.pack('>q', v))
    elif kind == STRING: s(b, v)
    elif kind == LONG_ARRAY:
        b.write(struct.pack('>i', len(v)))
        for x in v: b.write(struct.pack('>q', x))
    elif kind == LIST:
        el, items = v
        b.write(struct.pack('>bi', el, len(items)))
        for it in items: payload(b, el, it)
    elif kind == COMPOUND:
        for name, (k, val) in v.items():
            b.write(struct.pack('>b', k)); s(b, name); payload(b, k, val)
        b.write(struct.pack('>b', END))


SX, SY, SZ = 5, 2, 5
palette = ['minecraft:air', 'minecraft:oak_planks', 'minecraft:cobblestone', 'minecraft:torch']
blocks = {}
for x in range(SX):
    for z in range(SZ):
        blocks[(x, 0, z)] = 1
        if x in (0, SX - 1) or z in (0, SZ - 1):
            blocks[(x, 1, z)] = 2
blocks[(2, 1, 2)] = 3

bits = max(2, math.ceil(math.log2(len(palette))))
volume = SX * SY * SZ
longs = [0] * math.ceil(volume * bits / 64)
for (x, y, z), v in blocks.items():
    i = (y * SX * SZ + z * SX + x) * bits
    li, off = i // 64, i % 64
    longs[li] |= v << off
    if off + bits > 64:
        longs[li + 1] |= v >> (64 - off)
signed = [x - (1 << 64) if x >= (1 << 63) else x for x in longs]


def vec(x, y, z): return (COMPOUND, {'x': (INT, x), 'y': (INT, y), 'z': (INT, z)})


now = int(time.time() * 1000)
root = {
    'MinecraftDataVersion': (INT, 3955),
    'Version': (INT, 7),
    'SubVersion': (INT, 1),
    'Metadata': (COMPOUND, {
        'Name': (STRING, 'Foremon test house'), 'Author': (STRING, 'foremon'), 'Description': (STRING, ''),
        'RegionCount': (INT, 1), 'TotalVolume': (INT, volume), 'TotalBlocks': (INT, len(blocks)),
        'TimeCreated': (LONG, now), 'TimeModified': (LONG, now), 'EnclosingSize': vec(SX, SY, SZ),
    }),
    'Regions': (COMPOUND, {
        'Main': (COMPOUND, {
            'Position': vec(0, 0, 0), 'Size': vec(SX, SY, SZ),
            'BlockStatePalette': (LIST, (COMPOUND, [{'Name': (STRING, n)} for n in palette])),
            'BlockStates': (LONG_ARRAY, signed),
            'TileEntities': (LIST, (COMPOUND, [])), 'Entities': (LIST, (COMPOUND, [])),
            'PendingBlockTicks': (LIST, (COMPOUND, [])), 'PendingFluidTicks': (LIST, (COMPOUND, [])),
        }),
    }),
}
b = io.BytesIO()
b.write(struct.pack('>b', COMPOUND)); s(b, ''); payload(b, COMPOUND, root)
with gzip.open(sys.argv[1], 'wb') as f:
    f.write(b.getvalue())
print('wrote', sys.argv[1], len(blocks), 'blocks')
