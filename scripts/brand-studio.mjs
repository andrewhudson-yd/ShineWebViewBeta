#!/usr/bin/env node
// `npm run brand` — opens the brand studio in your browser. It previews the app icon, splash and
// intro video from brand/brand.json + brand/mark.svg, and "Generate everything" writes:
//   • Android launcher icons (legacy, round, adaptive), notification icon, splash images
//   • iOS app icon (opaque, as the App Store requires) and launch image
//   • resources/icon.png, resources/splash.png, resources/splash.mp4 (the intro video)
//   • brand/out/web/ — favicons, touch/PWA icons and the mark, ready to copy into your website
// Only files inside this project can be written.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { exec, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const port = Number(process.env.BRAND_PORT || 5171);
const res = 'android/app/src/main/res';

function pngSize(file) {
  const b = fs.readFileSync(file);
  return [b.readUInt32BE(16), b.readUInt32BE(20)];
}

/** Every image the projects need, with the size the existing placeholders use. */
function targets() {
  const out = [];
  const resDir = path.join(root, res);
  if (fs.existsSync(resDir)) {
    for (const dir of fs.readdirSync(resDir)) {
      const full = path.join(resDir, dir);
      if (!fs.statSync(full).isDirectory()) continue;
      for (const f of fs.readdirSync(full)) {
        const kind = f === 'ic_launcher.png' ? 'square' : f === 'ic_launcher_round.png' ? 'round' : f === 'ic_launcher_foreground.png' ? 'foreground'
          : f === 'splash.png' ? 'splash' : null;
        if (kind) out.push({ path: `${res}/${dir}/${f}`, kind, size: pngSize(path.join(full, f)) });
      }
    }
    const statSizes = { mdpi: 24, hdpi: 36, xhdpi: 48, xxhdpi: 72, xxxhdpi: 96 };
    for (const [d, s] of Object.entries(statSizes)) out.push({ path: `${res}/drawable-${d}/ic_stat_badge.png`, kind: 'silhouette', size: [s, s] });
  }
  const ios = 'ios/App/App/Assets.xcassets';
  if (fs.existsSync(path.join(root, ios))) {
    out.push({ path: `${ios}/AppIcon.appiconset/AppIcon-512@2x.png`, kind: 'full', size: [1024, 1024], opaque: true });
    for (const f of ['splash-2732x2732.png', 'splash-2732x2732-1.png', 'splash-2732x2732-2.png']) out.push({ path: `${ios}/Splash.imageset/${f}`, kind: 'splash', size: [2732, 2732] });
  }
  out.push({ path: 'resources/icon.png', kind: 'full', size: [1024, 1024] });
  out.push({ path: 'resources/splash.png', kind: 'splash', size: [2732, 2732] });
  for (const [f, s, kind] of [['favicon-16.png', 16, 'square'], ['favicon-32.png', 32, 'square'], ['icon-180.png', 180, 'full'], ['icon-192.png', 192, 'full'], ['icon-512.png', 512, 'full'], ['icon-maskable-512.png', 512, 'maskable']]) {
    out.push({ path: `brand/out/web/${f}`, kind, size: [s, s] });
  }
  return out;
}

function inside(p) {
  const full = path.resolve(root, p || '');
  return full.startsWith(root + path.sep) ? full : null;
}

http.createServer((req, resp) => {
  const url = new URL(req.url, 'http://x');
  const send = (code, body, type = 'text/plain') => { resp.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' }); resp.end(body); };
  if (url.pathname === '/' || url.pathname === '/studio.html') return send(200, fs.readFileSync(path.join(root, 'brand/studio.html')), 'text/html');
  if (url.pathname === '/brand.json') return send(200, fs.readFileSync(path.join(root, 'brand/brand.json')), 'application/json');
  if (url.pathname === '/mark.svg') {
    const brand = JSON.parse(fs.readFileSync(path.join(root, 'brand/brand.json'), 'utf8'));
    return send(200, fs.readFileSync(path.join(root, 'brand', brand.mark || 'mark.svg')), 'image/svg+xml');
  }
  if (url.pathname === '/targets') return send(200, JSON.stringify(targets()), 'application/json');
  if (url.pathname === '/save' && req.method === 'POST') {
    const dest = inside(url.searchParams.get('p'));
    if (!dest) return send(400, 'outside project');
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.writeFileSync(dest, Buffer.concat(chunks));
      if (url.searchParams.get('opaque') === '1') execFileSync(process.execPath, [path.join(root, 'scripts/flatten-icon.mjs'), dest, dest, url.searchParams.get('bg') || '#000000']);
      if (url.searchParams.get('p').endsWith('ic_launcher_foreground.png')) writeLauncherBackground(url.searchParams.get('bg'));
      send(200, String(Buffer.concat(chunks).length));
    });
    return;
  }
  if (url.pathname === '/copy-mark' && req.method === 'POST') {
    const brand = JSON.parse(fs.readFileSync(path.join(root, 'brand/brand.json'), 'utf8'));
    fs.mkdirSync(path.join(root, 'brand/out/web'), { recursive: true });
    fs.copyFileSync(path.join(root, 'brand', brand.mark || 'mark.svg'), path.join(root, 'brand/out/web/mark.svg'));
    return send(200, 'ok');
  }
  send(404, 'not found');
}).listen(port, '127.0.0.1', () => {
  const link = `http://127.0.0.1:${port}/`;
  console.log(`Brand studio: ${link}  (Ctrl+C to stop)`);
  if (!process.env.NO_OPEN) exec(process.platform === 'win32' ? `start "" "${link}"` : process.platform === 'darwin' ? `open "${link}"` : `xdg-open "${link}"`);
});

function writeLauncherBackground(hex) {
  const file = path.join(root, res, 'values/ic_launcher_background.xml');
  if (!hex || !fs.existsSync(path.dirname(file))) return;
  fs.writeFileSync(file, `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">${hex}</color>\n</resources>\n`);
}
