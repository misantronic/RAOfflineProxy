import { aggregate, Count, DailyLoad, MIN_SHOWN, PLATFORMS, REQUEST_SOURCES, Row, StatsModel } from './aggregate';

interface Series {
    name: string;
    color: string;
    values: number[];
}

const CHART_WIDTH = 720;
const CHART_HEIGHT = 220;
const MARGIN = { top: 12, right: 8, bottom: 26, left: 44 };
const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export function escapeHtml(value: string): string {
    return value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function formatNumber(value: number): string {
    return Math.round(value).toLocaleString('en-US');
}

function formatPercent(value: number): string {
    return `${Math.round(value * 100)}%`;
}

function shortDate(date: string): string {
    const [, month, day] = date.split('-').map(Number);
    return `${MONTH_NAMES[month - 1]} ${day}`;
}

function shortMonth(month: string): string {
    const [year, mon] = month.split('-').map(Number);
    return `${MONTH_NAMES[mon - 1]} ’${String(year).slice(2)}`;
}

function longMonth(month: string): string {
    const [year, mon] = month.split('-').map(Number);
    return new Date(Date.UTC(year, mon - 1, 1)).toLocaleString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' });
}

/** An axis whose ticks land on whole, round values: the step is 1, 2 or 5 times a power of ten
 *  and there are at most four of them above zero. */
export function niceScale(value: number): { max: number; step: number } {
    if (value <= 0) return { max: 1, step: 1 };
    const rough = value / 4;
    const magnitude = 10 ** Math.floor(Math.log10(rough));
    const factor = [1, 2, 5, 10].find((f) => f * magnitude >= rough) ?? 10;
    const step = Math.max(1, factor * magnitude);
    return { max: Math.ceil(value / step) * step, step };
}

function roundedTopBar(x: number, y: number, width: number, height: number, radius: number): string {
    const r = Math.min(radius, width / 2, height);
    return `M${x},${y + height}V${y + r}Q${x},${y} ${x + r},${y}H${x + width - r}Q${x + width},${y} ${x + width},${y + r}V${y + height}Z`;
}

function legend(series: Series[]): string {
    if (series.length < 2) return '';
    const items = series
        .map((s) => `<li><span class="swatch" style="background:var(${s.color})"></span>${escapeHtml(s.name)}</li>`)
        .join('');
    return `<ul class="legend">${items}</ul>`;
}

function dataTable(labels: string[], series: Series[], format: (value: number) => string): string {
    const head = `<tr><th scope="col"></th>${series.map((s) => `<th scope="col">${escapeHtml(s.name)}</th>`).join('')}</tr>`;
    const body = labels
        .map(
            (label, index) =>
                `<tr><th scope="row">${escapeHtml(label)}</th>${series
                    .map((s) => `<td>${format(s.values[index])}</td>`)
                    .join('')}</tr>`
        )
        .join('');
    return `<details class="table-view"><summary>Show data</summary><table><thead>${head}</thead><tbody>${body}</tbody></table></details>`;
}

/** Vertical bars, stacked when there is more than one series. Every segment carries a tooltip. */
export function columnChart(
    labels: string[],
    axisLabels: string[],
    series: Series[],
    format: (value: number) => string = formatNumber
): string {
    const plotWidth = CHART_WIDTH - MARGIN.left - MARGIN.right;
    const plotHeight = CHART_HEIGHT - MARGIN.top - MARGIN.bottom;
    const totals = labels.map((_, index) => series.reduce((sum, s) => sum + s.values[index], 0));
    if (totals.every((total) => total <= 0)) return '<p class="empty">No data yet.</p>';
    const { max, step: tickStep } = niceScale(Math.max(...totals));
    const slot = plotWidth / labels.length;
    const barWidth = Math.max(2, Math.min(28, slot * 0.7));
    const y = (value: number) => MARGIN.top + plotHeight - (value / max) * plotHeight;

    const ticks: number[] = [];
    for (let tick = 0; tick <= max; tick += tickStep) ticks.push(tick);
    const grid = ticks
        .map(
            (tick) =>
                `<line class="grid${tick === 0 ? ' base' : ''}" x1="${MARGIN.left}" x2="${CHART_WIDTH - MARGIN.right}" y1="${y(tick)}" y2="${y(tick)}"/>` +
                `<text class="tick" x="${MARGIN.left - 6}" y="${y(tick) + 4}" text-anchor="end">${format(tick)}</text>`
        )
        .join('');

    const bars = labels
        .map((_label, index) => {
            const x = MARGIN.left + slot * index + (slot - barWidth) / 2;
            let base = 0;
            const top = series.reduce((last, s, seriesIndex) => (s.values[index] > 0 ? seriesIndex : last), -1);
            const segments = series
                .map((s, seriesIndex) => {
                    const value = s.values[index];
                    if (value <= 0) return '';
                    const y0 = y(base);
                    const y1 = y(base + value);
                    base += value;
                    // A 2px surface gap separates stacked segments.
                    const height = Math.max(1, y0 - y1 - (seriesIndex === top ? 0 : 2));
                    const tip = `${axisLabels[index]}: ${series.length > 1 ? `${s.name} ` : ''}${format(value)}`;
                    const shape =
                        seriesIndex === top
                            ? `<path d="${roundedTopBar(x, y1, barWidth, height, 4)}"`
                            : `<rect x="${x}" y="${y1 + 2}" width="${barWidth}" height="${height}"`;
                    return `${shape} style="fill:var(${s.color})" data-tip="${escapeHtml(tip)}"><title>${escapeHtml(tip)}</title>${seriesIndex === top ? '</path>' : '</rect>'}`;
                })
                .join('');
            const total = totals[index];
            const hit =
                series.length > 1 && total > 0
                    ? `<rect class="hit" x="${MARGIN.left + slot * index}" y="${MARGIN.top}" width="${slot}" height="${plotHeight}" data-tip="${escapeHtml(
                          `${axisLabels[index]}: ${series.map((s) => `${s.name} ${format(s.values[index])}`).join(' · ')}`
                      )}"/>`
                    : '';
            return segments + hit;
        })
        .join('');

    // Every other label is "minor" and hidden on narrow screens, where the text is drawn larger.
    const labelStep = Math.ceil(labels.length / 8);
    const xLabels = axisLabels
        .map((label, index) => {
            const fromEnd = labels.length - 1 - index;
            if (fromEnd % labelStep !== 0) return '';
            const minor = (fromEnd / labelStep) % 2 === 1 ? ' minor' : '';
            return `<text class="tick${minor}" x="${MARGIN.left + slot * index + slot / 2}" y="${CHART_HEIGHT - 8}" text-anchor="middle">${escapeHtml(label)}</text>`;
        })
        .join('');

    const svg = `<svg viewBox="0 0 ${CHART_WIDTH} ${CHART_HEIGHT}" role="img" preserveAspectRatio="xMidYMid meet">${grid}${bars}${xLabels}</svg>`;
    return legend(series) + svg + dataTable(labels, series, format);
}

/** Horizontal bars for categories, with the value written next to each bar. */
export function barList(counts: Count[], format: (value: number) => string = formatNumber, max?: number): string {
    if (counts.length === 0) return '<p class="empty">No data yet.</p>';
    const top = max ?? Math.max(...counts.map((c) => c.value), 1);
    const rows = counts
        .map((count) => {
            const width = top > 0 && count.value > 0 ? Math.max(0.5, (count.value / top) * 100) : 0;
            const tip = `${count.label}: ${format(count.value)}`;
            return `<div class="bar-row" data-tip="${escapeHtml(tip)}"><span class="bar-label">${escapeHtml(count.label)}</span><span class="bar-track"><span class="bar" style="width:${width.toFixed(1)}%"></span></span><span class="bar-value">${format(count.value)}</span></div>`;
        })
        .join('');
    return `<div class="bar-list">${rows}</div>`;
}

function tile(label: string, value: string, note: string): string {
    return `<div class="tile"><div class="tile-label">${escapeHtml(label)}</div><div class="tile-value">${escapeHtml(value)}</div><div class="tile-note">${escapeHtml(note)}</div></div>`;
}

function card(title: string, subtitle: string, body: string, wide = false): string {
    return `<section class="card${wide ? ' wide' : ''}"><h2>${escapeHtml(title)}</h2><p class="subtitle">${escapeHtml(subtitle)}</p>${body}</section>`;
}

function dailyColumns(daily: DailyLoad[], series: Series[]): string {
    return columnChart(
        daily.map((day) => day.date),
        daily.map((day) => shortDate(day.date)),
        series
    );
}

export type ViewKey = 'all' | 'android' | 'linux';

export interface StatsView {
    key: ViewKey;
    label: string;
    model: StatsModel;
}

export function statsViews(rows: Row[], now: Date): StatsView[] {
    return [
        { key: 'all', label: 'All', model: aggregate(rows, now) },
        ...PLATFORMS.map((platform) => ({ key: platform.key, label: platform.label, model: aggregate(rows, now, platform.key) }))
    ];
}

function renderPanel(view: StatsView): string {
    const model = view.model;
    const daily = model.daily;
    const requestSeries: Series[] = REQUEST_SOURCES.map((source, index) => ({
        name: source.label,
        color: `--series-${index + 1}`,
        values: daily.map((day) => day.requests[source.key] ?? 0)
    }));
    const problemSeries: Series[] = [
        { name: 'Rate-limited (429)', color: '--series-1', values: daily.map((day) => day.rateLimited) },
        { name: 'Failed (network or 5xx)', color: '--series-2', values: daily.map((day) => day.failures) }
    ];
    const totalBatches = daily.reduce((sum, day) => sum + day.batches, 0);
    const timeLimited = daily.reduce((sum, day) => sum + day.batchesTimeLimited, 0);
    const month = longMonth(model.month);
    const firmwareTitle =
        view.key === 'android' ? 'Android versions' : view.key === 'linux' ? 'Firmware' : 'Android and firmware versions';

    const tiles = [
        tile('People this month', formatNumber(model.peopleThisMonth), month),
        tile('Active today', formatNumber(model.peopleToday), shortDate(model.today)),
        tile('Devices this month', formatNumber(model.devicesThisMonth), 'one person can use several'),
        tile('Requests to RA', formatNumber(model.requestsLast30Days), 'last 30 days'),
        tile('Rate limits (429)', formatNumber(model.rateLimitedLast30Days), 'last 30 days')
    ].join('');

    const devicesCard = card(
        'Devices',
        `People, ${month} · beyond the ${MIN_SHOWN} most used, devices used by only one person are grouped as Other`,
        barList(model.devices)
    );
    const breakdownCards = [
        view.key === 'all' ? card('Platforms', `People, ${month}`, barList(model.platforms)) : '',
        card(
            firmwareTitle,
            `People, ${month} · beyond the ${MIN_SHOWN} most used, versions used by only one person are grouped as Other`,
            barList(model.firmwares)
        ),
        card('Enabled emulators', `Share of devices, ${month}`, barList(model.emulators, formatPercent, 1)),
        card('Library size', 'Devices by number of cached games, latest report this month', barList(model.libraries)),
        card('App versions', `People, ${month}`, barList(model.appVersions))
    ].join('');

    const cards = [
        card(
            'Daily active people',
            'People who sent a report that day, last 30 days',
            dailyColumns(daily, [{ name: 'People', color: '--series-1', values: daily.map((day) => day.people) }]),
            true
        ),
        card(
            'Monthly active people',
            'Last 12 months',
            columnChart(
                model.monthly.map((m) => m.label),
                model.monthly.map((m) => shortMonth(m.label)),
                [{ name: 'People', color: '--series-1', values: model.monthly.map((m) => m.value) }]
            ),
            true
        ),
        card('Requests to RetroAchievements', 'Per day, by what sent them', dailyColumns(daily, requestSeries), true),
        card(
            'Busiest 30-minute window',
            'Most requests a single device sent within one 30-minute window, per day',
            dailyColumns(daily, [{ name: 'Requests', color: '--series-1', values: daily.map((day) => day.busiestWindow) }]),
            true
        ),
        card('Rate limits and failures', 'Requests per day that did not go through', dailyColumns(daily, problemSeries), true),
        card(
            'Background caching',
            `Games cached by the queue per day · ${formatNumber(totalBatches)} batches in 30 days, ${
                totalBatches ? formatPercent(timeLimited / totalBatches) : '0%'
            } stopped by the 10-minute limit`,
            dailyColumns(daily, [{ name: 'Games cached', color: '--series-1', values: daily.map((day) => day.queueCached) }]),
            true
        ),
        `<div class="split"><div class="col">${devicesCard}</div><div class="col">${breakdownCards}</div></div>`
    ].join('');

    return `<div class="tiles">${tiles}</div><div class="grid">${cards}</div>`;
}

/** One static page with a tab per view; "all" comes first and is shown by default. */
export function renderPage(views: StatsView[]): string {
    const first = views[0].model;
    const generated = new Date(first.generatedAt);
    const generatedLabel = `${shortDate(first.today)}, ${String(generated.getUTCHours()).padStart(2, '0')}:${String(
        generated.getUTCMinutes()
    ).padStart(2, '0')} UTC`;

    const tabs = views
        .map(
            (view, index) =>
                `<button type="button" role="tab" id="tab-${view.key}" aria-controls="panel-${view.key}" aria-selected="${
                    index === 0
                }" tabindex="${index === 0 ? 0 : -1}" data-view="${view.key}">${escapeHtml(view.label)}<span class="count">${formatNumber(
                    view.model.peopleThisMonth
                )}</span></button>`
        )
        .join('');
    const panels = views
        .map(
            (view, index) =>
                `<section role="tabpanel" id="panel-${view.key}" aria-labelledby="tab-${view.key}"${
                    index === 0 ? '' : ' hidden'
                }>${renderPanel(view)}</section>`
        )
        .join('');

    return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RAOfflineProxy Stats</title>
<meta name="description" content="Anonymous, opt-in usage statistics of RAOfflineProxy">
<meta name="robots" content="noindex">
<link rel="icon" type="image/png" href="/logo.png">
<style>${STYLES}</style>
</head>
<body>
<main class="viz-root">
<header class="page-header">
<a class="home" href="/"><img src="/logo.png" alt="" width="40" height="40">RAOfflineProxy</a>
<h1>Usage statistics</h1>
<p class="lede">Anonymous, opt-in statistics from the Android and Linux apps. Only people who agreed to share are counted, so real usage is higher. Updated daily · last update ${escapeHtml(generatedLabel)}.</p>
</header>
<div class="tabs" role="tablist" aria-label="Platform">${tabs}</div>
<p class="tab-note">Counters show people this month. Someone using both platforms counts once in All and once in each platform tab.</p>
${panels}
<footer>How this data is collected: <a href="/privacy-policy.html#anonymous-usage-statistics">privacy policy</a>.</footer>
</main>
<div class="tooltip" role="status" hidden></div>
<script>${TOOLTIP_SCRIPT}${TABS_SCRIPT}</script>
</body>
</html>
`;
}

const STYLES = `
:root{color-scheme:dark;--page:#0d0d0d;--surface-1:#1a1a19;--text-primary:#fff;--text-secondary:#c3c2b7;--muted:#898781;--grid:#2c2c2a;--axis:#383835;--border:rgba(255,255,255,.10);--brand:#4d8bbf;--series-1:#3987e5;--series-2:#d95926;--series-3:#199e70;--series-4:#c98500}
*{box-sizing:border-box}
body{margin:0;background:var(--page);color:var(--text-primary);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:1120px;margin:0 auto;padding:24px 16px 48px}
a{color:var(--brand)}
.home{display:inline-flex;align-items:center;gap:10px;font-weight:600;text-decoration:none;color:var(--text-primary)}
h1{margin:16px 0 4px;font-size:28px;line-height:1.2;color:var(--brand)}
.lede{margin:0;color:var(--text-secondary);max-width:70ch}
.tabs{display:flex;flex-wrap:wrap;gap:8px;margin:24px 0 6px}
.tabs button{display:inline-flex;align-items:center;gap:8px;font:inherit;font-weight:600;color:var(--text-secondary);background:var(--surface-1);border:1px solid var(--border);border-radius:999px;padding:6px 14px;cursor:pointer}
.tabs button:hover{color:var(--text-primary)}
.tabs button[aria-selected="true"]{color:var(--text-primary);border-color:var(--brand);box-shadow:inset 0 0 0 1px var(--brand)}
.tabs button:focus-visible{outline:2px solid var(--brand);outline-offset:2px}
.tabs .count{font-variant-numeric:tabular-nums;font-weight:600;font-size:12px;color:var(--text-primary);background:var(--border);border-radius:999px;padding:1px 8px}
.tab-note{margin:0;color:var(--muted);font-size:12px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin:16px 0 24px}
.tile,.card{background:var(--surface-1);border:1px solid var(--border);border-radius:12px}
.tile{padding:14px 16px}
.tile-label{color:var(--text-secondary);font-size:13px}
.tile-value{font-size:30px;font-weight:600;line-height:1.2;margin:2px 0}
.tile-note{color:var(--muted);font-size:12px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,340px),1fr));gap:12px}
.card{padding:16px;min-width:0}
.card.wide{grid-column:1/-1}
.split{grid-column:1/-1;display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,340px),1fr));gap:12px;align-items:start}
.col{display:grid;gap:12px;min-width:0}
h2{margin:0;font-size:16px}
.subtitle{margin:2px 0 12px;color:var(--text-secondary);font-size:13px}
svg{display:block;width:100%;height:auto;overflow:visible}
.grid line,line.grid{stroke:var(--grid);stroke-width:1}
line.base{stroke:var(--axis)}
.tick{fill:var(--muted);font-size:11px;font-variant-numeric:tabular-nums}
.hit{fill:transparent}
.legend{display:flex;flex-wrap:wrap;gap:4px 16px;list-style:none;margin:0 0 8px;padding:0;color:var(--text-secondary);font-size:13px}
.legend li{display:inline-flex;align-items:center;gap:6px}
.swatch{width:10px;height:10px;border-radius:3px;display:inline-block}
.bar-list{display:grid;gap:6px}
.bar-row{display:grid;grid-template-columns:minmax(0,11rem) 1fr auto;align-items:center;gap:10px;font-size:13px}
.bar-label{overflow-wrap:anywhere;color:var(--text-secondary)}
.bar-track{height:12px}
.bar{display:block;height:100%;background:var(--series-1);border-radius:0 4px 4px 0}
.bar-value{font-variant-numeric:tabular-nums;color:var(--text-primary);min-width:3ch;text-align:right}
.table-view{margin-top:8px;font-size:13px;color:var(--text-secondary)}
.table-view summary{cursor:pointer}
.table-view table{border-collapse:collapse;margin-top:8px;width:100%;font-variant-numeric:tabular-nums}
.table-view th,.table-view td{padding:3px 8px;border-bottom:1px solid var(--grid);text-align:right}
.table-view th[scope=row],.table-view thead th:first-child{text-align:left}
.empty{color:var(--muted);margin:0}
footer{margin-top:24px;color:var(--text-secondary);font-size:13px}
.tooltip{position:fixed;z-index:10;pointer-events:none;background:var(--surface-1);color:var(--text-primary);border:1px solid var(--border);border-radius:8px;padding:6px 10px;font-size:13px;box-shadow:0 4px 16px rgba(0,0,0,.16);max-width:320px}
@media (max-width:520px){h1{font-size:23px}.tile-value{font-size:24px}.bar-row{grid-template-columns:minmax(0,8rem) 1fr auto}.tick{font-size:20px}.tick.minor{display:none}}
`;

// Tab switching with the selection kept in the URL hash (stats.html#linux), arrow keys between
// tabs as the ARIA tabs pattern expects.
const TABS_SCRIPT = `
(function(){var tabs=[].slice.call(document.querySelectorAll('[role=tab]'));function select(key,focus){var found=tabs.some(function(t){return t.dataset.view===key;});if(!found)key=tabs[0].dataset.view;tabs.forEach(function(t){var on=t.dataset.view===key;t.setAttribute('aria-selected',on);t.tabIndex=on?0:-1;document.getElementById(t.getAttribute('aria-controls')).hidden=!on;if(on&&focus)t.focus();});}tabs.forEach(function(t,i){t.addEventListener('click',function(){select(t.dataset.view);history.replaceState(null,'','#'+t.dataset.view);});t.addEventListener('keydown',function(e){var d=e.key==='ArrowRight'?1:e.key==='ArrowLeft'?-1:0;if(!d)return;e.preventDefault();var next=tabs[(i+d+tabs.length)%tabs.length];select(next.dataset.view,true);history.replaceState(null,'','#'+next.dataset.view);});});select(location.hash.slice(1));window.addEventListener('hashchange',function(){select(location.hash.slice(1));});})();
`;

const TOOLTIP_SCRIPT = `
(function(){var tip=document.querySelector('.tooltip');function show(e){var t=e.target.closest('[data-tip]');if(!t){tip.hidden=true;return;}tip.textContent=t.getAttribute('data-tip');tip.hidden=false;var x=Math.min(e.clientX+14,window.innerWidth-tip.offsetWidth-8);var y=e.clientY+14;if(y+tip.offsetHeight>window.innerHeight-8)y=e.clientY-tip.offsetHeight-10;tip.style.left=x+'px';tip.style.top=y+'px';}document.addEventListener('pointermove',show);document.addEventListener('pointerdown',show);document.addEventListener('scroll',function(){tip.hidden=true;},{passive:true});})();
`;
