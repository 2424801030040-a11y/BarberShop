// Bootstrap admin/staff access using a trusted Google Cloud identity.
// node set-staff-claim.js <firebase-auth-uid> [--admin|--revoke|--revoke-admin]
const admin = require("firebase-admin");

admin.initializeApp({credential: admin.credential.applicationDefault()});

async function main() {
  const [, , uid, action] = process.argv;
  const actions = [undefined, "--admin", "--revoke", "--revoke-admin"];
  if (!uid || !actions.includes(action)) {
    throw new Error("Usage: node set-staff-claim.js <firebase-auth-uid> [--admin|--revoke|--revoke-admin]");
  }
  const user = await admin.auth().getUser(uid);
  const claims = {...user.customClaims};
  const claim = action === "--admin" || action === "--revoke-admin" ? "admin" : "staff";
  if (action === "--revoke" || action === "--revoke-admin") delete claims[claim];
  else claims[claim] = true;
  await admin.auth().setCustomUserClaims(uid, claims);
  console.log(`${claim} access ${action && action.startsWith("--revoke") ? "revoked from" : "granted to"} ${uid}. The user must sign out and back in.`);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
