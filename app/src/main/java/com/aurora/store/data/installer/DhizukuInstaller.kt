/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.EXTRA_SESSION_ID
import android.content.pm.PackageInstaller.PACKAGE_SOURCE_STORE
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.PendingIntentCompat
import com.aurora.extensions.TAG
import com.aurora.extensions.isOAndAbove
import com.aurora.extensions.isSAndAbove
import com.aurora.extensions.isTAndAbove
import com.aurora.extensions.runOnUiThread
import com.aurora.store.AuroraApp
import com.aurora.store.R
import com.aurora.store.data.event.InstallerEvent
import com.aurora.store.data.installer.AppInstaller.Companion.ACTION_INSTALL_STATUS
import com.aurora.store.data.installer.AppInstaller.Companion.EXTRA_DHIZUKU_INSTALL
import com.aurora.store.data.installer.AppInstaller.Companion.EXTRA_DISPLAY_NAME
import com.aurora.store.data.installer.AppInstaller.Companion.EXTRA_MAIN_PACKAGE_NAME
import com.aurora.store.data.installer.AppInstaller.Companion.EXTRA_PACKAGE_NAME
import com.aurora.store.data.installer.AppInstaller.Companion.EXTRA_VERSION_CODE
import com.aurora.store.data.installer.base.InstallerBase
import com.aurora.store.data.model.Installer
import com.aurora.store.data.model.InstallerInfo
import com.aurora.store.data.model.SessionInfo
import com.aurora.store.data.receiver.InstallerStatusReceiver
import com.aurora.store.data.room.download.Download
import com.aurora.store.util.PackageUtil.isSharedLibraryInstalled
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Installs through the real [PackageInstaller], with its binder wrapped by Dhizuku so the
 * calls execute as the device owner. Sessions, split writes and the status callback behave
 * exactly like the session installer; only the identity behind the binder differs.
 *
 * The recorded installer is therefore Dhizuku itself, and unlike Root or Shizuku, the
 * install source cannot be attributed to Play on this path. Every Dhizuku client's installs
 * share that attribution, so installs completed here are recorded locally (like Play is for
 * Root and Shizuku) and [com.aurora.store.util.CertUtil] checks the record, not the name.
 * The record is written by the installer status receiver on the success broadcast, which
 * the system delivers even when this process did not survive the install.
 *
 * This mirrors the pre-rewrite Shizuku installer, which drove the same framework classes
 * through Shizuku's binder wrapper; Refine and its stub classes are replaced here with
 * plain reflection through HiddenApiBypass.
 */
@Singleton
class DhizukuInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) : InstallerBase(context) {

    companion object {
        const val DHIZUKU_PACKAGE_NAME = "com.rosan.dhizuku"

        // Oldest Dhizuku release this backend is written against, as suggested
        // in the upstream feature request: the Dhizuku app's own versionCode
        // for its 2.9 release.
        private const val MIN_DHIZUKU_VERSION_CODE = 13L

        // PackageManager.INSTALL_REPLACE_EXISTING, a hidden field on SessionParams
        private const val INSTALL_REPLACE_EXISTING = 0x00000002

        val installerInfo: InstallerInfo
            get() = InstallerInfo(
                id = 7,
                installer = Installer.DHIZUKU,
                installerPackageNames = listOf(DHIZUKU_PACKAGE_NAME),
                title = R.string.pref_install_mode_dhizuku,
                subtitle = R.string.dhizuku_installer_subtitle,
                description = R.string.dhizuku_installer_desc
            )

        // No success is cached here: the Dhizuku API reuses a live connection
        // itself, and re-checking the owner state on every call keeps a
        // disabled or removed Dhizuku from being reported as available on
        // the strength of an earlier success.
        private fun ensureInit(context: Context): Boolean =
            runCatching { Dhizuku.init(context) }.getOrDefault(false)

        fun isAvailable(context: Context): Boolean = isOAndAbove && ensureInit(context)

        /**
         * True when the Dhizuku app predates [MIN_DHIZUKU_VERSION_CODE]. The version
         * is read from the owner app's own package info: the version codes the
         * Dhizuku API reports describe its server API, not the app release.
         */
        fun isOutdated(context: Context): Boolean = runCatching {
            if (!ensureInit(context)) return@runCatching false
            val packageInfo = context.packageManager
                .getPackageInfo(Dhizuku.getOwnerPackageName(), 0)

            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }
            versionCode in 1L until MIN_DHIZUKU_VERSION_CODE
        }.getOrDefault(false)

        fun hasPermission(context: Context): Boolean = runCatching {
            ensureInit(context) && Dhizuku.isPermissionGranted()
        }.getOrDefault(false)

        suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    if (continuation.isActive) {
                        continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            })
        }

        fun ownerPackageName(): String = runCatching {
            Dhizuku.getOwnerPackageName()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: DHIZUKU_PACKAGE_NAME
    }

    /** The wrapped installer service proxy alongside the [PackageInstaller] built on it. */
    private class DhizukuServices(
        val packageInstaller: PackageInstaller,
        val installerService: IInterface
    )

    /**
     * Lifecycle of one download's install. A task may only be committed once it
     * is [Phase.READY] (fully staged); [Phase.STOPPED] is terminal and is also
     * the state a completed task is left in once unregistered.
     */
    private enum class Phase {
        STAGING,
        READY,
        INSTALLING,
        STOPPED
    }

    /**
     * One in-flight install: its staged sessions in commit order (shared
     * libraries first, main package last) and the session currently committed
     * to the system, if any. [operationLock] serializes this task's commits
     * against its cancellation, so a cancel can never slip between the
     * validity check and the commit; the file writes during staging do not
     * hold it.
     */
    private class InstallTask(val packageName: String) {
        val operationLock = Any()
        var phase = Phase.STAGING
        val sessions = mutableListOf<SessionInfo>()
        var activeSessionId: Int? = null
    }

    // In-flight tasks by main package name. The map entry is the claim: it is
    // registered before anything is staged and lives independently of how
    // many sessions the task has written so far, so a concurrent request for
    // the same package always finds it. queueLock guards the map itself;
    // per-task state is guarded by the task's own lock.
    private val queueLock = Any()
    private val tasks = linkedMapOf<String, InstallTask>()

    @Volatile
    private var services: DhizukuServices? = null

    @Volatile
    private var callbackRegistered = false

    private val callback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {}

        override fun onBadgingChanged(sessionId: Int) {}

        override fun onActiveChanged(sessionId: Int, active: Boolean) {}

        override fun onProgressChanged(sessionId: Int, progress: Float) {
            val task = findTask { task ->
                task.activeSessionId == sessionId ||
                    task.sessions.any { it.sessionId == sessionId }
            }

            if (task != null && progress > 0.0) {
                AuroraApp.events.send(
                    InstallerEvent.Installing(
                        packageName = task.packageName,
                        progress = progress
                    )
                )
            }
        }

        override fun onFinished(sessionId: Int, success: Boolean) {
            // Only the task whose active session finished may advance; results
            // for sessions of stopped or replaced tasks are ignored.
            val task = findTask { it.activeSessionId == sessionId } ?: return

            var advance = false
            var complete = false
            synchronized(task.operationLock) {
                if (task.activeSessionId != sessionId) return
                task.activeSessionId = null
                task.sessions.removeAll { it.sessionId == sessionId }
                when {
                    !success -> Unit // Terminated below, outside the lock
                    task.sessions.isEmpty() -> {
                        task.phase = Phase.STOPPED
                        complete = true
                    }

                    else -> {
                        task.phase = Phase.READY
                        advance = true
                    }
                }
            }

            when {
                // The failure itself is reported through the status broadcast,
                // like the session installer; here the task is only retired.
                !success -> terminate(task)
                complete -> synchronized(queueLock) {
                    if (tasks[task.packageName] === task) {
                        tasks.remove(task.packageName)
                    }
                }

                advance -> startNext(task)
            }
        }
    }

    /**
     * The shared wrapped services, created on first use. The session callback is
     * registered on the wrapped installer, so it observes the sessions this
     * backend creates under the device-owner identity.
     */
    @Synchronized
    private fun services(): DhizukuServices {
        services?.let { return it }
        val created = dhizukuPackageInstaller()
        if (!callbackRegistered) {
            callbackRegistered = true
            runOnUiThread { created.packageInstaller.registerSessionCallback(callback) }
        }
        services = created
        return created
    }

    /**
     * A [PackageInstaller] whose IPC runs as the device owner: the `package` service binder
     * and the installer binder it hands out are both wrapped with [Dhizuku.binderWrapper],
     * then the framework's hidden PackageInstaller constructor is invoked reflectively.
     */
    private fun dhizukuPackageInstaller(): DhizukuServices {
        val packageBinder = HiddenApiBypass.invoke(
            Class.forName("android.os.ServiceManager"),
            null,
            "getService",
            "package"
        ) as IBinder

        val packageManager = HiddenApiBypass.invoke(
            Class.forName("android.content.pm.IPackageManager\$Stub"),
            null,
            "asInterface",
            Dhizuku.binderWrapper(packageBinder)
        )
        // getPackageInstaller() returns an IPackageInstaller proxy, not a raw binder:
        // take its binder, wrap that, and re-wrap it into a proxy of our own.
        val installerProxy = HiddenApiBypass.invoke(
            Class.forName("android.content.pm.IPackageManager"),
            packageManager,
            "getPackageInstaller"
        )
        val installerBinder = (installerProxy as IInterface).asBinder()

        val packageInstallerService = HiddenApiBypass.invoke(
            Class.forName("android.content.pm.IPackageInstaller\$Stub"),
            null,
            "asInterface",
            Dhizuku.binderWrapper(installerBinder)
        )

        val userId = Process.myUid() / 100_000
        val owner = ownerPackageName()
        val installerServiceClass = Class.forName("android.content.pm.IPackageInstaller")
        val installer = if (isSAndAbove) {
            // PackageInstaller(IPackageInstaller, String, String, int)
            HiddenApiBypass.getDeclaredConstructor(
                PackageInstaller::class.java,
                installerServiceClass,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!
            ).newInstance(packageInstallerService, owner, null, userId)
        } else {
            // PackageInstaller(IPackageInstaller, String, int)
            HiddenApiBypass.getDeclaredConstructor(
                PackageInstaller::class.java,
                installerServiceClass,
                String::class.java,
                Int::class.javaPrimitiveType!!
            ).newInstance(packageInstallerService, owner, userId)
        } as PackageInstaller
        return DhizukuServices(installer, packageInstallerService as IInterface)
    }

    /**
     * Opens a session whose every call runs as the device owner. The framework's own
     * `openSession` would wrap the raw session binder, and the server then rejects writes
     * with "Session does not belong to uid" because they arrive under Aurora's uid — so the
     * session binder is wrapped with Dhizuku first and the framework Session object is
     * constructed around the wrapped proxy, like the old Shizuku installer did.
     */
    private fun openDhizukuSession(
        services: DhizukuServices,
        sessionId: Int
    ): PackageInstaller.Session {
        val rawSession = HiddenApiBypass.invoke(
            Class.forName("android.content.pm.IPackageInstaller"),
            services.installerService,
            "openSession",
            sessionId
        )
        val wrappedSession = HiddenApiBypass.invoke(
            Class.forName("android.content.pm.IPackageInstallerSession\$Stub"),
            null,
            "asInterface",
            Dhizuku.binderWrapper((rawSession as IInterface).asBinder())
        )
        val session = HiddenApiBypass.getDeclaredConstructor(
            PackageInstaller.Session::class.java,
            Class.forName("android.content.pm.IPackageInstallerSession")
        ).newInstance(wrappedSession) as PackageInstaller.Session
        return session
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun install(download: Download) {
        super.install(download)

        if (isOutdated(context)) {
            fail(download.packageName, context.getString(R.string.installer_dhizuku_outdated))
            return
        }

        // Claim the package before staging anything: writing the APKs takes a
        // while, so registering only once staging finishes would let a
        // concurrent request open a second install for the same package. A
        // duplicate request is dropped here and must not touch the existing
        // task — in particular it must not commit a partially staged one.
        val task = InstallTask(download.packageName)
        synchronized(queueLock) {
            if (tasks.containsKey(download.packageName)) {
                Log.i(TAG, "${download.packageName} already queued")
                return
            }
            tasks[download.packageName] = task
        }

        Log.i(TAG, "Received session install request for ${download.packageName}")
        val services = try {
            services()
        } catch (exception: Exception) {
            terminate(task)
            fail(
                download.packageName,
                exception.localizedMessage,
                exception.stackTraceToString()
            )
            return
        }

        try {
            for (sharedLib in download.sharedLibs) {
                // Shared library packages cannot be updated
                if (isSharedLibraryInstalled(
                        context,
                        sharedLib.packageName,
                        sharedLib.versionCode
                    )
                ) {
                    continue
                }
                if (!stageSession(task, services, download, sharedLib.packageName)) return
            }
            if (!stageSession(task, services, download, "")) return
        } catch (exception: Exception) {
            val wasStopped = synchronized(task.operationLock) { task.phase == Phase.STOPPED }
            terminate(task)
            // A failure that is really a cancellation needs no error report
            if (!wasStopped) {
                fail(
                    download.packageName,
                    exception.localizedMessage,
                    exception.stackTraceToString()
                )
            }
            return
        }

        // Fully staged: only now may the commit chain start.
        synchronized(task.operationLock) {
            if (task.phase == Phase.STOPPED) return
            task.phase = Phase.READY
        }
        startNext(task)
    }

    override fun cancelInstall(packageName: String) {
        val task = synchronized(queueLock) { tasks[packageName] } ?: return

        Log.i(TAG, "Abandoning staged session(s) for $packageName")
        terminate(task)
        removeFromInstallQueue(packageName)
    }

    /**
     * Creates one session for [task] and writes its APKs into it without
     * committing. The whole staging — file lookup, session creation, writing —
     * is covered by the caller's exception handling, so any failure releases
     * the task's claim. The session is registered with the task the moment it
     * exists, so cancellation and cleanup can reclaim it even while it is
     * being written. Returns false when the task was stopped meanwhile; the
     * caller then drops the install silently.
     */
    private fun stageSession(
        task: InstallTask,
        services: DhizukuServices,
        download: Download,
        sharedLibPkgName: String
    ): Boolean {
        if (synchronized(task.operationLock) { task.phase == Phase.STOPPED }) return false

        val target = sharedLibPkgName.ifBlank { download.packageName }
        val targetVersionCode = if (sharedLibPkgName.isBlank()) {
            download.versionCode
        } else {
            download.sharedLibs.first { it.packageName == sharedLibPkgName }.versionCode
        }
        val displayName = if (sharedLibPkgName.isBlank()) download.displayName else ""

        val files = getFiles(download.packageName, download.versionCode, sharedLibPkgName)
        if (files.isEmpty()) {
            throw IOException(context.getString(R.string.installer_status_failure_session))
        }

        // Mirrors the session installer's params, except the ones that assert
        // Aurora's own identity (installer package, update ownership): here the
        // session's owner is Dhizuku by construction.
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(target)
            setSize(files.sumOf { it.length() })
            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (isSAndAbove) {
                setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (isTAndAbove) {
                setPackageSource(PACKAGE_SOURCE_STORE)
            }
        }
        val installFlagsField = HiddenApiBypass.getInstanceFields(SessionParams::class.java)
            .firstOrNull { it.name == "installFlags" }
        if (installFlagsField != null) {
            val flags = installFlagsField.getInt(params)
            installFlagsField.setInt(params, flags or INSTALL_REPLACE_EXISTING)
        }

        val sessionId = services.packageInstaller.createSession(params)
        val sessionInfo = SessionInfo(sessionId, target, targetVersionCode, displayName)
        val registered = synchronized(task.operationLock) {
            if (task.phase == Phase.STOPPED) {
                false
            } else {
                task.sessions.add(sessionInfo)
                true
            }
        }
        if (!registered) {
            // Cancelled while the session was being created
            abandonQuietly(sessionId)
            return false
        }

        Log.i(TAG, "Writing splits to session $sessionId for $target")
        openDhizukuSession(services, sessionId).use { session ->
            files.forEach { file ->
                file.inputStream().use { input ->
                    session.openWrite(file.name, 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            }
        }

        // If the task was cancelled while writing, terminate() has already
        // abandoned this session; say so instead of letting it be committed.
        return synchronized(task.operationLock) { task.phase != Phase.STOPPED }
    }

    /**
     * Finds an in-flight task by [predicate], which is evaluated with the
     * candidate's operation lock held, so it may safely read the task's
     * fields. The task list is snapshotted under queueLock and that lock is
     * released before any task lock is taken: queueLock is never held while
     * acquiring an operation lock anywhere (commits nest the other way,
     * briefly taking queueLock with the task lock held), which keeps the
     * lock order consistent and deadlock-free.
     */
    private fun findTask(predicate: (InstallTask) -> Boolean): InstallTask? {
        val snapshot = synchronized(queueLock) { tasks.values.toList() }
        return snapshot.firstOrNull { task ->
            synchronized(task.operationLock) { predicate(task) }
        }
    }

    /**
     * Commits [task]'s next staged session, if and only if the task is still
     * the registered one for its package, is fully staged, and has no session
     * in flight. This is the only commit path, called when staging completes
     * and when the task's previous session reports success — never by
     * scanning other tasks, so one download finishing can never push another
     * download's partial staging into the system.
     */
    private fun startNext(task: InstallTask) {
        synchronized(task.operationLock) {
            val registered = synchronized(queueLock) { tasks[task.packageName] === task }
            if (!registered || task.phase != Phase.READY || task.activeSessionId != null) return

            val sessionInfo = task.sessions.firstOrNull() ?: return
            task.activeSessionId = sessionInfo.sessionId
            task.phase = Phase.INSTALLING

            try {
                Log.i(TAG, "Starting install session for ${sessionInfo.packageName}")
                val services = services()
                if (services.packageInstaller.getSessionInfo(sessionInfo.sessionId) == null) {
                    Log.e(TAG, "Session ${sessionInfo.sessionId} is no longer valid.")
                    terminate(task)
                    removeFromInstallQueue(task.packageName)
                    return
                }
                openDhizukuSession(services, sessionInfo.sessionId).use { session ->
                    session.commit(getCallbackIntent(task, sessionInfo))
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Error committing session: ${exception.message}")
                terminate(task)
                fail(
                    task.packageName,
                    exception.localizedMessage,
                    exception.stackTraceToString()
                )
            }
        }
    }

    /**
     * Idempotently stops [task]: marks it stopped, unregisters it if it is
     * still the registered task for its package (an identity check, so a
     * stale task can never evict a newer retry under the same name), and
     * abandons every session it created that has not finished. A session the
     * system already accepted can only be attempted here; the guarantee is
     * that no further session of this task is committed afterwards.
     */
    private fun terminate(task: InstallTask) {
        val toAbandon = synchronized(task.operationLock) {
            task.phase = Phase.STOPPED
            task.activeSessionId = null
            task.sessions.toList().also { task.sessions.clear() }
        }
        synchronized(queueLock) {
            if (tasks[task.packageName] === task) {
                tasks.remove(task.packageName)
            }
        }
        toAbandon.forEach { abandonQuietly(it.sessionId) }
    }

    private fun abandonQuietly(sessionId: Int) {
        runCatching { services().packageInstaller.abandonSession(sessionId) }
    }

    private fun getCallbackIntent(task: InstallTask, sessionInfo: SessionInfo) =
        PendingIntentCompat.getBroadcast(
            context,
            sessionInfo.sessionId,
            Intent(context, InstallerStatusReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
                setPackage(context.packageName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(EXTRA_SESSION_ID, sessionInfo.sessionId)
                putExtra(EXTRA_PACKAGE_NAME, sessionInfo.packageName)
                putExtra(EXTRA_VERSION_CODE, sessionInfo.versionCode)
                putExtra(EXTRA_DISPLAY_NAME, sessionInfo.displayName)
                // The package this session belongs to: a shared library reports
                // under its own name, which no download record matches, so the
                // receiver mirrors a library failure onto the main package.
                putExtra(EXTRA_MAIN_PACKAGE_NAME, task.packageName)
                // Only the main package carries the marker (shared libraries have
                // no display name); the status receiver records the attribution on
                // success, since it is woken by the system even if this process
                // did not survive the install.
                putExtra(EXTRA_DHIZUKU_INSTALL, sessionInfo.displayName.isNotBlank())
            },
            PendingIntent.FLAG_UPDATE_CURRENT,
            true
        )!!.intentSender

    private fun fail(packageName: String, error: String?, extra: String? = null) {
        removeFromInstallQueue(packageName)
        postError(
            packageName,
            error ?: context.getString(R.string.installer_status_failure),
            extra ?: context.getString(R.string.installer_dhizuku_unavailable)
        )
    }
}
