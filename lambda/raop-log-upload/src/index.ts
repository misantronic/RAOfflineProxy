import { S3Client, PutObjectCommand, HeadObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { ADJECTIVES, NOUNS } from './words';

const REGION = process.env.AWS_REGION ?? 'eu-central-1';
const BUCKET = process.env.BUCKET_NAME ?? 'raop-support-logs';
const UPLOAD_EXPIRY_SECONDS = 15 * 60;
const MAX_ID_ATTEMPTS = 5;

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

exports.handler = async (): Promise<any> => {
    const id = await generateUniqueId();
    const key = `${id}.zip`;

    const uploadUrl = await getSignedUrl(
        s3,
        new PutObjectCommand({ Bucket: BUCKET, Key: key, ContentType: 'application/zip' }),
        { expiresIn: UPLOAD_EXPIRY_SECONDS }
    );

    return respond(200, { id, uploadUrl, expiresInSeconds: UPLOAD_EXPIRY_SECONDS });
};
