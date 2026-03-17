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
 * limitations under the License
 */

package com.android.dialer.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.android.dialer.R;

public class IodeSettingsFragment extends PreferenceFragmentCompat {
    private SharedPreferences prefs;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName("iode_spam_prefs");

        setPreferencesFromResource(R.xml.iode_preferences, rootKey);

        prefs = requireContext().getSharedPreferences("iode_spam_prefs", Context.MODE_PRIVATE);

        SwitchPreferenceCompat spamBlockPref = findPreference("enable_spam_block");
        if (spamBlockPref != null) {
            spamBlockPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    boolean isEnabled = (Boolean) newValue;

                    if (isEnabled) {
                        Toast.makeText(getContext(), R.string.iode_spam_blocking_enabled_toast, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(getContext(), R.string.iode_spam_blocking_disabled_toast, Toast.LENGTH_LONG).show();
                    }

                    return true;
                }
            });
        }

        Preference recentBlocksPref = findPreference("spam_recent_blocks_list");
        if (recentBlocksPref != null) {
            String savedList = prefs.getString("spam_blocked_numbers_list", "");

            if (!savedList.isEmpty()) {
                recentBlocksPref.setSummary(savedList);
            }
        }
    }
}
