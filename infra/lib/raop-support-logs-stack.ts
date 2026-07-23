import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as apigwv2 from 'aws-cdk-lib/aws-apigatewayv2';
import * as apigwv2_integrations from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import { Construct } from 'constructs';

const LAMBDA_DIR = path.join(__dirname, '..', '..', 'lambda');

export class RaopSupportLogsStack extends cdk.Stack {
    constructor(scope: Construct, id: string, props?: cdk.StackProps) {
        super(scope, id, props);

        const bucket = new s3.Bucket(this, 'SupportLogsBucket', {
            bucketName: 'raop-support-logs',
            removalPolicy: cdk.RemovalPolicy.RETAIN,
            lifecycleRules: [
                {
                    id: 'ExpireSupportLogs',
                    expiration: cdk.Duration.days(30)
                }
            ]
        });

        const uploadRole = new iam.Role(this, 'LogUploadLambdaRole', {
            roleName: 'raop-log-upload-lambda-role',
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole')
            ],
            inlinePolicies: {
                SupportLogsBucketPolicy: new iam.PolicyDocument({
                    statements: [
                        new iam.PolicyStatement({
                            actions: ['s3:PutObject', 's3:GetObject'],
                            resources: [bucket.arnForObjects('*')]
                        }),
                        new iam.PolicyStatement({
                            // Needed so HeadObject returns a real 404 for missing keys instead of a
                            // permission-denying 403 (S3's documented no-ListBucket behavior).
                            actions: ['s3:ListBucket'],
                            resources: [bucket.bucketArn]
                        })
                    ]
                })
            }
        });

        const requestUploadFn = new lambda.Function(this, 'RequestUploadFn', {
            functionName: 'raop-log-upload',
            runtime: lambda.Runtime.NODEJS_24_X,
            handler: 'index.handler',
            role: uploadRole,
            code: lambda.Code.fromAsset(
                path.join(LAMBDA_DIR, 'raop-log-upload', 'dist', 'raop-log-upload.zip')
            ),
            environment: { BUCKET_NAME: bucket.bucketName },
            timeout: cdk.Duration.seconds(10),
            memorySize: 256
        });

        const DISCORD_WEBHOOK_URL_PARAM = '/raop/support-report/discord-webhook-url';

        const supportReportRole = new iam.Role(this, 'SupportReportLambdaRole', {
            roleName: 'raop-support-report-lambda-role',
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole')
            ],
            inlinePolicies: {
                SupportLogsReadPolicy: new iam.PolicyDocument({
                    statements: [
                        new iam.PolicyStatement({
                            // Used only to presign a GET download link for the maintainer, not to
                            // read the log contents server-side.
                            actions: ['s3:GetObject'],
                            resources: [bucket.arnForObjects('*')]
                        })
                    ]
                }),
                SupportReportSecretsPolicy: new iam.PolicyDocument({
                    statements: [
                        new iam.PolicyStatement({
                            actions: ['ssm:GetParameter'],
                            resources: [`arn:aws:ssm:${this.region}:${this.account}:parameter${DISCORD_WEBHOOK_URL_PARAM}`]
                        }),
                        new iam.PolicyStatement({
                            // The SecureString param above uses the default AWS-managed SSM key.
                            actions: ['kms:Decrypt'],
                            resources: [`arn:aws:kms:${this.region}:${this.account}:alias/aws/ssm`]
                        })
                    ]
                })
            }
        });

        const api = new apigwv2.HttpApi(this, 'SupportLogsApi', {
            apiName: 'raop-support-logs',
            corsPreflight: {
                allowOrigins: ['https://raofflineproxy.com', 'http://localhost:5199'],
                allowMethods: [apigwv2.CorsHttpMethod.POST, apigwv2.CorsHttpMethod.GET],
                allowHeaders: ['content-type']
            }
        });

        const supportReportFn = new lambda.Function(this, 'SupportReportFn', {
            functionName: 'raop-support-report',
            runtime: lambda.Runtime.NODEJS_24_X,
            handler: 'index.handler',
            role: supportReportRole,
            code: lambda.Code.fromAsset(
                path.join(LAMBDA_DIR, 'raop-support-report', 'dist', 'raop-support-report.zip')
            ),
            environment: {
                BUCKET_NAME: bucket.bucketName,
                DISCORD_WEBHOOK_URL_PARAM,
                // Used to build the short /support/logs/{logId} redirect link posted to Discord,
                // instead of embedding an oversized presigned S3 URL directly.
                API_BASE_URL: api.apiEndpoint
            },
            timeout: cdk.Duration.seconds(10),
            memorySize: 256
        });

        // Throttle the whole API (both routes) so a scripted flood of /support/submit can't
        // spam Discord. Applied as an in-place override to the implicitly-created default
        // stage rather than replacing it, so the already-live /logs/request-upload route
        // used by shipped apps isn't disrupted.
        const defaultStage = api.defaultStage?.node.defaultChild as apigwv2.CfnStage | undefined;
        defaultStage?.addPropertyOverride('DefaultRouteSettings', {
            ThrottlingBurstLimit: 5,
            ThrottlingRateLimit: 2
        });

        api.addRoutes({
            path: '/logs/request-upload',
            methods: [apigwv2.HttpMethod.POST],
            integration: new apigwv2_integrations.HttpLambdaIntegration('RequestUploadIntegration', requestUploadFn)
        });

        const supportReportIntegration = new apigwv2_integrations.HttpLambdaIntegration(
            'SupportReportIntegration',
            supportReportFn
        );

        api.addRoutes({
            path: '/support/submit',
            methods: [apigwv2.HttpMethod.POST],
            integration: supportReportIntegration
        });

        api.addRoutes({
            path: '/support/logs/{logId}',
            methods: [apigwv2.HttpMethod.GET],
            integration: supportReportIntegration
        });

        api.addRoutes({
            path: '/support/logs/{logId}/metadata',
            methods: [apigwv2.HttpMethod.GET],
            integration: supportReportIntegration
        });

        new cdk.CfnOutput(this, 'SupportLogsApiUrl', {
            value: api.apiEndpoint,
            description: 'Base URL for the RAOfflineProxy support-logs upload API'
        });
        new cdk.CfnOutput(this, 'SupportLogsBucketName', { value: bucket.bucketName });
    }
}
