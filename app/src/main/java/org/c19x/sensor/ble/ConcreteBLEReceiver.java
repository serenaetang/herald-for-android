package org.c19x.sensor.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;

import org.c19x.sensor.PayloadDataSupplier;
import org.c19x.sensor.SensorDelegate;
import org.c19x.sensor.data.ConcreteSensorLogger;
import org.c19x.sensor.data.SensorLogger;
import org.c19x.sensor.datatype.BluetoothState;
import org.c19x.sensor.datatype.Callback;
import org.c19x.sensor.datatype.PayloadData;
import org.c19x.sensor.datatype.RSSI;
import org.c19x.sensor.datatype.TimeInterval;
import org.c19x.sensor.datatype.Tuple;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConcreteBLEReceiver extends BluetoothGattCallback implements BLEReceiver, BluetoothStateManagerDelegate {
    // Scan ON/OFF durations
    private final static long scanOnDurationMillis = TimeInterval.seconds(8).millis();
    private final static long scanOffDurationMillis = TimeInterval.seconds(4).millis();
    private SensorLogger logger = new ConcreteSensorLogger("Sensor", "BLE.ConcreteBLEReceiver");
    private final Context context;
    private final BluetoothStateManager bluetoothStateManager;
    private final PayloadDataSupplier payloadDataSupplier;
    private final BLEDatabase database;
    private final BLETransmitter transmitter;
    private final HandlerThread handlerThread = new HandlerThread("Sensor.BLE.ConcreteBLEReceiver");
    private final Handler handler;
    private final ExecutorService operationQueue = Executors.newSingleThreadExecutor();
    private final Queue<ScanResult> scanResults = new ConcurrentLinkedQueue<>();

    private enum NextTask {
        nothing, readPayload, writePayload, writeRSSI, writePayloadSharing
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            logger.debug("onScanResult (result={})", result);
            scanResults.add(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            logger.debug("onBatchScanResults (results=)", results.size());
            for (ScanResult scanResult : results) {
                onScanResult(0, scanResult);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            logger.fault("onScanFailed (error={})", onScanFailedErrorCodeToString(errorCode));
            super.onScanFailed(errorCode);
        }
    };
    private BluetoothLeScanner bluetoothLeScanner;
    private AtomicBoolean startScanLoop = new AtomicBoolean(false);

    /**
     * Receiver starts automatically when Bluetooth is enabled.
     */
    public ConcreteBLEReceiver(Context context, BluetoothStateManager bluetoothStateManager, PayloadDataSupplier payloadDataSupplier, BLEDatabase database, BLETransmitter transmitter) {
        this.context = context;
        this.bluetoothStateManager = bluetoothStateManager;
        this.payloadDataSupplier = payloadDataSupplier;
        this.database = database;
        this.transmitter = transmitter;
        handlerThread.start();
        this.handler = new Handler(handlerThread.getLooper());
        bluetoothStateManager.delegates.add(this);
        bluetoothStateManager(bluetoothStateManager.state());
    }

    @Override
    protected void finalize() throws Throwable {
        handlerThread.quit();
        super.finalize();
    }

    // MARK:- BLEReceiver

    @Override
    public void add(SensorDelegate delegate) {
        delegates.add(delegate);
    }

    @Override
    public void start() {
        logger.debug("start");
        // startScanLoop is started by Bluetooth state
    }

    @Override
    public void stop() {
        logger.debug("stop");
        // startScanLoop is stopped by Bluetooth state
    }

    // MARK:- BluetoothStateManagerDelegate

    @Override
    public void bluetoothStateManager(BluetoothState didUpdateState) {
        logger.debug("didUpdateState (state={})", didUpdateState);
        if (didUpdateState == BluetoothState.poweredOn) {
            startScanLoop();
        }
    }

    // MARK:- Scan loop for startScan-wait-stopScan-processScanResults-wait-repeat

    private void startScanLoop() {
        logger.debug("startScanLoop (on={},off={})", scanOnDurationMillis, scanOffDurationMillis);
        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            logger.fault("startScanLoop denied, Bluetooth adapter unavailable");
            return;
        }
        final BluetoothLeScanner bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            logger.fault("startScanLoop denied, Bluetooth LE scanner unavailable");
            return;
        }
        if (bluetoothStateManager.state() != BluetoothState.poweredOn) {
            logger.fault("startScanLoop denied, Bluetooth is not powered on");
            return;
        }
        if (!startScanLoop.compareAndSet(false, true)) {
            logger.fault("startScanLoop denied, already started");
            return;
        }
        final long timeStarting = System.currentTimeMillis();
        logger.debug("startScanLoop, starting");
        startScan(bluetoothLeScanner, new Callback<Boolean>() {
            @Override
            public void accept(Boolean value) {
                final long timeStarted = System.currentTimeMillis();
                logger.debug("startScanLoop, started (elapsed={}ms)", (timeStarted - timeStarting));
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        final long timeStopping = System.currentTimeMillis();
                        logger.debug("startScanLoop, stopping (elapsed={}ms)", (timeStopping - timeStarted));
                        stopScan(bluetoothLeScanner, new Callback<Boolean>() {
                            @Override
                            public void accept(Boolean value) {
                                final long timeStopped = System.currentTimeMillis();
                                logger.debug("startScanLoop, stopped (elapsed={}ms)", (timeStopped - timeStopping));
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        final long timeRestart = System.currentTimeMillis();
                                        logger.debug("startScanLoop, restart (elapsed={}ms)", (timeRestart - timeStopped));
                                        startScanLoop.set(false);
                                        logger.debug("startScanLoop (total={}ms)", (timeRestart - timeStarting));
                                        startScanLoop();
                                    }
                                }, scanOffDurationMillis);
                            }
                        });
                    }
                }, scanOnDurationMillis);
            }
        });
    }

    /// Get BLE scanner and start scan
    private void startScan(final BluetoothLeScanner bluetoothLeScanner, final Callback<Boolean> callback) {
        logger.debug("startScan");
        operationQueue.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    scanForPeripherals(bluetoothLeScanner);
                    logger.debug("startScan successful");
                    if (callback != null) {
                        callback.accept(true);
                    }
                } catch (Throwable e) {
                    logger.fault("startScan failed", e);
                    if (callback != null) {
                        callback.accept(false);
                    }
                }
            }
        });
    }


    /// Scan for devices advertising sensor service and all Apple devices as
    // iOS background advert does not include service UUID. There is a risk
    // that the sensor will spend time communicating with Apple devices that
    // are not running the sensor code repeatedly, but there is no reliable
    // way of filtering this as the service may be absent only because of
    // transient issues. This will be handled in taskConnect.
    private void scanForPeripherals(final BluetoothLeScanner bluetoothLeScanner) {
        logger.debug("scanForPeripherals");
        final List<ScanFilter> filter = new ArrayList<>(2);
        filter.add(new ScanFilter.Builder().setManufacturerData(
                BLESensorConfiguration.manufacturerIdForApple, new byte[0], new byte[0]).build());
        filter.add(new ScanFilter.Builder().setServiceUuid(
                new ParcelUuid(BLESensorConfiguration.serviceUUID),
                new ParcelUuid(new UUID(0xFFFFFFFFFFFFFFFFL, 0)))
                .build());
        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0)
                .build();
        bluetoothLeScanner.startScan(filter, settings, scanCallback);
    }

    /// Get BLE scanner and stop scan
    private void stopScan(final BluetoothLeScanner bluetoothLeScanner, final Callback<Boolean> callback) {
        logger.debug("stopScan");
        operationQueue.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    bluetoothLeScanner.stopScan(scanCallback);
                    logger.debug("stopScan, stopped scanner");
                } catch (Throwable e) {
                    logger.fault("stopScan warning, bluetoothLeScanner.stopScan error", e);
                }
                try {
                    final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    if (bluetoothAdapter != null) {
                        bluetoothAdapter.cancelDiscovery();
                    }
                    logger.debug("stopScan, cancelled discovery");
                } catch (Throwable e) {
                    logger.fault("stopScan warning, bluetoothAdapter.cancelDiscovery error", e);
                }
                try {
                    processScanResults();
                    logger.debug("stopScan, processed scan results");
                } catch (Throwable e) {
                    logger.fault("stopScan warning, processScanResults error", e);
                }
                logger.debug("stopScan successful");
                callback.accept(true);
            }
        });
    }

    // MARK:- Process scan results

    /// Process scan results.
    private void processScanResults() {
        final long t0 = System.currentTimeMillis();
        logger.debug("processScanResults (results={})", scanResults.size());
        // Identify devices discovered in last scan
        final Set<BLEDevice> devices = didDiscover();
        taskRemoveExpiredDevices();
        taskConnect();
        final long t1 = System.currentTimeMillis();
        logger.debug("processScanResults (results={},devices={},elapsed={}ms)", scanResults.size(), devices.size(), (t1 - t0));
    }

    // MARK:- didDiscover

    /**
     * Process scan results to ...
     * 1. Create BLEDevice from scan result for new devices
     * 2. Read RSSI
     * 3. Identify operating system
     */
    private Set<BLEDevice> didDiscover() {
        // Take current copy of concurrently modifiable scan results
        final List<ScanResult> scanResultList = new ArrayList<>(scanResults.size());
        while (scanResults.size() > 0) {
            scanResultList.add(scanResults.poll());
        }

        // Process scan results and return devices created/updated in scan results
        logger.debug("didDiscover (scanResults={})", scanResultList.size());
        final Set<BLEDevice> devices = new HashSet<>();
        for (ScanResult scanResult : scanResultList) {
            final BLEDevice device = database.device(scanResult.getDevice());
            device.registerDiscovery();
            if (devices.add(device)) {
                logger.debug("didDiscover (device={})", device);
            }
            // Read RSSI from scan result
            device.rssi(new RSSI(scanResult.getRssi()));
            // Don't ignore devices forever just because
            // sensor service or characteristic was not
            // found at some point. Check again every 5
            // minutes.
            if (device.operatingSystem() == BLEDeviceOperatingSystem.ignore &&
                    device.timeIntervalSinceLastOperatingSystemUpdate().value > TimeInterval.minutes(5).value) {
                logger.debug("didDiscover, switching ignore to unknown (device={})", device);
                device.operatingSystem(BLEDeviceOperatingSystem.unknown);
            }
            // Identify operating system from scan record where possible
            // - Sensor service found + Manufacturer is Apple -> iOS (Foreground)
            // - Sensor service found + Manufacturer not Apple -> Android
            // - Sensor service not found + Manufacturer is Apple -> iOS (Background) or Apple device not advertising sensor service, to be resolved later
            // - Sensor service not found + Manufacturer not Apple -> Ignore (shouldn't be possible as we are scanning for Apple or with service)
            final boolean hasSensorService = hasSensorService(scanResult);
            final boolean isAppleDevice = isAppleDevice(scanResult);
            if (hasSensorService && isAppleDevice) {
                // Definitely iOS device offering sensor service in foreground mode
                device.operatingSystem(BLEDeviceOperatingSystem.ios);
            } else if (hasSensorService && !isAppleDevice) {
                // Definitely Android device offering sensor service
                device.operatingSystem(BLEDeviceOperatingSystem.android);
            } else if (!hasSensorService && isAppleDevice) {
                // Possibly an iOS device offering sensor service in background mode,
                // can't be sure without additional checks after connection, so
                // only set operating system if it is unknown to offer a guess.
                if (device.operatingSystem() == BLEDeviceOperatingSystem.unknown) {
                    device.operatingSystem(BLEDeviceOperatingSystem.ios_tbc);
                }
            } else {
                // Sensor service not found + Manufacturer not Apple should be impossible (!hasSensorService && !isAppleDevice)
                // as we are scanning for devices with sensor service or Apple device.
                logger.fault("didDiscover, invalid non-Apple device without sensor service (device={})", device);
                device.operatingSystem(BLEDeviceOperatingSystem.ignore);
            }
        }
        return devices;
    }

    /// Does scan result include advert for sensor service?
    private static boolean hasSensorService(final ScanResult scanResult) {
        final ScanRecord scanRecord = scanResult.getScanRecord();
        if (scanRecord == null) {
            return false;
        }
        final List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
        if (serviceUuids == null || serviceUuids.size() == 0) {
            return false;
        }
        for (ParcelUuid serviceUuid : serviceUuids) {
            if (serviceUuid.getUuid().equals(BLESensorConfiguration.serviceUUID)) {
                return true;
            }
        }
        return false;
    }

    /// Does scan result indicate device was manufactured by Apple?
    private static boolean isAppleDevice(final ScanResult scanResult) {
        final ScanRecord scanRecord = scanResult.getScanRecord();
        if (scanRecord == null) {
            return false;
        }
        final byte[] data = scanRecord.getManufacturerSpecificData(BLESensorConfiguration.manufacturerIdForApple);
        return data != null;
    }

    // MARK:- House keeping tasks

    /// Remove devices that have not been updated for over an hour, as the UUID
    // is likely to have changed after being out of range for over 20 minutes,
    // so it will require discovery.
    private void taskRemoveExpiredDevices() {
        final List<BLEDevice> devicesToRemove = new ArrayList<>();
        for (BLEDevice device : database.devices()) {
            if (device.timeIntervalSinceLastUpdate().value > TimeInterval.hour.value) {
                devicesToRemove.add(device);
            }
        }
        for (BLEDevice device : devicesToRemove) {
            logger.debug("taskRemoveExpiredDevices (remove={})", device);
            database.delete(device.identifier);
        }
    }

    // MARK:- Connect task

    private void taskConnect() {
        final Tuple<List<BLEDevice>, List<BLEDevice>> devices = getDevices();
        final List<BLEDevice> disconnected = devices.getB("disconnected");
        final List<BLEDevice> pending = getDevicesWithPendingActions(disconnected);
        connectPendingDevices(pending, TimeInterval.seconds(30));
    }

    /// Separate devices by current connection state
    private Tuple<List<BLEDevice>, List<BLEDevice>> getDevices() {
        final List<BLEDevice> devices = database.devices();
        final List<BLEDevice> connected = new ArrayList<>(devices.size());
        final List<BLEDevice> disconnected = new ArrayList<>(devices.size());
        for (BLEDevice device : devices) {
            if (device.peripheral() == null) {
                continue;
            }
            if (device.state() == BLEDeviceState.connected) {
                connected.add(device);
            } else if (device.state() == BLEDeviceState.disconnected) {
                disconnected.add(device);
            }
        }
        logger.debug("taskConnect status summary (connected={},disconnected={}})", connected.size(), disconnected.size());
        for (BLEDevice device : connected) {
            logger.debug("taskConnect status connected (device={},upTime={})", device, device.upTime());
        }
        for (BLEDevice device : disconnected) {
            logger.debug("taskConnect status disconnected (device={},downTime={})", device, device.downTime());
        }
        return new Tuple<>("connected", connected, "disconnected", disconnected);
    }

    /// Establish pending connections for disconnected devices
    private List<BLEDevice> getDevicesWithPendingActions(List<BLEDevice> disconnected) {
        final List<BLEDevice> pending = new ArrayList<>(disconnected.size());
        for (BLEDevice device : disconnected) {
            if (nextTaskForDevice(device) != NextTask.nothing) {
                pending.add(device);
            }
        }
        Collections.sort(pending, new Comparator<BLEDevice>() {
            @Override
            public int compare(BLEDevice d0, BLEDevice d1) {
                return Long.compare(d1.timeIntervalSinceLastConnectRequestedAt().value, d0.timeIntervalSinceLastConnectRequestedAt().value);
            }
        });
        if (pending.size() > 0) {
            logger.debug("taskConnect pending summary (devices={})", pending.size());
            for (int i = 0; i < pending.size(); i++) {
                final BLEDevice device = pending.get(i);
                logger.debug("taskConnect pending, queue (priority={},device={},timeSinceLastRequest={}})", i + 1, device, device.timeIntervalSinceLastConnectRequestedAt());
            }
        }
        return pending;
    }

    /// Initiate connection to pending devices, aim to complete within
    // given time limit but avoid early termination of started tasks
    private void connectPendingDevices(List<BLEDevice> pending, TimeInterval limit) {
        if (pending.size() == 0) {
            return;
        }
        final long timeStart = System.currentTimeMillis();
        for (BLEDevice device : pending) {
            // Stop process if exceeded time limit
            final long elapsedTime = System.currentTimeMillis() - timeStart;
            if (elapsedTime > limit.millis()) {
                logger.debug("processPendingDevices, reached time limit (elapsed={}ms,limit={}ms)", elapsedTime, limit.millis());
                break;
            }
            // Connect to a device and give it the full time quota to complete pending tasks
            // as once the connection has been initiated, it is wasteful to terminate the
            // connection early to meet overall limit
            final long timeConnect = System.currentTimeMillis();
            logger.debug("processPendingDevices, connect (device={})", device);
            device.state(BLEDeviceState.connecting);
            final BluetoothGatt gatt = device.peripheral().connectGatt(context, false, this);
            while (device.state() != BLEDeviceState.disconnected && (System.currentTimeMillis() - timeConnect) < TimeInterval.seconds(10).millis()) {
                try {
                    Thread.sleep(250);
                } catch (Throwable e) {
                }
            }
            // Timeout connection if required, and always set state to disconnected
            boolean success = true;
            if (device.state() != BLEDeviceState.disconnected) {
                logger.fault("processPendingDevices, timeout (device={})", device);
                gatt.close();
                success = false;
            }
            device.state(BLEDeviceState.disconnected);
            final long timeDisconnect = System.currentTimeMillis();
            if (success) {
                logger.debug("processPendingDevices, disconnect (success=true,elapsed={}ms,device={})", (timeDisconnect - timeConnect), device);
            } else {
                logger.fault("processPendingDevices, disconnect (success=false,elapsed={}ms,device={})", (timeDisconnect - timeConnect), device);
            }
        }
    }

    // MARK:- BluetoothStateManagerDelegate

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        final BLEDevice device = database.device(gatt.getDevice());
        logger.debug("onConnectionStateChange (device={},status={},state={})", device, bleStatus(status), bleState(newState));
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            device.state(BLEDeviceState.connected);
            gatt.discoverServices();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            gatt.close();
            device.state(BLEDeviceState.disconnected);
            if (status != 0) {
                device.operatingSystem(BLEDeviceOperatingSystem.ignore);
            }
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        final BLEDevice device = database.device(gatt.getDevice());
        logger.debug("onServicesDiscovered (device={},status={})", device, bleStatus(status));

        final BluetoothGattService service = gatt.getService(BLESensorConfiguration.serviceUUID);
        if (service == null) {
            logger.fault("onServicesDiscovered, missing sensor service (device={})", device);
            // Ignore device for a while unless it is a confirmed iOS or Android device
            if (!(device.operatingSystem() == BLEDeviceOperatingSystem.ios || device.operatingSystem() == BLEDeviceOperatingSystem.android)) {
                device.operatingSystem(BLEDeviceOperatingSystem.ignore);
            }
            gatt.disconnect();
            return;
        }

        logger.debug("onServicesDiscovered, found sensor service (device={})", device);

        device.invalidateCharacteristics();
        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
            // Confirm operating system with signal characteristic
            if (characteristic.getUuid().equals(BLESensorConfiguration.androidSignalCharacteristicUUID)) {
                logger.debug("onServicesDiscovered, found Android signal characteristic (device={})", device);
                device.operatingSystem(BLEDeviceOperatingSystem.android);
                device.signalCharacteristic(characteristic);
            } else if (characteristic.getUuid().equals(BLESensorConfiguration.iosSignalCharacteristicUUID)) {
                logger.debug("onServicesDiscovered, found iOS signal characteristic (device={})", device);
                device.operatingSystem(BLEDeviceOperatingSystem.ios);
                device.signalCharacteristic(characteristic);
            } else if (characteristic.getUuid().equals(BLESensorConfiguration.payloadCharacteristicUUID)) {
                logger.debug("onServicesDiscovered, found payload characteristic (device={})", device);
                device.payloadCharacteristic(characteristic);
            }
        }
        nextTask(gatt);
    }

    private NextTask nextTaskForDevice(final BLEDevice device) {
        // No task for devices marked as .ignore
        if (device.operatingSystem() == BLEDeviceOperatingSystem.ignore) {
            return NextTask.nothing;
        }
        // Resolve or confirm operating system by reading payload which
        // triggers characteristic discovery to confirm the operating system
        if (device.operatingSystem() == BLEDeviceOperatingSystem.unknown ||
                device.operatingSystem() == BLEDeviceOperatingSystem.ios_tbc) {
            return NextTask.readPayload;
        }
        // Get payload as top priority
        if (device.payloadData() == null) {
            return NextTask.readPayload;
        }
        // Write payload, rssi and payload sharing data if this device cannot transmit
        if (!transmitter.isSupported()) {
            // Write payload data as top priority
            if (device.timeIntervalSinceLastWritePayload() == TimeInterval.never) {
                return NextTask.writePayload;
            }
            // Write payload sharing data to iOS device if there is data to be shared (up to once every 2.5 minutes)
            if (device.operatingSystem() == BLEDeviceOperatingSystem.ios &&
                    device.timeIntervalSinceLastWritePayloadSharing().value > TimeInterval.seconds(150).value &&
                    ((ConcreteBLETransmitter) transmitter).payloadSharingData(device).identifiers.size() > 0) {
                return NextTask.writePayloadSharing;
            }
            // Write RSSI as frequently as possible
            if (device.rssi() != null && device.timeIntervalSinceLastWriteRssi().value > TimeInterval.seconds(15).value) {
                return NextTask.writeRSSI;
            }
        }
        return NextTask.nothing;
    }


    private void nextTask(BluetoothGatt gatt) {
        final BLEDevice device = database.device(gatt.getDevice());
        final NextTask nextTask = nextTaskForDevice(device);
        switch (nextTask) {
            case readPayload: {
                final BluetoothGattCharacteristic payloadCharacteristic = device.payloadCharacteristic();
                if (payloadCharacteristic == null) {
                    logger.fault("nextTask failed (task=readPayload,device={},reason=missingPayloadCharacteristic)", device);
                    gatt.disconnect();
                    return;
                }
                if (!gatt.readCharacteristic(payloadCharacteristic)) {
                    logger.fault("nextTask failed (task=readPayload,device={},reason=readCharacteristicFailed)", device);
                    gatt.disconnect();
                    return;
                }
                logger.debug("nextTask (task=readPayload,device={})", device);
                return;
            }
            case writePayload: {
                final BluetoothGattCharacteristic signalCharacteristic = device.signalCharacteristic();
                if (signalCharacteristic == null) {
                    logger.fault("nextTask failed (task=writePayload,device={},reason=missingSignalCharacteristic)", device);
                    gatt.disconnect();
                    return;
                }
                final PayloadData payloadData = transmitter.payloadData();
                if (payloadData == null || payloadData.value == null || payloadData.value.length == 0) {
                    logger.fault("nextTask failed (task=writePayload,device={},reason=missingPayloadData)", device);
                    gatt.disconnect();
                    return;
                }
                final byte[] data = signalData(BLESensorConfiguration.signalCharacteristicActionWritePayload, transmitter.payloadData().value);
                signalCharacteristic.setValue(data);
                signalCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (!gatt.writeCharacteristic(signalCharacteristic)) {
                    logger.fault("nextTask failed (task=writePayload,device={},reason=writeCharacteristicFailed)", device);
                    gatt.disconnect();
                    return;
                }
                logger.debug("nextTask (task=writePayload,device={})", device);
                return;
            }
            case writePayloadSharing: {
                final BluetoothGattCharacteristic signalCharacteristic = device.signalCharacteristic();
                if (signalCharacteristic == null) {
                    logger.fault("nextTask failed (task=writePayloadSharing,device={},reason=missingSignalCharacteristic)", device);
                    gatt.disconnect();
                    return;
                }
                final ConcreteBLETransmitter.PayloadSharingData payloadSharingData = ((ConcreteBLETransmitter) transmitter).payloadSharingData(device);
                if (payloadSharingData == null) {
                    logger.fault("nextTask failed (task=writePayloadSharing,device={},reason=missingPayloadSharingData)", device);
                    gatt.disconnect();
                    return;
                }
                final byte[] data = signalData(BLESensorConfiguration.signalCharacteristicActionWritePayloadSharing, payloadSharingData.data.value);
                signalCharacteristic.setValue(data);
                signalCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (device.operatingSystem() == BLEDeviceOperatingSystem.android) {
                    gatt.beginReliableWrite();
                }
                if (!gatt.writeCharacteristic(signalCharacteristic)) {
                    logger.fault("nextTask failed (task=writePayloadSharing,device={},reason=writeCharacteristicFailed)", device);
                    gatt.disconnect();
                    return;
                }
                logger.debug("nextTask (task=writePayloadSharing,device={})", device);
                return;
            }
            case writeRSSI: {
                final BluetoothGattCharacteristic signalCharacteristic = device.signalCharacteristic();
                if (signalCharacteristic == null) {
                    logger.fault("nextTask failed (task=writeRSSI,device={},reason=missingSignalCharacteristic)", device);
                    gatt.disconnect();
                    return;
                }
                final RSSI rssi = device.rssi();
                if (rssi == null) {
                    logger.fault("nextTask failed (task=writeRSSI,device={},reason=missingRssiData)", device);
                    gatt.disconnect();
                    return;
                }
                final byte[] data = signalData(BLESensorConfiguration.signalCharacteristicActionWriteRSSI, device.rssi().value);
                signalCharacteristic.setValue(data);
                signalCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (!gatt.writeCharacteristic(signalCharacteristic)) {
                    logger.fault("nextTask failed (task=writeRSSI,device={},reason=writeCharacteristicFailed)", device);
                    gatt.disconnect();
                    return;
                }
                logger.debug("nextTask (task=writeRSSI,device={})", device);
                return;
            }
        }
        logger.debug("nextTask (task=nothing,device={})", device);
        gatt.disconnect();
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        final BLEDevice device = database.device(gatt.getDevice());
        final boolean success = (status == BluetoothGatt.GATT_SUCCESS);
        logger.debug("onCharacteristicRead (device={},status={})", device, bleStatus(status));
        if (characteristic.getUuid().equals(BLESensorConfiguration.payloadCharacteristicUUID)) {
            final PayloadData payloadData = (characteristic.getValue() != null ? new PayloadData(characteristic.getValue()) : null);
            if (success) {
                if (payloadData != null) {
                    logger.debug("onCharacteristicRead, read payload data success (device={},payload={})", device, payloadData.shortName());
                    device.payloadData(payloadData);
                } else {
                    logger.fault("onCharacteristicRead, read payload data failed, no data (device={})", device);
                }
            } else {
                logger.fault("onCharacteristicRead, read payload data failed (device={})", device);
            }
        }
        nextTask(gatt);
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        final BLEDevice device = database.device(gatt.getDevice());
        logger.debug("onCharacteristicWrite (device={},status={})", device, bleStatus(status));
        final BluetoothGattCharacteristic signalCharacteristic = device.signalCharacteristic();
        final boolean success = (status == BluetoothGatt.GATT_SUCCESS);
        final byte actionCode = (signalCharacteristic == null ? 0 : signalDataActionCode(signalCharacteristic.getValue()));
        switch (actionCode) {
            case BLESensorConfiguration.signalCharacteristicActionWritePayload:
                if (success) {
                    logger.debug("onCharacteristicWrite, write payload success (device={})", device);
                    device.registerWritePayload();
                } else {
                    logger.fault("onCharacteristicWrite, write payload failed (device={})", device);
                }
                break;
            case BLESensorConfiguration.signalCharacteristicActionWriteRSSI:
                if (success) {
                    logger.debug("onCharacteristicWrite, write RSSI success (device={})", device);
                    device.registerWriteRssi();
                } else {
                    logger.fault("onCharacteristicWrite, write RSSI failed (device={})", device);
                }
                break;
            case BLESensorConfiguration.signalCharacteristicActionWritePayloadSharing:
                if (success) {
                    logger.debug("onCharacteristicWrite, write payload sharing success (device={})", device);
                    device.registerWritePayloadSharing();
                } else {
                    logger.fault("onCharacteristicWrite, write payload sharing failed (device={})", device);
                }
                break;
            default:
                logger.fault("onCharacteristicWrite, write unknown data (device={},actionCode={},success={})", device, actionCode, success);
                break;
        }
        nextTask(gatt);
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        final BLEDevice device = database.device(gatt.getDevice());
        logger.debug("onMtuChanged (device={},status={},mtu={})", device, bleStatus(status), mtu);
    }

    // MARK:- Signal characteristic data bundles

    private static byte[] signalData(final byte actionCode, final byte[] data) {
        return signalData(actionCode, data.length, data);
    }

    private static byte[] signalData(final byte actionCode, final int shortValue) {
        return signalData(actionCode, shortValue, null);
    }

    private static byte[] signalData(final byte actionCode, final int shortValue, final byte[] data) {
        final ByteBuffer byteBuffer = ByteBuffer.allocate(3 + (data == null ? 0 : data.length));
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.put(0, actionCode);
        byteBuffer.putShort(1, Integer.valueOf(shortValue).shortValue());
        if (data != null) {
            byteBuffer.position(3);
            byteBuffer.put(data);
        }
        return byteBuffer.array();
    }

    private static byte signalDataActionCode(byte[] signalData) {
        if (signalData == null || signalData.length == 0) {
            return 0;
        }
        return signalData[0];
    }

    // MARK:- Bluetooth code transformers

    private static String bleStatus(final int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            return "GATT_SUCCESS";
        } else {
            return "GATT_FAILURE";
        }
    }

    private static String bleState(final int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                return "STATE_CONNECTED";
            case BluetoothProfile.STATE_DISCONNECTED:
                return "STATE_DISCONNECTED";
            default:
                return "UNKNOWN_STATE_" + state;
        }
    }


    private static String onCharacteristicWriteStatusToString(final int status) {
        switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
                return "GATT_SUCCESS";
            case BluetoothGatt.GATT_CONNECTION_CONGESTED:
                return "GATT_CONNECTION_CONGESTED";
            case BluetoothGatt.GATT_FAILURE:
                return "GATT_FAILURE";
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:
                return "GATT_INSUFFICIENT_AUTHENTICATION";
            case BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION:
                return "GATT_INSUFFICIENT_ENCRYPTION";
            case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH:
                return "GATT_INVALID_ATTRIBUTE_LENGTH";
            case BluetoothGatt.GATT_INVALID_OFFSET:
                return "GATT_INVALID_OFFSET";
            case BluetoothGatt.GATT_READ_NOT_PERMITTED:
                return "GATT_READ_NOT_PERMITTED";
            case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:
                return "GATT_REQUEST_NOT_SUPPORTED";
            case BluetoothGatt.GATT_SERVER:
                return "GATT_SERVER";
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
                return "GATT_WRITE_NOT_PERMITTED";
            default:
                return "UNKNOWN_STATUS_" + status;
        }
    }

    private static String onScanFailedErrorCodeToString(final int errorCode) {
        switch (errorCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return "SCAN_FAILED_ALREADY_STARTED";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return "SCAN_FAILED_INTERNAL_ERROR";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return "SCAN_FAILED_FEATURE_UNSUPPORTED";
            default:
                return "UNKNOWN_ERROR_CODE_" + errorCode;
        }
    }


}
