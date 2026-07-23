import { SSMClient, GetParameterCommand } from '@aws-sdk/client-ssm';
import { S3Client, GetObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';

const REGION = process.env.AWS_REGION ?? 'eu-central-1';
const BUCKET = process.env.BUCKET_NAME ?? 'raop-support-logs';
const API_BASE_URL = process.env.API_BASE_URL ?? '';
const DISCORD_WEBHOOK_URL_PARAM = process.env.DISCORD_WEBHOOK_URL_PARAM ?? '/raop/support-report/discord-webhook-url';
// Log IDs are always "adjective-noun-number" (see raop-log-upload/src/words.ts).
const LOG_ID_PATTERN = /^[a-z0-9-]{1,100}$/;
// Short-lived: minted fresh at click time, so it only needs to survive the redirect.
const LOG_DOWNLOAD_URL_EXPIRY_SECONDS = 5 * 60;

const ssm = new SSMClient({ region: REGION });
const s3 = new S3Client({ region: REGION });

let webhookUrlCache: string | null = null;

async function getDiscordWebhookUrl(): Promise<string> {
    if (webhookUrlCache) return webhookUrlCache;

    const param = await ssm.send(new GetParameterCommand({ Name: DISCORD_WEBHOOK_URL_PARAM, WithDecryption: true }));
    const value = param.Parameter?.Value;
    if (!value) throw new Error('Missing required SSM parameter');

    webhookUrlCache = value;
    return webhookUrlCache;
}

function respond(statusCode: number, body: unknown, headers?: Record<string, string>) {
    return {
        statusCode,
        headers: { 'Content-Type': 'application/json', ...headers },
        body: JSON.stringify(body)
    };
}

function redirect(location: string) {
    return { statusCode: 302, headers: { Location: location } };
}

interface SupportRequest {
    email: string;
    system: string;
    device: string;
    os_version: string;
    app_version: string;
    emulator?: string;
    log_id?: string;
    message: string;
}

function validate(body: any): body is SupportRequest {
    return (
        typeof body?.email === 'string' && body.email.trim().length > 0 &&
        typeof body?.system === 'string' && body.system.trim().length > 0 &&
        typeof body?.device === 'string' && body.device.trim().length > 0 &&
        typeof body?.os_version === 'string' && body.os_version.trim().length > 0 &&
        typeof body?.app_version === 'string' && body.app_version.trim().length > 0 &&
        typeof body?.message === 'string' && body.message.trim().length > 0
    );
}

function truncate(text: string, max: number): string {
    return text.length > max ? `${text.slice(0, max - 20)}\n...[truncated]` : text;
}

async function postToDiscord(webhookUrl: string, req: SupportRequest): Promise<void> {
    // Deliberately not a presigned S3 URL here: those signed with the Lambda's temporary
    // execution-role credentials run 1500+ chars (huge X-Amz-Security-Token) and blow past
    // Discord's 1024-char field limit, truncating mid-signature into a dead link. This short,
    // stable link redirects to a freshly presigned URL at click time instead.
    const logField = req.log_id
        ? `[${req.log_id}](${API_BASE_URL}/support/logs/${encodeURIComponent(req.log_id)})`
        : '-';

    // Discord rejects the whole request if any single field value exceeds 1024 chars, and
    // several of these are free-text inputs with no client-side length cap.
    const field = (value: string) => truncate(value, 1024);

    const embed = {
        title: 'New RAOfflineProxy support request',
        color: 0x5865f2,
        fields: [
            { name: 'System', value: field(req.system), inline: true },
            { name: 'Device', value: field(req.device), inline: true },
            { name: 'OS / firmware', value: field(req.os_version), inline: true },
            { name: 'App version', value: field(req.app_version), inline: true },
            { name: 'Emulator / core', value: field(req.emulator || '-'), inline: true },
            { name: 'Log ID', value: field(logField), inline: true },
            { name: 'Email', value: field(req.email), inline: false },
            { name: 'What the user reported', value: field(req.message), inline: false }
        ]
    };

    const response = await fetch(webhookUrl, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ embeds: [embed] })
    });

    if (!response.ok) {
        const body = await response.text();
        throw new Error(`Discord webhook failed ${response.status}: ${body.slice(0, 300)}`);
    }
}

async function handleSubmit(event: any): Promise<any> {
    let body: any;
    try {
        const raw = event.isBase64Encoded
            ? Buffer.from(event.body ?? '', 'base64').toString('utf-8')
            : event.body ?? '';
        body = JSON.parse(raw || '{}');
    } catch {
        return respond(400, { error: 'Malformed JSON body' });
    }

    if (!validate(body)) {
        return respond(400, { error: 'Missing required fields' });
    }

    const trimmedLogId = body.log_id?.trim();

    const req: SupportRequest = {
        email: body.email.trim(),
        system: body.system.trim(),
        device: body.device.trim(),
        os_version: body.os_version.trim(),
        app_version: body.app_version.trim(),
        emulator: body.emulator?.trim() || undefined,
        // This endpoint is public and callable directly (not just via the form), so log_id is
        // untrusted input. It's embedded as markdown link text in postToDiscord, so reject
        // anything that doesn't match our own generated ID format rather than sanitizing —
        // it can't be a real log either way.
        log_id: trimmedLogId && LOG_ID_PATTERN.test(trimmedLogId) ? trimmedLogId : undefined,
        message: body.message.trim()
    };

    try {
        const webhookUrl = await getDiscordWebhookUrl();
        await postToDiscord(webhookUrl, req);
    } catch (error) {
        console.error('Discord notification failed', error);
        return respond(502, { error: 'Could not deliver support request' });
    }

    return respond(200, { ok: true });
}

async function handleLogDownload(event: any): Promise<any> {
    const logId = event.pathParameters?.logId;
    if (typeof logId !== 'string' || !LOG_ID_PATTERN.test(logId)) {
        return respond(400, { error: 'Invalid log ID' });
    }

    try {
        const url = await getSignedUrl(
            s3,
            new GetObjectCommand({ Bucket: BUCKET, Key: `${logId}.zip` }),
            { expiresIn: LOG_DOWNLOAD_URL_EXPIRY_SECONDS }
        );
        return redirect(url);
    } catch (error) {
        console.error(`Failed to presign log download URL for ${logId}`, error);
        return respond(404, { error: 'Log not found' });
    }
}

async function handleLogMetadata(event: any): Promise<any> {
    const logId = event.pathParameters?.logId;
    if (typeof logId !== 'string' || !LOG_ID_PATTERN.test(logId)) {
        return respond(400, { error: 'Invalid log ID' });
    }

    try {
        const result = await s3.send(new GetObjectCommand({ Bucket: BUCKET, Key: `${logId}.json` }));
        const raw = await result.Body?.transformToString('utf-8');
        return respond(200, JSON.parse(raw ?? '{}'));
    } catch (error: any) {
        if (error?.$metadata?.httpStatusCode !== 404) {
            console.error(`Failed to fetch metadata for ${logId}`, error);
        }
        return respond(404, { error: 'No metadata for this log ID' });
    }
}

exports.handler = async (event: any): Promise<any> => {
    const method = event.requestContext?.http?.method;
    const routeKey = event.requestContext?.routeKey;

    if (method === 'GET' && routeKey === 'GET /support/logs/{logId}/metadata') {
        return handleLogMetadata(event);
    }

    if (method === 'GET') {
        return handleLogDownload(event);
    }

    return handleSubmit(event);
};
