import assert from 'node:assert/strict';
import { test } from 'node:test';
import { aggregate, deviceLabel, firmwareLabel, foldKeepingTop, Row } from './aggregate';
import { escapeHtml, niceScale, renderPage, statsViews } from './render';

const NOW = new Date('2026-10-20T23:55:00Z');

function monthRow(uid: string, device: string, extra: Row = {}): Row {
    return {
        pk: 'month#2026-10',
        sk: `${uid}#android#${device}`,
        uid,
        platform: 'android',
        device,
        os: 'Android',
        os_version: '13',
        app_version: '2.0.0',
        emulators: ['RetroArch'],
        ...extra
    };
}

function dayRow(date: string, uid: string, device: string, extra: Row = {}): Row {
    return { pk: `day#${date}`, sk: `${uid}#android#${device}`, uid, platform: 'android', device, ...extra };
}

test('device labels drop repeated manufacturers and chip vendors', () => {
    assert.equal(deviceLabel('AYN AYN Thor'), 'AYN Thor');
    assert.equal(deviceLabel('QUALCOMM AYANEO Pocket MICRO 2'), 'AYANEO Pocket MICRO 2');
    assert.equal(deviceLabel('Moorechip Retroid Pocket Nova'), 'Retroid Pocket Nova');
    assert.equal(deviceLabel('Retroid Pocket Nova'), 'Retroid Pocket Nova');
});

test('device labels use the manufacturer\'s usual spelling', () => {
    assert.equal(deviceLabel('ayn Odin3'), 'AYN Odin3');
    assert.equal(deviceLabel('ayn AYN Thor'), 'AYN Thor');
    assert.equal(deviceLabel('samsung SM-S928B'), 'Samsung SM-S928B');
    assert.equal(deviceLabel('retroid Pocket 6'), 'Retroid Pocket 6');
    assert.equal(deviceLabel('Google Pixel 3a'), 'Google Pixel 3a');
    assert.equal(deviceLabel('Unheard Of 9'), 'Unheard Of 9');
    assert.equal(deviceLabel('ayn'), 'ayn', 'a lone word is a model name, not a brand');
});

test('Linux hardware identifiers get a readable name', () => {
    assert.equal(deviceLabel('MY354'), 'Miyoo Mini Plus');
    assert.equal(deviceLabel('MY283'), 'Miyoo Mini');
    assert.equal(deviceLabel('TUI-BRICK'), 'Trimui Brick');
    assert.equal(deviceLabel('sun50iw10'), 'Trimui Smart Pro / Brick (sun50iw10)');
    assert.equal(deviceLabel('AnbernicXX720480NoStick'), 'Anbernic 720x480 (no stick)');
    assert.equal(deviceLabel('AnbernicXX640480'), 'Anbernic 640x480');
    assert.equal(deviceLabel('RG35XX-H'), 'Anbernic RG35XX-H');
    assert.equal(deviceLabel('rg40xx-v'), 'Anbernic RG40XX-V');
});

test('unknown hardware identifiers stay as reported', () => {
    assert.equal(deviceLabel('MY999'), 'MY999');
    assert.equal(deviceLabel('Anbernic RG DS'), 'Anbernic RG DS');
    assert.equal(deviceLabel('Anbernic RG556'), 'Anbernic RG556');
    assert.equal(deviceLabel('RGB30'), 'RGB30', 'a name that only starts like an RG model but is not one');
    assert.equal(deviceLabel('LENOVO TB323FU'), 'Lenovo TB323FU');
    assert.equal(deviceLabel('motorola moto g34 5G'), 'Motorola moto g34 5G');
});

test('the same Anbernic model from Knulli and muOS is one device', () => {
    const linux = (uid: string, device: string, os: string): Row => ({
        ...monthRow(uid, device),
        platform: 'linux',
        os,
        sk: `${uid}#linux#${device}`
    });
    const model = aggregate(
        [linux('a', 'Anbernic RG40XX-V', 'Knulli'), linux('b', 'Anbernic RG40XX-V', 'Knulli'), linux('c', 'RG40XX-V', 'muOS')],
        NOW
    );
    assert.deepEqual(model.devices, [{ label: 'Anbernic RG40XX-V', value: 3 }]);
});

test('differently spelled brands count as one device', () => {
    const model = aggregate(
        [monthRow('a', 'AYN Odin3'), monthRow('b', 'ayn Odin3'), monthRow('c', 'AYN AYN Odin3')],
        NOW
    );
    assert.deepEqual(model.devices, [{ label: 'AYN Odin3', value: 3 }]);
});

test('firmware labels keep Android versions and group Linux builds by name', () => {
    assert.equal(firmwareLabel({ os: 'Android', os_version: '14' }), 'Android 14');
    assert.equal(firmwareLabel({ os: 'Knulli', os_version: 'scarab 2026/05/11 00:09' }), 'Knulli');
});

test('the six most used are always listed, the rest needs two people', () => {
    const counts = [5, 3, 2, 1, 1, 1, 1, 1].map((value, index) => ({ label: `D${index}`, value }));
    assert.deepEqual(
        foldKeepingTop(counts).map((c) => [c.label, c.value]),
        [['D0', 5], ['D1', 3], ['D2', 2], ['D3', 1], ['D4', 1], ['D5', 1], ['Other', 2]]
    );
    const popular = [4, 4, 3, 3, 2, 2, 2, 1].map((value, index) => ({ label: `D${index}`, value }));
    assert.deepEqual(
        foldKeepingTop(popular).map((c) => [c.label, c.value]),
        [['D0', 4], ['D1', 4], ['D2', 3], ['D3', 3], ['D4', 2], ['D5', 2], ['D6', 2], ['Other', 1]]
    );
});

test('a platform view only counts that platform', () => {
    const rows = [
        monthRow('a', 'AYN Thor'),
        monthRow('b', 'AYN Thor'),
        { ...monthRow('b', 'RG40XX-H'), platform: 'linux', os: 'Knulli', sk: 'b#linux#RG40XX-H' },
        dayRow('2026-10-20', 'a', 'AYN Thor', { requests_emulator: 5 }),
        { ...dayRow('2026-10-20', 'b', 'RG40XX-H', { requests_emulator: 7 }), platform: 'linux' }
    ];
    const [all, android, linux] = statsViews(rows, NOW);
    assert.deepEqual([all.key, android.key, linux.key], ['all', 'android', 'linux']);
    assert.equal(all.model.peopleThisMonth, 2);
    assert.equal(android.model.peopleThisMonth, 2);
    assert.equal(linux.model.peopleThisMonth, 1);
    assert.equal(linux.model.requestsLast30Days, 7);
    assert.equal(android.model.requestsLast30Days, 5);
    assert.deepEqual(linux.model.firmwares, [{ label: 'Knulli', value: 1 }], 'within the six most used, a single-person firmware is listed');
});

test('the page has one tab per view with All selected by default', () => {
    const html = renderPage(statsViews([monthRow('a', 'AYN Thor')], NOW));
    assert.match(html, /id="tab-all"[^>]*aria-selected="true"/);
    assert.match(html, /id="tab-android"[^>]*aria-selected="false"/);
    assert.match(html, /id="panel-linux"[^>]*hidden/);
    assert.ok(!/id="panel-all"[^>]*hidden/.test(html));
});

test('people are counted once per month even with several devices', () => {
    const model = aggregate(
        [
            monthRow('a', 'AYN AYN Thor'),
            monthRow('a', 'Retroid Pocket Flip2'),
            monthRow('b', 'AYN AYN Thor'),
            monthRow('c', 'AYN AYN Thor'),
            { ...monthRow('d', 'RG40XX-V'), platform: 'linux', os: 'Knulli', os_version: 'scarab' }
        ],
        NOW
    );
    assert.equal(model.peopleThisMonth, 4);
    assert.equal(model.devicesThisMonth, 5);
    assert.deepEqual(model.devices, [
        { label: 'AYN Thor', value: 3 },
        { label: 'Anbernic RG40XX-V', value: 1 },
        { label: 'Retroid Pocket Flip2', value: 1 }
    ], 'within the six most used devices, single-person devices are listed');
    assert.deepEqual(model.platforms, [
        { label: 'Android', value: 3 },
        { label: 'Linux', value: 1 }
    ]);
    assert.equal(model.monthly[model.monthly.length - 1].value, 4);
});

test('dev builds and other months stay out', () => {
    const model = aggregate(
        [
            monthRow('a', 'AYN AYN Thor'),
            { ...monthRow('z', 'AYN Odin2 Portal'), pk: 'dev#month#2026-10' },
            { ...monthRow('y', 'AYN Odin2 Portal'), pk: 'month#2026-09' },
            dayRow('2026-10-20', 'z', 'AYN Odin2 Portal', { pk: 'dev#day#2026-10-20', requests_app: 99 })
        ],
        NOW
    );
    assert.equal(model.peopleThisMonth, 1);
    assert.equal(model.requestsLast30Days, 0);
    assert.equal(model.monthly[model.monthly.length - 2].value, 1);
});

test('daily load sums requests and keeps the busiest single-device window', () => {
    const model = aggregate(
        [
            dayRow('2026-10-20', 'a', 'Thor', { requests_emulator: 10, requests_background: 300, max_requests_per_window: 250, rate_limited: 1 }),
            dayRow('2026-10-20', 'b', 'Nova', { requests_award_sync: 2, max_requests_per_window: 40, failures_network: 3, queue_cached: 90, batches: 2, batches_time_limited: 1 }),
            dayRow('2026-09-01', 'c', 'Nova', { requests_app: 5 })
        ],
        NOW
    );
    const today = model.daily[model.daily.length - 1];
    assert.equal(model.daily.length, 30);
    assert.equal(today.date, '2026-10-20');
    assert.equal(today.people, 2);
    assert.equal(today.requestsTotal, 312);
    assert.equal(today.busiestWindow, 250);
    assert.equal(today.rateLimited, 1);
    assert.equal(today.failures, 3);
    assert.equal(today.queueCached, 90);
    assert.equal(model.requestsLast30Days, 312);
    assert.equal(model.peopleToday, 2);
});

test('library size uses the latest report per device this month', () => {
    const model = aggregate(
        [
            dayRow('2026-10-02', 'a', 'Thor', { cached_games: '1-9' }),
            dayRow('2026-10-19', 'a', 'Thor', { cached_games: '100-249' }),
            dayRow('2026-10-19', 'b', 'Nova', { cached_games: '0' })
        ],
        NOW
    );
    const counts = Object.fromEntries(model.libraries.map((c) => [c.label, c.value]));
    assert.equal(counts['100-249'], 1);
    assert.equal(counts['0'], 1);
    assert.equal(counts['1-9'], 0);
});

test('the page renders with no data and escapes labels', () => {
    const html = renderPage(statsViews([monthRow('a', '<script>x</script>')], NOW));
    assert.ok(html.startsWith('<!doctype html>'));
    assert.ok(!html.includes('<script>x</script>'));
    assert.equal(escapeHtml('a&"<'), 'a&amp;&quot;&lt;');
    assert.ok(renderPage(statsViews([], NOW)).includes('No data yet.'));
});

test('axis ticks land on whole, round values', () => {
    assert.deepEqual(niceScale(0), { max: 1, step: 1 });
    assert.deepEqual(niceScale(1), { max: 1, step: 1 });
    assert.deepEqual(niceScale(14), { max: 15, step: 5 });
    assert.deepEqual(niceScale(33), { max: 40, step: 10 });
    assert.deepEqual(niceScale(312), { max: 400, step: 100 });
});
