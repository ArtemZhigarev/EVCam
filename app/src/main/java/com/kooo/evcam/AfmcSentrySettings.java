package com.kooo.evcam;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * AppsForMyCar fork: the "Sentry mode" card in Settings (switch + picture interval), see
 * AfmcSentryMode. Kept out of SettingsFragment, which only calls bind().
 */
final class AfmcSentrySettings {
    private AfmcSentrySettings() {}

    static void bind(View root) {
        SwitchMaterial sw = root.findViewById(R.id.switch_afmc_sentry);
        Spinner interval = root.findViewById(R.id.spinner_afmc_sentry_interval);
        if (sw == null || interval == null) return;
        final Context context = root.getContext();

        sw.setChecked(AfmcSentryMode.isEnabled(context));
        sw.setOnCheckedChangeListener((b, on) -> {
            AfmcSentryMode.setEnabled(context, on);
            Toast.makeText(context, on
                    ? "Sentry mode on: records and takes pictures while the car is parked and left"
                    : "Sentry mode off", Toast.LENGTH_SHORT).show();
        });

        int[] choices = AfmcSentryPolicy.INTERVAL_CHOICES_MIN;
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) labels[i] = choices[i] + (choices[i] == 1 ? " minute" : " minutes");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        interval.setAdapter(adapter);
        interval.setSelection(AfmcSentryPolicy.intervalIndex(AfmcSentryMode.intervalMinutes(context)), false);
        interval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int minutes = choices[position];
                if (minutes == AfmcSentryMode.intervalMinutes(context)) return;
                AfmcSentryMode.setIntervalMinutes(context, minutes);
                Toast.makeText(context, "Sentry pictures every " + labels[position], Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
}
