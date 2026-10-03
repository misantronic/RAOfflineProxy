export type Row = Record<string, unknown>;

// Devices and firmwares used by fewer people are folded into "Other" on the public page, so a
// device only one person uses can't single out its owner. The most used ones are always listed,
// so the charts say something even while most devices have a single user.
export const MIN_GROUP_SIZE = 2;
export const MIN_SHOWN = 6;

export type Platform = 'android' | 'linux';
export const PLATFORMS: { key: Platform; label: string }[] = [
    { key: 'android', label: 'Android' },
    { key: 'linux', label: 'Linux' }
];
export const DAILY_WINDOW_DAYS = 30;
export const MONTHLY_WINDOW_MONTHS = 12;

export const REQUEST_SOURCES = [
    { key: 'requests_emulator', label: 'Emulators' },
    { key: 'requests_award_sync', label: 'Award sync' },
    { key: 'requests_background', label: 'Background caching' },
    { key: 'requests_app', label: 'App actions' }
] as const;

export const CACHED_GAMES_BUCKETS = ['0', '1-9', '10-49', '50-99', '100-249', '250-499', '500-999', '1000-2499', '2500+'];

export interface Count {
    label: string;
    value: number;
}

export interface DailyLoad {
    date: string;
    people: number;
    requests: Record<string, number>;
    requestsTotal: number;
    rateLimited: number;
    failures: number;
    busiestWindow: number;
    queueCached: number;
    batches: number;
    batchesTimeLimited: number;
}

export interface StatsModel {
    generatedAt: string;
    month: string;
    today: string;
    peopleThisMonth: number;
    devicesThisMonth: number;
    peopleToday: number;
    requestsLast30Days: number;
    rateLimitedLast30Days: number;
    monthly: Count[];
    daily: DailyLoad[];
    platforms: Count[];
    devices: Count[];
    firmwares: Count[];
    appVersions: Count[];
    emulators: Count[];
    libraries: Count[];
}

// Chip vendors and OEMs whose name only precedes the real brand ("Moorechip Retroid Pocket 5").
const STRIPPED_PREFIXES = ['QUALCOMM ', 'MediaTek ', 'Rockchip ', 'Moorechip ', 'unknown '];

// Manufacturers spell their own name differently from device to device ("ayn Odin3" next to
// "AYN Thor"). Matched case-insensitively on the first word; unknown brands stay as reported.
const BRAND_SPELLINGS: Record<string, string> = Object.fromEntries(
    ['AYN', 'AYANEO', 'Anbernic', 'Retroid', 'Samsung', 'Google', 'MANGMI', 'Lenovo', 'Motorola'].map((brand) => [
        brand.toLowerCase(),
        brand
    ])
);

// Linux handhelds report their own hardware identifiers, often without a brand, so the same device
// shows up under several names. Keys are lowercase; anything not listed stays as reported.
const HARDWARE_ALIASES: Record<string, string> = {
    'tui-brick': 'Trimui Brick',
    // The Allwinner A133 chip: what Knulli reports on Trimui devices without a devicetree model.
    sun50iw10: 'Trimui Smart Pro / Brick (sun50iw10)',
    // Onion's own model codes (/tmp/deviceModel).
    my283: 'Miyoo Mini',
    my354: 'Miyoo Mini Plus'
};

/** spruce's platform IDs name a screen layout, not a model ("AnbernicXX720480NoStick"), so they
 *  are shown as that and never guessed into a model. muOS reports Anbernic boards without the
 *  brand ("RG35XX-H"), which would otherwise split from Knulli's "Anbernic RG35XX-H". */
function knownHardware(label: string): string | undefined {
    const alias = HARDWARE_ALIASES[label.toLowerCase()];
    if (alias) return alias;
    const spruce = /^AnbernicXX(\d{3})(\d{3})(NoStick)?$/.exec(label);
    if (spruce) return `Anbernic ${spruce[1]}x${spruce[2]}${spruce[3] ? ' (no stick)' : ''}`;
    if (/^RG\d/i.test(label)) return `Anbernic ${label.toUpperCase()}`;
    return undefined;
}

function text(row: Row, key: string): string {
    const value = row[key];
    return typeof value === 'string' && value.length > 0 ? value : 'unknown';
}

function num(row: Row, key: string): number {
    const value = row[key];
    return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/** Android reports "<manufacturer> <model>", and some models repeat the manufacturer
 *  ("AYN AYN Thor"), or the manufacturer is a chip vendor or OEM ("QUALCOMM AYANEO Pocket MICRO 2").
 *  The manufacturer's own spelling is normalized so one device isn't split by capitalization. */
export function deviceLabel(raw: string): string {
    let label = raw.trim().replace(/\s+/g, ' ');
    for (const prefix of STRIPPED_PREFIXES) {
        if (label.toLowerCase().startsWith(prefix.toLowerCase()) && label.length > prefix.length) {
            label = label.slice(prefix.length);
        }
    }
    const [first, ...rest] = label.split(' ');
    if (rest.length > 0 && rest[0].toLowerCase() === first.toLowerCase()) {
        label = rest.join(' ');
    }
    const hardware = knownHardware(label);
    if (hardware) return hardware;
    const [brand, ...model] = label.split(' ');
    const spelling = BRAND_SPELLINGS[brand.toLowerCase()];
    return spelling && model.length > 0 ? [spelling, ...model].join(' ') : label;
}

/** "Android 13" stays as it is; Linux firmwares are grouped by name, not by build string. */
export function firmwareLabel(row: Row): string {
    const os = text(row, 'os');
    if (os === 'Android') return `Android ${text(row, 'os_version')}`;
    return os;
}

export function dateOffset(date: string, days: number): string {
    const value = new Date(`${date}T00:00:00Z`);
    value.setUTCDate(value.getUTCDate() + days);
    return value.toISOString().slice(0, 10);
}

export function monthOffset(month: string, months: number): string {
    const [year, mon] = month.split('-').map(Number);
    const value = new Date(Date.UTC(year, mon - 1 + months, 1));
    return value.toISOString().slice(0, 7);
}

function partition(row: Row): string {
    return typeof row.pk === 'string' ? row.pk : '';
}

function peopleCounts(rows: Row[], labelOf: (row: Row) => string): Count[] {
    const people = new Map<string, Set<string>>();
    for (const row of rows) {
        const label = labelOf(row);
        if (!people.has(label)) people.set(label, new Set());
        people.get(label)!.add(text(row, 'uid'));
    }
    return [...people.entries()]
        .map(([label, uids]) => ({ label, value: uids.size }))
        .sort((a, b) => b.value - a.value || a.label.localeCompare(b.label));
}

/** Keeps the first minShown entries (counts are sorted, most people first) and any further entry
 *  with at least minSize people; everything else becomes "Other". */
export function foldKeepingTop(
    counts: Count[],
    minShown: number = MIN_SHOWN,
    minSize: number = MIN_GROUP_SIZE
): Count[] {
    const kept = counts.filter((count, index) => index < minShown || count.value >= minSize);
    const other = counts.filter((count) => !kept.includes(count)).reduce((sum, count) => sum + count.value, 0);
    return other > 0 ? [...kept, { label: 'Other', value: other }] : kept;
}

function latestPerDevice(rows: Row[]): Row[] {
    const latest = new Map<string, Row>();
    for (const row of rows) {
        const key = typeof row.sk === 'string' ? row.sk : '';
        const current = latest.get(key);
        if (!current || partition(row) > partition(current)) latest.set(key, row);
    }
    return [...latest.values()];
}

function dailyLoad(date: string, rows: Row[]): DailyLoad {
    const requests: Record<string, number> = {};
    for (const source of REQUEST_SOURCES) {
        requests[source.key] = rows.reduce((sum, row) => sum + num(row, source.key), 0);
    }
    const sum = (key: string) => rows.reduce((total, row) => total + num(row, key), 0);
    return {
        date,
        people: new Set(rows.map((row) => text(row, 'uid'))).size,
        requests,
        requestsTotal: Object.values(requests).reduce((a, b) => a + b, 0),
        rateLimited: sum('rate_limited'),
        failures: sum('failures_network') + sum('failures_server'),
        busiestWindow: rows.reduce((max, row) => Math.max(max, num(row, 'max_requests_per_window')), 0),
        queueCached: sum('queue_cached'),
        batches: sum('batches'),
        batchesTimeLimited: sum('batches_time_limited')
    };
}

/** Only release rows ("month#…", "day#…") count; dev builds ("dev#…") and secrets are skipped.
 *  With a platform, only that platform's rows count. */
export function aggregate(rows: Row[], now: Date, platform?: Platform): StatsModel {
    if (platform) rows = rows.filter((row) => row.platform === platform);
    const today = now.toISOString().slice(0, 10);
    const month = today.slice(0, 7);
    const monthRows = rows.filter((row) => partition(row).startsWith('month#'));
    const dayRows = rows.filter((row) => partition(row).startsWith('day#'));
    const currentMonth = monthRows.filter((row) => partition(row) === `month#${month}`);
    const currentMonthDays = dayRows.filter((row) => partition(row).startsWith(`day#${month}`));

    const monthly: Count[] = [];
    for (let offset = MONTHLY_WINDOW_MONTHS - 1; offset >= 0; offset--) {
        const label = monthOffset(month, -offset);
        const uids = new Set(monthRows.filter((row) => partition(row) === `month#${label}`).map((row) => text(row, 'uid')));
        monthly.push({ label, value: uids.size });
    }

    const daily: DailyLoad[] = [];
    for (let offset = DAILY_WINDOW_DAYS - 1; offset >= 0; offset--) {
        const date = dateOffset(today, -offset);
        daily.push(dailyLoad(date, dayRows.filter((row) => partition(row) === `day#${date}`)));
    }

    const devicesWithEmulators = currentMonth.length;
    const emulatorCounts = new Map<string, number>();
    for (const row of currentMonth) {
        const emulators = Array.isArray(row.emulators) ? row.emulators : [];
        for (const name of new Set(emulators.filter((e): e is string => typeof e === 'string'))) {
            emulatorCounts.set(name, (emulatorCounts.get(name) ?? 0) + 1);
        }
    }

    const libraryRows = latestPerDevice(currentMonthDays);
    const libraryCounts = CACHED_GAMES_BUCKETS.map((bucket) => ({
        label: bucket,
        value: libraryRows.filter((row) => row.cached_games === bucket).length
    }));

    return {
        generatedAt: now.toISOString(),
        month,
        today,
        peopleThisMonth: new Set(currentMonth.map((row) => text(row, 'uid'))).size,
        devicesThisMonth: currentMonth.length,
        peopleToday: daily[daily.length - 1].people,
        requestsLast30Days: daily.reduce((sum, day) => sum + day.requestsTotal, 0),
        rateLimitedLast30Days: daily.reduce((sum, day) => sum + day.rateLimited, 0),
        monthly,
        daily,
        platforms: peopleCounts(currentMonth, (row) => (row.platform === 'linux' ? 'Linux' : 'Android')),
        devices: foldKeepingTop(peopleCounts(currentMonth, (row) => deviceLabel(text(row, 'device')))),
        firmwares: foldKeepingTop(peopleCounts(currentMonth, firmwareLabel)),
        appVersions: peopleCounts(currentMonth, (row) => text(row, 'app_version')),
        emulators: [...emulatorCounts.entries()]
            .map(([label, value]) => ({ label, value: devicesWithEmulators ? value / devicesWithEmulators : 0 }))
            .sort((a, b) => b.value - a.value || a.label.localeCompare(b.label)),
        libraries: libraryCounts
    };
}
