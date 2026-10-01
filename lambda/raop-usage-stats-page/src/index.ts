import { AttributeValue, DynamoDBClient, ScanCommand } from '@aws-sdk/client-dynamodb';
import { PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { aggregate, Row } from './aggregate';
import { renderPage } from './render';

const REGION = process.env.AWS_REGION ?? 'eu-central-1';
const TABLE = process.env.TABLE_NAME ?? 'raop-usage';
const BUCKET = process.env.SITE_BUCKET ?? 'ra-offline-proxy-web';
const PAGE_KEY = process.env.PAGE_KEY ?? 'stats.html';
// Short enough that CloudFront picks up the daily rebuild without an invalidation.
const CACHE_CONTROL = 'public, max-age=300';

const ddb = new DynamoDBClient({ region: REGION });
const s3 = new S3Client({ region: REGION });

export function unmarshal(value: AttributeValue): unknown {
    if (value.S !== undefined) return value.S;
    if (value.N !== undefined) return Number(value.N);
    if (value.BOOL !== undefined) return value.BOOL;
    if (value.NULL !== undefined) return null;
    if (value.L !== undefined) return value.L.map(unmarshal);
    if (value.M !== undefined) return unmarshalRow(value.M);
    return undefined;
}

export function unmarshalRow(item: Record<string, AttributeValue>): Row {
    const row: Row = {};
    for (const [key, value] of Object.entries(item)) row[key] = unmarshal(value);
    return row;
}

// The monthly HMAC secrets never leave the table: they are skipped in the scan itself.
async function loadRows(): Promise<Row[]> {
    const rows: Row[] = [];
    let startKey: Record<string, AttributeValue> | undefined;
    do {
        const page = await ddb.send(
            new ScanCommand({
                TableName: TABLE,
                ExclusiveStartKey: startKey,
                FilterExpression: 'pk <> :secret',
                ExpressionAttributeValues: { ':secret': { S: 'secret' } }
            })
        );
        for (const item of page.Items ?? []) rows.push(unmarshalRow(item));
        startKey = page.LastEvaluatedKey;
    } while (startKey);
    return rows;
}

export const handler = async (): Promise<{ rows: number; key: string }> => {
    const rows = await loadRows();
    const html = renderPage(aggregate(rows, new Date()));
    await s3.send(
        new PutObjectCommand({
            Bucket: BUCKET,
            Key: PAGE_KEY,
            Body: html,
            ContentType: 'text/html; charset=utf-8',
            CacheControl: CACHE_CONTROL
        })
    );
    console.log(`Rendered ${PAGE_KEY} from ${rows.length} rows`);
    return { rows: rows.length, key: PAGE_KEY };
};
