#!/usr/bin/env node
// Removes the alpha channel from a PNG (App Store icons must not have one).
// Usage: node scripts/flatten-icon.mjs <in.png> [out.png] [#background]
import fs from 'node:fs';
import zlib from 'node:zlib';

const [input, output = input, bgHex = '#000000'] = process.argv.slice(2);
if (!input) { console.error('usage: flatten-icon.mjs <in.png> [out.png] [#bg]'); process.exit(1); }
const buf = fs.readFileSync(input);
const bg = [1, 3, 5].map((i) => parseInt(bgHex.replace('#', '').padEnd(6, '0').slice(i - 1, i + 1), 16));

let pos = 8, width, height, depth, colorType;
const idat = [];
while (pos < buf.length) {
  const len = buf.readUInt32BE(pos), type = buf.toString('ascii', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
  if (type === 'IHDR') { width = data.readUInt32BE(0); height = data.readUInt32BE(4); depth = data[8]; colorType = data[9]; if (data[12]) throw new Error('interlaced PNGs not supported'); }
  if (type === 'IDAT') idat.push(data);
  pos += 12 + len;
}
if (depth !== 8 || (colorType !== 6 && colorType !== 2)) throw new Error(`unsupported PNG (depth ${depth}, colour type ${colorType})`);
if (colorType === 2) { if (output !== input) fs.copyFileSync(input, output); console.log('already opaque:', output); process.exit(0); }

const bpp = 4, stride = width * bpp, raw = zlib.inflateSync(Buffer.concat(idat));
const px = Buffer.alloc(stride * height);
for (let y = 0; y < height; y++) {
  const f = raw[y * (stride + 1)], line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
  const out = px.subarray(y * stride, (y + 1) * stride), prev = y ? px.subarray((y - 1) * stride, y * stride) : null;
  for (let x = 0; x < stride; x++) {
    const a = x >= bpp ? out[x - bpp] : 0, b = prev ? prev[x] : 0, c = prev && x >= bpp ? prev[x - bpp] : 0;
    let v = line[x];
    if (f === 1) v += a; else if (f === 2) v += b; else if (f === 3) v += (a + b) >> 1;
    else if (f === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
    out[x] = v & 255;
  }
}
const rgb = Buffer.alloc((width * 3 + 1) * height);
for (let y = 0; y < height; y++) {
  rgb[y * (width * 3 + 1)] = 0;
  for (let x = 0; x < width; x++) {
    const s = y * stride + x * 4, d = y * (width * 3 + 1) + 1 + x * 3, al = px[s + 3] / 255;
    for (let k = 0; k < 3; k++) rgb[d + k] = Math.round(px[s + k] * al + bg[k] * (1 - al));
  }
}
const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc = (b) => { let c = 0xffffffff; for (const x of b) c = crcTable[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => { const t = Buffer.from(type), l = Buffer.alloc(4), cr = Buffer.alloc(4); l.writeUInt32BE(data.length); cr.writeUInt32BE(crc(Buffer.concat([t, data]))); return Buffer.concat([l, t, data, cr]); };
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4); ihdr[8] = 8; ihdr[9] = 2;
fs.writeFileSync(output, Buffer.concat([buf.subarray(0, 8), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(rgb, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
console.log(`flattened ${width}x${height} → ${output}`);
