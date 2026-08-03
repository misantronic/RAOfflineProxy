import { S3Client, PutObjectCommand, HeadObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { ADJECTIVES, NOUNS } from './words';

const REGION = process.env.AWS_REGION ?? 'eu-central-1';
const BUCKET = process.env.BUCKET_NAME ?? 'raop-support-logs';
const UPLOAD_EXPIRY_SECONDS = 15 * 60;
const MAX_ID_ATTEMPTS = 5;
const METADATA_FIELD_MAX_LENGTH = 200;
const METADATA_EMULATOR_MAX_ITEMS = 20;

const s3 = new S3Client({ region: REGION });

function respond(statusCode: number, body: unknown) {
    return {
        statusCode,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    };
}

function randomId(): string {
    const adjective = ADJECTIVES[Math.floor(Math.random() * ADJECTIVES.length)];
    const noun = NOUNS[Math.floor(Math.random() * NOUNS.length)];
    const number = Math.floor(Math.random() * 100);
    return `${adjective}-${noun}-${number}`;
}

async function idExists(id: string): Promise<boolean> {
    try {
        await s3.send(new HeadObjectCommand({ Bucket: BUCKET, Key: `${id}.zip` }));
        return true;
    } catch (error: any) {
        if (error?.$metadata?.httpStatusCode === 404) return false;
        throw error;
    }
}

async function generateUniqueId(): Promise<string> {
    for (let attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
        const id = randomId();
        if (!(await idExists(id))) return id;
    }
    throw new Error('Failed to generate a unique log ID');
}

// Client-supplied device info, submitted alongside the log so the support form
// doesn't have to ask for it again when the user provides a Log ID. Every field
// is optional/best-effort — clients send whatever they can reliably detect.
interface UploadMetadata {
    system?: string;
    os?: string;
    device?: string;
    os_version?: string;
    app_version?: string;
    emulator?: string[];
}

const METADATA_STRING_KEYS = ['system', 'os', 'device', 'os_version', 'app_version'] as const;

function parseMetadata(rawBody: string): UploadMetadata {
    let body: any;
    try {
        body = JSON.parse(rawBody || '{}');
    } catch {
        return {};
    }

    const metadata: UploadMetadata = {};
    for (const key of METADATA_STRING_KEYS) {
        const value = body?.[key];
        if (typeof value === 'string' && value.trim().length > 0) {
            metadata[key] = value.trim().slice(0, METADATA_FIELD_MAX_LENGTH);
        }
    }

    if (Array.isArray(body?.emulator)) {
        const emulator = body.emulator
            .filter((value: unknown): value is string => typeof value === 'string' && value.trim().length > 0)
            .map((value: string) => value.trim().slice(0, METADATA_FIELD_MAX_LENGTH))
            .slice(0, METADATA_EMULATOR_MAX_ITEMS);
        if (emulator.length > 0) {
            metadata.emulator = emulator;
        }
    }

    return metadata;
}

exports.handler = async (event: any): Promise<any> => {
    const id = await generateUniqueId();
    const key = `${id}.zip`;

    const metadata = parseMetadata(
        event.isBase64Encoded ? Buffer.from(event.body ?? '', 'base64').toString('utf-8') : event.body ?? ''
    );

    if (Object.keys(metadata).length > 0) {
        // Written synchronously here (not via the client's presigned zip upload) so the
        // client's PUT request stays completely unchanged — no header/signature coordination
        // required between the two steps.
        await s3.send(
            new PutObjectCommand({
                Bucket: BUCKET,
                Key: `${id}.json`,
                Body: JSON.stringify(metadata),
                ContentType: 'application/json'
            })
        );
    }

    const uploadUrl = await getSignedUrl(
        s3,
        new PutObjectCommand({ Bucket: BUCKET, Key: key, ContentType: 'application/zip' }),
        { expiresIn: UPLOAD_EXPIRY_SECONDS }
    );

    return respond(200, { id, uploadUrl, expiresInSeconds: UPLOAD_EXPIRY_SECONDS });
};
