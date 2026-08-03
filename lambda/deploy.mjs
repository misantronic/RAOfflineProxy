import { execSync, spawnSync } from 'child_process';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

const REGION = 'eu-central-1';

const ALL_LAMBDAS = [
    'raop-log-upload',
    'raop-support-report',
    'raop-support-payment'
];

function deployToRegion(name, region, zipPath) {
    const result = spawnSync('aws', [
        'lambda', 'update-function-code',
        '--function-name', name,
        '--zip-file', `fileb://${zipPath}`,
        '--region', region,
        '--profile', 'kumo-admin',
        '--output', 'text',
        '--query', 'CodeSize'
    ], { encoding: 'utf8' });

    if (result.status !== 0) {
        throw new Error(result.stderr.trim() || `exit code ${result.status}`);
    }
    return result.stdout.trim();
}

const name = process.argv[2];

if (!name) {
    console.error('Usage: node deploy.mjs <lambda-name>');
    console.error(`Available: ${ALL_LAMBDAS.join(', ')}`);
    process.exit(1);
}

if (!ALL_LAMBDAS.includes(name)) {
    console.error(`Unknown lambda: ${name}`);
    console.error(`Available: ${ALL_LAMBDAS.join(', ')}`);
    process.exit(1);
}

const zipPath = join(__dirname, name, 'dist', `${name}.zip`);

console.log(`Building ${name}…`);
execSync(`node build.mjs ${name}`, { cwd: __dirname, stdio: 'inherit' });

console.log(`Deploying to ${REGION}…`);
const codeSize = deployToRegion(name, REGION, zipPath);
console.log(`  ✓ ${REGION} (${codeSize} bytes)`);
console.log(`Done.`);
