import { SSMClient, GetParameterCommand } from '@aws-sdk/client-ssm';
import { S3Client, GetObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';

const REGION = process.env.AWS_REGION ?? 'eu-central-1';
const BUCKET = process.env.BUCKET_NAME ?? 'raop-support-logs';
const DISCORD_WEBHOOK_URL_PARAM = process.env.DISCORD_WEBHOOK_URL_PARAM ?? '/raop/support-report/discord-webhook-url';
// SigV4's hard cap. Note the URL is only actually valid until the Lambda execution role's
// underlying temporary credentials expire, which is typically much sooner than this.
const LOG_DOWNLOAD_URL_EXPIRY_SECONDS = 7 * 24 * 60 * 60;

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

function respond(statusCode: number, body: unknown) {
    return {
        statusCode,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    };
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

async function buildLogDownloadUrl(logId: string): Promise<string | null> {
    try {
        return await getSignedUrl(
            s3,
            new GetObjectCommand({ Bucket: BUCKET, Key: `${logId}.zip` }),
            { expiresIn: LOG_DOWNLOAD_URL_EXPIRY_SECONDS }
        );
    } catch (error) {
        console.error(`Failed to presign log download URL for ${logId}`, error);
        return null;
    }
}

async function postToDiscord(webhookUrl: string, req: SupportRequest, logDownloadUrl: string | null): Promise<void> {
    const logField = req.log_id
        ? logDownloadUrl
            ? `${req.log_id} — [Download](${logDownloadUrl})`
            : `${req.log_id} (download link failed, fetch manually from S3)`
        : '-';

    const embed = {
        title: 'New RAOfflineProxy support request',
        color: 0x5865f2,
        fields: [
            { name: 'System', value: req.system, inline: true },
            { name: 'Device', value: req.device, inline: true },
            { name: 'OS / firmware', value: req.os_version, inline: true },
            { name: 'App version', value: req.app_version, inline: true },
            { name: 'Emulator / core', value: req.emulator || '-', inline: true },
            { name: 'Log ID', value: logField, inline: true },
            { name: 'Email', value: req.email, inline: false },
            { name: 'What the user reported', value: truncate(req.message, 1024), inline: false }
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

exports.handler = async (event: any): Promise<any> => {
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

    const req: SupportRequest = {
        email: body.email.trim(),
        system: body.system.trim(),
        device: body.device.trim(),
        os_version: body.os_version.trim(),
        app_version: body.app_version.trim(),
        emulator: body.emulator?.trim() || undefined,
        log_id: body.log_id?.trim() || undefined,
        message: body.message.trim()
    };

    try {
        const [webhookUrl, logDownloadUrl] = await Promise.all([
            getDiscordWebhookUrl(),
            req.log_id ? buildLogDownloadUrl(req.log_id) : Promise.resolve(null)
        ]);
        await postToDiscord(webhookUrl, req, logDownloadUrl);
    } catch (error) {
        console.error('Discord notification failed', error);
        return respond(502, { error: 'Could not deliver support request' });
    }

    return respond(200, { ok: true });
};
