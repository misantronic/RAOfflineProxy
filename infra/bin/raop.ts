#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { RaopSupportLogsStack } from '../lib/raop-support-logs-stack';

const app = new cdk.App();

const account = process.env.CDK_DEFAULT_ACCOUNT;

new RaopSupportLogsStack(app, 'RaopSupportLogsStack', {
    env: { account, region: 'eu-central-1' }
});
