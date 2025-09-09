/*
 * Copyright (C) 2006 The Android Open Source Project
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
package com.android.server;
import com.android.internal.app.ResolverActivity;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.PKG_INSTALL_COMPLETE;
import static android.content.pm.PackageManager.PKG_INSTALL_INCOMPLETE;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.*;
import android.view.Display;
import android.view.WindowManager;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
class PackageManagerService extends IPackageManager.Stub {
    private static final String TAG = "PackageManager";
    private static final boolean DEBUG_SETTINGS = false;
    private static final boolean DEBUG_PREFERRED = false;
    private static final boolean MULTIPLE_APPLICATION_UIDS = true;
    private static final int RADIO_UID = Process.PHONE_UID;
    private static final int FIRST_APPLICATION_UID =
        Process.FIRST_APPLICATION_UID;
    private static final int MAX_APPLICATION_UIDS = 1000;
    private static final boolean SHOW_INFO = false;
    private static final boolean GET_CERTIFICATES = true;
    private static final int REMOVE_EVENTS =
        FileObserver.CLOSE_WRITE | FileObserver.DELETE | FileObserver.MOVED_FROM;
    private static final int ADD_EVENTS =
        FileObserver.CLOSE_WRITE /*| FileObserver.CREATE*/ | FileObserver.MOVED_TO;
    private static final int OBSERVER_EVENTS = REMOVE_EVENTS | ADD_EVENTS;
    static final int SCAN_MONITOR = 1<<0;
    static final int SCAN_NO_DEX = 1<<1;
    static final int SCAN_FORCE_DEX = 1<<2;
    static final int SCAN_UPDATE_SIGNATURE = 1<<3;
    static final int SCAN_FORWARD_LOCKED = 1<<4;
    static final int SCAN_NEW_INSTALL = 1<<5;
    
    static final int LOG_BOOT_PROGRESS_PMS_START = 3060;
    static final int LOG_BOOT_PROGRESS_PMS_SYSTEM_SCAN_START = 3070;
    static final int LOG_BOOT_PROGRESS_PMS_DATA_SCAN_START = 3080;
    static final int LOG_BOOT_PROGRESS_PMS_SCAN_END = 3090;
    static final int LOG_BOOT_PROGRESS_PMS_READY = 3100;
    final HandlerThread mHandlerThread = new HandlerThread("PackageManager",
            Process.THREAD_PRIORITY_BACKGROUND);
    final Handler mHandler;
    final int mSdkVersion = Build.VERSION.SDK_INT;
    final String mSdkCodename = "REL".equals(Build.VERSION.CODENAME)
            ? null : Build.VERSION.CODENAME;
    
    final Context mContext;
    final boolean mFactoryTest;
    final boolean mNoDexOpt;
    final DisplayMetrics mMetrics;
    final int mDefParseFlags;
    final String[] mSeparateProcesses;
    // This is where all application persistent data goes.
    final File mAppDataDir;
    // This is the object monitoring the framework dir.
    final FileObserver mFrameworkInstallObserver;
    // This is the object monitoring the system app dir.
    final FileObserver mSystemInstallObserver;
    // This is the object monitoring mAppInstallDir.
    final FileObserver mAppInstallObserver;
    // This is the object monitoring mDrmAppPrivateInstallDir.
    final FileObserver mDrmAppInstallObserver;
    // Used for priviledge escalation.  MUST NOT BE CALLED WITH mPackages
    // LOCK HELD.  Can be called with mInstallLock held.
    final Installer mInstaller;
    
    final File mFrameworkDir;
    final File mSystemAppDir;
    final File mAppInstallDir;
    // Directory containing the private parts (e.g. code and non-resource assets) of forward-locked
    // apps.
    final File mDrmAppPrivateInstallDir;
    
    // ----------------------------------------------------------------
    
    // Lock for state used when installing and doing other long running
    // operations.  Methods that must be called with this lock held have
    // the prefix "LI".
    final Object mInstallLock = new Object();
    
    // These are the directories in the 3rd party applications installed dir
    // that we have currently loaded packages from.  Keys are the application's
    // installed zip file (absolute codePath), and values are Package.
    final HashMap<String, PackageParser.Package> mAppDirs =
            new HashMap<String, PackageParser.Package>();
    // Information for the parser to write more useful error messages.
    File mScanningPath;
    int mLastScanError;
    final int[] mOutPermissions = new int[3];
    // ----------------------------------------------------------------
    
    // Keys are String (package name), values are Package.  This also serves
    // as the lock for the global state.  Methods that must be called with
    // this lock held have the prefix "LP".
    final HashMap<String, PackageParser.Package> mPackages =
            new HashMap<String, PackageParser.Package>();
    final Settings mSettings;
    boolean mRestoredSettings;
    boolean mReportedUidError;
    // Group-ids that are given to all packages as read from etc/permissions/*.xml.
    int[] mGlobalGids;
    // These are the built-in uid -> permission mappings that were read from the
    // etc/permissions.xml file.
    final SparseArray<HashSet<String>> mSystemPermissions =
            new SparseArray<HashSet<String>>();
    
    // These are the built-in shared libraries that were read from the
    // etc/permissions.xml file.
    final HashMap<String, String> mSharedLibraries = new HashMap<String, String>();
    
    // All available activities, for your resolving pleasure.
    final ActivityIntentResolver mActivities =
            new ActivityIntentResolver();
    // All available receivers, for your resolving pleasure.
    final ActivityIntentResolver mReceivers =
            new ActivityIntentResolver();
    // All available services, for your resolving pleasure.
    final ServiceIntentResolver mServices = new ServiceIntentResolver();
    // Keys are String (provider class name), values are Provider.
    final HashMap<ComponentName, PackageParser.Provider> mProvidersByComponent =
            new HashMap<ComponentName, PackageParser.Provider>();
    // Mapping from provider base names (first directory in content URI codePath)
    // to the provider information.
    final HashMap<String, PackageParser.Provider> mProviders =
            new HashMap<String, PackageParser.Provider>();
    // Mapping from instrumentation class names to info about them.
    final HashMap<ComponentName, PackageParser.Instrumentation> mInstrumentation =
            new HashMap<ComponentName, PackageParser.Instrumentation>();
    // Mapping from permission names to info about them.
    final HashMap<String, PackageParser.PermissionGroup> mPermissionGroups =
            new HashMap<String, PackageParser.PermissionGroup>();
    // Broadcast actions that are only available to the system.
    final HashSet<String> mProtectedBroadcasts = new HashSet<String>();
    
    boolean mSystemReady;
    boolean mSafeMode;
    boolean mHasSystemUidErrors;
    ApplicationInfo mAndroidApplication;
    final ActivityInfo mResolveActivity = new ActivityInfo();
    final ResolveInfo mResolveInfo = new ResolveInfo();
    ComponentName mResolveComponentName;
    PackageParser.Package mPlatformPackage;
    public static final IPackageManager main(Context context, boolean factoryTest) {
        PackageManagerService m = new PackageManagerService(context, factoryTest);
        ServiceManager.addService("package", m);
        return m;
    }
    static String[] splitString(String str, char sep) {
        int count = 1;
        int i = 0;
        while ((i=str.indexOf(sep, i)) >= 0) {
            count++;
            i++;
        }
        
        String[] res = new String[count];
        i=0;
        count = 0;
        int lastI=0;
        while ((i=str.indexOf(sep, i)) >= 0) {
            res[count] = str.substring(lastI, i);
            count++;
            i++;
            lastI = i;
        }
        res[count] = str.substring(lastI, str.length());
        return res;
    }
    
    public PackageManagerService(Context context, boolean factoryTest) {
        EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_START,
                SystemClock.uptimeMillis());
        
        if (mSdkVersion <= 0) {
            Log.w(TAG, "**** ro.build.version.sdk not set!");
        }
        
        mContext = context;
        mFactoryTest = factoryTest;
        mNoDexOpt = "eng".equals(SystemProperties.get("ro.build.type"));
        mMetrics = new DisplayMetrics();
        mSettings = new Settings();
        mSettings.addSharedUserLP("android.uid.system",
                Process.SYSTEM_UID, ApplicationInfo.FLAG_SYSTEM);
        mSettings.addSharedUserLP("android.uid.phone",
                MULTIPLE_APPLICATION_UIDS
                        ? RADIO_UID : FIRST_APPLICATION_UID,
                ApplicationInfo.FLAG_SYSTEM);
        String separateProcesses = SystemProperties.get("debug.separate_processes");
        if (separateProcesses != null && separateProcesses.length() > 0) {
            if ("*".equals(separateProcesses)) {
                mDefParseFlags = PackageParser.PARSE_IGNORE_PROCESSES;
                mSeparateProcesses = null;
                Log.w(TAG, "Running with debug.separate_processes: * (ALL)");
            } else {
                mDefParseFlags = 0;
                mSeparateProcesses = separateProcesses.split(",");
                Log.w(TAG, "Running with debug.separate_processes: "
                        + separateProcesses);
            }
        } else {
            mDefParseFlags = 0;
            mSeparateProcesses = null;
        }
        
        Installer installer = new Installer();
        // Little hacky thing to check if installd is here, to determine
        // whether we are running on the simulator and thus need to take
        // care of building the /data file structure ourself.
        // (apparently the sim now has a working installer)
        if (installer.ping() && Process.supportsProcesses()) {
            mInstaller = installer;
        } else {
            mInstaller = null;
        }
        WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        d.getMetrics(mMetrics);
        synchronized (mInstallLock) {
        synchronized (mPackages) {
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
            
            File dataDir = Environment.getDataDirectory();
            mAppDataDir = new File(dataDir, "data");
            mDrmAppPrivateInstallDir = new File(dataDir, "app-private");
            if (mInstaller == null) {
                // Make sure these dirs exist, when we are running in
                // the simulator.
                // Make a wide-open directory for random misc stuff.
                File miscDir = new File(dataDir, "misc");
                miscDir.mkdirs();
                mAppDataDir.mkdirs();
                mDrmAppPrivateInstallDir.mkdirs();
            }
            readPermissions();
            mRestoredSettings = mSettings.readLP();
            long startTime = SystemClock.uptimeMillis();
            
            EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_SYSTEM_SCAN_START,
                    startTime);
            
            int scanMode = SCAN_MONITOR;
            if (mNoDexOpt) {
                Log.w(TAG, "Running ENG build: no pre-dexopt!");
                scanMode |= SCAN_NO_DEX; 
            }
            
            final HashSet<String> libFiles = new HashSet<String>();
            
            mFrameworkDir = new File(Environment.getRootDirectory(), "framework");
            
            if (mInstaller != null) {
                /**
                 * Out of paranoia, ensure that everything in the boot class
                 * path has been dexed.
                 */
                String bootClassPath = System.getProperty("java.boot.class.path");
                if (bootClassPath != null) {
                    String[] paths = splitString(bootClassPath, ':');
                    for (int i=0; i<paths.length; i++) {
                        try {
                            if (dalvik.system.DexFile.isDexOptNeeded(paths[i])) {
                                libFiles.add(paths[i]);
                                mInstaller.dexopt(paths[i], Process.SYSTEM_UID, true);
                            }
                        } catch (FileNotFoundException e) {
                            Log.w(TAG, "Boot class path not found: " + paths[i]);
                        } catch (IOException e) {
                            Log.w(TAG, "Exception reading boot class path: " + paths[i], e);
                        }
                    }
                } else {
                    Log.w(TAG, "No BOOTCLASSPATH found!");
                }
                
                /**
                 * Also ensure all external libraries have had dexopt run on them.
                 */
                if (mSharedLibraries.size() > 0) {
                    Iterator<String> libs = mSharedLibraries.values().iterator();
                    while (libs.hasNext()) {
                        String lib = libs.next();
                        try {
                            if (dalvik.system.DexFile.isDexOptNeeded(lib)) {
                                libFiles.add(lib);
                                mInstaller.dexopt(lib, Process.SYSTEM_UID, true);
                            }
                        } catch (FileNotFoundException e) {
                            Log.w(TAG, "Library not found: " + lib);
                        } catch (IOException e) {
                            Log.w(TAG, "Exception reading library: " + lib, e);
                        }
                    }
                }
                
                // Gross hack for now: we know this file doesn't contain any
                // code, so don't dexopt it to avoid the resulting log spew.
                libFiles.add(mFrameworkDir.getPath() + "/framework-res.apk");
                
                /**
                 * And there are a number of commands implemented in Java, which
                 * we currently need to do the dexopt on so that they can be
                 * run from a non-root shell.
                 */
                String[] frameworkFiles = mFrameworkDir.list();
                if (frameworkFiles != null && mInstaller != null) {
                    for (int i=0; i<frameworkFiles.length; i++) {
                        File libPath = new File(mFrameworkDir, frameworkFiles[i]);
                        String path = libPath.getPath();
                        // Skip the file if we alrady did it.
                        if (libFiles.contains(path)) {
                            continue;
                        }
                        // Skip the file if it is not a type we want to dexopt.
                        if (!path.endsWith(".apk") && !path.endsWith(".jar")) {
                            continue;
                        }
                        try {
                            if (dalvik.system.DexFile.isDexOptNeeded(path)) {
                                mInstaller.dexopt(path, Process.SYSTEM_UID, true);
                            }
                        } catch (FileNotFoundException e) {
                            Log.w(TAG, "Jar not found: " + path);
                        } catch (IOException e) {
                            Log.w(TAG, "Exception reading jar: " + path, e);
                        }
                    }
                }
            }
            
            mFrameworkInstallObserver = new AppDirObserver(
                mFrameworkDir.getPath(), OBSERVER_EVENTS, true);
            mFrameworkInstallObserver.startWatching();
            scanDirLI(mFrameworkDir, PackageParser.PARSE_IS_SYSTEM,
                    scanMode | SCAN_NO_DEX);
            mSystemAppDir = new File(Environment.getRootDirectory(), "app");
            mSystemInstallObserver = new AppDirObserver(
                mSystemAppDir.getPath(), OBSERVER_EVENTS, true);
            mSystemInstallObserver.startWatching();
            scanDirLI(mSystemAppDir, PackageParser.PARSE_IS_SYSTEM, scanMode);
            mAppInstallDir = new File(dataDir, "app");
            if (mInstaller == null) {
                // Make sure these dirs exist, when we are running in
                // the simulator.
                mAppInstallDir.mkdirs(); // scanDirLI() assumes this dir exists
            }
            //look for any incomplete package installations
            ArrayList<String> deletePkgsList = mSettings.getListOfIncompleteInstallPackages();
            //clean up list
            for(int i = 0; i < deletePkgsList.size(); i++) {
                //clean up here
                cleanupInstallFailedPackage(deletePkgsList.get(i));
            }
            //delete tmp files
            deleteTempPackageFiles();
            
            EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_DATA_SCAN_START,
                    SystemClock.uptimeMillis());
            mAppInstallObserver = new AppDirObserver(
                mAppInstallDir.getPath(), OBSERVER_EVENTS, false);
            mAppInstallObserver.startWatching();
            scanDirLI(mAppInstallDir, 0, scanMode);
            mDrmAppInstallObserver = new AppDirObserver(
                mDrmAppPrivateInstallDir.getPath(), OBSERVER_EVENTS, false);
            mDrmAppInstallObserver.startWatching();
            scanDirLI(mDrmAppPrivateInstallDir, 0, scanMode | SCAN_FORWARD_LOCKED);
            EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_SCAN_END,
                    SystemClock.uptimeMillis());
            Log.i(TAG, "Time to scan packages: "
                    + ((SystemClock.uptimeMillis()-startTime)/1000f)
                    + " seconds");
            updatePermissionsLP();
            mSettings.writeLP();
            EventLog.writeEvent(LOG_BOOT_PROGRESS_PMS_READY,
                    SystemClock.uptimeMillis());
            
            // Now after opening every single application zip, make sure they
            // are all flushed.  Not really needed, but keeps things nice and
            // tidy.
            Runtime.getRuntime().gc();
        } // synchronized (mPackages)
        } // synchronized (mInstallLock)
    }
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException) && !(e instanceof IllegalArgumentException)) {
                Log.e(TAG, "Package Manager Crash", e);
            }
            throw e;
        }
    }
    void cleanupInstallFailedPackage(String packageName) {
        if (mInstaller != null) {
            int retCode = mInstaller.remove(packageName);
            if (retCode < 0) {
                Log.w(TAG, "Couldn't remove app data directory for package: "
                           + packageName + ", retcode=" + retCode);
            }
        } else {
            //for emulator
            PackageParser.Package pkg = mPackages.get(packageName);
            File dataDir = new File(pkg.applicationInfo.dataDir);
            dataDir.delete();
        }
        mSettings.removePackageLP(packageName);
    }
}