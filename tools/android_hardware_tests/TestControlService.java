package fr.androforge.validation.hardwaretests;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Bootstrap only for this package's private test documents and explicit URI grants. */
public final class TestControlService extends Service {
    static final String DESCRIPTOR = "fr.androforge.validation.hardwaretests.PrivateControls";
    static final int CONTROL = IBinder.FIRST_CALL_TRANSACTION;
    private static final String TARGET = "fr.androforge.validation";

    private void enforceValidationCaller() {
        final int caller = Binder.getCallingUid();
        PackageManager packages = getPackageManager();
        try {
            int target = packages.getApplicationInfo(TARGET, 0).uid;
            if (caller != target || packages.checkSignatures(caller, getApplicationInfo().uid)
                    != PackageManager.SIGNATURE_MATCH)
                throw new SecurityException("Only the same-signed validation UID may control private test files");
        } catch (PackageManager.NameNotFoundException missing) {
            throw new SecurityException("Isolated validation target not installed", missing);
        }
    }

    private final Binder controls = new Binder() {
        @Override protected boolean onTransact(int code, Parcel request, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            if (code != CONTROL) return super.onTransact(code, request, reply, flags);
            request.enforceInterface(DESCRIPTOR);
            enforceValidationCaller();
            String action = request.readString();
            String id = request.readString();
            Bundle extras = request.readBundle(TestControlService.class.getClassLoader());
            if (action == null || action.length() > 32 || id == null || id.length() != 36)
                throw new IllegalArgumentException("Bounded private controls required");
            long identity = Binder.clearCallingIdentity();
            try {
                // This owner process bootstraps its own provider; the target never bypasses
                // MANAGE_DOCUMENTS and uses only real, explicitly granted document URIs.
                Bundle result = getContentResolver().call(
                        Uri.parse("content://" + TestDocumentsProvider.AUTHORITY),
                        "hardwaretests." + action, id, extras);
                if (result == null) throw new IllegalStateException("Private provider returned no result");
                reply.writeNoException();
                reply.writeBundle(result);
                return true;
            } finally { Binder.restoreCallingIdentity(identity); }
        }
    };

    @Override public IBinder onBind(Intent intent) { return controls; }
}
