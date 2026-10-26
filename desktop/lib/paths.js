const path = require('path');
const os = require('os');
// Contract: %APPDATA%/ObsidianOptimizer, ~/.config/ObsidianOptimizer (mac: Application Support).
function dataDir(platform = process.platform, env = process.env, home = os.homedir()) {
  if (platform === 'win32') return path.win32.join(env.APPDATA || path.win32.join(home, 'AppData', 'Roaming'), 'ObsidianOptimizer');
  if (platform === 'darwin') return path.posix.join(home, 'Library', 'Application Support', 'ObsidianOptimizer');
  return path.posix.join(env.XDG_CONFIG_HOME || path.posix.join(home, '.config'), 'ObsidianOptimizer');
}
const defaultVault = (home = os.homedir()) => path.join(home, 'ObsidianOptimizer', 'vault');
// Finder-launched Electron apps get a minimal PATH on macOS; docker lives in these dirs.
function extendedPath(platform = process.platform, env = process.env) {
  const cur = env.PATH || env.Path || '';
  if (platform !== 'darwin') return cur;
  const extra = ['/usr/local/bin', '/opt/homebrew/bin', '/Applications/Docker.app/Contents/Resources/bin'];
  const have = cur.split(':');
  return [...have.filter(Boolean), ...extra.filter((d) => !have.includes(d))].join(':');
}
module.exports = { dataDir, defaultVault, extendedPath };
