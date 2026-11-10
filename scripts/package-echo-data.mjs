// Package reviewed extraction output, never a mod/user-script release.
// node scripts/package-echo-data.mjs SEQUENCE [OUTPUT_DIRECTORY] [PUBLIC_PACK_URL]
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
const [sequenceText, output = 'app/src/main/assets/portrait/echo', url] = process.argv.slice(2);
const sequence = Number(sequenceText);
if (!Number.isSafeInteger(sequence) || sequence < 1) throw new Error('Expected a positive monotonic release sequence');
const read = async file => JSON.parse(await fs.readFile(file, 'utf8'));
const root = 'app/src/main/assets/portrait';
const catalog = await read(`${root}/echo/catalog.json`);
const images = await read(`${root}/echo/images.json`);
const names = await read('domain/bc-text/src/main/resources/echo/names.json');
const supplement = await read(`${root}/supplement/manifest.json`);
if (images.revision !== catalog.revision || names.revision !== catalog.revision ||
    supplement.echoRevision !== catalog.revision) throw new Error('Mixed Echo revisions');
const pack = { format: 1, capability: 'portrait-data-v1', bcVersion: 'R133', sequence,
    catalog, images, names, supplement,
    license: await fs.readFile(`${root}/echo/LICENSE.txt`, 'utf8'),
    licenseAppendix: await fs.readFile(`${root}/echo/LICENSE-APPENDIX.md`, 'utf8') };
const bytes = Buffer.from(JSON.stringify(pack) + '\n');
await fs.mkdir(output, { recursive: true });
await fs.writeFile(path.join(output, 'pack.json'), bytes);
if (url) {
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.search || parsed.hash) {
        throw new Error('Expected an HTTPS public pack URL');
    }
    await fs.writeFile(path.join(output, 'latest.json'), JSON.stringify({ format: 1, sequence, url,
        sha256: createHash('sha256').update(bytes).digest('hex') }, null, 2) + '\n');
}
console.log(`Packaged Echo ${catalog.revision}, sequence ${sequence}, ${bytes.length} bytes`);
