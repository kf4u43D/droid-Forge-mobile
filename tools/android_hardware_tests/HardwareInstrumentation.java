package fr.androforge.validation.hardwaretests;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** JNI + read-only discovery + SAF on our own provider; never starts an Activity. */
public final class HardwareInstrumentation extends Instrumentation {
    private static final String TARGET = "fr.androforge.validation";
    private final List<String> trees = new ArrayList<>();
    private Bundle arguments;
    private int checks;
    private Context target;
    private File privateTestRoot;
    private Method nativeLoad, nativeSource, nativeState, nativePrepare, nativeFrame;
    private String originalNativeSource;
    private String keptFixtureId;
    private volatile IBinder privateControls;
    private boolean controlServiceBound;
    private final CountDownLatch controlConnected = new CountDownLatch(1);
    private final ServiceConnection controlConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            privateControls = service; controlConnected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { privateControls = null; }
        @Override public void onNullBinding(ComponentName name) { controlConnected.countDown(); }
        @Override public void onBindingDied(ComponentName name) {
            privateControls = null; controlConnected.countDown();
        }
    };

    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }

    private void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }

    private static Object invoke(Method method, Object instance, Object... arguments) throws Throwable {
        try { return method.invoke(instance, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static Object field(Object object, String name) throws Exception { return object.getClass().getField(name).get(object); }
    private interface Action { void run() throws Throwable; }

    private Throwable rejected(Action action, String reason) throws Throwable {
        try { action.run(); }
        catch (IOException | SecurityException | IllegalArgumentException expected) { check(true, reason); return expected; }
        throw new AssertionError(reason);
    }

    private void connectPrivateControls() throws Exception {
        Intent intent = new Intent().setComponent(new ComponentName(
                "fr.androforge.validation.hardwaretests", TestControlService.class.getName()));
        controlServiceBound = target.bindService(intent, controlConnection, Context.BIND_AUTO_CREATE);
        if (!controlServiceBound || !controlConnected.await(10, TimeUnit.SECONDS) || privateControls == null)
            throw new IOException("Cannot connect the private test-owner control service");
    }

    private Bundle control(String id, String action, Bundle extras) {
        IBinder service = privateControls;
        if (service == null) throw new IllegalStateException("Private test-owner service is disconnected");
        Parcel request = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(TestControlService.DESCRIPTOR);
            request.writeString(action); request.writeString(id); request.writeBundle(extras);
            if (!service.transact(TestControlService.CONTROL, request, reply, 0))
                throw new IllegalStateException("Private control transaction was rejected");
            reply.readException();
            Bundle result = reply.readBundle(HardwareInstrumentation.class.getClassLoader());
            if (result == null) throw new IllegalStateException("Private provider returned no test result");
            return result;
        } catch (RemoteException failure) { throw new IllegalStateException("Private test service failed", failure); }
        finally { request.recycle(); reply.recycle(); }
    }

    private String newTree() {
        String id = UUID.randomUUID().toString();
        control(id, "prepare", null); trees.add(id); return id;
    }

    private void setupUiFixture(JSONObject report) throws Throwable {
        String id = newTree();
        byte[] seed = ("# Validation UI only - private synthetic patch\r\n"
                + "[copy]\r\ninput = I1\r\noutput = O1\r\n").getBytes(StandardCharsets.UTF_8);
        Bundle extras = new Bundle(); extras.putByteArray("bytes", seed);
        control(id, "seed", extras);
        Bundle fixture = control(id, "fixture", null);
        check(id.equals(fixture.getString("id")), "Provider kept the wrong fixture UUID");
        check(fixture.getString("title").equals("Validation DROID " + id.substring(0, 8)), "Fixture root title is not explicit");
        keptFixtureId = id;
        report.put("fixture_uuid", id).put("fixture_title", fixture.getString("title"))
                .put("fixture_tree", fixture.getString("tree")).put("fixture_seed_bytes", seed.length)
                .put("fixture_seed_sha256", sha256(seed)).put("fixture_kept", true)
                .put("fixture_files", "droid.ini; optional droid-original.ini");
    }

    private static Uri tree(String id) { return android.provider.DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, id); }

    private byte[] nativeTests(JSONObject report) throws Throwable {
        Class<?> bridge = Class.forName("fr.androforge.NativeBridge", true, target.getClassLoader());
        nativeLoad = bridge.getMethod("load", String.class);
        nativeSource = bridge.getMethod("source"); nativeState = bridge.getMethod("state");
        nativePrepare = bridge.getMethod("prepareDeployment"); nativeFrame = bridge.getMethod("activationMessage", String.class);
        originalNativeSource = (String) invoke(nativeSource, null);
        String original = "\ufeff# Test privé 🥁 é\r\n# LABELS: master=18\r\n# I1: [CLK] annotation\r\n\r\n"
                + "[copy]\r\n input = I1 * 0.5 + -2V # commentaire\r\n output = O1\r\n"
                + "# input = invalid disabled\r\n[display]\r\nheader = \"a # b\"\r\n";
        invoke(nativeLoad, null, original);
        String before = (String) invoke(nativeState, null);
        String deployment = (String) invoke(nativePrepare, null);
        check(deployment.equals("[copy]\ninput=I1*0.5-2V\noutput=O1\n[display]\nheader=\"a # b\"\n"), "Native deployment is not the official compact format");
        byte[] bytes = deployment.getBytes(StandardCharsets.US_ASCII);
        byte[] expected = new byte[bytes.length + 6];
        expected[0] = (byte)0xf0; expected[2] = expected[3] = 0x66; expected[4] = 'P'; expected[expected.length - 1] = (byte)0xf7;
        System.arraycopy(bytes, 0, expected, 5, bytes.length);
        check(Arrays.equals(expected, (byte[]) invoke(nativeFrame, null, deployment)), "Actual Android JNI returned an incorrect activation frame");
        check(original.equals(invoke(nativeSource, null)), "Preparing deployment altered original UTF-8 source");
        check(before.equals(invoke(nativeState, null)), "Preparing activation altered native history");
        rejected(() -> invoke(nativeFrame, null, "\ud800"), "Unpaired UTF-16 surrogate accepted by JNI");
        rejected(() -> invoke(nativeFrame, null, "[copy]\ninput=\u0000\n"), "NUL accepted by native activation codec");
        rejected(() -> invoke(nativeFrame, null, repeat('a', 64)), "Activation line exceeded 63 bytes");
        String max = repeatLine(repeat('a', 63) + "\n", 1000);
        check(((byte[]) invoke(nativeFrame, null, max)).length == 64006, "Exact activation boundary rejected on Android");
        rejected(() -> invoke(nativeFrame, null, max + "\n"), "Oversized activation accepted on Android");
        check(before.equals(invoke(nativeState, null)), "Rejected native activation changed document/history");
        invoke(nativeLoad, null, "[copy]\ninput=\n");
        rejected(() -> invoke(nativePrepare, null), "Incomplete patch was deployed on Android");
        report.put("jni", "PASS"); report.put("deployment_bytes", bytes.length); report.put("activation_frame_bytes", expected.length);
        return bytes;
    }

    private static String repeat(char value, int count) { char[] text = new char[count]; Arrays.fill(text, value); return new String(text); }
    private static String repeatLine(String line, int count) { StringBuilder text = new StringBuilder(line.length() * count); for (int i=0;i<count;i++) text.append(line); return text.toString(); }

    private void discoveryTests(JSONObject report) throws Exception {
        UsbManager usb = (UsbManager) target.getSystemService(Context.USB_SERVICE);
        MidiManager midi = (MidiManager) target.getSystemService(Context.MIDI_SERVICE);
        check(usb != null, "Android USB service unavailable");
        int usbCount = 0, x7Msc = 0, usbMidiPorts = 0;
        for (UsbDevice device : usb.getDeviceList().values()) {
            usbCount++;
            boolean storage = false;
            for (int index=0; index<device.getInterfaceCount(); index++)
                storage |= device.getInterface(index).getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE;
            if (device.getVendorId() == 0x0483 && device.getProductId() == 0xa416 && storage) x7Msc++;
        }
        if (midi != null) {
            Iterable<MidiDeviceInfo> devices = Build.VERSION.SDK_INT >= 33
                    ? midi.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM) : Arrays.asList(midi.getDevices());
            for (MidiDeviceInfo info : devices)
                if (info.getType() == MidiDeviceInfo.TYPE_USB)
                    for (MidiDeviceInfo.PortInfo port : info.getPorts())
                        if (port.getType() == MidiDeviceInfo.PortInfo.TYPE_INPUT) usbMidiPorts++;
        }
        if (arguments == null || !"false".equals(arguments.getString("expectX7Msc")))
            check(x7Msc > 0, "Expected X7 MSC VID0483/PIDa416 is not attached");
        report.put("usb_devices", usbCount); report.put("x7_msc_devices", x7Msc);
        report.put("usb_midi_input_ports", usbMidiPorts); report.put("discovery", "read-only PASS");
    }

    private void storageTests(byte[] deployed, JSONObject report) throws Throwable {
        String ownId = UUID.randomUUID().toString();
        privateTestRoot = target.getDir("hardwaretests-" + ownId, Context.MODE_PRIVATE).getCanonicalFile();
        Context ownStorage = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return privateTestRoot; }
        };
        Class<?> storageClass = Class.forName("fr.androforge.DroidSdStorage", true, target.getClassLoader());
        Object storage = storageClass.getConstructor(Context.class).newInstance(ownStorage);
        Method read = storageClass.getMethod("read", Uri.class), save = storageClass.getMethod("save", Uri.class, byte[].class);
        Method listBackups = storageClass.getMethod("listBackups");
        Method canEject = storageClass.getMethod("canEject", Uri.class), eject = storageClass.getMethod("ejectRoot", Uri.class);
        Class<?> backupClass = Class.forName("fr.androforge.DroidSdStorage$BackupRecord", true, target.getClassLoader());
        Method readBackup = storageClass.getMethod("readBackup", backupClass);
        check(Array.getLength(invoke(listBackups, storage)) == 0, "Private UUID backup directory was not initially empty");

        String id = newTree(); Uri tree = tree(id);
        byte[] original = "\ufeff# Original privé 🥁\r\n[copy]\r\ninput=I2\r\noutput=O2\r\n".getBytes(StandardCharsets.UTF_8);
        Bundle seed = new Bundle(); seed.putByteArray("bytes", original); control(id, "seed", seed);
        check(target.checkUriPermission(tree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == PackageManager.PERMISSION_DENIED, "Provider tree was accessible without an explicit grant");
        rejected(() -> invoke(read, storage, tree), "Ungrantable private provider tree was read");
        control(id, "grantRead", null);
        check(Arrays.equals(original, (byte[])invoke(read, storage, tree)), "SAF read altered BOM/CRLF/Unicode original bytes");
        rejected(() -> invoke(save, storage, tree, deployed), "Read-only tree allowed overwrite");
        check(control(id, "stats", null).getInt("writes") == 0, "Read-only refusal opened a write descriptor");
        check(Arrays.equals(original, (byte[])invoke(read, storage, tree)), "Read-only overwrite refusal altered the original");

        control(id, "grantReadWrite", null);
        Object result = invoke(save, storage, tree, deployed);
        check("droid.ini".equals(field(result, "name")), "Storage changed the official filename");
        check(((Integer)field(result, "bytes")) == deployed.length, "Incorrect reported write length");
        check((Boolean)field(result, "changed") && (Boolean)field(result, "backupVerified"), "Existing original was not backed up before replacement");
        check(sha256(deployed).equals(field(result, "sha256")), "Storage's SHA-256 differs from actual prepared bytes");
        File backup = (File)field(result, "backupFile");
        check(backup != null && backup.getCanonicalFile().getParentFile().equals(new File(privateTestRoot, "droid-sd-backups")), "Backup escaped the test-owned private UUID directory");
        check(Arrays.equals(original, readOwned(backup)), "Private synced backup is not byte-exact");
        check(Arrays.equals(deployed, (byte[])invoke(read, storage, tree)), "Regular-FD write/sync/readback failed through actual Android SAF");
        check(control(id, "stats", null).getInt("writes") == 1, "Expected exactly one SAF write descriptor");
        Object backups = invoke(listBackups, storage);
        int backupCount = Array.getLength(backups);
        check(backupCount > 0, "Verified original backup was not discoverable through the public API");
        boolean found = false;
        for (int index=0; index<backupCount; index++) {
            Object record = Array.get(backups, index);
            if (((File)field(record, "file")).equals(backup)) {
                check(Arrays.equals(original, (byte[])invoke(readBackup, storage, record)), "Public backup read differs from verified original");
                found = true;
            }
        }
        check(found, "Successful save did not retain its completed backup");

        Object noop = invoke(save, storage, tree, deployed);
        check(!(Boolean)field(noop, "changed") && field(noop, "backupFile") == null, "Byte-identical save unnecessarily overwrote the patch");
        check(control(id, "stats", null).getInt("writes") == 1, "No-op save opened a write descriptor");
        check(Array.getLength(invoke(listBackups, storage)) == backupCount, "No-op save created a redundant private backup");
        check(!(Boolean)invoke(canEject, storage, tree), "A test tree was incorrectly considered a whole ejectable volume");
        rejected(() -> invoke(eject, storage, tree), "Unsupported provider ejection reported success");

        control(id, "revoke", null);
        check(target.checkUriPermission(tree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == PackageManager.PERMISSION_DENIED, "Explicit tree revocation did not remove access");
        rejected(() -> invoke(read, storage, tree), "Revoked tree was still readable");
        rejected(() -> invoke(save, storage, tree, deployed), "Revoked tree was still writable");
        check(control(id, "stats", null).getInt("writes") == 1, "Revocation refusal opened another write descriptor");
        control(id, "grantReadWrite", null);
        Bundle fault = new Bundle(); fault.putBoolean("offline", true); control(id, "fault", fault);
        rejected(() -> invoke(read, storage, tree), "Disconnected provider was treated as readable");
        rejected(() -> invoke(save, storage, tree, deployed), "Disconnected provider was treated as a successful save");
        control(id, "fault", new Bundle());
        check(Arrays.equals(deployed, (byte[])invoke(read, storage, tree)), "Provider disconnection/refusal altered last completed patch");

        byte[] next = "[copy]\ninput=I3\noutput=O3\n".getBytes(StandardCharsets.US_ASCII);
        fault = new Bundle(); fault.putBoolean("disconnectAfterWrite", true); control(id, "fault", fault);
        Throwable interrupted = rejected(() -> invoke(save, storage, tree, next), "Lost readback was reported as a successful save");
        check((Boolean)field(interrupted, "mayHaveChanged"), "Interrupted write did not report possible disk modification");
        File interruptedBackup = (File)field(interrupted, "backupFile");
        check(interruptedBackup != null && Arrays.equals(deployed, readOwned(interruptedBackup)), "Interrupted save lost its verified original backup");
        control(id, "fault", new Bundle());
        check(Arrays.equals(next, (byte[])invoke(read, storage, tree)), "Simulated readback loss did not leave inspectable actual disk bytes");

        String freshId = newTree(); Uri fresh = tree(freshId); control(freshId, "grantReadWrite", null);
        rejected(() -> invoke(read, storage, fresh), "Missing droid.ini was not reported");
        Object created = invoke(save, storage, fresh, deployed);
        check((Boolean)field(created, "changed") && !(Boolean)field(created, "backupVerified") && field(created, "backupFile") == null,
                "Creating a new patch reported an existing original backup");
        check(control(freshId, "stats", null).getInt("creates") == 1 && control(freshId, "stats", null).getInt("writes") == 1,
                "New patch was not created once with the exact official filename");
        check(Arrays.equals(deployed, (byte[])invoke(read, storage, fresh)), "New droid.ini failed actual SAF round trip");

        String wrongId = newTree(); Uri wrong = tree(wrongId); control(wrongId, "grantReadWrite", null);
        fault = new Bundle(); fault.putBoolean("wrongName", true); control(wrongId, "fault", fault);
        rejected(() -> invoke(save, storage, wrong, deployed), "Provider silently changed droid.ini's filename");
        Bundle wrongStats = control(wrongId, "stats", null);
        check(wrongStats.getInt("writes") == 0 && wrongStats.getLong("wrongNameBytes") == 0 && wrongStats.getLong("patchBytes") == -1,
                "Payload was written after provider changed the requested filename");
        report.put("saf", "PASS on test-owned DocumentsProvider");
        report.put("saf_checks", "grants, read-only refusal, create, write/sync/readback, byte-exact backup, no-op, revoke, disconnect, filename");
        report.put("hardware_written", false); report.put("normal_application_accessed", false);
    }

    private byte[] readOwned(File file) throws IOException {
        File canonical = file.getCanonicalFile();
        if (privateTestRoot == null || !canonical.getParentFile().equals(new File(privateTestRoot, "droid-sd-backups")))
            throw new IOException("Refusing to inspect a file outside the test UUID directory");
        try (FileInputStream input = new FileInputStream(canonical)) {
            byte[] result = new byte[(int)canonical.length()]; int total = 0;
            while (total<result.length) { int count=input.read(result,total,result.length-total); if (count<0) throw new IOException("Short private backup"); total+=count; }
            return result;
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private void cleanup() throws Throwable {
        Throwable first = null;
        if (nativeLoad != null && originalNativeSource != null) {
            try { invoke(nativeLoad, null, originalNativeSource); } catch (Throwable failure) { first = failure; }
        }
        for (String id : trees) {
            if (id.equals(keptFixtureId)) continue;
            try { check(control(id, "cleanup", null).getBoolean("cleaned"), "Private provider cleanup did not complete"); }
            catch (Throwable failure) { if (first == null) first = failure; }
        }
        if (privateTestRoot != null) {
            File directory = new File(privateTestRoot, "droid-sd-backups");
            File[] ownBackups = directory.listFiles();
            if (ownBackups != null) for (File file : ownBackups) {
                File canonical = file.getCanonicalFile();
                if (!canonical.getParentFile().equals(directory) || !file.getName().startsWith("droid-sd-")
                        || !(file.getName().endsWith(".ini") || file.getName().endsWith(".ini.new") || file.getName().endsWith(".ini.bak")))
                    throw new IOException("Refusing to delete an unexpected file in our UUID test directory");
                if (!file.delete()) throw new IOException("Cannot remove own backup test file");
            }
            if (directory.exists() && !directory.delete()) throw new IOException("Cannot remove own empty backup directory");
            if (!privateTestRoot.delete()) throw new IOException("Cannot remove own empty UUID directory");
        }
        if (first != null) throw first;
    }

    @Override public void onStart() {
        Bundle result = new Bundle(); JSONObject report = new JSONObject(); Throwable failure = null;
        try {
            target = getTargetContext();
            check(TARGET.equals(target.getPackageName()), "Refusing any target other than the isolated validation package");
            report.put("android_sdk", Build.VERSION.SDK_INT);
            String cleanupId = arguments == null ? null : arguments.getString("cleanupUiFixture");
            boolean setup = arguments != null && Boolean.parseBoolean(arguments.getString("setupUiFixture", "false"));
            if (setup && cleanupId != null) throw new IllegalArgumentException("Choose fixture setup or exact UUID cleanup");
            if (setup || cleanupId != null) {
                connectPrivateControls();
                if (setup) setupUiFixture(report);
                else {
                    check(control(cleanupId, "cleanupUiFixture", null).getBoolean("cleaned"), "Exact fixture cleanup failed");
                    report.put("fixture_uuid", cleanupId).put("fixture_cleaned", true);
                }
                report.put("hardware_written", false).put("normal_application_accessed", false);
            } else {
                byte[] deployment = nativeTests(report);
                discoveryTests(report);
                connectPrivateControls();
                storageTests(deployment, report);
            }
        } catch (Throwable rejected) { failure = rejected; }
        try { cleanup(); report.put("test_files_cleaned", keptFixtureId == null); }
        catch (Throwable cleanupFailure) { if (failure == null) failure = cleanupFailure; }
        finally {
            if (controlServiceBound) {
                try { target.unbindService(controlConnection); }
                catch (Throwable unbindFailure) { if (failure == null) failure = unbindFailure; }
                controlServiceBound = false; privateControls = null;
            }
        }
        try {
            report.put("checks", checks); report.put("passed", failure == null);
            report.put("ui_started", false); report.put("lock_access_attempted", false);
            if (failure != null) report.put("failure", failure.toString());
            result.putString("report", report.toString()); result.putString("passed", Boolean.toString(failure == null));
        } catch (Exception ignored) { result.putString("failure", "Cannot serialize private instrumentation report"); }
        finish(failure == null ? 0 : 1, result);
    }
}
