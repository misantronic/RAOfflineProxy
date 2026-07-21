import { build } from 'esbuild';
import { createWriteStream, mkdirSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import archiver from 'archiver';

const __dirname = dirname(fileURLToPath(import.meta.url));

const packages = [
    'raop-log-upload'
];

async function buildPackage(name) {
    const pkgDir = join(__dirname, name);
    const outDir = join(pkgDir, 'dist');
    const outFile = join(outDir, 'index.js');
    const zipFile = join(outDir, `${name}.zip`);

    mkdirSync(outDir, { recursive: true });

    await build({
        entryPoints: [join(pkgDir, 'src', 'index.ts')],
        bundle: true,
        platform: 'node',
        target: 'node20',
        outfile: outFile,
        external: [],
        minify: false,
        sourcemap: false,
        format: 'cjs'
    });

    await zip(outFile, zipFile);
    console.log(`✓ ${name} → dist/${name}.zip`);
}

function zip(jsFile, zipFile) {
    return new Promise((resolve, reject) => {
        const output = createWriteStream(zipFile);
        const archive = archiver('zip', { zlib: { level: 9 } });
        output.on('close', resolve);
        archive.on('error', reject);
        archive.pipe(output);
        archive.file(jsFile, { name: 'index.js' });
        archive.finalize();
    });
}

const target = process.argv[2];
const toBuild = target ? [target] : packages;

for (const pkg of toBuild) {
    if (!packages.includes(pkg)) {
        console.error(`Unknown package: ${pkg}`);
        console.error(`Available: ${packages.join(', ')}`);
        process.exit(1);
    }
    await buildPackage(pkg);
}
