
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

package com.android.dialer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.android.dialer.util.IodeAccountUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

public class IodeApiUtil {
    private static final String TAG = "IodeApiUtil";

    public static JSONObject verifyApiDate(Context context) throws IOException, JSONException {
        IodeAccountUtil.IodeAccountInfo accountInfo = IodeAccountUtil.getAccountInfo(context);
        if (accountInfo == null) {
            Log.w(TAG, "No iode account found. Skipping spam list download.");
            return null;
        }

        Log.i(TAG, "Iode account found");

        // Read the blocklist date from the API
        Uri.Builder apiDateBuilder = Uri.parse("https://api.iode.tech/spam/blocklist/date").buildUpon();
        apiDateBuilder.appendQueryParameter("username", accountInfo.userId);
        apiDateBuilder.appendQueryParameter("token", accountInfo.token);
        String apiDateUrl = apiDateBuilder.build().toString();

        URL apiDate = URI.create(apiDateUrl).toURL();
        HttpURLConnection dateConn = (HttpURLConnection) apiDate.openConnection();
        dateConn.setRequestMethod("GET");
        dateConn.setConnectTimeout(10000);
        dateConn.setReadTimeout(10000);

        int responseCode = dateConn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            Log.e(TAG, "API rejected request. HTTP Code: " + responseCode);
            dateConn.disconnect();
            return null;
        }

        StringBuilder dateResponse = new StringBuilder();
        try (BufferedReader dateIn = new BufferedReader(new InputStreamReader(dateConn.getInputStream(), StandardCharsets.UTF_8))) {
            String dateLine;
            while ((dateLine = dateIn.readLine()) != null) {
                dateResponse.append(dateLine);
            }
        } finally {
            dateConn.disconnect();
        }

        JSONObject dateJson = new JSONObject(dateResponse.toString());
        String serverDateVersion = dateJson.getJSONObject("data")
                                           .getJSONObject("updatedAt")
                                           .getString("utc");

        // Compare server date with our locally saved date
        SharedPreferences prefs = context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);
        String localDateVersion = prefs.getString("saved_list_date", "");
            
        if (serverDateVersion.equals(localDateVersion)) {
            Log.w(TAG, "Spam list is already up to date (Date: " + localDateVersion + "). Skipping download.");
            return null;
        }

        Log.i(TAG, "New spam list date detected: " + serverDateVersion + ". Downloading...");

        return dateJson;
    }

    public static String getApiBlocklist(Context context) throws IOException, JSONException {
        IodeAccountUtil.IodeAccountInfo accountInfo = IodeAccountUtil.getAccountInfo(context);
        if (accountInfo == null) {
            Log.w(TAG, "No iode account found. Skipping spam list download.");
            return null;
        }

        // Verify if the API has a newer version of the blocklist
        JSONObject dateJson = verifyApiDate(context);
        if (dateJson == null) return null;

        // Retrieve the actual blocklist from the API after passing the date verification
        URL apiList = URI.create("https://api.iode.tech/spam/blocklist").toURL();
        HttpURLConnection blocklistConn = (HttpURLConnection) apiList.openConnection();
        blocklistConn.setRequestMethod("POST");
        blocklistConn.setConnectTimeout(10000);
        blocklistConn.setReadTimeout(10000);
        blocklistConn.setRequestProperty("Content-Type", "application/json; utf-8");
        blocklistConn.setRequestProperty("Accept", "application/json");
        blocklistConn.setDoOutput(true);

        String jsonPayload;
        try {
            JSONObject payload = new JSONObject();
            payload.put("username", accountInfo.userId);
            payload.put("token", accountInfo.token);
            jsonPayload = payload.toString();
        } catch (JSONException e) {
            throw new IOException("Failed to build or parse JSON", e);
        }

        try (OutputStream os = blocklistConn.getOutputStream()) {
            byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = blocklistConn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            Log.e(TAG, "API rejected request. HTTP Code: " + responseCode);
            blocklistConn.disconnect();
            return null;
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(blocklistConn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line).append("\n");
            }
        } finally {
            blocklistConn.disconnect();
        }

        // API content has been sucessfully retrieved, save the new date version locally
        String serverDateVersion = dateJson.getJSONObject("data")
                                        .getJSONObject("updatedAt")
                                        .getString("utc");
        context.getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_list_date", serverDateVersion)
            .putLong("saved_list_timestamp", System.currentTimeMillis())
            .apply();

        // Return the blocklist
        return response.toString();
    }
}
