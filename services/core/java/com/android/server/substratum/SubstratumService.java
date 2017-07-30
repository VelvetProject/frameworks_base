/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.substratum;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.substratum.ISubstratumService;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.util.List;

public final class SubstratumService extends SystemService {

    private static final String TAG = "SubstratumService";
    private static final boolean DEBUG = true;
    private static IOverlayManager mOM;
    private static IPackageManager mPM;
    private static boolean isWaiting = false;
    private final Object mLock = new Object();
    private Context mContext;
    public SubstratumService(@NonNull final Context context) {
        super(context);
        mContext = context;
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
        public void installOverlay(List<String> paths) {
            final long ident = Binder.clearCallingIdentity();
            final int packageVerifierEnable = Settings.Global.getInt(
                    mContext.getContentResolver(),
                    Settings.Global.PACKAGE_VERIFIER_ENABLE, 1);
            try {
                synchronized (mLock) {
                    PackageInstallObserver observer = new PackageInstallObserver();
                    for (String path : paths) {
                        File apkFile = new File(path);
                        if (apkFile.exists()) {
                            log("Installer - installing package from path \'" + path + "\'");
                            isWaiting = true;
                            Settings.Global.putInt(mContext.getContentResolver(),
                                    Settings.Global.PACKAGE_VERIFIER_ENABLE, 0);
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
                    for (String p : packages) {
                        if (isOverlayEnabled(p)) {
                            log("Remover - disabling overlay for \'" + p + "\'...");
                            switchOverlayState(p, false);
                        }

                        log("Remover - uninstalling \'" + p + "\'...");
                        isWaiting = true;
                        getPM().deletePackageAsUser(
                                p,
                                observer,
                                0,
                                UserHandle.USER_SYSTEM);
                        while (isWaiting) {
                            Thread.sleep(500);
                        }
                    }
                    if (restartUi) {
                        restartUi();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "", e);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void switchOverlay(List<String> packages, boolean enable, boolean restartUi) {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    for (String p : packages) {
                        log(enable ? "Enabling" : "Disabling" + " overlay " + p);
                        switchOverlayState(p, enable);
                    }
                    if (restartUi) {
                        restartUi();
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void changePriority(List<String> packages, boolean restartUi) {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    log("PriorityJob - processing priority changes...");
                    for (int i = 0; i < packages.size() - 1; i++) {
                        String parentName = packages.get(i);
                        String packageName = packages.get(i + 1);

                        getOM().setPriority(packageName, parentName,
                                UserHandle.USER_SYSTEM);
                    }
                    if (restartUi) {
                        restartUi();
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void restartSystemUI() {
            final long ident = Binder.clearCallingIdentity();
            try {
                log("Restarting SystemUI...");
                restartUi();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void copy(String source, String destination) {
            // copy
        }

        @Override
        public void move(String source, String destination) {
            // move
        }

        @Override
        public void mkdir(String destination) {
            // mkdir
        }

        @Override
        public void deleteDirectory(String directory, boolean withParent) {
            // delete directory
        }

        @Override
        public void applyProfile(List<String> enable, List<String> disable, String name,
                boolean restartUi) {
            // apply profile
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
                Log.e(TAG, "OverlayInfo is null.");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return enabled;
    }

    private void restartUi() {
        try {
            ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            Class ActivityManagerNative = Class.forName("android.app.ActivityManagerNative");
            Method getDefault = ActivityManagerNative.getDeclaredMethod("getDefault", null);
            Object amn = getDefault.invoke(null, null);
            Method killApplicationProcess = amn.getClass().getDeclaredMethod
                    ("killApplicationProcess", String.class, int.class);

            mContext.stopService(new Intent().setComponent(new ComponentName(
                    "com.android.systemui", "com.android.systemui.SystemUIService")));
            am.killBackgroundProcesses("com.android.systemui");

            for (ActivityManager.RunningAppProcessInfo app : am.getRunningAppProcesses()) {
                if ("com.android.systemui".equals(app.processName)) {
                    killApplicationProcess.invoke(amn, app.processName, app.uid);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "", e);
        }
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }

    private class PackageInstallObserver extends IPackageInstallObserver2.Stub {
        @Override
        public void onUserActionRequired(Intent intent) throws RemoteException {
            log("Installer - user action required callback");
            isWaiting = false;
        }

        @Override
        public void onPackageInstalled(String packageName, int returnCode,
                                       String msg, Bundle extras) {
            log("Installer - successfully installed \'" + packageName + "\'!");
            isWaiting = false;
        }
    }

    private class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        @Override
        public void packageDeleted(String packageName, int returnCode) {
            log("Remover - successfully removed \'" + packageName + "\'");
            isWaiting = false;
        }
    }
}
