# Chí Phèo BarberShop

Vietnamese barber shop customer app and admin dashboard. The repository contains an Android app, a Firebase Hosting admin site, Supabase Edge Functions, and a local MLX hair-consultation server.

## Components

- **Android app** (`app/`): Java, XML, Material Components, AndroidX, Firebase Authentication, Cloud Firestore, Firebase Cloud Messaging, and WorkManager. The app supports email/password and Google sign-in, appointment booking and management, product browsing and checkout, saved addresses, favorites, notifications, promotions, points and vouchers, product/service reviews, and a Vietnamese hair-advisor chat. In debug builds, face scanning uses the MediaPipe Face Landmarker on-device and sends only geometric ratios to the local consultation server; it does not upload face images.
- **Admin dashboard** (`admin/`): responsive Vietnamese web app hosted on Firebase Hosting. Firebase Authentication and Firestore Security Rules protect catalog, branches, stylists, promotions, reward vouchers, shop events, customers, reviews, orders, appointments, and contact requests. Firebase Auth user roles and account status are managed server-side through `manage-staff`.
- **Supabase Edge Functions** (`supabase/functions/`): trusted operations for appointments, order creation and inventory, staff status changes, bank-transfer verification, unpaid-order expiry, loyalty redemption, event notifications, contact replies, hair-advisor responses, and staff account management. Functions verify Firebase ID tokens or the SePay HMAC signature as appropriate. Firebase service-account credentials belong in Supabase Secrets only.
- **Local AI service** (`ai-training/`): FastAPI server using MLX-LM and the bundled fused Qwen model. It provides `/api/chat`, `/api/consult-face`, and `/health`. The Android debug build calls this local service; release builds use the Supabase hair-advisor function. The development server is unauthenticated and must stay on a trusted network.
- **Data and project configuration**: Firestore rules and indexes are in the repository root. `seed/seed.js` inserts sample Vietnamese services, branches, stylists, products, and promotions. `scripts/set-staff-claim.js` is a trusted-machine bootstrap tool for initial Firebase custom claims.

## Main workflows

- Booking validates the service, branch, stylist, opening hours, and available hourly slots through Supabase. Customers can cancel or reschedule eligible appointments with the configured notice period.
- Checkout validates current prices, promotions, vouchers, and stock server-side. Orders support cash and SePay bank transfers. The SePay webhook checks its HMAC, destination, order code, and exact amount before confirming payment.
- Customers earn one loyalty point per 10,000 VND on eligible completed invoices. Reward vouchers and promotion codes are single-use per account; voucher redemption and order creation update balances and usage records atomically.
- Reviews are grouped in one app section. Service reviews require a completed appointment; product reviews require a completed, paid order and are tracked per purchased product.
- Admin-created shop events appear in the app inbox and are sent as push notifications when a user has a device token.

## Build and run

- Open the repository in Android Studio with JDK 17 and Android SDK 34 installed. Add the Firebase `google-services.json` file to `app/`, sync Gradle, and run the `app` debug variant. Build from the repository root with `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
- Configure and deploy Firebase Hosting, Firestore rules/indexes, and Supabase Edge Functions in their respective consoles/CLIs. The Android and admin files contain public client configuration only; never put Firebase service-account JSON, private keys, Supabase secret/service-role keys, or SePay secrets in the app or website.
- To run local AI on an Apple Silicon Mac, create `ai-training/.venv`, install `ai-training/requirements.txt`, and start `./ai-training/run_server.sh`. Set the Mac LAN address in `app/src/main/java/com/example/salonapp/Config.java` for a physical phone. Debug cleartext HTTP is enabled only in `app/src/debug/AndroidManifest.xml`; release builds should use HTTPS services. The face-scan action is hidden in release builds because its consultation endpoint is local-only.
- `seed/serviceAccountKey.json` and `ai-training/serviceAccountKey.json`, if present locally, are ignored credential files. Keep them private and rotate them if they have ever been shared.

## External model attribution

The bundled fused model is based on **Qwen2.5-1.5B-Instruct**, distributed under Apache-2.0. The bundled LoRA adapter is project-specific. Review the upstream model license and applicable model terms before redistributing model weights.
