// Download a GitHub revision for offline review/extraction. Does not execute upstream code.
// node scripts/fetch-echo-source.mjs REF OUTPUT_DIRECTORY
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
const [ref, directory] = process.argv.slice(2);
if (!ref || !directory) throw new Error('Expected a GitHub branch, tag or commit and a new output directory');
const repo = 'SugarChain-Studio/echo-clothing-ext';
async function request(url) {
    const result = await fetch(url, { signal: AbortSignal.timeout(30000), headers: { 'User-Agent': 'mobile-bc-echo-data' } });
    if (!result.ok) throw new Error(`HTTP ${result.status}: ${url}`);
    return result;
}
const commit = await (await request(`https://api.github.com/repos/${repo}/commits/${encodeURIComponent(ref)}`)).json();
if (!/^[a-f0-9]{40}$/.test(commit.sha)) throw new Error('Missing resolved commit');
const tree = await (await request(`https://api.github.com/repos/${repo}/git/trees/${commit.sha}?recursive=1`)).json();
if (tree.truncated || !Array.isArray(tree.tree)) throw new Error('Incomplete upstream tree');
// Tree API sha is the tree identity; extraction revision must be the resolved commit identity.
tree.sha = commit.sha;
await fs.mkdir(directory, { recursive: false });
await fs.writeFile(path.join(directory, 'tree.json'), JSON.stringify(tree));
for (const entry of tree.tree) {
    if (entry.type !== 'blob' || !(entry.path.startsWith('src/') && entry.path.endsWith('.js') ||
        ['LICENSE', 'LICENSE-APPENDIX.md'].includes(entry.path))) continue;
    if (entry.path.includes('\\') || entry.path.split('/').some(p => !p || p === '..' || p === '.')) throw new Error('Unsafe source path');
    const response = await request(`https://raw.githubusercontent.com/${repo}/${commit.sha}/${entry.path.split('/').map(encodeURIComponent).join('/')}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    const hash = createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
    if (hash !== entry.sha) throw new Error(`Source hash mismatch: ${entry.path}`);
    const target = path.join(directory, entry.path);
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, bytes);
}
console.log(`Downloaded ${repo}@${commit.sha}. Review license changes and extraction failures before publishing.`);
