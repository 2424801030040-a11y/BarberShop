# Chí Phèo BarberShop – How to Run the App

Android app (Java, XML, Material, AndroidX) using Firebase (Auth, Firestore, FCM), Cloudinary (avatars) and WorkManager (reminders).

---

## 1. Requirements

| Tool | Version |
|---|---|
| Android Studio | Koala (2024.1.1) or newer |
| JDK | 17 (bundled with Android Studio) |
| Android SDK | API 34 platform installed |
| Gradle | 9.3.1 (included through the project wrapper) |
| Test device | Emulator with a **Google Play** system image, or a real phone (API 24+) |
| Accounts | Google/Firebase account, Supabase account, Cloudinary account (free tiers have usage limits) |

---

## 2. Open the project

1. Unzip `ChiPheoBarberShop.zip`.
2. In Android Studio: **File → Open** and select the `ChiPheoBarberShop` folder.
3. Wait for Gradle sync. The project uses Android Gradle Plugin 9.1.1 and includes the Gradle 9.3.1 wrapper; do not downgrade Gradle to 8.7. Run `./gradlew assembleDebug` on macOS/Linux or `gradlew.bat assembleDebug` on Windows to build from a terminal.
4. Add your Firebase project's `google-services.json` to `app/` before syncing. This file is intentionally ignored by Git because it is project-specific.

---

## 3. Firebase setup

### 3.1 Create the project
1. Go to <https://console.firebase.google.com> → **Add project**.
2. In the project, click **Add app → Android**.
3. Package name: `com.example.salonapp` (if you rename the package in the code, use the new name here).

### 3.2 Get your debug SHA fingerprints (needed for Google sign-in)
Run this in a terminal (macOS/Linux; on Windows use `%USERPROFILE%\.android\debug.keystore`):

```
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Copy the **SHA-1** and **SHA-256** values into **Project settings → Your apps → Add fingerprint**.

### 3.3 Add `google-services.json`
1. Download `google-services.json` from the app settings page.
2. Place it in `ChiPheoBarberShop/app/` (next to `app/build.gradle`).
3. **Download it again after enabling the sign-in providers below**, so it contains the web client ID.
4. In Android Studio click **Sync Project with Gradle Files**.

### 3.4 Enable sign-in methods
**Build → Authentication → Sign-in method**:
- **Email/Password** – enable.
- **Google** – enable and choose a support email.
- Do not enable phone/SMS sign-in if you want to stay on Firebase Spark; Firebase SMS verification requires Blaze and charges per SMS. Use Email/Password or Google sign-in.

### 3.5 Create Firestore and publish the rules
1. **Build → Firestore Database → Create database** (production mode, pick a region).
2. Open the **Rules** tab, paste the full contents of `firestore.rules` from the project root, and click **Publish**.

Publish both `firestore.rules` and `firestore.indexes.json` from the project root as described in section 8.

---

## 4. Add sample data (Firestore)

Create these collections in the console (**Firestore → Start collection**). Use *Auto-ID* for document IDs. Numbers must be saved as **number** type.

| Collection | Fields |
|---|---|
| `services` | `name` (string), `description` (string), `durationMinutes` (integer), `price` (number, VND) |
| `branches` | `name`, `description` (**the street address**, used for the map), `phone` |
| `stylists` | `name`, `description`, `branchId` (**the ID of a document in `branches`**) |
| `products` | `name`, `description`, `price` (number), `category` (string), `stock` (number) |
| `promotions` | `name`, `description`, `code` (string, e.g. `WELCOME10` for a manually created promo), `percent` (number, e.g. `10`); optional `active`, `startsAt`, `endsAt`, `minSubtotal` |

Example documents:

```
services:   { name: "Classic Haircut", description: "30 min", durationMinutes: 30, price: 80000 }
branches:   { name: "District 1", description: "12 Nguyen Hue, District 1, Ho Chi Minh City", phone: "0900000000" }
stylists:   { name: "Minh", description: "Senior stylist", branchId: "<branch doc ID>" }
products:   { name: "Matte Pomade", description: "Strong hold", price: 180000, category: "Styling", stock: 25 }
promotions: { name: "Welcome 10%", description: "10% off your first order", code: "WELCOME10", percent: 10 }
```

If you import the Vietnamese sample data with `seed/seed.js`, use `CHAOBAN10` instead; that is the code created by the seed script. The app uses the explicit `durationMinutes` field when calculating appointment availability; services without it default to 60 minutes.

> Without at least one branch **and** one stylist whose `branchId` matches, the Book tab has nobody to book with.

---

## 5. Configure `Config.java`

File: `app/src/main/java/com/example/salonapp/Config.java`

### 5.1 Cloudinary (avatar upload)
1. Create a free account at <https://cloudinary.com>. Copy your **Cloud name**.
2. **Settings → Upload → Upload presets → Add upload preset**:
   - Signing mode: **Unsigned**
   - Allowed formats: `jpg, png, webp`
   - Folder: `avatars`
   - Set a maximum file size (e.g. 5 MB)
3. Fill in:
   ```java
   CLOUD_NAME    = "your_cloud_name";
   UPLOAD_PRESET = "your_unsigned_preset";
   ```
4. **Never put the Cloudinary API secret in the app.**

### 5.2 Payment details
Replace the placeholders so the bank QR and wallet instructions show your real information:

```java
BANK_ID           = "BIDV";           // bank code for the linked BIDV account
BANK_ACCOUNT      = "your SePay VA";  // use the virtual account, not the BIDV main account
BANK_ACCOUNT_NAME = "BUI NGOC ANH"; // bank account holder shown by BIDV
WALLET_PHONE      = "0900000000";     // MoMo / ZaloPay / VNPay number
```

Also change `SHOP_PHONE` in `MoreFragment.java` to the shop's phone number.

---

## 6. Run the app

1. Start an emulator (Google Play image) or connect a phone with USB debugging.
2. Select the device in the toolbar and press **Run ▶**.
3. Allow the notification permission when asked (Android 13+).

---

## 7. Test checklist

| Feature | How to test |
|---|---|
| Email sign-up / login / reset | Login screen → *Create account*, *Log in*, *Forgot password?* |
| Google login | *Continue with Google* (needs SHA-1 + provider enabled) |
| Profile | Profile tab → edit name and phone → *Save profile* |
| Avatar | *Change photo* (needs Cloudinary config) |
| Addresses | Profile → *Delivery addresses* → add, set default, delete |
| Services and reviews | Services tab → tap a service to see its reviews |
| Branches and map | More → *Branches & map* → tap a branch |
| Promotions, FAQ, call, contact | More tab |
| Book a haircut | Book tab → branch, stylist, service, date, free time → *Confirm* |
| Cancel / reschedule | Profile → *My appointments* → tap a pending or confirmed appointment (needs ≥ 2 hours notice); Supabase validates and atomically updates reserved time slots |
| Shop | Shop tab → category, sort, search; tap = add to cart; long-press = reviews |
| Checkout | *View cart* → address, promo code (e.g. `CHAOBAN10` after importing seed data), choose **Cash / Bank transfer** → *Place order* |
| Orders | Profile → *My orders* |
| Notifications | Firebase console → **Messaging → New campaign → Notification**, target topic `promotions` |

---

## 8. Staff actions

Staff can access **Profile → Quản lý cửa hàng** after an administrator grants the Firebase Auth `staff` custom claim. The staff screen lists the latest 50 appointments and orders and allows valid status changes through Supabase. Grant or revoke a claim from a trusted machine:

```sh
cd scripts && npm install
GOOGLE_APPLICATION_CREDENTIALS=/path/to/admin-service-account.json node set-staff-claim.js FIREBASE_AUTH_UID
# revoke access with: node set-staff-claim.js FIREBASE_AUTH_UID --revoke
# bootstrap the first web administrator with: node set-staff-claim.js FIREBASE_AUTH_UID --admin
```

After bootstrapping the first administrator, use the [web admin dashboard](admin/README.md) for catalog, contact, and review management. Grant or revoke staff access with the trusted local script above; the browser dashboard does not change Authentication claims.

Staff can confirm/complete appointments, cancel eligible appointments, mark orders as shipped/delivered, cancel pending orders (which restores stock), and mark payments paid. Supabase validates status changes and creates in-app notifications, then attempts FCM delivery.

For a browser-based administrator dashboard, follow [`admin/README.md`](admin/README.md). The web UI can manage catalog data, contact requests, and reviews, and can read appointments, orders, customers, and staff data. Trusted appointment/order status changes are handled by Supabase Edge Functions.

| Collection | Field | Values |
|---|---|---|
| `appointments` | `status` | `pending` → `confirmed` → `completed` (or canceled while pending/confirmed) |
| `orders` | `status` | `pending` → `shipping` → `delivered`; pending orders can be canceled |
| `orders` | `paymentStatus` | `unpaid` → `paid` after a verified SePay webhook or staff confirms payment |

- Customers can rate a service only when the appointment is `completed`, and a product only when the order is `delivered`.
- Deploy the Supabase functions listed in `supabase/README.md` for booking, appointment changes, checkout, staff actions, notifications, and bank confirmation. Publish Firestore rules and indexes from the project root:
  ```sh
  npx --yes firebase-tools deploy --only firestore:rules,firestore:indexes --project barbershop-e85e1
  ```
  Keep `seed/serviceAccountKey.json` private and never commit or share it; if it has already been exposed, revoke that service-account key in Google Cloud Console and create a replacement.

---

## 9. Troubleshooting

| Problem | Fix |
|---|---|
| Sync error: `google-services.json is missing` | Put the file in `app/` (step 3.3). |
| `Unresolved reference: default_web_client_id` | Enable the Google provider, then re-download `google-services.json`. |
| Google sign-in fails | Add the SHA-1 fingerprint, re-download `google-services.json`, use an emulator with Google Play. |
| `PERMISSION_DENIED` toast | Publish `firestore.rules` (step 3.5). |
| Lists are empty | Add the sample data (step 4) and check the collection names. |
| No stylists or no times when booking | Each stylist needs a `branchId` equal to a branch document ID; pick a future date. |
| Avatar upload fails | Check `CLOUD_NAME` / `UPLOAD_PRESET`, and that the preset is **Unsigned**. |
| QR image does not load | The device needs internet; check `BANK_ID` and `BANK_ACCOUNT`. For BIDV personal accounts, `BANK_ACCOUNT` must be the linked SePay VA. |
| Reminder or push not shown | Allow notifications in system settings (Android 13+). |
| Booking, checkout, or staff actions fail | Deploy the relevant Supabase Edge Functions, configure their secrets, and deploy the Firestore rules/indexes in step 8. |
| Gradle or AGP version error | Use Gradle 9.3.1 with Android Gradle Plugin 9.1.1; see step 2. |

---

## 10. Known limitations
- Checkout totals, promotion eligibility, and stock are checked by the Supabase `create-order` Edge Function.
- Automatic bank confirmation depends on SePay delivering a signed webhook for the correct receiving account, order code, and exact amount. Transfers absent from SePay's transaction feed cannot update an order automatically; reconcile these with SePay and verify payment before using the staff fallback.
- The app interface is in Vietnamese; Firebase status codes remain in English in stored data.
