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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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
        Core.setAppVisible(true);
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Core.setAppVisible(false);
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
        final EditText cap = field(box, "Battery capacity (kWh; 19.14 is this car)", String.valueOf(e.capacityKwh));
        final EditText rated = field(box, "Rated range (km; 240 matches the unit's own screen; 0 = unknown)", String.valueOf(e.wltpKm));
        final EditText reserve = field(box, "Reserve (km)", String.valueOf(e.reserveKm));
        final EditText speed3000 = field(box, String.format(Locale.US,
                "Speed at 3000 motor rpm (km/h). Now: %.1f km/h", e.kmPerMotorRev * 3000 * 60), "");
        final EditText known = field(box, String.format(Locale.US,
                "Or calibrate: real km of this trip (%.1f km counted, %.0f motor revs)", e.tripKm(), e.tripRevs()), "");
        TextView srcLabel = new TextView(this);
        srcLabel.setText("Speed and distance from");
        box.addView(srcLabel);
        final RadioGroup srcGroup = new RadioGroup(this);
        String[] srcNames = {"Auto (GPS while it agrees with rpm)", "Motor rpm (reacts fastest)", "GPS (lags a little)"};
        int[] srcIds = {RangeEngine.SRC_AUTO, RangeEngine.SRC_RPM, RangeEngine.SRC_GPS};
        for (int i = 0; i < srcNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(100 + srcIds[i]);
            rb.setText(srcNames[i]);
            srcGroup.addView(rb);
        }
        srcGroup.check(100 + e.speedSource);
        box.addView(srcGroup);
        // the car's speed-linked volume: needs the updated data feed, which reports its state
        TextView volTitle = new TextView(this);
        volTitle.setText("Car's speed-linked volume");
        box.addView(volTitle);
        final SpeedVol.State vs = SpeedVol.read(new java.io.File(SpeedVol.STATUS_PATH), System.currentTimeMillis());
        TextView volNow = new TextView(this);
        volNow.setText("Now: " + SpeedVol.label(vs));
        box.addView(volNow);
        final CheckBox volBox = new CheckBox(this);
        volBox.setText("Stop the speed-linked volume");
        volBox.setChecked(vs.boost == SpeedVol.BOOST_OFF);
        volBox.setEnabled(vs.boost != SpeedVol.UNKNOWN);
        box.addView(volBox);
        // floating range bubble
        TextView bubTitle = new TextView(this);
        bubTitle.setText("Floating range bubble" + (Bubble.canDraw(this) ? ""
                : " (needs the 'Draw over other apps' permission, see docs/INSTALL.md)"));
        box.addView(bubTitle);
        final RadioGroup bubGroup = new RadioGroup(this);
        String[] bubNames = {"Off", "Only while driving (recommended)", "Always (can cover the reverse camera until you move)"};
        for (int i = 0; i < bubNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(200 + i);
            rb.setText(bubNames[i]);
            bubGroup.addView(rb);
        }
        bubGroup.check(200 + Core.prefs().getInt("bubble", BubbleLogic.DRIVING));
        box.addView(bubGroup);
        final CheckBox demoBox = new CheckBox(this);        demoBox.setText("Demo mode (replay recorded drive from ev_demo.csv)");
        demoBox.setChecked(Core.isDemo());
        box.addView(demoBox);
        // the dialog is taller than the 720 px screen: it must scroll to reach the speed source and demo options
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("Settings").setView(scroll)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        saveSettings(cap, rated, reserve, speed3000, known, srcGroup.getCheckedRadioButtonId() - 100,
                                bubGroup.getCheckedRadioButtonId() - 200,
                                volBox.isEnabled() && volBox.isChecked() != (vs.boost == SpeedVol.BOOST_OFF)
                                        ? Boolean.valueOf(volBox.isChecked()) : null,
                                demoBox.isChecked());
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
            EditText known, int speedSource, int bubbleMode, Boolean volStop, boolean demoOn) {
        RangeEngine e = Core.engine;
        SharedPreferences.Editor ed = Core.prefs().edit();
        double c = num(cap, e.capacityKwh);
        if (c > 1 && c < 200) {
            ed.putLong("cap", Double.doubleToLongBits(c));
        }
        if (speedSource >= RangeEngine.SRC_AUTO && speedSource <= RangeEngine.SRC_GPS) {
            ed.putInt("speedsrc", speedSource);
        }
        if (bubbleMode >= BubbleLogic.OFF && bubbleMode <= BubbleLogic.ALWAYS) {
            ed.putInt("bubble", bubbleMode);
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
        if (volStop != null) {
            confirmVol(volStop.booleanValue());
        }
    }

    /** Changing the volume setting is a write to the head unit, so it is confirmed first. */
    private void confirmVol(final boolean stop) {
        new AlertDialog.Builder(this).setTitle("Change a setting on the head unit?")
                .setMessage(stop ? "This stops the car's speed-linked volume: the music no longer gets louder as you speed up. "
                        + "It changes a setting on the head unit, through the data feed. You can undo it here."
                        : "This restores the car's speed-linked volume: the music gets louder as you speed up.")
                .setPositiveButton(stop ? "Stop it" : "Restore it", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Core.setSpeedVolStop(stop);
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
