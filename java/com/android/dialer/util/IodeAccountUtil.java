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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.util.Log;

public class IodeAccountUtil {
    private static final String TAG = "IodeAccountUtil";
    private static final boolean DEBUG = true;

    public static class IodeAccountInfo {
        public final String email;
        public final String userId;
        public final String token;

        public IodeAccountInfo(String email, String userId, String token) {
            this.email = email;
            this.userId = userId;
            this.token = token;
        }
    }

    public static IodeAccountInfo getAccountInfo(Context context) {
        AccountManager accountManager = AccountManager.get(context);
        Account[] accounts = accountManager.getAccountsByType("com.iode.app.account");

        if (accounts != null && accounts.length > 0) {
            Account account = accounts[0];
            try {
                String email = account.name;
                String userId = accountManager.getUserData(account, "user");
                String token = accountManager.blockingGetAuthToken(account, "full_access", true);

                if (token != null && !token.isEmpty()) {
                    if (DEBUG) Log.d(TAG, "Token from AccountManager: " + token);
                    return new IodeAccountInfo(email, userId, token);
                } else {
                    Log.e(TAG, "Account found but no token is stored");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get token from AccountManager", e);
            }
        } else {
            Log.e(TAG, "No account found in AccountManager");
        }

        // Account or token not found, return null
        return null;
    }
}
