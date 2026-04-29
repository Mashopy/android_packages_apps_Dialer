/*
 * Copyright (C) 2026 The iodéOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.spam;

import android.content.Context;
import android.content.SharedPreferences;
import android.telecom.Call;
import android.telecom.Connection;
import android.telephony.TelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import androidx.preference.PreferenceManager;

import com.android.dialer.R;
import com.android.dialer.util.IodeApiUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.Set;

public class SpamDetect {
    private static final String TAG = "SpamDetectTracker";

    // List of prefixes that are potential spam in France and Italy.
    private static final String SPAM_REGEX_FR = "^\\+33(?:162|163|2688|2689|270|271|377|378|424|425|568|569|598[7-9]|947[5-9]|94[8-9]|130956210)\\d*$";
    private static final String SPAM_REGEX_IT = "^\\+39(?:84[34])\\d+$";

    // List of prefixes that make uses of the STIR/SHAKEN protocol.
    private static final String STIR_SHAKEN_COUNTRIES_REGEX = "^(?:\\+1[2-9]\\d{8}|\\+33[1-9]\\d{8})$"; // USA, Canada and France

    private static List<Pattern> spamPatterns = null;
    private static List<String> spamNumbers = null;

    public static void incrementSpamCount(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);
        String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        int todayCount = prefs.getInt("spam_count_" + todayDate, 0);
        int totalCount = prefs.getInt("spam_count_total", 0);

        prefs.edit()
             .putInt("spam_count_" + todayDate, todayCount + 1)
             .putInt("spam_count_total", totalCount + 1)
             .apply();
    }

    public static void decrementSpamCount(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);
        String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        int todayCount = prefs.getInt("spam_count_" + todayDate, 0);
        int totalCount = prefs.getInt("spam_count_total", 0);

        // Math.max ensures we never accidentally display a negative number like "-1 spam blocked"
        prefs.edit()
             .putInt("spam_count_" + todayDate, Math.max(0, todayCount - 1))
             .putInt("spam_count_total", Math.max(0, totalCount - 1))
             .apply();
    }

    public static void logBlockedNumber(Context context, String phoneNumber) {
        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);

        String existingList = prefs.getString("spam_blocked_numbers_list", "");
        String timeStr = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(new Date());
        String newEntry = timeStr + "  -  " + phoneNumber;

        String updatedList = existingList.isEmpty() ? newEntry : newEntry + "\n" + existingList;

        // Limit the list to the last 20 entries (might change that value later)
        String[] lines = updatedList.split("\n");
        if (lines.length > 20) {
            updatedList = TextUtils.join("\n", Arrays.copyOfRange(lines, 0, 20));
        }

        prefs.edit().putString("spam_blocked_numbers_list", updatedList).apply();
    }

    public static void unlogBlockedNumber(Context context, String phoneNumber) {
        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);
        String existingList = prefs.getString("spam_blocked_numbers_list", "");

        if (existingList.isEmpty()) return;

        String[] lines = existingList.split("\n");
        List<String> updatedLines = new ArrayList<>();

        // Loop through the log and keep only the lines that DO NOT contain this phone number
        for (String line : lines) {
            if (!line.contains(phoneNumber)) {
                updatedLines.add(line);
            }
        }

        String updatedList = TextUtils.join("\n", updatedLines);
        prefs.edit().putString("spam_blocked_numbers_list", updatedList).apply();
    }

    public static boolean isSpamEnabled(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }

        if (phoneNumber.contains("&")) {
            phoneNumber = phoneNumber.split("&")[0];
            Log.i(TAG, "Found weird number! Cleaning up: " + phoneNumber);
        }

        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);

        if (!prefs.getBoolean("enable_spam_block", true)) {
            return false;
        }

        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String simCountryIso = (tm != null) ? tm.getNetworkCountryIso().toUpperCase() : "";
        if (simCountryIso.isEmpty()) simCountryIso = Locale.getDefault().getCountry().toUpperCase();
        String e164Number = PhoneNumberUtils.formatNumberToE164(phoneNumber, simCountryIso);
        if (e164Number == null) e164Number = phoneNumber;

        synchronized (SpamDetect.class) {
            if (spamPatterns == null && spamNumbers == null) {
                loadSpamList(context);
            }

            if (spamPatterns != null && !spamPatterns.isEmpty()) {
                for (Pattern p : spamPatterns) {
                    if (p.matcher(e164Number).matches()) {
                        Log.w(TAG, "Spam pattern match! " + e164Number + " matched pattern: " + p.pattern());
                        return true;
                    }
                }

                for (String s : spamNumbers) {
                    if (s.equals(e164Number)) {
                        Log.w(TAG, "Spam number match! " + e164Number + " matched exact number: " + s);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static boolean isSpam(Context context, int verificationStatus, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }

        if (phoneNumber.contains("&")) {
            phoneNumber = phoneNumber.split("&")[0];
            Log.i(TAG, "Found weird number! Cleaning up: " + phoneNumber);
        }

        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String simCountryIso = (tm != null) ? tm.getNetworkCountryIso().toUpperCase() : "";
        if (simCountryIso.isEmpty()) simCountryIso = Locale.getDefault().getCountry().toUpperCase();
        String e164Number = PhoneNumberUtils.formatNumberToE164(phoneNumber, simCountryIso);
        if (e164Number == null) e164Number = phoneNumber;

        if (isAllowlisted(context, e164Number)) {
            return false; // Allowlisted number, even if it's maybe spam
        }

        Log.i(TAG, "Incoming call received. Analyzing number: " + phoneNumber);

        // Phone number regexes: France and Italy have known spam numbers.
        if (e164Number.matches(SPAM_REGEX_FR) || e164Number.matches(SPAM_REGEX_IT)) {
            Log.w(TAG, "SPAM DETECTED: Matched French ARCEP prefix: " + e164Number);
            return true; // Known spam
        }

        // STIR/SHAKEN Check: Is it a supported country AND explicitly failed ?
        if (e164Number.matches(STIR_SHAKEN_COUNTRIES_REGEX)) {
            if (verificationStatus == Connection.VERIFICATION_STATUS_FAILED) {
                Log.w(TAG, "SPAM DETECTED: Failed STIR/SHAKEN verification: " + e164Number);
                return true; // STIR/SHAKEN Failed, mark as spam
            }

            Log.i(TAG, "STIR/SHAKEN status passed or not verified for: " + e164Number);
        }

        Log.i(TAG, "Call passed spam checks. Allowed: " + e164Number);
        return false;
    }

    public static boolean isAllowlisted(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }

        if (phoneNumber.contains("&")) {
            phoneNumber = phoneNumber.split("&")[0];
            Log.i(TAG, "Found weird number! Cleaning up: " + phoneNumber);
        }

        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);
        Set<String> allowedNumbers = prefs.getStringSet("spam_allowlist_set", new HashSet<>());

        if (allowedNumbers.isEmpty()) {
            return false;
        }

        String cleanIncoming = phoneNumber.replaceAll("[^0-9]", "");

        for (String allowed : allowedNumbers) {
            String cleanAllowed = allowed.replaceAll("[^0-9]", "");

            if (cleanAllowed.length() > 3 && cleanIncoming.endsWith(cleanAllowed)) {
                Log.w(TAG, "ALLOWLIST MATCH: Allowing number " + phoneNumber);
                return true;
            }
        }
        
        return false;
    }

    public static void removeFromAllowlist(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return;
        }

        if (phoneNumber.contains("&")) {
            phoneNumber = phoneNumber.split("&")[0];
            Log.i(TAG, "Found weird number! Cleaning up: " + phoneNumber);
        }

        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);
        Set<String> allowedNumbers = new HashSet<>(prefs.getStringSet("spam_allowlist_set", new HashSet<>()));

        if (allowedNumbers.remove(phoneNumber)) {
            prefs.edit().putStringSet("spam_allowlist_set", allowedNumbers).apply();

            incrementSpamCount(context);
            logBlockedNumber(context, phoneNumber);

            Log.i(TAG, "Numéro retiré de la liste blanche depuis l'historique : " + phoneNumber);
        } else {
            Log.i(TAG, "Ignoré : Le numéro " + phoneNumber + " n'était pas dans la liste blanche.");
        }
    }

    public static void addToAllowlist(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return;
        }

        if (phoneNumber.contains("&")) {
            phoneNumber = phoneNumber.split("&")[0];
            Log.i(TAG, "Found weird number! Cleaning up: " + phoneNumber);
        }

        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);
        Set<String> allowedNumbers = new HashSet<>(prefs.getStringSet("spam_allowlist_set", new HashSet<>()));

        if (allowedNumbers.add(phoneNumber)) {
            prefs.edit().putStringSet("spam_allowlist_set", allowedNumbers).apply();

            decrementSpamCount(context);
            unlogBlockedNumber(context, phoneNumber);

            Log.i(TAG, "Numéro ajouté à la liste blanche depuis l'historique : " + phoneNumber);
        } else {
            Log.i(TAG, "Ignoré : Le numéro " + phoneNumber + " était déjà dans la liste blanche.");
        }
    }

    private static void loadSpamList(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);
        long lastFetch = prefs.getLong("saved_list_timestamp", 0);
        spamPatterns = new ArrayList<>();
        spamNumbers = new ArrayList<>();

        if (lastFetch == 0 || System.currentTimeMillis() - lastFetch > 24 * 60 * 60 * 1000L) {
            Log.d(TAG, "No cached blocklist found or cache expired. Fetching from API...");

            try {
                String raw = IodeApiUtil.getApiBlocklist(context);
                if (raw == null || raw.isEmpty()) {
                    Log.e(TAG, "No blocklist received, getApiBlocklist returned null or empty string.");
                    return;
                }

                for (String entry : raw.split("\n")) {
                    entry = entry.trim();
                    if (entry.isEmpty()) continue;

                    if (entry.contains("#")) {
                        // Pattern e.g. 33162###### → ^\+?33162\d{6}$
                        String regexStr = "^\\+?" + entry.replace("#", "\\d") + "$";
                        spamPatterns.add(Pattern.compile(regexStr));
                    } else {
                        // Complete number e.g. +12015345822 → exact match
                        spamNumbers.add(entry);
                    }
                }
                Log.w(TAG, "Successfully compiled " + spamPatterns.size() + " block patterns.");
                Log.w(TAG, "Successfully compiled " + spamNumbers.size() + " block numbers.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to build blocklist API", e);
            }
        } else  {
            Log.d(TAG, "Blocklist cache is fresh. Skipping API check.");
        }
    }
}
