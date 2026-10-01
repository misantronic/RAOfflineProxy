import assert from 'node:assert/strict';
import { test } from 'node:test';
import { aggregate, deviceLabel, firmwareLabel, foldSmallGroups, Row } from './aggregate';
import { escapeHtml, niceScale, renderPage } from './render';

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
    assert.equal(deviceLabel('MY354'), 'MY354');
});

test('firmware labels keep Android versions and group Linux builds by name', () => {
    assert.equal(firmwareLabel({ os: 'Android', os_version: '14' }), 'Android 14');
    assert.equal(firmwareLabel({ os: 'Knulli', os_version: 'scarab 2026/05/11 00:09' }), 'Knulli');
});

test('groups smaller than three people fold into Other', () => {
    assert.deepEqual(
        foldSmallGroups([
            { label: 'AYN Thor', value: 6 },
            { label: 'Retroid Pocket Nova', value: 3 },
            { label: 'RG40XX-V', value: 1 },
            { label: 'Odin2 Portal', value: 1 }
        ]),
        [
            { label: 'AYN Thor', value: 6 },
            { label: 'Retroid Pocket Nova', value: 3 },
            { label: 'Other', value: 2 }
        ]
    );
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
        { label: 'Other', value: 2 }
    ]);
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
    const html = renderPage(aggregate([monthRow('a', '<script>x</script>')], NOW));
    assert.ok(html.startsWith('<!doctype html>'));
    assert.ok(!html.includes('<script>x</script>'));
    assert.equal(escapeHtml('a&"<'), 'a&amp;&quot;&lt;');
    assert.ok(renderPage(aggregate([], NOW)).includes('No data yet.'));
});

test('axis ticks land on whole, round values', () => {
    assert.deepEqual(niceScale(0), { max: 1, step: 1 });
    assert.deepEqual(niceScale(1), { max: 1, step: 1 });
    assert.deepEqual(niceScale(14), { max: 15, step: 5 });
    assert.deepEqual(niceScale(33), { max: 40, step: 10 });
    assert.deepEqual(niceScale(312), { max: 400, step: 100 });
});
