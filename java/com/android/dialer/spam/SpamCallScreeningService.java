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

import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.util.Log;

public class SpamCallScreeningService extends CallScreeningService {

    private static final String TAG = "SpamDetectTracker";

    @Override
    public void onScreenCall(Call.Details callDetails) {
        if (callDetails.getHandle() == null) {
            respondToCall(callDetails, new CallResponse.Builder().build());
            return;
        }

        if (callDetails.getCallDirection() != Call.Details.DIRECTION_INCOMING) {
            respondToCall(callDetails, new CallResponse.Builder().build());
            return;
        }

        String phoneNumber = callDetails.getHandle().getSchemeSpecificPart();
        
        if (SpamDetect.isSpamEnabled(getApplicationContext(), phoneNumber) && !SpamDetect.isAllowlisted(getApplicationContext(), phoneNumber)) {
            Log.w(TAG, "Spam Match! Blocking call.");

            SpamDetect.incrementSpamCount(getApplicationContext());
            SpamDetect.logBlockedNumber(getApplicationContext(), phoneNumber);

            CallResponse.Builder responseBuilder = new CallResponse.Builder()
                .setDisallowCall(true)      // 1. Tell Android: Do not let this call go through
                .setRejectCall(true)        // 2. Tell Android: Hang up the modem immediately
                .setSkipNotification(true)  // 3. Tell Android: Do not show a missed call notification
                .setSkipCallLog(false);     // 4. Tell Android: Keep it in history so it shows up Red

            respondToCall(callDetails, responseBuilder.build());
            return;
        }

        respondToCall(callDetails, new CallResponse.Builder().build());
    }
}