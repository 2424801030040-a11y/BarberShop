# Web Admin

Responsive Vietnamese admin dashboard for the Chí Phèo BarberShop Firebase project. It uses Firebase Authentication, Cloud Firestore, and Firebase Hosting, which are usable on Spark within their free quotas.

## Available on Spark

- Email/password or Google administrator sign-in (the account must have the `admin: true` custom claim).
- Dashboard reads for appointments, orders, messages, reviews, and catalog records.
- Read-only audit log for incoming SePay bank transfers, including unmatched codes and amount mismatches.
- Create, edit, and delete services, products, branches, stylists, and promotions.
- Paste a public HTTP(S) image URL in the admin form; Cloudinary imports the URL, and the resulting `imageUrl` is saved in Firestore and rendered in the Android catalog and booking selector.
- Resolve/reopen contact requests and remove reviews.
- Update appointment and order statuses through the authenticated `update-work-status` Supabase Edge Function.
- Delete safeguards check existing stylist, appointment, and pending-order references before removal.
- Read existing Firestore user profile documents.

The Web app `Chí Phèo BarberShop Admin` is registered in Firebase project `barbershop-e85e1`; its public client configuration is in `firebase-config.js`. The browser uses Firestore Security Rules for all data access. Rules restrict admin reads/writes to a verified Firebase Auth custom claim; they do not trust a role value supplied by the page.

Catalog image import uses the Cloudinary cloud `dj0e3lctd` and the unsigned preset `salon_unsigned` configured in `cloudinary-config.js`. Paste a publicly accessible direct image URL; Cloudinary fetches and stores the image, and Firestore accepts only delivery URLs from this Cloudinary account. **Unsigned presets are public by design:** Firebase admin sign-in gates the import controls in this page, but it cannot prevent someone who discovers the preset name from sending uploads directly to Cloudinary. In Cloudinary, restrict the preset to image formats, disable user-supplied public IDs, and apply an incoming resize/quality transformation. Use a separate restricted preset for catalog assets if possible. Cloudinary notes that unsigned preset names are visible in client code and recommends signed uploads for sensitive use cases; signed uploads require a trusted signing backend, which is outside the Firebase Spark-only setup. See [Cloudinary upload preset security](https://cloudinary.com/documentation/upload_presets) and [client-side upload security](https://cloudinary.com/documentation/client_side_uploading).

## Requires a separate trusted backend/tool

These features need Firebase Admin SDK privileges and are intentionally unavailable in the Spark-only web dashboard:

- Listing all Firebase Authentication users, granting/revoking staff claims, or disabling accounts.

The Android staff panel and customer appointment cancellation/rescheduling use Supabase Edge Functions (`list-staff-work`, `update-work-status`, and `manage-appointment`), alongside booking creation and checkout. Firebase Cloud Functions are not required by the current app flows. Follow `supabase/README.md` to configure and deploy the functions. For account and role operations, use a trusted local admin script/tool. Never place a service-account key in the website.

## Setup and deploy on Spark

1. In Firebase Authentication, keep **Google** sign-in enabled for the Google account shown in your console (or enable **Email/Password** for password accounts), and add the Hosting domain to authorized domains.
2. Give the intended administrator the `admin: true` custom claim from a trusted environment with Firebase Admin credentials. The account must sign out and back in after the claim is set. Keep service-account credentials outside this project and never paste them into the browser.
3. Deploy only the Spark-compatible rules and static site:

   ```sh
   npx firebase-tools deploy --only firestore:rules,hosting --project barbershop-e85e1
   ```

   Do not include `functions` in this command while staying on Spark.
4. Open `https://barbershop-e85e1.web.app/` and sign in with the admin account.

## Local preview

```sh
python3 -m http.server 8080
```

Open `http://localhost:8080/admin/`. Add `localhost` to Authentication's authorized domains if required.

Firestore Spark quotas are shared across the project. If a free quota is exceeded, Firebase can pause that product until its quota resets. Admin reads use up to 300 documents per Firestore collection per refresh.
