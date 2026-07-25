import { SSMClient, GetParameterCommand } from '@aws-sdk/client-ssm';
import { SESClient, SendEmailCommand } from '@aws-sdk/client-ses';
import Stripe from 'stripe';

const REGION = process.env.AWS_REGION ?? 'eu-central-1';
const STRIPE_SECRET_KEY_PARAM = process.env.STRIPE_SECRET_KEY_PARAM ?? '/raop/support-payment/stripe-secret-key';
const STRIPE_MONTHLY_PRODUCT_ID = process.env.STRIPE_MONTHLY_PRODUCT_ID ?? '';
// PaymentIntents/InvoiceItems have no Product/Price of their own, so this is attached only as
// metadata for Dashboard reporting parity with the (Price-based) monthly product above.
const STRIPE_ONETIME_PRODUCT_ID = process.env.STRIPE_ONETIME_PRODUCT_ID ?? '';
const STRIPE_API_VERSION = '2025-08-27.basil';
const SES_SENDER_EMAIL = process.env.SES_SENDER_EMAIL ?? 'donations@raofflineproxy.com';

const MIN_AMOUNT_CENTS = 100;
const MAX_AMOUNT_CENTS = 100_000;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const ssm = new SSMClient({ region: REGION });
const ses = new SESClient({ region: REGION });

let stripeClientCache: Stripe | null = null;

async function getStripeClient(): Promise<Stripe> {
    if (stripeClientCache) return stripeClientCache;

    const param = await ssm.send(new GetParameterCommand({ Name: STRIPE_SECRET_KEY_PARAM, WithDecryption: true }));
    const secretKey = param.Parameter?.Value;
    if (!secretKey) throw new Error('Missing required SSM parameter');

    stripeClientCache = new Stripe(secretKey, { apiVersion: STRIPE_API_VERSION as Stripe.LatestApiVersion });
    return stripeClientCache;
}

function respond(statusCode: number, body: unknown) {
    return {
        statusCode,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    };
}

function parseBody(event: any): any {
    const raw = event.isBase64Encoded
        ? Buffer.from(event.body ?? '', 'base64').toString('utf-8')
        : event.body ?? '';
    return JSON.parse(raw || '{}');
}

function validateAmount(amount: unknown): amount is number {
    return typeof amount === 'number' && Number.isInteger(amount) && amount >= MIN_AMOUNT_CENTS && amount <= MAX_AMOUNT_CENTS;
}

async function handlePaymentIntent(event: any): Promise<any> {
    let body: any;
    try {
        body = parseBody(event);
    } catch {
        return respond(400, { error: 'Malformed JSON body' });
    }

    if (!validateAmount(body.amount)) {
        return respond(400, { error: 'Invalid amount' });
    }

    try {
        const stripe = await getStripeClient();
        const paymentIntent = await stripe.paymentIntents.create({
            amount: body.amount,
            currency: 'usd',
            automatic_payment_methods: { enabled: true },
            metadata: { product_id: STRIPE_ONETIME_PRODUCT_ID }
        });
        return respond(200, { clientSecret: paymentIntent.client_secret });
    } catch (error) {
        console.error('Failed to create payment intent', error);
        return respond(502, { error: 'Could not create payment' });
    }
}

async function handleSubscription(event: any): Promise<any> {
    let body: any;
    try {
        body = parseBody(event);
    } catch {
        return respond(400, { error: 'Malformed JSON body' });
    }

    if (!validateAmount(body.amount)) {
        return respond(400, { error: 'Invalid amount' });
    }

    try {
        const stripe = await getStripeClient();
        const customer = await stripe.customers.create({});
        const ephemeralKey = await stripe.ephemeralKeys.create({ customer: customer.id }, { apiVersion: STRIPE_API_VERSION });
        const subscription = await stripe.subscriptions.create({
            customer: customer.id,
            items: [{
                price_data: {
                    currency: 'usd',
                    product: STRIPE_MONTHLY_PRODUCT_ID,
                    recurring: { interval: 'month' },
                    unit_amount: body.amount
                }
            }],
            payment_behavior: 'default_incomplete',
            payment_settings: { save_default_payment_method: 'on_subscription' },
            expand: ['latest_invoice.confirmation_secret']
        });

        const invoice = subscription.latest_invoice as Stripe.Invoice;
        const clientSecret = invoice.confirmation_secret?.client_secret;
        if (!clientSecret) throw new Error('Subscription invoice has no confirmation secret');

        return respond(200, {
            clientSecret,
            customerId: customer.id,
            ephemeralKey: ephemeralKey.secret
        });
    } catch (error) {
        console.error('Failed to create subscription', error);
        return respond(502, { error: 'Could not create subscription' });
    }
}

async function handleEmailInvoice(event: any): Promise<any> {
    let body: any;
    try {
        body = parseBody(event);
    } catch {
        return respond(400, { error: 'Malformed JSON body' });
    }

    if (!validateAmount(body.amount)) {
        return respond(400, { error: 'Invalid amount' });
    }
    if (typeof body.frequency !== 'string' || !['once', 'monthly'].includes(body.frequency)) {
        return respond(400, { error: 'Invalid frequency' });
    }
    if (typeof body.email !== 'string' || !EMAIL_PATTERN.test(body.email.trim())) {
        return respond(400, { error: 'Invalid email' });
    }

    const email = body.email.trim();
    const frequency = body.frequency as 'once' | 'monthly';

    // Primary path: a Payment Link that we email ourselves via SES. This never touches
    // Stripe's own "send invoice" action, so it isn't subject to Stripe's live-mode risk
    // checks on manually-sent invoices (see sendStripeHostedInvoice below).
    try {
        await sendPaymentLinkEmail(email, body.amount, frequency);
        return respond(200, { status: 'sent' });
    } catch (error) {
        console.error('Failed to send payment link email, falling back to Stripe-hosted invoice', error);
    }

    // Fallback: the original Stripe Invoicing flow. Kept around in case the Payment Link/SES
    // path itself breaks (SES outage, DNS issue, etc.) — still worth a shot even though this
    // is the path Stripe's risk checks can block for one-off invoices in live mode.
    try {
        await sendStripeHostedInvoice(email, body.amount, frequency);
        return respond(200, { status: 'sent' });
    } catch (error) {
        console.error('Failed to send email invoice', error);
        return respond(502, { error: 'Could not send payment link' });
    }
}

async function sendPaymentLinkEmail(email: string, amount: number, frequency: 'once' | 'monthly'): Promise<void> {
    const stripe = await getStripeClient();
    const isMonthly = frequency === 'monthly';

    // `managed_payments` isn't in the installed SDK's types yet, but Stripe's API requires it
    // here: without a tax_code on the product, Managed Payments (on by default) rejects the
    // line item, so we opt this link out of it instead of tax-categorizing a donation product.
    const paymentLinkParams: Stripe.PaymentLinkCreateParams & Record<string, unknown> = {
        line_items: [{
            price_data: {
                currency: 'usd',
                product: isMonthly ? STRIPE_MONTHLY_PRODUCT_ID : STRIPE_ONETIME_PRODUCT_ID,
                unit_amount: amount,
                ...(isMonthly ? { recurring: { interval: 'month' } } : {})
            },
            quantity: 1
        }],
        // Single-use: this link is emailed to one specific donor, so it shouldn't stay valid
        // for anyone who later gets hold of the email.
        restrictions: { completed_sessions: { limit: 1 } },
        metadata: { donor_email: email },
        managed_payments: { enabled: false }
    };
    const paymentLink = await stripe.paymentLinks.create(paymentLinkParams);
    const frequencyLabel = isMonthly ? 'monthly' : 'one-time';

    await ses.send(new SendEmailCommand({
        Source: SES_SENDER_EMAIL,
        Destination: { ToAddresses: [email] },
        Message: {
            Subject: { Data: 'Complete your RAOfflineProxy donation', Charset: 'UTF-8' },
            Body: {
                Text: {
                    Charset: 'UTF-8',
                    Data: `Thanks for supporting RAOfflineProxy!\n\nComplete your ${frequencyLabel} donation here:\n${paymentLink.url}\n\nThis link is for your use only and can only be completed once.`
                },
                Html: {
                    Charset: 'UTF-8',
                    Data: paymentLinkEmailHtml(paymentLink.url, frequencyLabel)
                }
            }
        }
    }));
}

function paymentLinkEmailHtml(paymentLinkUrl: string, frequencyLabel: string): string {
    return `<!DOCTYPE html>
<html>
  <body style="margin:0; padding:0; background-color:#f4f4f5; font-family:Helvetica,Arial,sans-serif;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f5; padding:32px 16px;">
      <tr>
        <td align="center">
          <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="background-color:#ffffff; border-radius:12px; overflow:hidden; max-width:480px; width:100%;">
            <tr>
              <td align="center" style="background-color:#1c5182; padding:32px 24px;">
                <img src="https://raofflineproxy.com/logo-320.png" width="72" height="72" alt="RAOfflineProxy" style="display:block; border-radius:16px;" />
              </td>
            </tr>
            <tr>
              <td style="padding:32px 32px 8px 32px;">
                <p style="margin:0 0 16px 0; font-size:16px; line-height:1.5; color:#1a1a1a;">Thanks for supporting RAOfflineProxy!</p>
                <p style="margin:0 0 24px 0; font-size:16px; line-height:1.5; color:#1a1a1a;">Complete your ${frequencyLabel} donation using the button below.</p>
              </td>
            </tr>
            <tr>
              <td align="center" style="padding:0 32px 32px 32px;">
                <a href="${paymentLinkUrl}" style="display:inline-block; background-color:#d2a448; color:#1a1400; text-decoration:none; font-weight:bold; font-size:15px; padding:14px 28px; border-radius:24px;">Complete donation</a>
              </td>
            </tr>
            <tr>
              <td style="padding:0 32px 32px 32px;">
                <p style="margin:0; font-size:13px; line-height:1.5; color:#6b6b6b;">This link is for your use only and can only be completed once.</p>
              </td>
            </tr>
          </table>
        </td>
      </tr>
    </table>
  </body>
</html>`;
}

async function sendStripeHostedInvoice(email: string, amount: number, frequency: 'once' | 'monthly'): Promise<void> {
    let invoiceItemId: string | undefined;
    let invoiceId: string | undefined;

    try {
        const stripe = await getStripeClient();
        // Reuse an existing customer for this email instead of creating a duplicate, so repeat
        // donations from the same person consolidate under one Stripe Customer.
        const existing = await stripe.customers.list({ email, limit: 1 });
        const customer = existing.data[0] ?? await stripe.customers.create({ email });

        if (frequency === 'once') {
            const invoiceItem = await stripe.invoiceItems.create({
                customer: customer.id,
                amount,
                currency: 'usd',
                description: 'RAOfflineProxy donation',
                metadata: { product_id: STRIPE_ONETIME_PRODUCT_ID }
            });
            invoiceItemId = invoiceItem.id;

            const invoice = await stripe.invoices.create({
                customer: customer.id,
                collection_method: 'send_invoice',
                days_until_due: 7,
                auto_advance: true
            });
            invoiceId = invoice.id!;
        } else {
            const subscription = await stripe.subscriptions.create({
                customer: customer.id,
                items: [{
                    price_data: {
                        currency: 'usd',
                        product: STRIPE_MONTHLY_PRODUCT_ID,
                        recurring: { interval: 'month' },
                        unit_amount: amount
                    }
                }],
                collection_method: 'send_invoice',
                days_until_due: 7
            });
            invoiceId = subscription.latest_invoice as string;
        }

        // Explicitly finalize+send rather than waiting on Stripe's ~1hr auto-finalize delay,
        // since the user is actively waiting for the email right now.
        await stripe.invoices.finalizeInvoice(invoiceId);
        await stripe.invoices.sendInvoice(invoiceId);
    } catch (error) {
        // Without this, a failed send leaves the invoice item (and, if finalization never
        // happened, the draft invoice) dangling as "pending" on the customer — it silently
        // attaches itself to whatever invoice gets created for them next.
        await cleanupFailedInvoice(invoiceId, invoiceItemId);
        throw error;
    }
}

async function cleanupFailedInvoice(invoiceId: string | undefined, invoiceItemId: string | undefined): Promise<void> {
    try {
        const stripe = await getStripeClient();

        if (invoiceId) {
            const invoice = await stripe.invoices.retrieve(invoiceId);
            if (invoice.status === 'draft') {
                // Deleting a draft invoice detaches its items but returns them to "pending"
                // rather than deleting them, so the invoice item below still needs cleanup.
                await stripe.invoices.del(invoiceId);
            } else if (invoice.status === 'open') {
                // Voiding a finalized invoice cancels it along with its line items, so the
                // underlying invoice item is already accounted for — nothing left to delete.
                await stripe.invoices.voidInvoice(invoiceId);
                invoiceItemId = undefined;
            }
        }

        if (invoiceItemId) {
            await stripe.invoiceItems.del(invoiceItemId);
        }
    } catch (cleanupError) {
        console.error('Failed to clean up after invoice send failure', cleanupError);
    }
}

async function handleSyncCustomerEmail(event: any): Promise<any> {
    let body: any;
    try {
        body = parseBody(event);
    } catch {
        return respond(400, { error: 'Malformed JSON body' });
    }

    if (typeof body.paymentIntentId !== 'string' || !body.paymentIntentId.startsWith('pi_')) {
        return respond(400, { error: 'Invalid paymentIntentId' });
    }
    if (typeof body.customerId !== 'string' || !body.customerId.startsWith('cus_')) {
        return respond(400, { error: 'Invalid customerId' });
    }

    try {
        const stripe = await getStripeClient();
        const paymentIntent = await stripe.paymentIntents.retrieve(body.paymentIntentId, { expand: ['payment_method'] });

        // Only trust a PaymentIntent that's actually tied to this customer, so this endpoint
        // can't be abused to overwrite an arbitrary customer's email.
        if (paymentIntent.customer !== body.customerId) {
            return respond(403, { error: 'Payment intent does not belong to this customer' });
        }

        // Whichever payment method the user picked (card, Amazon Pay, etc.), PaymentSheet
        // attaches whatever email it collected to the confirmed PaymentMethod's billing
        // details — it never updates the Customer object itself, so we sync it here.
        const paymentMethod = paymentIntent.payment_method as Stripe.PaymentMethod | null;
        const email = paymentMethod?.billing_details?.email;
        if (email) {
            await stripe.customers.update(body.customerId, { email });
        }

        return respond(200, { synced: Boolean(email) });
    } catch (error) {
        console.error('Failed to sync customer email', error);
        return respond(502, { error: 'Could not sync customer email' });
    }
}

exports.handler = async (event: any): Promise<any> => {
    const routeKey = event.requestContext?.routeKey;

    if (routeKey === 'POST /support/subscription') {
        return handleSubscription(event);
    }
    if (routeKey === 'POST /support/email-invoice') {
        return handleEmailInvoice(event);
    }
    if (routeKey === 'POST /support/subscription/sync-email') {
        return handleSyncCustomerEmail(event);
    }
    return handlePaymentIntent(event);
};
