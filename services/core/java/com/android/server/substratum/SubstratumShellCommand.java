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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.substratum.ISubstratumService;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

final class SubstratumShellCommand extends ShellCommand {
    private final ISubstratumService mInterface;

    SubstratumShellCommand(@NonNull final ISubstratumService iss) {
        mInterface = iss;
    }

    @Override
    public int onCommand(@Nullable final String cmd) {
        if (cmd == null) {
            final PrintWriter out = getOutPrintWriter();
            out.println("lmfao");
            return 0;
        }
        final PrintWriter err = getErrPrintWriter();
        try {
            switch (cmd) {
                case "enable":
                    return runEnableDisable(true);
                case "disable":
                    return runEnableDisable(false);
                case "install":
                    return runInstall();
                case "uninstall":
                    return runUninstall();
                default:
                return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
        } catch (RemoteException e) {
            err.println("Remote exception: " + e);
        }
        return -1;
    }

    @Override
    public void onHelp() {
        final PrintWriter out = getOutPrintWriter();
        out.println("substratum command");
    }

    private int runEnableDisable(final boolean enable) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int argc = 0;
        String packageName = getNextArgRequired();
        List<String> packages = new ArrayList<>();
        if (packageName == null) {
            System.err.println("Error: No packages specified");
            return 0;
        }
        while (packageName != null) {
            argc++;
            packages.add(packageName);
            packageName = getNextArg();
        }
        if (argc >= 1) {
            mInterface.switchOverlay(packages, enable, false /* restartUi */);
            return 1;
        } else {
            System.err.println("Error: A fatal exception has occurred.");
            return 0;
        }
    }

    private int runInstall() throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int argc = 0;
        String packageName = getNextArgRequired();
        List<String> packages = new ArrayList<>();
        if (packageName == null) {
            System.err.println("Error: No packages specified");
            return 0;
        }
        while (packageName != null) {
            argc++;
            packages.add(packageName);
            packageName = getNextArg();
        }
        if (argc >= 1) {
            mInterface.installOverlay(packages);
            return 1;
        } else {
            System.err.println("Error: A fatal exception has occurred.");
            return 0;
        }
    }

    private int runUninstall() throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int argc = 0;
        String packageName = getNextArgRequired();
        List<String> packages = new ArrayList<>();
        if (packageName == null) {
            System.err.println("Error: No packages specified");
            return 0;
        }
        while (packageName != null) {
            argc++;
            packages.add(packageName);
            packageName = getNextArg();
        }
        if (argc >= 1) {
            mInterface.uninstallOverlay(packages, false);
            return 1;
        } else {
            System.err.println("Error: A fatal exception has occurred.");
            return 0;
        }
    }
}
