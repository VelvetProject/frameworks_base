package com.android.server;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ISubstratumService;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.util.List;

public final class SubstratumService extends SystemService {

    private static final String TAG = "SubstratumService";
    private static final boolean DEBUG = true;
    private static IOverlayManager mOM;
    private static IPackageManager mPM;
    private static boolean isWaiting = false;
    private final Object mLock = new Object();
    private SubstratumWorkerThread mWorker;
    private SubstratumWorkerHandler mHandler;
    private Context mContext;
    public SubstratumService(@NonNull final Context context) {
        super(context);
        mContext = context;
        /*
        mWorker = new SubstratumWorkerThread("SubstratumServiceWorker");
        mWorker.start();
        */
        publishBinderService("substratum", mService);
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onSwitchUser(final int newUserId) {
    }

    private final IBinder mService = new ISubstratumService.Stub() {

        @Override
        public void switchOverlay(List<String> packages, boolean enable, boolean restartUi) {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    for (String p : packages) {
                        if (DEBUG) {
                            Log.d(TAG, (enable ? "Enabling" : "Disabling" + " overlay " + p));
                        }
                        switchOverlayState(p, enable);
                    }
                    if (restartUi) {
                        // restartUi
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void installOverlay(List<String> paths) {
            final long ident = Binder.clearCallingIdentity();
            int packageVerifierEnable = Settings.Global.getInt(
                    mContext.getContentResolver(),
                    Settings.Global.PACKAGE_VERIFIER_ENABLE, 1);
            try {
                synchronized (mLock) {
                    PackageInstallObserver observer = new PackageInstallObserver();
                    for (String path : paths) {
                        File apkFile = new File(path);
                        if (apkFile.exists()) {
                            Settings.Global.putInt(mContext.getContentResolver(),
                                    Settings.Global.PACKAGE_VERIFIER_ENABLE, 0);
                            isWaiting = true;
                            getPM().installPackageAsUser(
                                    path,
                                    observer,
                                    PackageManager.INSTALL_REPLACE_EXISTING,
                                    null,
                                    UserHandle.USER_SYSTEM);
                            while (isWaiting) {
                                Thread.sleep(500);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "", e);
            } finally {
                Settings.Global.putInt(mContext.getContentResolver(),
                        Settings.Global.PACKAGE_VERIFIER_ENABLE, packageVerifierEnable);
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void uninstallOverlay(List<String> packages, boolean restartUi) {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    PackageDeleteObserver observer = new PackageDeleteObserver();
                    for (String path : packages) {
                        if (isOverlayEnabled(path)) {
                            //log("Remover - disabling overlay for \'" + p + "\'...");
                            switchOverlayState(path, false);
                        }

                        //log("Remover - uninstalling \'" + p + "\'...");
                        isWaiting = true;
                        getPM().deletePackageAsUser(
                                path,
                                observer,
                                0,
                                UserHandle.USER_SYSTEM);
                        while (isWaiting) {
                            Thread.sleep(500);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "", e);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onShellCommand(@NonNull final FileDescriptor in,
                @NonNull final FileDescriptor out, @NonNull final FileDescriptor err,
                @NonNull final String[] args, @NonNull final ResultReceiver resultReceiver) {
            (new SubstratumShellCommand(this)).exec(this, in, out, err, args, resultReceiver);
        }
    };

    private static IOverlayManager getOM() {
        if (mOM == null) {
            mOM = IOverlayManager.Stub.asInterface(
                    ServiceManager.getService(Context.OVERLAY_SERVICE));
        }
        return mOM;
    }

    private static IPackageManager getPM() {
        if (mPM == null) {
            mPM = IPackageManager.Stub.asInterface(
                    ServiceManager.getService("package"));
        }
        return mPM;
    }

    private void switchOverlayState(String packageName, boolean enable) {
        try {
        getOM().setEnabled(packageName, enable, UserHandle.USER_SYSTEM, false);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    private boolean isOverlayEnabled(String packageName) {
        boolean enabled = false;

        try {
            OverlayInfo info = getOM().getOverlayInfo(packageName, UserHandle.USER_SYSTEM);
            if (info != null) {
                enabled = info.isEnabled();
            } else {
                //log("OverlayInfo is null.");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }

        return enabled;
    }

    private class PackageInstallObserver extends IPackageInstallObserver2.Stub {
        @Override
        public void onUserActionRequired(Intent intent) throws RemoteException {
            //log("Installer - user action required callback");
            isWaiting = false;
        }

        @Override
        public void onPackageInstalled(String packageName, int returnCode,
                                       String msg, Bundle extras) {
            //log("Installer - successfully installed \'" + packageName + "\'!");
            isWaiting = false;
        }
    }

    private class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        @Override
        public void packageDeleted(String packageName, int returnCode) {
            //log("Remover - successfully removed \'" + packageName + "\'");
            isWaiting = false;
        }
    }

    private class SubstratumWorkerThread extends Thread {

        public SubstratumWorkerThread(String name) {
            super(name);
        }

        public void run() {
            Looper.prepare();
            mHandler = new SubstratumWorkerHandler();
            Looper.loop();
        }
    }

    private class SubstratumWorkerHandler extends Handler {
        private static final int MESSAGE_SET = 0;

        @Override
        public void handleMessage(Message msg) {
            try {
                if (msg.what == MESSAGE_SET) {
                    Log.i(TAG, "woop woop: " + msg.arg1);
                }
            } catch (Exception e) {
                Log.e(TAG, "", e);
            }
        }
    }
}
