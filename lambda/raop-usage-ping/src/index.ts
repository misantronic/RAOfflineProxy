import { createHmac, randomBytes } from 'crypto';
import {
    AttributeValue,
    ConditionalCheckFailedException,
    DynamoDBClient,
    GetItemCommand,
    PutItemCommand,
    UpdateItemCommand
} from '@aws-sdk/client-dynamodb';

const REGION = process.env.AWS_REGION ?? 'eu-central-1';
const TABLE = process.env.TABLE_NAME ?? 'raop-usage';
const RETENTION_SECONDS = 395 * 24 * 60 * 60;
const MAX_BODY_BYTES = 8 * 1024;
const TEXT_MAX_LENGTH = 80;
const MAX_EMULATORS = 20;
const COUNTER_MAX = 10_000_000;
const CLIENT_ID_PATTERN = /^[0-9a-f]{64}$/;
const BUILD_PATTERN = /^[a-z0-9]{1,16}$/;
const GAUGE_PATTERN = /^[0-9a-z<+-]{1,12}$/i;
const PLATFORMS = new Set(['android', 'linux']);
const GAUGE_KEYS = ['cached_games', 'queued_games', 'oldest_queued', 'pending_awards'];
const COUNTER_KEYS = [
    'requests_emulator',
    'requests_award_sync',
    'requests_background',
    'requests_app',
    'max_requests_per_window',
    'failures_network',
    'failures_server',
    'rate_limited',
    'batches',
    'batches_time_limited',
    'batch_ms_total',
    'queue_cached',
    'queue_no_match',
    'queue_emptied',
    'queue_failed'
];
// The busiest window is a maximum, not a sum, so several pings on one day must not add up.
const MAX_COUNTERS = new Set(['max_requests_per_window']);

const ddb = new DynamoDBClient({ region: REGION });
const monthSecrets = new Map<string, Uint8Array>();

interface UsagePing {
    clientId: string;
    platform: string;
    os: string;
    osVersion: string;
    device: string;
    appVersion: string;
    build: string;
    emulators: string[];
    gauges: Record<string, string>;
    counters: Record<string, number>;
}

function respond(statusCode: number, body?: unknown) {
    return body === undefined
        ? { statusCode }
        : { statusCode, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

function text(value: unknown, fallback = 'unknown'): string {
    if (typeof value !== 'string') return fallback;
    const cleaned = value.replace(/[#\u0000-\u001f]/g, '').trim().slice(0, TEXT_MAX_LENGTH);
    return cleaned.length > 0 ? cleaned : fallback;
}

function parseGauges(raw: unknown): Record<string, string> {
    const gauges: Record<string, string> = {};
    if (typeof raw !== 'object' || raw === null) return gauges;
    for (const key of GAUGE_KEYS) {
        const value = (raw as Record<string, unknown>)[key];
        if (typeof value === 'string' && GAUGE_PATTERN.test(value)) gauges[key] = value;
    }
    return gauges;
}

function parseCounters(raw: unknown): Record<string, number> {
    const counters: Record<string, number> = {};
    if (typeof raw !== 'object' || raw === null) return counters;
    for (const key of COUNTER_KEYS) {
        const value = (raw as Record<string, unknown>)[key];
        if (typeof value === 'number' && Number.isInteger(value) && value > 0) {
            counters[key] = Math.min(value, COUNTER_MAX);
        }
    }
    return counters;
}

function parsePing(rawBody: string): UsagePing | null {
    if (Buffer.byteLength(rawBody, 'utf-8') > MAX_BODY_BYTES) return null;
    let body: any;
    try {
        body = JSON.parse(rawBody);
    } catch {
        return null;
    }
    if (typeof body?.client_id !== 'string' || !CLIENT_ID_PATTERN.test(body.client_id)) return null;
    if (!PLATFORMS.has(body?.platform)) return null;

    return {
        clientId: body.client_id,
        platform: body.platform,
        os: text(body.os),
        osVersion: text(body.os_version),
        device: text(body.device),
        appVersion: text(body.app_version),
        build: typeof body.build === 'string' && BUILD_PATTERN.test(body.build) ? body.build : 'unknown',
        emulators: Array.isArray(body.emulators)
            ? body.emulators.map((value: unknown) => text(value, '')).filter(Boolean).slice(0, MAX_EMULATORS)
            : [],
        gauges: parseGauges(body.gauges),
        counters: parseCounters(body.counters)
    };
}

function startOfNextMonthSeconds(now: Date): number {
    return Math.floor(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1) / 1000);
}

async function readSecret(month: string): Promise<Uint8Array | undefined> {
    const result = await ddb.send(
        new GetItemCommand({
            TableName: TABLE,
            Key: { pk: { S: 'secret' }, sk: { S: month } },
            ConsistentRead: true
        })
    );
    return result.Item?.value?.B;
}

// One random secret per month, created by whichever invocation needs it first. Its TTL deletes it
// shortly after the month ends, after which that month's IDs can't be recomputed by anyone.
async function monthSecret(month: string, now: Date): Promise<Uint8Array> {
    const cached = monthSecrets.get(month);
    if (cached) return cached;

    let secret = await readSecret(month);
    if (!secret) {
        const fresh = randomBytes(32);
        try {
            await ddb.send(
                new PutItemCommand({
                    TableName: TABLE,
                    Item: {
                        pk: { S: 'secret' },
                        sk: { S: month },
                        value: { B: fresh },
                        ttl: { N: String(startOfNextMonthSeconds(now) + 24 * 60 * 60) }
                    },
                    ConditionExpression: 'attribute_not_exists(pk)'
                })
            );
            secret = fresh;
        } catch (error) {
            if (!(error instanceof ConditionalCheckFailedException)) throw error;
            secret = await readSecret(month);
        }
    }
    if (!secret) throw new Error(`Monthly secret for ${month} unavailable`);

    monthSecrets.clear();
    monthSecrets.set(month, secret);
    return secret;
}

function anonymousId(secret: Uint8Array, clientId: string): string {
    return createHmac('sha256', secret).update(clientId).digest('hex').slice(0, 32);
}

async function recordMonth(pk: string, sk: string, uid: string, ping: UsagePing, day: string, ttl: number) {
    await ddb.send(
        new UpdateItemCommand({
            TableName: TABLE,
            Key: { pk: { S: pk }, sk: { S: sk } },
            UpdateExpression:
                'SET #uid = :uid, #platform = :platform, #device = :device, #os = :os, #osVersion = :osVersion, ' +
                '#appVersion = :appVersion, #build = :build, #emulators = :emulators, #lastSeen = :day, ' +
                '#firstSeen = if_not_exists(#firstSeen, :day), #ttl = :ttl',
            ExpressionAttributeNames: {
                '#uid': 'uid',
                '#platform': 'platform',
                '#device': 'device',
                '#os': 'os',
                '#osVersion': 'os_version',
                '#appVersion': 'app_version',
                '#build': 'build',
                '#emulators': 'emulators',
                '#lastSeen': 'last_seen',
                '#firstSeen': 'first_seen',
                '#ttl': 'ttl'
            },
            ExpressionAttributeValues: {
                ':uid': { S: uid },
                ':platform': { S: ping.platform },
                ':device': { S: ping.device },
                ':os': { S: ping.os },
                ':osVersion': { S: ping.osVersion },
                ':appVersion': { S: ping.appVersion },
                ':build': { S: ping.build },
                ':emulators': { L: ping.emulators.map((name) => ({ S: name })) },
                ':day': { S: day },
                ':ttl': { N: String(ttl) }
            }
        })
    );
}

async function recordDay(pk: string, sk: string, uid: string, ping: UsagePing, ttl: number) {
    const names: Record<string, string> = {
        '#uid': 'uid',
        '#platform': 'platform',
        '#device': 'device',
        '#appVersion': 'app_version',
        '#ttl': 'ttl'
    };
    const values: Record<string, AttributeValue> = {
        ':uid': { S: uid },
        ':platform': { S: ping.platform },
        ':device': { S: ping.device },
        ':appVersion': { S: ping.appVersion },
        ':ttl': { N: String(ttl) }
    };
    const sets = ['#uid = :uid', '#platform = :platform', '#device = :device', '#appVersion = :appVersion', '#ttl = :ttl'];
    const adds: string[] = [];

    for (const [key, value] of Object.entries(ping.gauges)) {
        names[`#g_${key}`] = key;
        values[`:g_${key}`] = { S: value };
        sets.push(`#g_${key} = :g_${key}`);
    }
    for (const [key, value] of Object.entries(ping.counters)) {
        names[`#c_${key}`] = key;
        values[`:c_${key}`] = { N: String(value) };
        if (MAX_COUNTERS.has(key)) {
            sets.push(`#c_${key} = :c_${key}`);
        } else {
            adds.push(`#c_${key} :c_${key}`);
        }
    }

    const expression = `SET ${sets.join(', ')}` + (adds.length > 0 ? ` ADD ${adds.join(', ')}` : '');
    await ddb.send(
        new UpdateItemCommand({
            TableName: TABLE,
            Key: { pk: { S: pk }, sk: { S: sk } },
            UpdateExpression: expression,
            ExpressionAttributeNames: names,
            ExpressionAttributeValues: values
        })
    );
}

// Never log the event: it carries the caller's IP address in requestContext.
exports.handler = async (event: any): Promise<any> => {
    const rawBody = event.isBase64Encoded
        ? Buffer.from(event.body ?? '', 'base64').toString('utf-8')
        : event.body ?? '';
    const ping = parsePing(rawBody);
    if (!ping) return respond(400, { error: 'invalid_ping' });

    const now = new Date();
    const day = now.toISOString().slice(0, 10);
    const month = day.slice(0, 7);
    const uid = anonymousId(await monthSecret(month, now), ping.clientId);
    const prefix = ping.build === 'release' ? '' : 'dev#';
    const sk = `${uid}#${ping.platform}#${ping.device}`;
    const ttl = Math.floor(now.getTime() / 1000) + RETENTION_SECONDS;

    await recordMonth(`${prefix}month#${month}`, sk, uid, ping, day, ttl);
    await recordDay(`${prefix}day#${day}`, sk, uid, ping, ttl);

    return respond(204);
};
