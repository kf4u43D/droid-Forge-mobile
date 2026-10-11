package fr.androforge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Android MIDI byte-stream transport. MSC storage is discovered, never claimed or written here. */
final class DroidMidiTransport implements Closeable {
    interface Listener {
        void onDevicesChanged(Snapshot snapshot);
        default void onSysex(Endpoint endpoint, byte[] frame) {}
    }
    interface Completion {
        /** Bytes accepted by Android; the protocol defines no master acknowledgement. */
        void onSent();
        void onError(String message);
        default void onProgress(int sent, int total) {}
    }

    static final class Endpoint {
        final int deviceId, inputPort, outputPort;
        final String name, portName;
        final boolean compatibleX7, inputOpen;
        final String label;
        final boolean x7;
        private final MidiDeviceInfo info;

        private Endpoint(MidiDeviceInfo info, int inputPort, int outputPort, String name, String portName, boolean inputOpen) {
            this.info = info; deviceId = info.getId(); this.inputPort = inputPort; this.outputPort = outputPort;
            this.name = name; this.portName = portName; compatibleX7 = x7Name(name); this.inputOpen = inputOpen;
            label = displayName(); x7 = compatibleX7;
        }

        String displayName() { return name + (portName.isEmpty() ? " · port " + (inputPort + 1) : " · " + portName); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Endpoint)) return false;
            Endpoint endpoint = (Endpoint) other;
            return deviceId == endpoint.deviceId && inputPort == endpoint.inputPort;
        }
        @Override public int hashCode() { return deviceId * 31 + inputPort; }
    }

    static final class UsbDeviceSummary {
        final String name;
        final int vendorId, productId;
        final boolean massStorage, midi, compatibleX7;
        private UsbDeviceSummary(UsbDevice device) {
            name = usbName(device); vendorId = device.getVendorId(); productId = device.getProductId();
            boolean storage = false, music = false;
            for (int index = 0; index < device.getInterfaceCount(); index++) {
                android.hardware.usb.UsbInterface usbInterface = device.getInterface(index);
                storage |= usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE;
                music |= usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO && usbInterface.getInterfaceSubclass() == 3;
            }
            massStorage = storage; midi = music; compatibleX7 = x7Name(name);
        }
    }

    static final class Snapshot {
        final boolean midiSupported;
        final List<Endpoint> endpoints;
        final List<UsbDeviceSummary> usbDevices;
        final String error;
        final boolean x7StorageConnected;
        final String summary;
        Snapshot(boolean supported, List<Endpoint> endpoints, List<UsbDeviceSummary> usbDevices, String error) {
            midiSupported = supported;
            this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
            this.usbDevices = Collections.unmodifiableList(new ArrayList<>(usbDevices));
            this.error = error;
            x7StorageConnected = hasX7MassStorage();
            if (!error.isEmpty()) summary = error;
            else if (x7StorageConnected && endpoints.isEmpty()) summary = "DROID X7 in USB storage mode: no MIDI port. Select X7 MIDI mode to activate a patch.";
            else if (!supported) summary = "The Android MIDI service is unavailable on this device.";
            else if (endpoints.isEmpty()) summary = "No USB MIDI port detected.";
            else summary = endpoints.size() == 1 ? "1 USB MIDI port available." : endpoints.size() + " USB MIDI ports available.";
        }
        boolean hasX7MassStorage() {
            for (UsbDeviceSummary device : usbDevices) if (device.compatibleX7 && device.massStorage) return true;
            return false;
        }
    }

    private final Context application;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService discovery = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "DROID USB discovery"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean(), refreshPending = new AtomicBoolean();
    private final AtomicBoolean refreshAgain = new AtomicBoolean();
    private final AtomicBoolean transferring = new AtomicBoolean();
    private final Map<Integer, MidiDeviceStatus> statuses = new HashMap<>();
    private final MidiTransferSession session;
    private final MidiManager midi;
    private final UsbManager usb;
    private boolean midiRegistered, usbRegistered;
    private volatile Endpoint sendingEndpoint;
    private volatile Snapshot latest = new Snapshot(false, Collections.emptyList(), Collections.emptyList(), "Searching for USB devices…");

    DroidMidiTransport(Context context, Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("Missing MIDI context or listener");
        application = context.getApplicationContext(); this.listener = listener;
        midi = (MidiManager) application.getSystemService(Context.MIDI_SERVICE);
        usb = (UsbManager) application.getSystemService(Context.USB_SERVICE);
        session = new MidiTransferSession(task -> main.post(task));
    }

    private final MidiManager.DeviceCallback devices = new MidiManager.DeviceCallback() {
        @Override public void onDeviceAdded(MidiDeviceInfo info) { refresh(); }
        @Override public void onDeviceRemoved(MidiDeviceInfo info) {
            synchronized (statuses) { statuses.remove(info.getId()); }
            Endpoint endpoint = sendingEndpoint;
            if (endpoint != null && endpoint.deviceId == info.getId()) session.disconnected();
            refresh();
        }
        @Override public void onDeviceStatusChanged(MidiDeviceStatus status) {
            synchronized (statuses) { if (statuses.size() < 256 || statuses.containsKey(status.getDeviceInfo().getId())) statuses.put(status.getDeviceInfo().getId(), status); }
            refresh();
        }
    };

    private final BroadcastReceiver usbChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    /** May be called from any thread. No USB permission dialog or MIDI write occurs. */
    void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        main.post(() -> {
            if (closed.get()) return;
            try {
                if (midi != null && application.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI)) {
                    if (Build.VERSION.SDK_INT >= 33)
                        midi.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, task -> main.post(task), devices);
                    else midi.registerDeviceCallback(devices, main);
                    midiRegistered = true;
                }
                IntentFilter filter = new IntentFilter();
                filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
                if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(usbChanges, filter, Context.RECEIVER_NOT_EXPORTED);
                else application.registerReceiver(usbChanges, filter);
                usbRegistered = true;
                refresh();
            } catch (RuntimeException failure) {
                publish(new Snapshot(midi != null, Collections.emptyList(), Collections.emptyList(), MidiTransferSession.errorMessage(failure)));
            }
        });
    }

    void refresh() {
        if (closed.get()) return;
        refreshAgain.set(true);
        if (!refreshPending.compareAndSet(false, true)) return;
        try {
            discovery.execute(() -> {
                do {
                    refreshAgain.set(false);
                    Snapshot snapshot;
                    try { snapshot = discover(); }
                    catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
                        snapshot = new Snapshot(midi != null, Collections.emptyList(), Collections.emptyList(), MidiTransferSession.errorMessage(failure));
                    }
                    publish(snapshot);
                } while (refreshAgain.get() && !closed.get());
                refreshPending.set(false);
                if (refreshAgain.get() && !closed.get()) refresh();
            });
        } catch (RejectedExecutionException finished) { refreshPending.set(false); }
    }

    private Snapshot discover() {
        List<Endpoint> endpoints = new ArrayList<>();
        List<UsbDeviceSummary> connectedUsb = new ArrayList<>();
        boolean supported = midi != null && application.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI);
        if (supported) {
            Iterable<MidiDeviceInfo> infos = Build.VERSION.SDK_INT >= 33
                    ? midi.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM) : Arrays.asList(midi.getDevices());
            for (MidiDeviceInfo info : infos) {
                if (info.getType() != MidiDeviceInfo.TYPE_USB) continue;
                int receivePort = -1;
                for (MidiDeviceInfo.PortInfo port : info.getPorts())
                    if (port.getType() == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) { receivePort = port.getPortNumber(); break; }
                String name = midiName(info);
                MidiDeviceStatus status;
                synchronized (statuses) { status = statuses.get(info.getId()); }
                for (MidiDeviceInfo.PortInfo port : info.getPorts()) {
                    if (port.getType() != MidiDeviceInfo.PortInfo.TYPE_INPUT) continue;
                    if (endpoints.size() >= 256) break;
                    endpoints.add(new Endpoint(info, port.getPortNumber(), receivePort, name, safeName(port.getName()),
                            status != null && status.isInputPortOpen(port.getPortNumber())));
                }
            }
        }
        if (usb != null) for (UsbDevice device : usb.getDeviceList().values()) {
            if (connectedUsb.size() >= 256) break;
            connectedUsb.add(new UsbDeviceSummary(device));
        }
        endpoints.sort(Comparator.comparing((Endpoint endpoint) -> !endpoint.compatibleX7).thenComparing(endpoint -> endpoint.name).thenComparingInt(endpoint -> endpoint.inputPort));
        connectedUsb.sort(Comparator.comparing(device -> device.name));
        return new Snapshot(supported, endpoints, connectedUsb, "");
    }

    Snapshot snapshot() { return latest; }
    private void publish(Snapshot snapshot) {
        latest = snapshot;
        main.post(() -> { if (!closed.get()) listener.onDevicesChanged(snapshot); });
    }

    /** Opens only the selected USB MIDI port, sends the official upload frame and closes it. */
    void send(Endpoint endpoint, byte[] frame, int timeoutMs, Completion completion) {
        if (completion == null) throw new IllegalArgumentException("Missing upload callback");
        if (closed.get()) return;
        if (midi == null || endpoint == null || endpoint.info.getType() != MidiDeviceInfo.TYPE_USB || !endpoint.compatibleX7) {
            main.post(() -> { if (!closed.get()) completion.onError("Select a DROID X7 USB MIDI port."); }); return;
        }
        if (!transferring.compareAndSet(false, true)) {
            main.post(() -> { if (!closed.get()) completion.onError("A MIDI transfer is already in progress."); }); return;
        }
        sendingEndpoint = endpoint;
        try { session.send(opened -> midi.openDevice(endpoint.info, device -> {
            if (device == null) { opened.onFailure("Android could not open DROID X7 MIDI."); return; }
            if (!opened.isWanted()) { closeDevice(device); opened.onFailure("MIDI connection cancelled."); return; }
            MidiInputPort input = null;
            MidiOutputPort output = null;
            try {
                input = device.openInputPort(endpoint.inputPort);
                if (input == null) { closeDevice(device); opened.onFailure("The X7 MIDI port is busy or unavailable."); return; }
                FrameReceiver receiver = null;
                if (endpoint.outputPort >= 0) {
                    try {
                        output = device.openOutputPort(endpoint.outputPort);
                        if (output != null) { receiver = new FrameReceiver(endpoint, opened); output.connect(receiver); }
                    } catch (RuntimeException optionalReceiveUnavailable) { closePort(output); output = null; }
                }
                opened.onOpened(new AndroidChannel(device, input, output, receiver));
            } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
                closeDevice(device); closePort(input); closePort(output);
                opened.onFailure(MidiTransferSession.errorMessage(failure));
            }
        }, null), frame, timeoutMs, new MidiTransferSession.Completion() {
            @Override public void onSent() { sendingEndpoint = null; transferring.set(false); completion.onSent(); refresh(); }
            @Override public void onFailure(String message) { sendingEndpoint = null; transferring.set(false); completion.onError(message); refresh(); }
            @Override public void onProgress(int sent, int total) { completion.onProgress(sent, total); }
        }); } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            sendingEndpoint = null; transferring.set(false);
            main.post(() -> { if (!closed.get()) completion.onError(MidiTransferSession.errorMessage(failure)); });
        }
    }

    void cancel() { session.cancel(); }
    boolean isBusy() { return !closed.get() && (transferring.get() || session.isBusy()); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        transferring.set(false);
        session.close(); discovery.shutdownNow(); sendingEndpoint = null;
        main.post(() -> {
            if (midiRegistered) {
                try { midi.unregisterDeviceCallback(devices); } catch (RuntimeException ignored) { /* Service may already be gone. */ }
                midiRegistered = false;
            }
            if (usbRegistered) {
                try { application.unregisterReceiver(usbChanges); } catch (IllegalArgumentException ignored) { /* No receiver remains registered. */ }
                usbRegistered = false;
            }
            synchronized (statuses) { statuses.clear(); }
        });
    }

    private final class FrameReceiver extends MidiReceiver {
        private final MidiTransferSession.Opened operation;
        private final AtomicInteger queued = new AtomicInteger();
        private final MidiSysExFramer framer;
        FrameReceiver(Endpoint endpoint, MidiTransferSession.Opened operation) {
            super(1024); this.operation = operation;
            framer = new MidiSysExFramer(MidiSysExFramer.MAX_ACTIVATION_BYTES, new MidiSysExFramer.Listener() {
                @Override public void onFrame(byte[] frame) {
                    if (!operation.isWanted() || closed.get()) return;
                    if (queued.incrementAndGet() > 16) { queued.decrementAndGet(); return; }
                    main.post(() -> {
                        try { if (!closed.get() && operation.isWanted()) listener.onSysex(endpoint, frame); }
                        finally { queued.decrementAndGet(); }
                    });
                }
                @Override public void onDiscarded() { /* A malformed/truncated frame is never reported as a response. */ }
            });
        }
        @Override public void onSend(byte[] data, int offset, int count, long timestamp) {
            if (operation.isWanted() && !closed.get()) framer.accept(data, offset, count);
        }
        @Override public void onFlush() { framer.reset(); }
        void clear() { framer.reset(); }
    }

    private static final class AndroidChannel implements MidiTransferSession.Channel {
        private final MidiDevice device;
        private final MidiInputPort input;
        private final MidiOutputPort output;
        private final FrameReceiver receiver;
        private final AtomicBoolean closed = new AtomicBoolean();
        AndroidChannel(MidiDevice device, MidiInputPort input, MidiOutputPort output, FrameReceiver receiver) {
            this.device = device; this.input = input; this.output = output; this.receiver = receiver;
        }
        @Override public void send(byte[] data, int offset, int count) throws IOException {
            if (closed.get()) throw new IOException("The MIDI port has been closed.");
            input.send(data, offset, count);
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (receiver != null) receiver.clear();
            if (output != null && receiver != null) output.disconnect(receiver);
            // Device first: releasing its server connection can unblock a stalled port write.
            closeDevice(device); closePort(input); closePort(output);
        }
    }

    private static void closeDevice(MidiDevice device) { closePort(device); }
    private static void closePort(Closeable port) {
        if (port != null) try { port.close(); } catch (IOException | RuntimeException ignored) { /* Best effort release after unplug/service loss. */ }
    }
    private static String midiName(MidiDeviceInfo info) {
        Bundle properties = info.getProperties();
        String name = safeName(properties.getString(MidiDeviceInfo.PROPERTY_NAME));
        if (name.isEmpty()) name = safeName(properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT));
        if (name.isEmpty()) name = "USB MIDI device " + info.getId();
        return name;
    }
    private static String usbName(UsbDevice device) {
        String name = safeName(device.getProductName());
        return name.isEmpty() ? "USB " + device.getVendorId() + ":" + device.getProductId() : name;
    }
    private static String safeName(String name) {
        if (name == null) return "";
        return name.length() > 256 ? name.substring(0, 256) : name;
    }
    private static boolean x7Name(String name) {
        String normalized = name.toUpperCase(Locale.ROOT);
        return normalized.contains("DROID") && normalized.contains("X7");
    }
}
