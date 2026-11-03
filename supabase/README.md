# Automatic bank-transfer confirmation

The Android checkout already puts the order code in the VietQR transfer memo. This Supabase Edge Function receives incoming-transaction webhooks from SePay, verifies SePay's HMAC-SHA256 signature, and checks the receiving account, direction, order code, payment method, and exact amount. It records each SePay transaction once in Firestore's `bankTransferEvents` collection. Duplicate webhook retries are idempotent.

The function keeps the existing Firebase Auth/Firestore data model. A Firebase service-account private key is needed only as a Supabase secret so the server can access Firestore; never put it in the Android app, admin website, or this repository. Create a dedicated service account and grant it the Google Cloud IAM role **Cloud Datastore User** (`roles/datastore.user`) on the Firebase project's Google Cloud project. This role permits Firestore document reads and writes used by checkout. Firestore Security Rules do not grant IAM permissions to this server account.

## Configure and deploy

The current app backend is designed to run without a paid Firebase or Supabase plan: keep Firebase on Spark, use Supabase Free, and stay within each service's published free quotas. The app uses Firebase Email/Password or Google authentication; SMS phone sign-in is intentionally not included because Firebase SMS verification is available only on Blaze and is billed per message. Disable the Phone provider in Firebase Authentication as well. Supabase Free allows Edge Function deployments and includes 500,000 invocations per month; projects can pause after inactivity and service can be restricted when free quotas are exceeded. This configuration does not require a paid plan, but it is not an uptime guarantee. External providers such as SePay or banks may impose their own fees outside this codebase.

1. Create or select a Supabase project and link the local CLI to it (`supabase login`, then `supabase link --project-ref <your-project-ref>`).
2. In Supabase **Project Settings → Edge Functions → Secrets**, add:
   - `FIREBASE_PROJECT_ID`: `barbershop-e85e1`
   - `FIREBASE_WEB_API_KEY`: the Firebase Web API key from `admin/firebase-config.js` (public Firebase client key, used server-side to validate Firebase ID tokens; make sure Identity Toolkit API is enabled and allowed for the key)
   - `FIREBASE_CLIENT_EMAIL`: the dedicated Firebase service account email
   - `FIREBASE_PRIVATE_KEY`: its PEM private key, preserving newlines (or storing them as `\n`)
   - `SEPAY_HMAC_SECRET`: the HMAC secret configured for this SePay webhook
   - `EXPECTED_BANK_ACCOUNT`: the linked BIDV main account number (`8876551958`); SePay sends this as `accountNumber`
   - `EXPECTED_BANK_VA`: the linked BIDV virtual account number (`96247MLSM8`); SePay sends this as `subAccount`
   - `ORDER_EXPIRY_SECRET`: a new random secret used only by the scheduled unpaid-order cleanup endpoint
   Make sure the service account named by `FIREBASE_CLIENT_EMAIL` has `roles/datastore.user` on the Google Cloud project `barbershop-e85e1`. Without it, checkout can fail while reading the Firestore transaction.
3. Set `Config.SUPABASE_URL` and `Config.SUPABASE_PUBLISHABLE_KEY` in `app/src/main/java/com/example/salonapp/Config.java` to this Supabase project's URL and public/publishable API key. These values are public client configuration; never put a Supabase secret/service-role key in the app.
4. Deploy the endpoints from the repository root: `supabase functions deploy create-order`, `supabase functions deploy create-appointment`, `supabase functions deploy manage-appointment`, `supabase functions deploy list-staff-work`, `supabase functions deploy expire-unpaid-orders`, `supabase functions deploy sepay-webhook`, `supabase functions deploy update-work-status`, and `supabase functions deploy send-contact-reply`.
5. In SePay, create an **incoming-only** webhook pointing to `https://<your-project-ref>.supabase.co/functions/v1/sepay-webhook`. Select the linked BIDV account and its `96247MLSM8` VA. Choose JSON and **HMAC-SHA256** authentication. Configure `CPB` as a payment-code prefix if SePay offers that filter, and use its test-send feature before taking real payments.
6. Make a small real test transfer and confirm the order history changes from unpaid to paid. `OrdersFragment` listens for Firestore updates and should refresh the status without reopening the screen.

The Edge Function's Supabase JWT check is disabled because SePay cannot send a Supabase user JWT. The endpoint instead verifies SePay's HMAC signature and rejects stale signatures. Do not configure the SePay webhook without HMAC authentication. Supabase function configuration and secrets are documented in [Supabase Edge Function configuration](https://supabase.com/docs/guides/functions/function-configuration) and [Supabase function secrets](https://supabase.com/docs/guides/functions/secrets); SePay's payload and signature contract are in [SePay webhook integration](https://developer.sepay.vn/en/sepay-webhooks/tich-hop-webhook) and [SePay webhook authentication](https://developer.sepay.vn/en/sepay-webhooks/xac-thuc).

`update-work-status` also disables the Supabase JWT gateway check because the browser sends a Firebase ID token. The function verifies that token with Firebase Identity Toolkit and requires `admin: true` or `staff: true` custom claims before writing. Its CORS allowlist is limited to the Firebase Hosting origins for this app and localhost development.

## App inbox and push delivery

Admin replies are sent through the `send-contact-reply` Supabase Edge Function. It validates the admin/staff Firebase ID token, reads the selected contact, writes the reply into the existing Firestore `notifications` inbox, then sends a push through the Firebase Cloud Messaging HTTP v1 API. `update-work-status` now creates the same inbox and push notification when an admin changes an order or appointment status. The Android app continues to use Firestore for its inbox and FCM for Android push delivery; only the server-side trigger/sender has moved from Firebase Cloud Functions to Supabase Edge Functions.

For these two functions, configure the existing Supabase secrets `FIREBASE_PROJECT_ID`, `FIREBASE_WEB_API_KEY`, `FIREBASE_CLIENT_EMAIL`, and `FIREBASE_PRIVATE_KEY`. The service account must have **Cloud Datastore User** (`roles/datastore.user`) for Firestore reads/writes and **Firebase Cloud Messaging API Admin** (`roles/firebasecloudmessaging.admin`) to send FCM messages. FCM HTTP v1 requires the `https://www.googleapis.com/auth/firebase.messaging` OAuth scope; see [Google's FCM IAM roles](https://cloud.google.com/iam/docs/roles-permissions/firebasecloudmessaging) and [FCM HTTP v1 authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api). A missing device token or FCM send failure does not prevent the in-app inbox message from being saved.

Deploy the notification path on Spark without deploying Firebase Cloud Functions. From the repository root, after linking the Supabase CLI to the configured project:

```sh
npx --yes supabase functions deploy send-contact-reply
npx --yes supabase functions deploy update-work-status
```

Deploy the updated admin interface and its Firestore rules separately:

```sh
npx --yes firebase-tools deploy --only hosting,firestore:rules,firestore:indexes --project barbershop-e85e1
```

Keep the Firebase service-account private key only in Supabase secrets, never in this repository, the web admin, or the Android app.

Order creation, appointment creation/changes, and staff status updates run through Supabase Edge Functions, so the app does not use Firebase Cloud Functions. `create-appointment` validates the signed-in Firebase ID token, checks the branch, stylist, service and opening hours, then reserves all required time slots and creates the appointment atomically in Firestore. `manage-appointment` validates customer ownership and the two-hour notice rule, then atomically cancels or reschedules the appointment and its slot documents. `list-staff-work` requires the Firebase `staff` or `admin` custom claim. Keep Firebase custom claims and the service account IAM role configured; never expose the service-account key to the browser. Staff claims are managed by the trusted local script in `scripts/`, not a deployed Firebase Function.

## Unpaid stock reservation expiry

The bank-transfer code and stock reservation are valid for 15 minutes; cash orders hold stock for 24 hours. `expire-unpaid-orders` cancels eligible unpaid orders, restores each existing product's stock in a Firestore transaction, and sends an in-app notification with a best-effort push. With the one-minute schedule below, the expiry notification is sent on the first worker run after the deadline (normally within one minute). It also applies those windows to older orders that predate `reservationExpiresAt`. The endpoint uses `ORDER_EXPIRY_SECRET` as a dedicated bearer token and must be scheduled; deploying the function alone does not run it automatically.

After adding the same random value to the Edge Function secret `ORDER_EXPIRY_SECRET`, store it in Supabase Vault under `order_expiry_secret` (and store the project URL under `barbershop_project_url`). Enable the `pg_cron` and `pg_net` extensions, then run this SQL in the Supabase SQL editor:

    select vault.create_secret('https://<project-ref>.supabase.co', 'barbershop_project_url');
    select vault.create_secret('<same-random-value-as-ORDER_EXPIRY_SECRET>', 'order_expiry_secret');

    select cron.unschedule(jobid)
    from cron.job
    where jobname in ('expire-unpaid-orders-every-five-minutes', 'expire-unpaid-orders-every-minute');

    select cron.schedule(
      'expire-unpaid-orders-every-minute',
      '* * * * *',
      $$
      select net.http_post(
        url := (select decrypted_secret from vault.decrypted_secrets where name = 'barbershop_project_url') || '/functions/v1/expire-unpaid-orders',
        headers := jsonb_build_object(
          'Content-Type', 'application/json',
          'Authorization', 'Bearer ' || (select decrypted_secret from vault.decrypted_secrets where name = 'order_expiry_secret')
        ),
        body := '{}'::jsonb
      ) as request_id;
      $$
    );

Monitor the job's run history in Supabase **Cron**. The worker handles up to 100 bank and 100 cash orders per run; subsequent runs drain larger backlogs. Deploy the Firestore composite index together with the rules.

Transfers with missing/ambiguous order codes, an amount mismatch, the wrong destination account or VA, or a non-bank order are never auto-marked paid. If an exact matching transfer arrives after a bank order's 15-minute reservation deadline, the webhook records it as paid but flags `paymentReviewRequired`, keeps an already-canceled order canceled, and sends the customer an in-app/push notice to contact the shop. It does not automatically fulfill the order because the reserved stock may already have been released. Staff can review the flag on the order and the `late_payment_review` event in the admin interface before arranging fulfillment or refund. Authenticated unmatched transfers are recorded as `bankTransferEvents` with a result code for reconciliation. A SePay account and a bank account connected to SePay are required; scanning a QR by itself does not report whether money arrived. BIDV personal-account QR payments must target the configured VA, not the main account.
