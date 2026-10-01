import { readFileSync, writeFileSync } from 'fs';
import { aggregate } from './aggregate';
import { unmarshalRow } from './index';
import { renderPage } from './render';

// Renders the page from a saved scan, e.g.
// aws dynamodb scan --table-name raop-usage --profile kumo-admin > scan.json
// npm run preview -w raop-usage-stats-page -- scan.json stats.html
const [input, output = 'stats.html', now] = process.argv.slice(2);
if (!input) {
    console.error('Usage: npm run preview -- <scan.json> [out.html] [ISO date]');
    process.exit(1);
}
const items = JSON.parse(readFileSync(input, 'utf-8')).Items ?? [];
const rows = items.map(unmarshalRow).filter((row: Record<string, unknown>) => row.pk !== 'secret');
writeFileSync(output, renderPage(aggregate(rows, now ? new Date(now) : new Date())));
console.log(`Wrote ${output} from ${rows.length} rows`);
