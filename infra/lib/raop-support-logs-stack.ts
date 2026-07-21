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

        const api = new apigwv2.HttpApi(this, 'SupportLogsApi', {
            apiName: 'raop-support-logs'
        });

        api.addRoutes({
            path: '/logs/request-upload',
            methods: [apigwv2.HttpMethod.POST],
            integration: new apigwv2_integrations.HttpLambdaIntegration('RequestUploadIntegration', requestUploadFn)
        });

        new cdk.CfnOutput(this, 'SupportLogsApiUrl', {
            value: api.apiEndpoint,
            description: 'Base URL for the RAOfflineProxy support-logs upload API'
        });
        new cdk.CfnOutput(this, 'SupportLogsBucketName', { value: bucket.bucketName });
    }
}
