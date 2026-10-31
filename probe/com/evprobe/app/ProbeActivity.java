package com.evprobe.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * READ-ONLY probe of the head unit's car-info interface. It makes exactly one kind of call, the read
 * method requestCarBaseInfo (transaction 6), once a second while the screen is open. It never calls the
 * interface's command or serial-port methods. Values are shown with names and logged without the VIN.
 */
public class ProbeActivity extends Activity {
    private static final String DESCRIPTOR = "com.ts.can.carinfo.ICarInfoService";
    private static final int TX_REQUEST_CAR_BASE_INFO = 6;  // the only call this app ever makes

    private final Handler handler = new Handler();
    private final ProbeModel model = new ProbeModel();
    private IBinder binder;
    private boolean bindRequested;
    private TextView text;
    private String lastMarker = "(none)";
    private String status = "connecting...";

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            status = "connected";
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            status = "service disconnected";
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                poll();
            } catch (Throwable t) {
                status = "error: " + t;
            }
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 8, 16, 8);
        TextView title = new TextView(this);
        title.setText("CarInfo Probe (read-only). Shift gear, then tap the matching button:");
        title.setTextSize(18);
        root.addView(title);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final String label : new String[] {"P", "R", "N", "D", "S", "Other"}) {
            Button bt = new Button(this);
            bt.setText(label);
            bt.setTextSize(20);
            bt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mark(label);
                }
            });
            row.addView(bt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setTextSize(20);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetModel();
            }
        });
        row.addView(reset, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
        ScrollView sv = new ScrollView(this);
        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(16);
        sv.addView(text);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private synchronized void resetModel() {
        // a fresh model instance is not possible (final), so re-create the activity state through a new object
        recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!bindRequested) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName("com.ts.MainUI", "com.ts.can.carinfo.CarInfoService"));
                bindRequested = bindService(i, conn, Context.BIND_AUTO_CREATE);
                if (!bindRequested) {
                    status = "bindService refused";
                }
            } catch (Throwable t) {
                status = "bind failed: " + t;
            }
        }
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bindRequested) {
            try {
                unbindService(conn);
            } catch (Throwable ignored) {
            }
            bindRequested = false;
        }
    }

    private void poll() throws Exception {
        if (binder == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TX_REQUEST_CAR_BASE_INFO, data, reply, 0);
            reply.readException();
            int[] values = reply.createIntArray();
            if (values != null && values.length >= ProbeModel.SIZE) {
                model.add(values);
                log(System.currentTimeMillis(), values);
                status = "connected, " + model.samples() + " readings";
            } else {
                status = "unexpected reply length " + (values == null ? "null" : String.valueOf(values.length));
            }
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void append(String file, String header, String line) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, file);
            boolean fresh = !f.exists();
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                if (fresh) {
                    out.write((header + "\n").getBytes("UTF-8"));
                }
                out.write((line + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private void log(long ms, int[] values) {
        append("carinfo_probe.csv", ProbeModel.csvHeader(), ProbeModel.csvRow(ms, values));
    }

    private void mark(String label) {
        long now = System.currentTimeMillis();
        append("markers.csv", "epoch_ms,label", now + "," + label);
        lastMarker = label + " at " + String.format(Locale.US, "%tT", now);
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        sb.append("status: ").append(status).append("   last marker: ").append(lastMarker).append('\n');
        int[] v = model.last();
        if (v != null) {
            for (int k = 0; k < ProbeModel.KEY.length; k++) {
                int i = ProbeModel.KEY[k];
                sb.append(String.format(Locale.US, "%s%2d %-14s %6d%s", model.changed(i) ? "*" : " ", i,
                        ProbeModel.NAMES[i], v[i], k % 2 == 1 ? "\n" : "    "));
            }
            sb.append("\n\nvalues that changed since the start (* above):\n").append(model.changedSummary());
        }
        text.setText(sb.toString());
    }
}
