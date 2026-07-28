import { SSMClient, GetParameterCommand } from '@aws-sdk/client-ssm';
import { SESClient, SendEmailCommand } from '@aws-sdk/client-ses';
import Stripe from 'stripe';

const REGION = process.env.AWS_REGION ?? 'eu-central-1';
const STRIPE_SECRET_KEY_PARAM = process.env.STRIPE_SECRET_KEY_PARAM ?? '/raop/support-payment/stripe-secret-key';
const STRIPE_WEBHOOK_SECRET_PARAM = process.env.STRIPE_WEBHOOK_SECRET_PARAM ?? '/raop/support-payment/stripe-webhook-secret';
const DISCORD_WEBHOOK_URL_PARAM = process.env.DISCORD_WEBHOOK_URL_PARAM ?? '/raop/support-payment/discord-webhook-url';
const STRIPE_MONTHLY_PRODUCT_ID = process.env.STRIPE_MONTHLY_PRODUCT_ID ?? '';
// PaymentIntents/InvoiceItems have no Product/Price of their own, so this is attached only as
// metadata for Dashboard reporting parity with the (Price-based) monthly product above.
const STRIPE_ONETIME_PRODUCT_ID = process.env.STRIPE_ONETIME_PRODUCT_ID ?? '';
const STRIPE_API_VERSION = '2025-08-27.basil';
const SES_SENDER_EMAIL = process.env.SES_SENDER_EMAIL ?? 'donations@raofflineproxy.com';
const RA_HOST = 'https://retroachievements.org';
const PORTAL_RETURN_URL = 'https://raofflineproxy.com';

const MIN_AMOUNT_CENTS = 100;
const MAX_AMOUNT_CENTS = 100_000;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// The RA username tied to a monthly donation, so a donor can later look up / manage their
// own subscription without any auth system of our own — see verifyRaCredentials below.
const RA_USERNAME_METADATA_KEY = 'ra_username';

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

let webhookSecretCache: string | null = null;

async function getStripeWebhookSecret(): Promise<string> {
    if (webhookSecretCache) return webhookSecretCache;

    const param = await ssm.send(new GetParameterCommand({ Name: STRIPE_WEBHOOK_SECRET_PARAM, WithDecryption: true }));
    const value = param.Parameter?.Value;
    if (!value) throw new Error('Missing required SSM parameter');

    webhookSecretCache = value;
    return webhookSecretCache;
}

let discordWebhookUrlCache: string | null = null;

async function getDiscordWebhookUrl(): Promise<string> {
    if (discordWebhookUrlCache) return discordWebhookUrlCache;

    const param = await ssm.send(new GetParameterCommand({ Name: DISCORD_WEBHOOK_URL_PARAM, WithDecryption: true }));
    const value = param.Parameter?.Value;
    if (!value) throw new Error('Missing required SSM parameter');

    discordWebhookUrlCache = value;
    return discordWebhookUrlCache;
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

// Proves a caller actually owns the RA account they claim, by making the exact same kind of
// authenticated call the app's own proxy already trusts (dorequest.php with u=user&t=token) —
// no separate auth system of our own needed. Uses the "patch" action (same one the app's own
// RomScanner.refreshGamePatch calls) rather than "ping", since ping is meant to keep an
// already-active game session alive and can fail with no session started, regardless of
// whether the token itself is valid — patch has no such session prerequisite.
//
// RA's API validates the User-Agent against a known allowlist of RA client apps and rejects
// anything else with "unsupported_client" — a made-up UA string doesn't work here, so the app
// sends its own real one (the exact value it already uses for its direct RA calls).
async function verifyRaCredentials(username: string, token: string, userAgent: string): Promise<boolean> {
    // Game ID 1 is just used as a fixed, always-valid target for the auth check itself; its
    // achievement data (the actual response body) is irrelevant and discarded.
    const url = `${RA_HOST}/dorequest.php?r=patch&g=1&u=${encodeURIComponent(username)}&t=${encodeURIComponent(token)}`;
    const response = await fetch(url, { headers: { 'User-Agent': userAgent } });
    if (!response.ok) {
        const body = await response.text().catch(() => '');
        console.error(`verifyRaCredentials: RA API returned HTTP ${response.status} server=${response.headers.get('server')} body=${body.slice(0, 500)}`);
        return false;
    }

    const json = await response.json().catch(() => null) as { Success?: boolean; Error?: string } | null;
    if (!json?.Success) {
        console.error(`verifyRaCredentials: RA API rejected credentials, response=${JSON.stringify(json)}`);
    }
    return Boolean(json?.Success);
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

    // Optional: if the app is logged into RA, it sends the username + connect-API token it
    // already holds, so this donation can be tied to that account for the manage-subscription
    // feature. Verified here (not just trusted) so donations can't be falsely attributed to
    // someone else's RA account.
    let raUsername: string | undefined;
    if (body.raUsername !== undefined || body.raToken !== undefined) {
        if (typeof body.raUsername !== 'string' || typeof body.raToken !== 'string' || typeof body.raUserAgent !== 'string') {
            return respond(400, { error: 'Invalid RA credentials' });
        }
        if (!(await verifyRaCredentials(body.raUsername, body.raToken, body.raUserAgent))) {
            return respond(403, { error: 'Could not verify RA credentials' });
        }
        raUsername = body.raUsername;
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
            // Kept on the Subscription (not the Customer) so both this in-app flow and the
            // Payment Link flow (which can only set subscription_data.metadata, not customer
            // metadata) store it in the same consistent place for the status/portal lookups.
            metadata: raUsername ? { [RA_USERNAME_METADATA_KEY]: raUsername } : undefined,
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

    // Same optional RA-account linking as handleSubscription, only relevant for monthly.
    let raUsername: string | undefined;
    if (frequency === 'monthly' && (body.raUsername !== undefined || body.raToken !== undefined)) {
        if (typeof body.raUsername !== 'string' || typeof body.raToken !== 'string' || typeof body.raUserAgent !== 'string') {
            return respond(400, { error: 'Invalid RA credentials' });
        }
        if (!(await verifyRaCredentials(body.raUsername, body.raToken, body.raUserAgent))) {
            return respond(403, { error: 'Could not verify RA credentials' });
        }
        raUsername = body.raUsername;
    }

    // Primary path: a Payment Link that we email ourselves via SES. This never touches
    // Stripe's own "send invoice" action, so it isn't subject to Stripe's live-mode risk
    // checks on manually-sent invoices (see sendStripeHostedInvoice below).
    try {
        await sendPaymentLinkEmail(email, body.amount, frequency, raUsername);
        return respond(200, { status: 'sent' });
    } catch (error) {
        console.error('Failed to send payment link email, falling back to Stripe-hosted invoice', error);
    }

    // Fallback: the original Stripe Invoicing flow. Kept around in case the Payment Link/SES
    // path itself breaks (SES outage, DNS issue, etc.) — still worth a shot even though this
    // is the path Stripe's risk checks can block for one-off invoices in live mode.
    try {
        await sendStripeHostedInvoice(email, body.amount, frequency, raUsername);
        return respond(200, { status: 'sent' });
    } catch (error) {
        console.error('Failed to send email invoice', error);
        return respond(502, { error: 'Could not send payment link' });
    }
}

async function sendPaymentLinkEmail(
    email: string,
    amount: number,
    frequency: 'once' | 'monthly',
    raUsername: string | undefined
): Promise<void> {
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
        managed_payments: { enabled: false },
        ...(isMonthly && raUsername
            ? { subscription_data: { metadata: { [RA_USERNAME_METADATA_KEY]: raUsername } } }
            : {})
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

async function sendStripeHostedInvoice(
    email: string,
    amount: number,
    frequency: 'once' | 'monthly',
    raUsername: string | undefined
): Promise<void> {
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
                days_until_due: 7,
                metadata: raUsername ? { [RA_USERNAME_METADATA_KEY]: raUsername } : undefined
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

async function findActiveSubscriptionForRaUser(username: string): Promise<Stripe.Subscription | null> {
    const stripe = await getStripeClient();
    // Search Query Language escaping: backslash and single-quote are the only special chars.
    const escapedUsername = username.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
    const result = await stripe.subscriptions.search({
        query: `status:'active' AND metadata['${RA_USERNAME_METADATA_KEY}']:'${escapedUsername}'`
    });
    return result.data[0] ?? null;
}

async function handleSubscriptionStatus(event: any): Promise<any> {
    let body: any;
    try {
        body = parseBody(event);
    } catch {
        return respond(400, { error: 'Malformed JSON body' });
    }

    if (typeof body.username !== 'string' || typeof body.token !== 'string' || typeof body.userAgent !== 'string') {
        return respond(400, { error: 'Missing RA credentials' });
    }
    if (!(await verifyRaCredentials(body.username, body.token, body.userAgent))) {
        return respond(403, { error: 'Could not verify RA credentials' });
    }

    try {
        const subscription = await findActiveSubscriptionForRaUser(body.username);
        return respond(200, { hasActiveSubscription: Boolean(subscription) });
    } catch (error) {
        console.error('Failed to look up subscription status', error);
        return respond(502, { error: 'Could not look up subscription status' });
    }
}

async function handleSubscriptionPortal(event: any): Promise<any> {
    let body: any;
    try {
        body = parseBody(event);
    } catch {
        return respond(400, { error: 'Malformed JSON body' });
    }

    if (typeof body.username !== 'string' || typeof body.token !== 'string' || typeof body.userAgent !== 'string') {
        return respond(400, { error: 'Missing RA credentials' });
    }
    if (!(await verifyRaCredentials(body.username, body.token, body.userAgent))) {
        return respond(403, { error: 'Could not verify RA credentials' });
    }

    try {
        const subscription = await findActiveSubscriptionForRaUser(body.username);
        if (!subscription) {
            return respond(404, { error: 'No active subscription found' });
        }

        const stripe = await getStripeClient();
        const customerId = typeof subscription.customer === 'string' ? subscription.customer : subscription.customer.id;
        const session = await stripe.billingPortal.sessions.create({
            customer: customerId,
            return_url: PORTAL_RETURN_URL
        });

        return respond(200, { url: session.url });
    } catch (error) {
        console.error('Failed to create billing portal session', error);
        return respond(502, { error: 'Could not create manage-subscription link' });
    }
}

async function handleStripeWebhook(event: any): Promise<any> {
    // Stripe signs the raw request bytes, so this must be the untouched body — not the
    // parseBody() helper, which JSON-parses (and would invalidate the signature anyway).
    const rawBody = event.isBase64Encoded
        ? Buffer.from(event.body ?? '', 'base64')
        : Buffer.from(event.body ?? '', 'utf-8');

    // API Gateway HTTP APIs lowercase all header names.
    const signature = event.headers?.['stripe-signature'];
    if (typeof signature !== 'string') {
        return respond(400, { error: 'Missing Stripe-Signature header' });
    }

    let stripeEvent: Stripe.Event;
    try {
        const stripe = await getStripeClient();
        const webhookSecret = await getStripeWebhookSecret();
        stripeEvent = stripe.webhooks.constructEvent(rawBody, signature, webhookSecret);
    } catch (error) {
        console.error('Stripe webhook signature verification failed', error);
        return respond(400, { error: 'Invalid signature' });
    }

    // Acknowledge every event type Stripe sends us (even ones we don't act on) so it doesn't
    // keep retrying delivery — we only subscribed to specific event types when creating the
    // endpoint, but staying tolerant here avoids breakage if that ever changes.
    if (stripeEvent.type === 'payment_intent.succeeded') {
        try {
            await notifyDiscordOfDonation(stripeEvent.data.object as Stripe.PaymentIntent);
        } catch (error) {
            // Never fail the webhook response over a Discord hiccup — Stripe would just
            // retry and re-notify, and the payment itself already succeeded regardless.
            console.error('Failed to post donation notification to Discord', error);
        }
    }

    if (stripeEvent.type === 'checkout.session.completed') {
        try {
            await linkRaUsernameFromCheckoutSession(stripeEvent.data.object as Stripe.Checkout.Session);
        } catch (error) {
            console.error('Failed to link RA username from checkout session', error);
        }
    }

    return respond(200, { received: true });
}

// The static monthly Payment Links (Linux QR codes, docs website) collect an optional
// "RA username" custom field directly on Stripe's own checkout page — self-reported, not
// verified against RA the way the in-app flow is, but that's an acceptable tradeoff here
// (see donate-me design discussion: typing someone else's username only gives *them* power
// over *your* subscription, not the other way around). This copies that value onto the
// resulting Subscription's metadata so it's discoverable the same way as verified ones.
async function linkRaUsernameFromCheckoutSession(session: Stripe.Checkout.Session): Promise<void> {
    if (session.mode !== 'subscription' || !session.subscription) return;

    const field = session.custom_fields?.find((customField) => customField.key === RA_USERNAME_METADATA_KEY);
    const raUsername = field?.text?.value?.trim();
    if (!raUsername) return;

    const stripe = await getStripeClient();
    const subscriptionId = typeof session.subscription === 'string' ? session.subscription : session.subscription.id;
    await stripe.subscriptions.update(subscriptionId, {
        metadata: { [RA_USERNAME_METADATA_KEY]: raUsername }
    });
}

async function notifyDiscordOfDonation(paymentIntent: Stripe.PaymentIntent): Promise<void> {
    const stripe = await getStripeClient();

    // A PaymentIntent can be tied to an Invoice for two different reasons: a subscription
    // (our monthly donations) or the one-time email-invoice fallback (sendStripeHostedInvoice,
    // which also bills through a real Stripe Invoice). So the mere presence of an invoice isn't
    // enough — only one whose parent is a subscription is actually recurring. This API version
    // puts the invoice reference at payment_details.order_reference (an "in_..." invoice id)
    // rather than a top-level `invoice` field — confirmed against a real subscription payment;
    // neither field is in the installed SDK's types.
    const orderReference = (paymentIntent as unknown as { payment_details?: { order_reference?: string | null } })
        .payment_details?.order_reference;
    const isMonthly = orderReference
        ? Boolean((await stripe.invoices.retrieve(orderReference)).parent?.subscription_details)
        : false;

    let email = paymentIntent.receipt_email ?? undefined;
    if (!email) {
        const full = await stripe.paymentIntents.retrieve(paymentIntent.id, {
            expand: ['payment_method', 'customer']
        });
        email =
            (full.payment_method as Stripe.PaymentMethod | null)?.billing_details?.email ??
            (full.customer as Stripe.Customer | null)?.email ??
            undefined;
    }

    const amount = (paymentIntent.amount / 100).toLocaleString('en-US', {
        style: 'currency',
        currency: paymentIntent.currency.toUpperCase()
    });

    const embed = {
        title: `New ${isMonthly ? 'monthly' : 'one-time'} donation received 💛`,
        color: 0xd2a448,
        fields: [
            { name: 'Amount', value: amount, inline: true },
            { name: 'Type', value: isMonthly ? 'Monthly' : 'One-time', inline: true },
            { name: 'Donor', value: email ?? 'Unknown', inline: false }
        ]
    };

    const webhookUrl = await getDiscordWebhookUrl();
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
    if (routeKey === 'POST /support/subscription/status') {
        return handleSubscriptionStatus(event);
    }
    if (routeKey === 'POST /support/subscription/portal') {
        return handleSubscriptionPortal(event);
    }
    if (routeKey === 'POST /support/stripe-webhook') {
        return handleStripeWebhook(event);
    }
    return handlePaymentIntent(event);
};
