package com.evrange.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Display only. Tracking runs in Core, kept alive by TrackService. */
public class MainActivity extends Activity implements DashView.Listener {
    private final Handler ui = new Handler();
    private DashView view;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            view.setStatus(Core.mode, Core.stale, Core.ageSec, Core.gpsFix());
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Boolean demoExtra = null;
        if (getIntent() != null && getIntent().hasExtra("demo")) {
            demoExtra = Boolean.valueOf(getIntent().getBooleanExtra("demo", false));
        }
        Core.start(this, demoExtra);
        try {
            Intent svc = new Intent(this, TrackService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable ignored) {
        }
        view = new DashView(this);
        view.bind(Core.engine, this);
        setContentView(view);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasExtra("demo")) {
            Core.start(this, Boolean.valueOf(intent.getBooleanExtra("demo", false)));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
        Core.saveState();
    }

    // ---- DashView.Listener ----
    @Override
    public void onWindowSelected(int km) {
        Core.engine.windowKm = km;
        Core.prefs().edit().putInt("window", km).apply();
        view.invalidate();
    }

    @Override
    public void onNewTrip() {
        Core.engine.resetTrip();
        view.invalidate();
    }

    @Override
    public void onSettings() {
        final RangeEngine e = Core.engine;
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 20, 40, 10);
        final EditText cap = field(box, "Battery capacity (kWh, usable)", String.valueOf(e.capacityKwh));
        final EditText rated = field(box, "Rated range (km; 240 matches the unit's own screen; 0 = unknown)", String.valueOf(e.wltpKm));
        final EditText reserve = field(box, "Reserve (km)", String.valueOf(e.reserveKm));
        final EditText speed3000 = field(box, String.format(Locale.US,
                "Speed at 3000 motor rpm (km/h). Now: %.1f km/h", e.kmPerMotorRev * 3000 * 60), "");
        final EditText known = field(box, String.format(Locale.US,
                "Or calibrate: real km of this trip (%.1f km counted, %.0f motor revs)", e.tripKm(), e.tripRevs()), "");
        final CheckBox demoBox = new CheckBox(this);
        demoBox.setText("Demo mode (replay recorded drive from ev_demo.csv)");
        demoBox.setChecked(Core.isDemo());
        box.addView(demoBox);
        new AlertDialog.Builder(this).setTitle("Settings").setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        saveSettings(cap, rated, reserve, speed3000, known, demoBox.isChecked());
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private EditText field(LinearLayout box, String label, String value) {
        TextView t = new TextView(this);
        t.setText(label);
        box.addView(t);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        box.addView(e);
        return e;
    }

    private static double num(EditText e, double fallback) {
        try {
            return Double.parseDouble(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void saveSettings(EditText cap, EditText rated, EditText reserve, EditText speed3000,
            EditText known, boolean demoOn) {
        RangeEngine e = Core.engine;
        SharedPreferences.Editor ed = Core.prefs().edit();
        double c = num(cap, e.capacityKwh);
        if (c > 1 && c < 200) {
            ed.putLong("cap", Double.doubleToLongBits(c));
        }
        ed.putLong("wltp", Double.doubleToLongBits(Math.max(0, num(rated, e.wltpKm))));
        ed.putLong("reserve", Double.doubleToLongBits(Math.max(0, num(reserve, e.reserveKm))));
        double s3000 = num(speed3000, 0);
        double realKm = num(known, 0);
        if (s3000 > 5 && s3000 < 120) {
            ed.putLong("kmrev2", Double.doubleToLongBits(s3000 / (3000.0 * 60.0)));
        } else if (realKm > 0.2 && e.tripRevs() > 100) {
            ed.putLong("kmrev2", Double.doubleToLongBits(realKm / e.tripRevs()));
        }
        ed.apply();
        Core.applyConfig();
        Core.setDemo(demoOn);
        view.invalidate();
    }
}
