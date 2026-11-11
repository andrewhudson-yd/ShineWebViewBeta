// Loads app.config.json, with app.config.local.json (if present, git-ignored) merged on top.
// Use the local file for test settings — e.g. a dev startUrl — without touching the template.
//
// If brand/brand.json exists it is the single source for the app's name and brand colours
// (see `npm run brand`), so a rebrand is one file rather than several.
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');

function merge(base, over) {
  if (Array.isArray(over) || typeof over !== 'object' || over === null) return over;
  const out = { ...base };
  for (const [k, v] of Object.entries(over)) {
    out[k] = base && typeof base[k] === 'object' && !Array.isArray(base[k]) ? merge(base[k], v) : v;
  }
  return out;
}

function readJson(file) {
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null;
}

function fromBrand(brand) {
  const c = brand.colors || {};
  const pick = (v) => (v && c[v]) || v;
  const out = { appName: brand.name, theme: {}, splash: {} };
  if (c.primary) out.theme.accentColor = c.primary;
  const splashBg = pick(brand.splash && brand.splash.background);
  if (splashBg) out.splash.backgroundColor = splashBg;
  return out;
}

function loadAppConfig() {
  let app = readJson(path.join(root, 'app.config.json'));
  const brand = readJson(path.join(root, 'brand', 'brand.json'));
  if (brand) app = merge(app, fromBrand(brand));
  const local = readJson(path.join(root, 'app.config.local.json'));
  return local ? merge(app, local) : app;
}

module.exports = { loadAppConfig };
