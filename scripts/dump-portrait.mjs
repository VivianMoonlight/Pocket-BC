import fs from 'node:fs/promises';
import path from 'node:path';

const args = process.argv.slice(2);
const options = {};
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--include-scene') options.includeScene = true;
  else if (['--port', '--page', '--out'].includes(args[i]) && args[i + 1]) options[args[i].slice(2)] = args[++i];
  else throw new Error('Usage: node scripts/dump-portrait.mjs --out NEW_DIRECTORY [--port 18765] [--page ID] [--include-scene]');
}
if (!options.out) throw new Error('--out must name a new output directory');
const port = Number(options.port || 18765);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid port');
const response = await fetch(`http://127.0.0.1:${port}/json`, { signal: AbortSignal.timeout(5000) });
if (!response.ok) throw new Error(`DevTools HTTP ${response.status}`);
const pages = (await response.json()).filter(page => {
  const url = new URL(page.url);
  return url.pathname === '/portrait/index.html' &&
    (url.origin === 'https://portrait.mobilebc.invalid' ||
      url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname));
});
const page = options.page ? pages.find(page => page.id === options.page) : pages.length === 1 ? pages[0] : null;
if (!page) throw new Error(`Open one portrait, or select --page ID. Available IDs: ${pages.map(page => page.id).join(', ')}`);
const socketUrl = new URL(page.webSocketDebuggerUrl);
if (!['localhost', '127.0.0.1'].includes(socketUrl.hostname) || socketUrl.protocol !== 'ws:') throw new Error('Expected a local DevTools socket');
const socket = new WebSocket(socketUrl);
try {
  const report = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Portrait dump timed out')), 10000);
    const finish = (error, value) => { clearTimeout(timer); error ? reject(error) : resolve(value); };
    socket.addEventListener('error', () => finish(new Error('DevTools connection failed')), { once: true });
    socket.addEventListener('close', () => finish(new Error('DevTools connection closed')), { once: true });
    socket.addEventListener('open', () => socket.send(JSON.stringify({
      id: 1, method: 'Runtime.evaluate', params: {
        expression: `JSON.stringify(MobileBcPortraitDump.capture({includeScene:${Boolean(options.includeScene)},includeCanvas:true}))`,
        returnByValue: true,
      },
    })), { once: true });
    socket.addEventListener('message', event => {
      const result = JSON.parse(event.data);
      if (result.id !== 1) return;
      if (result.error || result.result?.exceptionDetails) return finish(new Error('Dump module unavailable; install a build containing portrait/dump.js'));
      try { finish(null, JSON.parse(result.result.result.value)); } catch (error) { finish(error); }
    });
  });
  const output = path.resolve(options.out);
  await fs.mkdir(path.dirname(output), { recursive: true });
  await fs.mkdir(output); // Do not overwrite an earlier diagnostic capture.
  if (report.canvas.png) {
    await fs.writeFile(path.join(output, 'canvas.png'), Buffer.from(report.canvas.png.split(',')[1], 'base64'));
    delete report.canvas.png;
  }
  await fs.writeFile(path.join(output, 'dump.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ output, state: report.status?.state, checks: report.checks, sceneIncluded: Boolean(report.scene) }, null, 2));
} finally {
  socket.close();
}
