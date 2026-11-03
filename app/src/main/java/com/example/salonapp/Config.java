package com.example.salonapp;
public class Config {
    // Cloudinary: NO api secret in the app. Create an UNSIGNED preset limited to images, a folder and a max size.
    public static final String CLOUD_NAME = "dj0e3lctd";
    public static final String UPLOAD_PRESET = "salon_unsigned";
    public static final int MAX_IMAGE_BYTES = 5_000_000;
    // SePay-linked BIDV personal accounts require transfers to the configured VA.
    public static final String BANK_ID = "BIDV";
    public static final String BANK_ACCOUNT = "96247MLSM8"; // SePay BIDV virtual account (VA)
    public static final String BANK_ACCOUNT_NAME = "BUI NGOC ANH";
    // Supabase project URL and publishable key are public client config, never service secrets.
    // Set these after creating/linking the Supabase project described in supabase/README.md.
    public static final String SUPABASE_URL = "https://qyvsjahijqtpskwslqku.supabase.co";
    public static final String SUPABASE_PUBLISHABLE_KEY = "sb_publishable_SP-FMcj7l3KzeG1ZQRm-gw_NmrIW9bw";
    public static boolean isSupabaseConfigured() {
        return !SUPABASE_URL.contains("YOUR_PROJECT_REF") && !SUPABASE_PUBLISHABLE_KEY.contains("YOUR_");
    }
}
