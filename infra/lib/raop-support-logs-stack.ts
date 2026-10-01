import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as budgets from 'aws-cdk-lib/aws-budgets';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as events from 'aws-cdk-lib/aws-events';
import * as eventsTargets from 'aws-cdk-lib/aws-events-targets';
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

        const STRIPE_SECRET_KEY_PARAM = '/raop/support-payment/stripe-secret-key';
        const STRIPE_WEBHOOK_SECRET_PARAM = '/raop/support-payment/stripe-webhook-secret';
        const DONATION_DISCORD_WEBHOOK_URL_PARAM = '/raop/support-payment/discord-webhook-url';
        const STRIPE_MONTHLY_PRODUCT_ID = 'prod_UwXVoGHCZgyLHr';
        const STRIPE_ONETIME_PRODUCT_ID = 'prod_UwXVN3Ib6UUpFE';
        // Stripe test-mode counterparts, used only by the debug-only "test" checkbox in the
        // app's donation dialog so the checkout flow can be exercised without real money.
        const STRIPE_SECRET_KEY_PARAM_TEST = '/raop/support-payment/stripe-secret-key-test';
        const STRIPE_WEBHOOK_SECRET_PARAM_TEST = '/raop/support-payment/stripe-webhook-secret-test';
        const STRIPE_MONTHLY_PRODUCT_ID_TEST = 'prod_UwXRSyJpaF1FmT';
        const STRIPE_ONETIME_PRODUCT_ID_TEST = 'prod_UwXWrdkTIRAO2g';

        const paymentRole = new iam.Role(this, 'PaymentLambdaRole', {
            roleName: 'raop-support-payment-lambda-role',
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole')
            ],
            inlinePolicies: {
                PaymentSecretsPolicy: new iam.PolicyDocument({
                    statements: [
                        new iam.PolicyStatement({
                            actions: ['ssm:GetParameter'],
                            resources: [
                                `arn:aws:ssm:${this.region}:${this.account}:parameter${STRIPE_SECRET_KEY_PARAM}`,
                                `arn:aws:ssm:${this.region}:${this.account}:parameter${STRIPE_WEBHOOK_SECRET_PARAM}`,
                                `arn:aws:ssm:${this.region}:${this.account}:parameter${DONATION_DISCORD_WEBHOOK_URL_PARAM}`,
                                `arn:aws:ssm:${this.region}:${this.account}:parameter${STRIPE_SECRET_KEY_PARAM_TEST}`,
                                `arn:aws:ssm:${this.region}:${this.account}:parameter${STRIPE_WEBHOOK_SECRET_PARAM_TEST}`
                            ]
                        }),
                        new iam.PolicyStatement({
                            // The SecureString params above use the default AWS-managed SSM key.
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

        const paymentFn = new lambda.Function(this, 'PaymentFn', {
            functionName: 'raop-support-payment',
            runtime: lambda.Runtime.NODEJS_24_X,
            handler: 'index.handler',
            role: paymentRole,
            code: lambda.Code.fromAsset(
                path.join(LAMBDA_DIR, 'raop-support-payment', 'dist', 'raop-support-payment.zip')
            ),
            environment: {
                STRIPE_SECRET_KEY_PARAM,
                STRIPE_WEBHOOK_SECRET_PARAM,
                DISCORD_WEBHOOK_URL_PARAM: DONATION_DISCORD_WEBHOOK_URL_PARAM,
                STRIPE_MONTHLY_PRODUCT_ID,
                STRIPE_ONETIME_PRODUCT_ID,
                STRIPE_SECRET_KEY_PARAM_TEST,
                STRIPE_WEBHOOK_SECRET_PARAM_TEST,
                STRIPE_MONTHLY_PRODUCT_ID_TEST,
                STRIPE_ONETIME_PRODUCT_ID_TEST
            },
            timeout: cdk.Duration.seconds(10),
            memorySize: 256
        });

        // Anonymous usage pings. Rows expire via TTL after ~13 months; the per-month HMAC secret
        // rows (pk "secret") expire shortly after their month ends, which makes old IDs unlinkable.
        const usageTable = new dynamodb.Table(this, 'UsageTable', {
            tableName: 'raop-usage',
            partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
            sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
            billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
            timeToLiveAttribute: 'ttl',
            removalPolicy: cdk.RemovalPolicy.RETAIN
        });

        const usagePingRole = new iam.Role(this, 'UsagePingLambdaRole', {
            roleName: 'raop-usage-ping-lambda-role',
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole')
            ]
        });
        usageTable.grant(usagePingRole, 'dynamodb:GetItem', 'dynamodb:PutItem', 'dynamodb:UpdateItem');

        const usagePingFn = new lambda.Function(this, 'UsagePingFn', {
            functionName: 'raop-usage-ping',
            runtime: lambda.Runtime.NODEJS_24_X,
            handler: 'index.handler',
            role: usagePingRole,
            code: lambda.Code.fromAsset(
                path.join(LAMBDA_DIR, 'raop-usage-ping', 'dist', 'raop-usage-ping.zip')
            ),
            environment: { TABLE_NAME: usageTable.tableName },
            timeout: cdk.Duration.seconds(10),
            memorySize: 256,
            logRetention: logs.RetentionDays.ONE_MONTH
        });

        // Bakes the aggregated usage stats into raofflineproxy.com/stats.html once a day. The site
        // bucket belongs to the docs deploy (deploy-docs.yml), which excludes stats.html from its
        // --delete sync; this role may only write that one object.
        const SITE_BUCKET_NAME = 'ra-offline-proxy-web';
        const STATS_PAGE_KEY = 'stats.html';

        const statsPageRole = new iam.Role(this, 'UsageStatsPageLambdaRole', {
            roleName: 'raop-usage-stats-page-lambda-role',
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole')
            ],
            inlinePolicies: {
                StatsPagePublishPolicy: new iam.PolicyDocument({
                    statements: [
                        new iam.PolicyStatement({
                            actions: ['s3:PutObject'],
                            resources: [`arn:aws:s3:::${SITE_BUCKET_NAME}/${STATS_PAGE_KEY}`]
                        })
                    ]
                })
            }
        });
        usageTable.grant(statsPageRole, 'dynamodb:Scan');

        const statsPageFn = new lambda.Function(this, 'UsageStatsPageFn', {
            functionName: 'raop-usage-stats-page',
            runtime: lambda.Runtime.NODEJS_24_X,
            handler: 'index.handler',
            role: statsPageRole,
            code: lambda.Code.fromAsset(
                path.join(LAMBDA_DIR, 'raop-usage-stats-page', 'dist', 'raop-usage-stats-page.zip')
            ),
            environment: {
                TABLE_NAME: usageTable.tableName,
                SITE_BUCKET: SITE_BUCKET_NAME,
                PAGE_KEY: STATS_PAGE_KEY
            },
            timeout: cdk.Duration.seconds(60),
            memorySize: 512,
            logRetention: logs.RetentionDays.ONE_MONTH
        });

        new events.Rule(this, 'UsageStatsPageSchedule', {
            ruleName: 'raop-usage-stats-page-daily',
            description: 'Rebuild raofflineproxy.com/stats.html at the end of each UTC day',
            schedule: events.Schedule.cron({ minute: '55', hour: '23' }),
            targets: [new eventsTargets.LambdaFunction(statsPageFn)]
        });

        // The whole account normally costs cents; anything near this means abuse (e.g. a flood of the
        // public /usage/ping or support routes) or a misconfiguration. Budgets only warns, it never
        // stops anything.
        const MONTHLY_BUDGET_USD = 5;
        const BUDGET_ALERT_EMAIL = 'david.skx@posteo.de';
        const budgetSubscribers = [{ subscriptionType: 'EMAIL', address: BUDGET_ALERT_EMAIL }];
        new budgets.CfnBudget(this, 'MonthlyCostBudget', {
            budget: {
                budgetName: 'raop-monthly-cost',
                budgetType: 'COST',
                timeUnit: 'MONTHLY',
                budgetLimit: { amount: MONTHLY_BUDGET_USD, unit: 'USD' }
            },
            notificationsWithSubscribers: [
                {
                    notification: {
                        notificationType: 'ACTUAL',
                        comparisonOperator: 'GREATER_THAN',
                        threshold: 80,
                        thresholdType: 'PERCENTAGE'
                    },
                    subscribers: budgetSubscribers
                },
                {
                    notification: {
                        notificationType: 'ACTUAL',
                        comparisonOperator: 'GREATER_THAN',
                        threshold: 100,
                        thresholdType: 'PERCENTAGE'
                    },
                    subscribers: budgetSubscribers
                },
                {
                    notification: {
                        notificationType: 'FORECASTED',
                        comparisonOperator: 'GREATER_THAN',
                        threshold: 100,
                        thresholdType: 'PERCENTAGE'
                    },
                    subscribers: budgetSubscribers
                }
            ]
        });

        // Throttle the whole API (both routes) so a scripted flood of /support/submit can't
        // spam Discord. Applied as an in-place override to the implicitly-created default
        // stage rather than replacing it, so the already-live /logs/request-upload route
        // used by shipped apps isn't disrupted. Now also covers the payment routes below,
        // which is a good thing since each request triggers a Stripe API call.
        const defaultStage = api.defaultStage?.node.defaultChild as apigwv2.CfnStage | undefined;
        defaultStage?.addPropertyOverride('DefaultRouteSettings', {
            ThrottlingBurstLimit: 5,
            ThrottlingRateLimit: 2
        });
        // Every opted-in install pings once a day, so this route needs more headroom than the
        // support routes. A throttled ping is simply retried on the next app start.
        defaultStage?.addPropertyOverride('RouteSettings', {
            'POST /usage/ping': {
                ThrottlingBurstLimit: 50,
                ThrottlingRateLimit: 20
            }
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

        const paymentIntegration = new apigwv2_integrations.HttpLambdaIntegration('PaymentIntegration', paymentFn);

        api.addRoutes({
            path: '/support/payment-intent',
            methods: [apigwv2.HttpMethod.POST],
            integration: paymentIntegration
        });

        api.addRoutes({
            path: '/support/subscription',
            methods: [apigwv2.HttpMethod.POST],
            integration: paymentIntegration
        });

        api.addRoutes({
            path: '/support/email-invoice',
            methods: [apigwv2.HttpMethod.POST],
            integration: paymentIntegration
        });

        api.addRoutes({
            path: '/support/stripe-webhook',
            methods: [apigwv2.HttpMethod.POST],
            integration: paymentIntegration
        });

        api.addRoutes({
            path: '/support/subscription/sync-email',
            methods: [apigwv2.HttpMethod.POST],
            integration: paymentIntegration
        });

        api.addRoutes({
            path: '/support/subscription/status',
            methods: [apigwv2.HttpMethod.POST],
            integration: paymentIntegration
        });

        api.addRoutes({
            path: '/support/subscription/portal',
            methods: [apigwv2.HttpMethod.POST],
            integration: paymentIntegration
        });

        const usagePingRoutes = api.addRoutes({
            path: '/usage/ping',
            methods: [apigwv2.HttpMethod.POST],
            integration: new apigwv2_integrations.HttpLambdaIntegration('UsagePingIntegration', usagePingFn)
        });
        // The stage's RouteSettings reference this route by key, so it has to exist first.
        usagePingRoutes.forEach((route) => defaultStage?.node.addDependency(route));

        new cdk.CfnOutput(this, 'SupportLogsApiUrl', {
            value: api.apiEndpoint,
            description: 'Base URL for the RAOfflineProxy support-logs upload API'
        });
        new cdk.CfnOutput(this, 'SupportLogsBucketName', { value: bucket.bucketName });
    }
}
