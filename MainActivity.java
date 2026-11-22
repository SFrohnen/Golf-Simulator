package com.privategolf.r10range;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;

/** Native offline Android shell. BLE operations are serialized on the UI thread. */
public final class MainActivity extends Activity {
    private static final int BT_PERMS_REQUEST = 41;
    private static final int SAVE_CSV_REQUEST = 42;
    private static final UUID MULTILINK = UUID.fromString("6a4e2800-667b-11e3-949a-0800200c9a66");
    private static final UUID CHAR_NOTIFY_AND_REGISTER = UUID.fromString("6a4e2810-667b-11e3-949a-0800200c9a66");
    private static final UUID CHAR_WRITE = UUID.fromString("6a4e2820-667b-11e3-949a-0800200c9a66");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<BleWrite> writes = new ArrayDeque<>();
    private WebView web;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic notifyAndRegister;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothDevice awaitingBond;
    private R10Protocol protocol;
    private boolean scanning, writeBusy, closed;
    private Runnable stopScanTask, writeTimeout;
    private String pendingCsv;
    private boolean bondReceiverRegistered;

    private static final class BleWrite {
        final BluetoothGattCharacteristic target;
        final byte[] data;
        BleWrite(BluetoothGattCharacteristic target, byte[] data) { this.target=target;this.data=data; }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff081625);
        getWindow().setNavigationBarColor(0xff081625);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        web = new WebView(this);
        web.setBackgroundColor(0xff081625);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
        });
        // Our JS interface is only exposed to the local APK asset, never remote pages.
        web.addJavascriptInterface(new Bridge(), "R10Native");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        IntentFilter bondFilter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bondReceiver, bondFilter);
        bondReceiverRegistered = true;
    }

    private void emit(String type, String message) {
        mainHandler.post(() -> {
            if (web != null) web.evaluateJavascript("window.nativeEvent(" + JSONObject.quote(type) + "," + JSONObject.quote(message) + ")", null);
        });
    }
    private void liveShot(R10Protocol.Shot shot) {
        try {
            JSONObject data = new JSONObject();
            data.put("source", "R10");
            data.put("shotId", shot.shotId);
            data.put("practice", shot.practice);
            data.put("ballSpeed", shot.speedMps); // m/s
            data.put("launch", shot.verticalDeg); // degrees
            data.put("direction", shot.horizontalDeg); // degrees
            data.put("spin", shot.spinRpm); // rpm
            data.put("spinAxis", shot.spinAxisDeg);
            data.put("spinSource", new String[]{"ESTIMATED", "FLIGHT_MODEL", "UNCLASSIFIED", "MEASURED"}[Math.max(0, Math.min(3, shot.spinSource))]);
            if (shot.hasClub) {
                data.put("clubSpeed", shot.clubSpeedMps);
                data.put("face", shot.faceDeg);
                data.put("path", shot.pathDeg);
                data.put("attack", shot.attackDeg);
            }
            // The R10 does not report carry/total; HTML estimates those separately.
            mainHandler.post(() -> {
                if (web != null) web.evaluateJavascript("window.receiveR10Shot(" + data.toString() + ")", null);
            });
        } catch (Exception ignored) { emit("log", "Shot JSON conversion failed"); }
    }

    public final class Bridge {
        @JavascriptInterface public void scan() { runOnUiThread(() -> beginScan()); }
        @JavascriptInterface public void disconnect() { runOnUiThread(() -> closeBle()); }
        @JavascriptInterface public void exportCsv(String filename, String csv) {
            runOnUiThread(() -> {
                pendingCsv = csv;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/csv");
                i.putExtra(Intent.EXTRA_TITLE, filename.replaceAll("[^A-Za-z0-9_.-]", "_"));
                startActivityForResult(i, SAVE_CSV_REQUEST);
            });
        }
    }

    private boolean allowed(String name) { return Build.VERSION.SDK_INT < 23 || checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED; }
    private void beginScan() {
        if (adapter == null) { emit("status", "Bluetooth not available on this device"); return; }
        if (Build.VERSION.SDK_INT >= 31 && (!allowed(Manifest.permission.BLUETOOTH_SCAN) || !allowed(Manifest.permission.BLUETOOTH_CONNECT))) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, BT_PERMS_REQUEST); return;
        }
        if (Build.VERSION.SDK_INT < 31 && !allowed(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, BT_PERMS_REQUEST); return;
        }
        if (!adapter.isEnabled()) { emit("status", "Turn on Android Bluetooth, then scan again"); return; }
        closeBle();
        closed = false;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { emit("status", "BLE scanner unavailable"); return; }
            scanning = true;
            scanner.startScan(scanCallback);
            emit("status", "Scanning for Approach R10 (15 seconds)...");
            stopScanTask = () -> {
                if (!scanning) return;
                stopScan();
                emit("status", "R10 not found. Turn off Garmin Golf and pair the R10 in Android Bluetooth settings.");
            };
            mainHandler.postDelayed(stopScanTask, 15000);
        } catch (Exception e) { scanning = false; emit("status", "BLE scan error: " + e.getMessage()); }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            try { if (name == null) name = device.getName(); }
            catch (SecurityException ignored) {}
            // Exact R10 name preferred; Garmin vendor alone is not enough (many Garmin devices advertise).
            if (name == null || !name.toLowerCase(java.util.Locale.ROOT).contains("approach r10")) return;
            final String foundName = name;
            mainHandler.post(() -> {
                if (!scanning) return;
                stopScan();
                emit("status", "Found " + foundName + ". Checking Bluetooth pairing...");
                connectOrBond(device);
            });
        }
        @Override public void onScanFailed(int code) {
            mainHandler.post(() -> {stopScan(); emit("status", "BLE scan failed (" + code + ")");});
        }
    };
    private void stopScan() {
        if (stopScanTask != null) mainHandler.removeCallbacks(stopScanTask);
        if (!scanning) return;
        scanning = false;
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
    }
    private void connectOrBond(BluetoothDevice device) {
        try {
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) { connect(device); return; }
            awaitingBond = device;
            if (device.getBondState() == BluetoothDevice.BOND_BONDING || device.createBond()) {
                emit("status", "Bluetooth pairing requested. Accept Android's pairing prompt.");
            } else emit("status", "Pair the R10 in Android Bluetooth settings, then scan again.");
        } catch (Exception e) { emit("status", "BLE pairing error: " + e.getMessage()); }
    }
    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
            BluetoothDevice device;
            if (Build.VERSION.SDK_INT >= 33) device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            else { @SuppressWarnings("deprecation") BluetoothDevice old = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE); device = old; }
            if (device == null || awaitingBond == null || !device.getAddress().equals(awaitingBond.getAddress())) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            if (state == BluetoothDevice.BOND_BONDED) {
                awaitingBond = null;
                emit("log", "R10 Bluetooth bond established");
                connect(device);
            } else if (state == BluetoothDevice.BOND_NONE) {
                awaitingBond = null;
                emit("status", "Pairing did not complete. Pair using Android Bluetooth settings and retry.");
            }
        }
    };

    private void connect(BluetoothDevice device) {
        if (closed) return;
        try {
            emit("status", "Connecting to R10 via Bluetooth LE...");
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) { emit("status", "GATT connection error: " + e.getMessage()); }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            mainHandler.post(() -> {
                if (g != gatt || closed) return;
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    emit("status", "Connected. Discovering R10 GATT services...");
                    try { if (!g.discoverServices()) emit("status", "Service discovery could not start"); }
                    catch (Exception e) { emit("status", "Service discovery failed: " + e.getMessage()); }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    emit("status", "R10 disconnected (GATT " + status + "). Reconnect using Scan.");
                    closeGattOnly();
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            mainHandler.post(() -> {
                if (closed || g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { emit("status", "Service discovery error " + status); return; }
                BluetoothGattService service = g.getService(MULTILINK);
                if (service == null) { emit("status", "R10 MultiLink BLE service not found"); return; }
                notifyAndRegister = service.getCharacteristic(CHAR_NOTIFY_AND_REGISTER);
                writeCharacteristic = service.getCharacteristic(CHAR_WRITE);
                if (notifyAndRegister == null || writeCharacteristic == null) {
                    emit("status", "R10 MultiLink characteristics missing"); return;
                }
                BluetoothGattDescriptor descriptor = notifyAndRegister.getDescriptor(CCCD);
                if (descriptor == null || !g.setCharacteristicNotification(notifyAndRegister, true)) {
                    emit("status", "Cannot subscribe to R10 BLE notifications"); return;
                }
                try {
                    boolean ok;
                    if (Build.VERSION.SDK_INT >= 33) ok = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
                    else {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        ok = g.writeDescriptor(descriptor);
                    }
                    if (!ok) emit("status", "R10 notification subscription failed to start");
                    else emit("status", "Subscribing to R10 MultiLink notifications...");
                } catch (Exception e) { emit("status", "Notification subscription error: " + e.getMessage()); }
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            mainHandler.post(() -> {
                if (closed || g != gatt || !CCCD.equals(descriptor.getUuid())) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { emit("status", "R10 notification enable failed (" + status + ")"); return; }
                protocol = new R10Protocol(new R10Protocol.Events() {
                    @Override public void write(boolean register, byte[] chunk) { queueWrite(register, chunk); }
                    @Override public void phase(String name) { emit("status", name); }
                    @Override public void log(String message) { emit("log", message); }
                    @Override public void shot(R10Protocol.Shot s) { liveShot(s); }
                });
                protocol.start();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            mainHandler.post(() -> {
                if (closed || g != gatt) return;
                writeBusy = false;
                if (writeTimeout != null) mainHandler.removeCallbacks(writeTimeout);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emit("status", "R10 BLE write failed (" + status + "). Disconnect and retry.");
                    writes.clear(); return;
                }
                drainWrites();
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (!CHAR_NOTIFY_AND_REGISTER.equals(characteristic.getUuid())) return;
            byte[] copy = value == null ? null : value.clone();
            mainHandler.post(() -> { if (!closed && g == gatt && protocol != null) protocol.notification(copy); });
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT >= 33 || !CHAR_NOTIFY_AND_REGISTER.equals(characteristic.getUuid())) return;
            byte[] value = characteristic.getValue();
            byte[] copy = value == null ? null : value.clone();
            mainHandler.post(() -> { if (!closed && g == gatt && protocol != null) protocol.notification(copy); });
        }
    };

    private void queueWrite(boolean register, byte[] chunk) {
        BluetoothGattCharacteristic ch = register ? notifyAndRegister : writeCharacteristic;
        if (gatt == null || ch == null) { emit("log", "Write ignored: GATT not ready"); return; }
        writes.addLast(new BleWrite(ch, chunk));
        drainWrites();
    }
    private void drainWrites() {
        if (writeBusy || writes.isEmpty() || gatt == null || closed) return;
        BleWrite next = writes.removeFirst();
        try {
            next.target.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(next.target, next.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
            } else {
                next.target.setValue(next.data);
                ok = gatt.writeCharacteristic(next.target);
            }
            if (!ok) {
                writes.clear(); emit("status", "BLE write could not start. Try reconnecting."); return;
            }
            writeBusy = true;
            writeTimeout = () -> {
                if (writeBusy) {
                    writeBusy = false;
                    writes.clear();
                    emit("status", "Bluetooth write timed out. Disconnect and retry.");
                }
            };
            mainHandler.postDelayed(writeTimeout, 6000);
        } catch (Exception e) {
            writes.clear(); writeBusy = false;
            emit("status", "BLE write error: " + e.getMessage());
        }
    }
    private void closeGattOnly() {
        if (writeTimeout != null) mainHandler.removeCallbacks(writeTimeout);
        writes.clear(); writeBusy=false; protocol=null;
        notifyAndRegister=null; writeCharacteristic=null;
        BluetoothGatt current = gatt; gatt = null;
        if (current != null) try { current.disconnect(); current.close(); } catch (Exception ignored) {}
    }
    private void closeBle() {
        stopScan(); awaitingBond = null;
        closed=true;
        closeGattOnly();
        emit("status", "Not connected — demo mode available");
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == BT_PERMS_REQUEST) {
            boolean granted = grants.length > 0;
            for (int value: grants) if (value != PackageManager.PERMISSION_GRANTED) granted=false;
            if (granted) beginScan();
            else emit("status", "Nearby devices permission is needed for R10 access");
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_CSV_REQUEST && resultCode == RESULT_OK && data != null && pendingCsv != null) {
            Uri uri = data.getData();
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) out.write(pendingCsv.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Shot history exported", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, "CSV export failed", Toast.LENGTH_LONG).show(); }
            pendingCsv = null;
        }
    }
    @Override protected void onDestroy() {
        closeBle();
        if (bondReceiverRegistered) { unregisterReceiver(bondReceiver); bondReceiverRegistered=false; }
        if (web != null) { web.removeJavascriptInterface("R10Native"); web.destroy(); web=null; }
        super.onDestroy();
    }
}
