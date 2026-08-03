# Privacy Policy

**Last updated: July 25, 2026**

RAOfflineProxy ("the app") is a local proxy tool for Android that enables offline RetroAchievements support with supported emulators such as RetroArch, Dolphin, PPSSPP, and ARMSX2. This privacy policy explains what data is handled by the app, how it is stored, and when it is transmitted.

## Summary

- The app does **not** collect, store, or transmit any data to its developer, except what is necessary to process a voluntary donation you choose to make (see [Donations](#donations)).
- The app does **not** contain ads, analytics, or crash reporting SDKs.
- Aside from donations, all data handled by the app stays on your device or is sent directly to [RetroAchievements.org](https://retroachievements.org) on your behalf: the same requests your emulator would have made itself.
- If you choose to support development via the in-app donation dialog, payment details are handled entirely by Stripe and never reach the app developer or the app's local storage. An optional email address may be sent to a developer-operated backend solely to process the donation.
- The website (`raofflineproxy.com`) uses Google Analytics to measure page traffic.

---

## What data is handled

### RetroAchievements credentials

When the proxy starts, the app reads your saved RetroAchievements login from a supported emulator config. It prefers an existing API token when available. If no token is available, it can use the saved username and password once to retrieve a token from RetroAchievements, then stores that token in its local cache. This cache is used to authenticate requests when your device is offline.

Your credentials are **never sent to the app developer**. They are only ever forwarded to `retroachievements.org`, exactly as the emulator would do directly.

### Game and achievement data

The app caches game patch data (achievement lists, point values, badge names) and your unlock state in the local database. This is what allows achievements to display correctly when you are offline.

### Pending (offline) award queue

When you unlock an achievement while offline, the app queues the award in the local database and sends it to RetroAchievements when your connection is restored. Each queued award is signed with a device-local cryptographic key (stored in Android Keystore) and chained to the previous award for tamper evidence. The public key is included in the flushed request; the private key never leaves Android Keystore.

### ROM file hashes

When you scan a ROM file or folder, the app computes an MD5 hash of each file and sends that hash to RetroAchievements to look up the corresponding game. ROM file contents are never stored or transmitted: only the MD5 hash.

### Emulator user-agent string

The app caches the `User-Agent` header sent by the emulator (for example an `rcheevos/...` user-agent) and appends its own identifier (`RAOfflineProxy/<version>`) when forwarding requests to RetroAchievements so the server can distinguish proxied traffic.

### Game icon images

Game badge and icon images are downloaded from RetroAchievements and stored in local app storage for display in the UI. They are never transmitted elsewhere.

### Donations

The app includes an optional "Support development" dialog for making a voluntary one-time or monthly donation. This feature is entirely optional and is not used for any core app functionality.

- **Payment details** (card number, Amazon Pay, etc.) are entered into Stripe's own PaymentSheet UI and go directly from your device to Stripe. They are never sent to, or seen by, the app developer, and are never stored in the app's local database.
- **Donation amount** and, if you choose to pay by card in-app, the **billing email** Stripe's payment form collects are sent to a backend operated by the app developer (an AWS Lambda function) so it can create the corresponding payment or subscription with Stripe.
- If you choose to receive a payment link by email instead of paying in-app, the **email address you enter** is sent to the same backend, which asks Stripe to email you an invoice. Stripe looks up or creates a Stripe Customer record for that email so repeat donations from the same address are grouped together.
- No donation data (amount, email, payment status) is stored in the app's local database or cache.
- Alternatively, the dialog offers a link to the developer's [Ko-fi](https://ko-fi.com/misantronic) page, which opens in your browser and is governed by Ko-fi's own privacy policy.

See [Third-party services](#third-party-services) for how Stripe handles this data.

---

## Data storage

All data is stored locally on your device in:

- A SQLite database (`raofflineproxy.db`) managed by Android Room
- App-internal file storage for cached images

Both are excluded from Android Auto-Backup and device-to-device transfer (as declared in `backup_rules.xml` and `data_extraction_rules.xml`). Your credentials will not appear in cloud backups.

---

## Data transmission

For its core functionality, the app transmits data only to `retroachievements.org` over HTTPS:

| Data | When |
|---|---|
| Username + API token | On every proxied API request |
| Achievement award | When flushing the offline queue after reconnecting |
| ROM MD5 hash | When scanning ROM files |
| ECDSA public key | Attached to flushed award requests for chain verification |
| Seconds-since-unlock offset | Attached to flushed award requests |

The only other data transmission happens if you choose to make a donation:

| Data | Sent to | When |
|---|---|---|
| Donation amount, frequency | Developer's backend, then Stripe | When starting a donation |
| Billing/contact email | Developer's backend, then Stripe | When paying by card in-app, or requesting an emailed payment link |
| Card/payment method details | Stripe directly (never our backend) | When confirming payment in Stripe's PaymentSheet |

The developer's backend never receives your card or payment method details. It only receives the donation amount, frequency, and (where applicable) your email address, which it forwards to Stripe to create the payment, subscription, or invoice.

---

## Data deletion

You can delete all locally stored data at any time:

- **Clear Cache** (Settings screen): removes cached game and achievement data. Does not remove credentials or pending awards.
- **Clear Database** (Settings screen): removes all locally stored data including credentials, cached game data, and pending awards.

Deleting the app also removes all app data.

---

## Third-party services

For its core functionality, the app communicates exclusively with [RetroAchievements.org](https://retroachievements.org). Their privacy policy governs how they handle data you send to their servers: [https://retroachievements.org/terms](https://retroachievements.org/terms).

If you make a donation, payment processing is handled by [Stripe](https://stripe.com), including the in-app card entry form (Stripe's PaymentSheet SDK), storage of your Stripe Customer/payment records, and any emailed invoices or payment links. Stripe's privacy policy governs how they handle this data: [https://stripe.com/privacy](https://stripe.com/privacy). Donations only process on production builds of the app; development builds use Stripe's test mode and cannot complete a real payment.

If you tap the Ko-fi link in the donation dialog, you leave the app and are taken to [Ko-fi](https://ko-fi.com/misantronic) in your browser. Ko-fi's own privacy policy governs that page: [https://more.ko-fi.com/privacy-policy](https://more.ko-fi.com/privacy-policy).

The website (`raofflineproxy.com`) also uses [Google Analytics](https://policies.google.com/privacy) to measure page traffic and general site usage.

When you visit the website, Google Analytics may process data such as page views, browser information, device information, and general location derived from your IP address. This analytics setup is for the website only and is not part of the Android app itself.

---

## Children

The app does not knowingly collect data from children. It is a utility for interacting with RetroAchievements, which has its own terms of service.

---

## Changes to this policy

If this policy changes materially, the updated version will be published at this URL with a new "Last updated" date.

---

## Contact

For questions about this privacy policy, open an issue or discussion on the [GitHub repository](https://github.com/misantronic/RAOfflineProxy).
