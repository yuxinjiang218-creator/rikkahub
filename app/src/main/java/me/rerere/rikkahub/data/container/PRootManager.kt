package me.rerere.rikkahub.data.container

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.sandbox.SandboxEngine

/**
 * PRoot 容器管理器 - 全局单例架构
 *
 * 核心变更：
 * - 全局单一容器实例（非 per-conversation）
 * - 4 状态管理：未初始化 / 初始化中 / 运行中 / 已停止
 * - upper 层全局共享（所有对话共用 pip 包）
 * - 沙箱目录 per-conversation 隔离（通过 workspace bind mount）
 *
 * 状态流转：
 * NotInitialized → Initializing → Running ←→ Stopped
 *                      ↓              ↓
 *                   Error          NotInitialized (销毁后)
 */
@Singleton
class PRootManager(
    private val context: Context
) {

    data class ContainerDirectoryEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long,
    )

    companion object {
        private const val TAG = "PRootManager"
        private const val DEFAULT_TIMEOUT_MS = 300_000L // 5分钟
        private const val DEFAULT_MAX_MEMORY_MB = 6144  // 6GB for compilation tasks

        // Rootfs 版本控制 - 每次更新 alpine rootfs 时递增此版本号
        private const val ROOTFS_VERSION = 2
        private const val ROOTFS_VERSION_FILE = "rootfs_version.txt"
        private const val ALPINE_ROOTFS_VERSION = "3.23.3"
        private const val ALPINE_ROOTFS_ASSET = "rootfs/alpine-minirootfs-$ALPINE_ROOTFS_VERSION-aarch64.tar.gz"
        private const val LAYOUT_VERSION = 2
        private const val LAYOUT_VERSION_FILE = "layout_version.txt"
    }

    // 目录
    private val prootDir: File by lazy { File(context.filesDir, "proot") }
    private val rootfsDir: File by lazy { File(context.filesDir, "rootfs") }
    private val containerDir: File by lazy { File(context.filesDir, "container") }
    private val systemLayerDir: File by lazy { File(containerDir, "system-v$LAYOUT_VERSION") }
    private val legacyUpperDir: File by lazy { File(containerDir, "upper") }
    private val legacyWorkDir: File by lazy { File(containerDir, "work") }
    private val containerConfigDir: File by lazy { File(containerDir, "config") }
    private val layoutVersionFile: File by lazy { File(containerDir, LAYOUT_VERSION_FILE) }

    // 全局容器状态
    private var globalContainer: ContainerState? = null
    private var currentProcess: Process? = null
    private val processMutex = Mutex()  // 保护 currentProcess 的并发访问

    // 后台进程管理
    private val backgroundProcesses = ConcurrentHashMap<String, BackgroundProcessRecord>()

    // 状态流
    private val _containerState = MutableStateFlow<ContainerStateEnum>(ContainerStateEnum.NotInitialized)
    val containerState: StateFlow<ContainerStateEnum> = _containerState.asStateFlow()

    // 运行状态（用于工具暴露判断）
    val isRunning: Boolean
        get() = _containerState.value == ContainerStateEnum.Running

    /**
     * 获取容器 upper 层目录路径（用于工具安装）
     * 修复：不再依赖 globalContainer，直接检查目录存在性
     */
    fun getUpperDir(): String? {
        // 优先使用 globalContainer 的路径（如果已创建）
        globalContainer?.systemDir?.let { return it }

        // App 重启后 globalContainer 为 null，直接检查当前布局目录
        return if (systemLayerDir.exists()) systemLayerDir.absolutePath else null
    }

    // 自动管理标志
    @Volatile
    private var autoManagementEnabled = false

    // 自动管理的监听器引用（用于移除）
    private var autoManagementObserver: LifecycleEventObserver? = null

    // 当前容器启用设置
    @Volatile
    private var currentEnableContainerRuntime = false

    private fun formatContainerError(throwable: Throwable): String {
        val builder = StringBuilder()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        appendThrowableDetails(
            builder = builder,
            throwable = throwable,
            label = null,
            indent = "",
            seen = seen
        )
        return builder.toString().ifBlank {
            throwable.message ?: "Unknown error: ${throwable.javaClass.name}"
        }
    }

    private fun appendThrowableDetails(
        builder: StringBuilder,
        throwable: Throwable,
        label: String?,
        indent: String,
        seen: MutableSet<Throwable>,
    ) {
        if (!seen.add(throwable)) {
            builder.append(indent)
            if (label != null) {
                builder.append(label).append(": ")
            }
            builder.append("[CIRCULAR REFERENCE: ").append(throwable.javaClass.name).append(']').appendLine()
            return
        }

        builder.append(indent)
        if (label != null) {
            builder.append(label).append(": ")
        }
        builder.append(throwable.javaClass.name)
        throwable.message
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.append(": ").append(it) }
        builder.appendLine()

        throwable.stackTrace.forEach { frame ->
            builder.append(indent).append("\tat ").append(frame).appendLine()
        }

        throwable.suppressed.forEach { suppressed ->
            appendThrowableDetails(
                builder = builder,
                throwable = suppressed,
                label = "Suppressed",
                indent = "$indent\t",
                seen = seen
            )
        }

        throwable.cause?.let { cause ->
            appendThrowableDetails(
                builder = builder,
                throwable = cause,
                label = "Caused by",
                indent = indent,
                seen = seen
            )
        }
    }

    private fun resolveRootfsAssetPath(): String {
        val candidates = listOf(
            ALPINE_ROOTFS_ASSET,
            ALPINE_ROOTFS_ASSET.removeSuffix(".gz")
        ).distinct()
        val availableAssets = context.assets.list("rootfs")?.toSet().orEmpty()

        candidates.firstOrNull { candidate ->
            availableAssets.contains(candidate.substringAfterLast('/'))
        }?.let { return it }

        candidates.forEach { candidate ->
            try {
                context.assets.open(candidate).close()
                return candidate
            } catch (_: FileNotFoundException) {
                // Try next candidate.
            }
        }

        throw FileNotFoundException(
            "Missing rootfs asset. Tried=${candidates.joinToString()} available=${availableAssets.joinToString()}"
        )
    }

    /**
     * 检查是否需要初始化（资源文件是否存在且有效）
     */
    fun checkInitializationStatus(): Boolean {
        val prootBinary = File(prootDir, "proot")
        val rootfsValid = rootfsDir.exists()
                && rootfsDir.listFiles()?.isNotEmpty() == true
                && File(rootfsDir, "bin/sh").exists()
                && File(rootfsDir, "bin/sh").length() > 0

        if (prootBinary.exists() && rootfsDir.exists()) {
            val shFile = File(rootfsDir, "bin/sh")
            Log.d(TAG, "Checking rootfs: exists=${rootfsDir.exists()}, " +
                    "hasFiles=${rootfsDir.listFiles()?.isNotEmpty()}, " +
                    "shExists=${shFile.exists()}, " +
                    "shSize=${if (shFile.exists()) shFile.length() else 0}")
        }

        return prootBinary.exists() && rootfsValid
    }

    private fun requiredSystemPaths(): List<String> = listOf(
        "bin",
        "sbin",
        "etc",
        "lib",
        "root",
        "tmp",
        "usr",
        "usr/bin",
        "usr/lib",
        "usr/local",
        "var",
        "var/cache",
        "var/cache/apk",
        "var/lib",
        "lib/apk",
        "lib/apk/db",
        "etc/apk",
    )

    private fun inspectLayoutStatus(): ContainerLayoutStatus {
        val version = layoutVersionFile.takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.toIntOrNull()
        val hasCurrentLayout = systemLayerDir.exists()
        val hasLegacyLayout = legacyUpperDir.exists() || legacyWorkDir.exists()
        val hasRuntimeArtifacts = containerConfigDir.exists() || hasCurrentLayout || hasLegacyLayout || version != null
        val compatible = version == LAYOUT_VERSION &&
            hasCurrentLayout &&
            requiredSystemPaths().all { relative -> File(systemLayerDir, relative).exists() }

        return when {
            compatible -> ContainerLayoutStatus(
                version = version,
                compatible = true,
                hasLegacyLayout = hasLegacyLayout,
                hasCurrentLayout = true,
            )

            hasLegacyLayout -> ContainerLayoutStatus(
                version = version,
                needsRebuild = true,
                reason = "Detected legacy container layout. Rebuild is required.",
                hasLegacyLayout = true,
                hasCurrentLayout = hasCurrentLayout,
            )

            hasRuntimeArtifacts -> ContainerLayoutStatus(
                version = version,
                needsRebuild = true,
                reason = "Detected incomplete or incompatible container system layer.",
                hasLegacyLayout = false,
                hasCurrentLayout = hasCurrentLayout,
            )

            else -> ContainerLayoutStatus(
                version = version,
                compatible = false,
                needsRebuild = false,
            )
        }
    }

    private fun writeLayoutVersion() {
        layoutVersionFile.parentFile?.mkdirs()
        layoutVersionFile.writeText(LAYOUT_VERSION.toString())
    }

    /**
     * 初始化 PRoot 环境（下载/解压资源）
     * 从 NotInitialized → Initializing → Running
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_containerState.value is ContainerStateEnum.Initializing) {
                return@withContext Result.failure(IllegalStateException("Already initializing"))
            }
            if (_containerState.value == ContainerStateEnum.Running) {
                return@withContext Result.success(Unit)
            }
            
            Log.d(TAG, "Starting container initialization...")
            _containerState.value = ContainerStateEnum.Initializing(0f)
            
            // 创建目录
            Log.d(TAG, "Creating directories: prootDir=$prootDir, rootfsDir=$rootfsDir, containerDir=$containerDir")
            prootDir.mkdirs()
            rootfsDir.mkdirs()
            containerDir.mkdirs()
            
            _containerState.value = ContainerStateEnum.Initializing(0.2f)
            
            // 解压 PRoot 二进制文件
            Log.d(TAG, "Extracting PRoot binary...")
            extractPRootBinary()
            Log.d(TAG, "PRoot binary extracted successfully")
            
            _containerState.value = ContainerStateEnum.Initializing(0.5f)
            
            // 解压 Alpine rootfs
            Log.d(TAG, "Extracting Alpine rootfs...")
            extractAlpineRootfs()
            Log.d(TAG, "Alpine rootfs extracted successfully")
            
            _containerState.value = ContainerStateEnum.Initializing(0.8f)

            // 旧布局或残缺布局必须先清理，避免假恢复后继续污染新系统层
            clearContainerRuntimeLayers(preserveRootfs = true)
            seedSystemLayer()
            
            // 创建全局容器
            Log.d(TAG, "Creating global container...")
            createGlobalContainer()
            Log.d(TAG, "Global container created successfully")

            // [修复 npm] 补丁代码写入独立 wrapper
            try {
                Log.i(TAG, "Repairing node and npm paths...")

                // JS 补丁代码：通过 heredoc 传入 node stdin，无 execve 参数长度限制
                val patchCode = """
try {
process.stdout.write('PATCH_INIT\n');
process.on('uncaughtException', function(err) {
    process.stdout.write('UNCAUGHT: ' + err.message + '\n' + (err.stack || '') + '\n');
    process.exit(4);
});
process.on('unhandledRejection', function(reason, promise) {
    process.stdout.write('UNHANDLED_REJECTION: ' + (reason instanceof Error ? reason.message + '\n' + reason.stack : String(reason)) + '\n');
});
process.on('exit', function(code) {
    process.stdout.write('PROCESS_EXIT: ' + code + '\n');
});
var _origExit = process.exit;
process.exit = function(code) {
    if (code !== 0) {
        process.stdout.write('EXIT_CALL: code=' + code + ' stack=' + new Error().stack + '\n');
    }
    _origExit.call(process, code);
};
var _origStderrWrite = process.stderr.write;
process.stderr.write = function(chunk) {
    process.stdout.write('STDERR: ' + chunk);
    return _origStderrWrite.apply(process.stderr, arguments);
};
var _origEmit = process.emit;
process.emit = function(event) {
    if (event === 'log') {
        var args = Array.prototype.slice.call(arguments, 1);
        var detail = args.map(function(a) {
            if (a === undefined) return '';
            if (a instanceof Error) return '[Error:' + a.message + '] ' + (a.stack || '');
            if (typeof a === 'object') { try { return JSON.stringify(a); } catch(e) { return String(a); } }
            return String(a);
        }).join(' ');
        process.stdout.write('NPMLOG: ' + detail + '\n');
    }
    return _origEmit.apply(process, arguments);
};
var _origConsoleError = console.error;
console.error = function() {
    var args = Array.prototype.slice.call(arguments);
    var detail = args.map(function(a) {
        if (a === undefined) return '[undefined]';
        if (a === null) return '[null]';
        if (a === '') return '[empty-string]';
        if (a instanceof Error) return '[Error:' + a.message + '] ' + (a.stack || '');
        if (typeof a === 'object') { try { return JSON.stringify(a); } catch(e) { return String(a); } }
        return String(a);
    }).join(' ');
    process.stdout.write('CERR[' + args.length + ']: ' + detail + '\n');
    return _origConsoleError.apply(console, arguments);
};
var _origConsoleWarn = console.warn;
console.warn = function() {
    var args = Array.prototype.slice.call(arguments);
    var detail = args.map(function(a) {
        if (a === undefined) return '[undefined]';
        if (a === null) return '[null]';
        if (a === '') return '[empty-string]';
        if (a instanceof Error) return '[Error:' + a.message + '] ' + (a.stack || '');
        if (typeof a === 'object') { try { return JSON.stringify(a); } catch(e) { return String(a); } }
        return String(a);
    }).join(' ');
    process.stdout.write('CWARN[' + args.length + ']: ' + detail + '\n');
    return _origConsoleWarn.apply(console, arguments);
};
var Module = require('module');
var fs = require('fs');
var path = require('path');
var origFindPath = Module._findPath;
Module._findPath = function(request, paths, isMain) {
    var result = origFindPath.call(Module, request, paths, isMain);
    if (result) return result;
    for (var i = 0; i < paths.length; i++) {
        var basePath = path.resolve(paths[i], request);
        var tries = [basePath, basePath + '.js', basePath + '.json',
                     path.join(basePath, 'index.js'), path.join(basePath, 'index.json')];
        try {
            var pkgContent = fs.readFileSync(path.join(basePath, 'package.json'), 'utf8');
            var pkg = JSON.parse(pkgContent);
            if (pkg.main) {
                var mainPath = path.resolve(basePath, pkg.main);
                tries.splice(1, 0, mainPath, mainPath + '.js', mainPath + '/index.js');
            }
        } catch(e) {}
        for (var j = 0; j < tries.length; j++) {
            try {
                var fd = fs.openSync(tries[j], 'r');
                var s = fs.fstatSync(fd);
                fs.closeSync(fd);
                if (s.isFile()) {
                    Module._pathCache[request + '\x00' + paths.join('\x00')] = tries[j];
                    return tries[j];
                }
            } catch(e) {}
        }
    }
    return false;
};
var origStatSync = fs.statSync;
fs.statSync = function(p, options) {
    try { return origStatSync.call(fs, p, options); }
    catch (err) {
        if (err.code === 'ENOENT') {
            try {
                var fd = fs.openSync(p, 'r');
                var stats = fs.fstatSync(fd);
                fs.closeSync(fd);
                return stats;
            } catch (z) {}
        }
        throw err;
    }
};
var origLstatSync = fs.lstatSync;
fs.lstatSync = function(p, options) {
    try { return origLstatSync.call(fs, p, options); }
    catch (err) {
        if (err.code === 'ENOENT') {
            try {
                var fd = fs.openSync(p, 'r');
                var stats = fs.fstatSync(fd);
                fs.closeSync(fd);
                return stats;
            } catch (z) {}
        }
        throw err;
    }
};
var origRealpathSync = fs.realpathSync;
fs.realpathSync = function(p, options) {
    try { return origRealpathSync.call(fs, p, options); }
    catch (err) {
        if (err.code === 'ENOENT') {
            try {
                var fd = fs.openSync(p, 'r');
                fs.closeSync(fd);
                return path.resolve(p);
            } catch(e) {
                try { fs.readdirSync(p); return path.resolve(p); }
                catch(e2) {}
            }
        }
        throw err;
    }
};
var origExistsSync = fs.existsSync;
fs.existsSync = function(p) {
    var result = origExistsSync.call(fs, p);
    if (!result) {
        try {
            var fd = fs.openSync(p, 'r');
            fs.closeSync(fd);
            return true;
        } catch(e) {
            try { fs.readdirSync(p); return true; }
            catch(e2) { return false; }
        }
    }
    return result;
};
var origAccessSync = fs.accessSync;
fs.accessSync = function(p, mode) {
    try { return origAccessSync.call(fs, p, mode); }
    catch (err) {
        if (err.code === 'ENOENT') {
            try { var fd = fs.openSync(p, 'r'); fs.closeSync(fd); return; } catch(z) {}
        }
        throw err;
    }
};
var _stat = fs.stat;
fs.stat = function(p, o, cb) {
    if (typeof o === 'function') { cb = o; o = {}; }
    _stat.call(fs, p, o, function(e, s) {
        if (e && e.code === 'ENOENT') { try { var f = fs.openSync(p,'r'); s = fs.fstatSync(f); fs.closeSync(f); return cb(null,s); } catch(x) {} }
        cb(e, s);
    });
};
var _lstat = fs.lstat;
fs.lstat = function(p, o, cb) {
    if (typeof o === 'function') { cb = o; o = {}; }
    _lstat.call(fs, p, o, function(e, s) {
        if (e && e.code === 'ENOENT') { try { var f = fs.openSync(p,'r'); s = fs.fstatSync(f); fs.closeSync(f); return cb(null,s); } catch(x) {} }
        cb(e, s);
    });
};
var _realpath = fs.realpath;
fs.realpath = function(p, o, cb) {
    if (typeof o === 'function') { cb = o; o = {}; }
    _realpath.call(fs, p, o, function(e, r) {
        if (e && e.code === 'ENOENT') { try { var f = fs.openSync(p,'r'); fs.closeSync(f); return cb(null,path.resolve(p)); } catch(x) { try { fs.readdirSync(p); return cb(null,path.resolve(p)); } catch(x2) {} } }
        cb(e, r);
    });
};
var _access = fs.access;
fs.access = function(p, m, cb) {
    if (typeof m === 'function') { cb = m; m = fs.constants.F_OK; }
    _access.call(fs, p, m, function(e) {
        if (e && e.code === 'ENOENT') { try { var f = fs.openSync(p,'r'); fs.closeSync(f); return cb(null); } catch(x) {} }
        cb(e);
    });
};
if (fs.promises) {
    var fsp = fs.promises;
    var _pStat = fsp.stat; fsp.stat = function(p,o) { return _pStat.call(fsp,p,o).catch(function(e) { if(e.code==='ENOENT'){try{var f=fs.openSync(p,'r');var s=fs.fstatSync(f);fs.closeSync(f);return s;}catch(x){}} throw e; }); };
    var _pLstat = fsp.lstat; fsp.lstat = function(p,o) { return _pLstat.call(fsp,p,o).catch(function(e) { if(e.code==='ENOENT'){try{var f=fs.openSync(p,'r');var s=fs.fstatSync(f);fs.closeSync(f);return s;}catch(x){}} throw e; }); };
    var _pRealpath = fsp.realpath; fsp.realpath = function(p,o) { return _pRealpath.call(fsp,p,o).catch(function(e) { if(e.code==='ENOENT'){try{var f=fs.openSync(p,'r');fs.closeSync(f);return path.resolve(p);}catch(x){try{fs.readdirSync(p);return path.resolve(p);}catch(x2){}}} throw e; }); };
    var _pAccess = fsp.access; fsp.access = function(p,m) { return _pAccess.call(fsp,p,m).catch(function(e) { if(e.code==='ENOENT'){try{var f=fs.openSync(p,'r');fs.closeSync(f);return;}catch(x){}} throw e; }); };
}
var _origMkdirSync = fs.mkdirSync;
fs.mkdirSync = function(p, options) {
    try {
        return _origMkdirSync.call(fs, p, options);
    } catch (err) {
        if (err.code === 'ENOENT') {
            var cp = require('child_process');
            var cmd = 'mkdir ';
            if (options && options.recursive) cmd += '-p ';
            cmd += '"' + p.replace(/"/g, '\\"') + '"';
            try {
                cp.execSync(cmd, { stdio: 'pipe' });
                return undefined;
            } catch (e) {
                if (e.message && e.message.indexOf('File exists') >= 0) {
                    var errExists = new Error('EEXIST: file already exists, mkdir \'' + p + '\'');
                    errExists.code = 'EEXIST';
                    errExists.syscall = 'mkdir';
                    errExists.path = p;
                    throw errExists;
                }
                throw err;
            }
        }
        throw err;
    }
};
if (fs.promises) {
    var _origPromisesMkdir = fs.promises.mkdir;
    fs.promises.mkdir = async function(p, options) {
        try {
            return await _origPromisesMkdir(p, options);
        } catch (err) {
            if (err.code === 'ENOENT') {
                return new Promise(function(resolve, reject) {
                    try {
                        fs.mkdirSync(p, options);
                        resolve(undefined);
                    } catch (e) {
                        reject(e);
                    }
                });
            }
            throw err;
        }
    };
}
var target = process.env._PATCH_TARGET;
if (target) {
    process.argv = [process.argv[0], target].concat(
        process.argv.slice(1).filter(function(a) { return a !== '-'; })
    );
    try { process.stdout.write('ENV_CWD: ' + process.cwd() + '\n'); } catch(e) { process.stdout.write('ENV_CWD_ERROR: ' + e.message + '\n'); }
    try { var os = require('os'); process.stdout.write('ENV_HOME: ' + os.homedir() + '\n'); } catch(e) { process.stdout.write('ENV_HOME_ERROR: ' + e.message + '\n'); }
    try { var os = require('os'); process.stdout.write('ENV_TMPDIR: ' + os.tmpdir() + '\n'); } catch(e) { process.stdout.write('ENV_TMPDIR_ERROR: ' + e.message + '\n'); }
    // 主线程预扫描 npm 文件树（主线程的 openSync 能正常工作）
    var _FM = {};
    var _PM = {};
    function _scanFiles(dir, dep) {
        if (dep > 15) return;
        try {
            var ent = fs.readdirSync(dir);
            for (var i = 0; i < ent.length; i++) {
                var fp = path.join(dir, ent[i]);
                try {
                    var fd = fs.openSync(fp, 'r');
                    var st = fs.fstatSync(fd);
                    fs.closeSync(fd);
                    if (st.isFile()) _FM[fp] = 1;
                    else if (st.isDirectory()) _scanFiles(fp, dep + 1);
                } catch(e) {
                    try { fs.readdirSync(fp); _scanFiles(fp, dep + 1); } catch(e2) {}
                }
            }
        } catch(e) {}
    }
    function _scanPkgs(dir) {
        var nm = path.join(dir, 'node_modules');
        try {
            var ent = fs.readdirSync(nm);
            for (var i = 0; i < ent.length; i++) {
                var n = ent[i]; if (n[0] === '.') continue;
                if (n[0] === '@') {
                    try { var se = fs.readdirSync(path.join(nm, n));
                        for (var j = 0; j < se.length; j++) { _loadPkg(path.join(nm, n, se[j]), n + '/' + se[j]); _scanPkgs(path.join(nm, n, se[j])); }
                    } catch(e) {}
                } else { _loadPkg(path.join(nm, n), n); _scanPkgs(path.join(nm, n)); }
            }
        } catch(e) {}
    }
    function _loadPkg(pd, pn) {
        try {
            var fd = fs.openSync(path.join(pd, 'package.json'), 'r');
            var buf = Buffer.alloc(65536); var n = fs.readSync(fd, buf); fs.closeSync(fd);
            var pk = JSON.parse(buf.slice(0, n).toString());
            if (!_PM[pn]) _PM[pn] = [];
            _PM[pn].push({ d: pd, e: pk.exports || null, m: pk.main || null, i: pk.imports || null });
        } catch(e) {}
    }
    process.stdout.write('PRESCAN...\n');
    _scanFiles('/usr/lib/node_modules/npm', 0);
    _scanPkgs('/usr/lib/node_modules/npm');
    process.stdout.write('PRESCAN_DONE: ' + Object.keys(_FM).length + 'f ' + Object.keys(_PM).length + 'p\n');
    var _samples = [
        '/usr/lib/node_modules/npm/node_modules/chalk/package.json',
        '/usr/lib/node_modules/npm/node_modules/chalk/source/index.js',
        '/usr/lib/node_modules/npm/node_modules/chalk/source/vendor/ansi-styles/index.js'
    ];
    _samples.forEach(function(s) { process.stdout.write('FM_HAS[' + s + ']: ' + (_FM[s] ? 'YES' : 'NO') + '\n'); });
    var _fmKeys = Object.keys(_FM);
    var _chalkKeys = _fmKeys.filter(function(k) { return k.indexOf('chalk') >= 0 && k.indexOf('vendor') >= 0; }).slice(0, 3);
    process.stdout.write('FM_SAMPLE: ' + JSON.stringify(_chalkKeys) + '\n');
    // 注册 ESM 加载器（Worker 线程只查地图，不做文件操作）
    try {
        var _mod = require('node:module');
        if (typeof _mod.register === 'function') {
            var _L = [];
            _L.push('import { dirname, join, resolve as pathResolve } from "node:path";');
            _L.push('import { fileURLToPath, pathToFileURL } from "node:url";');
            _L.push('var _FM,_PM;');
            _L.push('export function initialize(d){_FM=d.fm;_PM=d.pm;}');
            _L.push('function fe(p){return !!_FM[p];}');
            _L.push('function ge(pk,sub){');
            _L.push('  if(sub){if(pk.e){var m=pk.e["./"+sub];if(m)return typeof m==="string"?m:(m.node||m.import||m.default||sub);}return sub;}');
            _L.push('  if(pk.e){var e=pk.e["."]; if(!e)e=pk.e; if(typeof e==="string")return e; if(e&&typeof e==="object"){var v=e.node||e.import||e.default; if(v&&typeof v==="object")v=v.node||v.import||v.default; if(typeof v==="string")return v;}}');
            _L.push('  return pk.m||"index.js";');
            _L.push('}');
            _L.push('export async function resolve(spec,ctx,next){');
            _L.push('  try{return await next(spec,ctx);}');
            _L.push('  catch(err){');
            _L.push('    if(err.code!=="ERR_MODULE_NOT_FOUND")throw err;');
            _L.push('    if(spec.startsWith("node:"))throw err;');
            _L.push('    if(spec.startsWith("#")){');
            _L.push('      if(!ctx.parentURL)throw err;');
            _L.push('      var pf=fileURLToPath(ctx.parentURL);');
            _L.push('      var pd=dirname(pf);');
            _L.push('      while(pd.length>1){');
            _L.push('        var pkgPath=join(pd,"package.json");');
            _L.push('        if(fe(pkgPath)){');
            _L.push('          var pkList=[];');
            _L.push('          for(var pn in _PM){for(var pi of _PM[pn]){if(pi.d===pd)pkList.push(pi);}}');
            _L.push('          if(pkList.length>0){');
            _L.push('            var pk=pkList[0];');
            _L.push('            if(pk.i&&pk.i[spec]){');
            _L.push('              var tgt=pk.i[spec];');
            _L.push('              if(typeof tgt==="string"){');
            _L.push('                var rv=pathResolve(pd,tgt);');
            _L.push('                if(fe(rv))return{url:pathToFileURL(rv).href,shortCircuit:true};');
            _L.push('              }else if(tgt&&typeof tgt==="object"){');
            _L.push('                var v=tgt.node||tgt.import||tgt.default;');
            _L.push('                if(typeof v==="string"){');
            _L.push('                  var rv2=pathResolve(pd,v);');
            _L.push('                  if(fe(rv2))return{url:pathToFileURL(rv2).href,shortCircuit:true};');
            _L.push('                }');
            _L.push('              }');
            _L.push('            }');
            _L.push('          }');
            _L.push('          break;');
            _L.push('        }');
            _L.push('        var prev=pd;pd=dirname(pd);if(pd===prev)break;');
            _L.push('      }');
            _L.push('      throw err;');
            _L.push('    }');
            _L.push('    if(spec.startsWith(".")||spec.startsWith("/")){');
            _L.push('      if(!ctx.parentURL)throw err;');
            _L.push('      var pd=dirname(fileURLToPath(ctx.parentURL));');
            _L.push('      var r=pathResolve(pd,spec);');
            _L.push('      var cs=[r,r+".js",r+".mjs",r+".json",join(r,"index.js"),join(r,"index.mjs")];');
            _L.push('      for(var c of cs){if(fe(c))return{url:pathToFileURL(c).href,shortCircuit:true};}');
            _L.push('      throw err;');
            _L.push('    }');
            _L.push('    if(spec.startsWith("file:"))throw err;');
            _L.push('    var pts=spec.split("/");');
            _L.push('    var pn=spec.startsWith("@")?pts.slice(0,2).join("/"):pts[0];');
            _L.push('    var sp=spec.startsWith("@")?pts.slice(2).join("/"):pts.slice(1).join("/");');
            _L.push('    if(!_PM[pn])throw err;');
            _L.push('    var idir=ctx.parentURL?dirname(fileURLToPath(ctx.parentURL)):"/";');
            _L.push('    var best=null,bestLen=-1;');
            _L.push('    for(var pi of _PM[pn]){var nmBase=dirname(pi.d);if(idir.startsWith(dirname(nmBase))){if(nmBase.length>bestLen){bestLen=nmBase.length;best=pi;}}}');
            _L.push('    if(!best&&_PM[pn].length>0)best=_PM[pn][0];');
            _L.push('    if(!best)throw err;');
            _L.push('    var en=ge(best,sp);if(typeof en!=="string")en="index.js";');
            _L.push('    var rv=pathResolve(best.d,en);');
            _L.push('    var cs=[rv,rv+".js",rv+".mjs",join(rv,"index.js"),join(rv,"index.mjs")];');
            _L.push('    for(var c of cs){if(fe(c))return{url:pathToFileURL(c).href,shortCircuit:true};}');
            _L.push('    throw err;');
            _L.push('  }');
            _L.push('}');
            var _esmCode = _L.join('\n');
            _mod.register('data:text/javascript,' + encodeURIComponent(_esmCode), { data: { fm: _FM, pm: _PM } });
            process.stdout.write('ESM_LOADER_OK\n');
        } else {
            process.stdout.write('ESM_LOADER_SKIP: register not available\n');
        }
    } catch(esmErr) {
        process.stdout.write('ESM_LOADER_ERROR: ' + esmErr.message + '\n');
    }
    process.stdout.write('REQUIRING: ' + target + '\n');
    require(target);
    process.stdout.write('REQUIRE_OK\n');
}
} catch(e) {
    process.stdout.write('NPM_PATCH_ERROR: ' + e.message + '\n' + (e.stack || '') + '\n');
    process.exit(2);
}
""".trimStart()

                // 在 host 层写入 npm/npx 包装脚本（heredoc 方式，不经过 execve 参数）
                val upperLocalBin = File(systemLayerDir, "usr/local/bin")
                upperLocalBin.mkdirs()

                File(upperLocalBin, "npm").apply {
                    writeText("#!/bin/sh\nexport _PATCH_TARGET=/usr/lib/node_modules/npm/bin/npm-cli.js\nnode - \"\$@\" << 'ENDPATCH'\n${patchCode}ENDPATCH\n")
                    setExecutable(true, false)
                }
                File(upperLocalBin, "npx").apply {
                    writeText("#!/bin/sh\nexport _PATCH_TARGET=/usr/lib/node_modules/npm/bin/npx-cli.js\nnode - \"\$@\" << 'ENDPATCH'\n${patchCode}ENDPATCH\n")
                    setExecutable(true, false)
                }

                // 创建 node wrapper（放在 /usr/local/bin，避免覆盖系统二进制）
                File(upperLocalBin, "node").apply {
                    writeText("""#!/bin/sh
# PRoot Node.js Wrapper - Auto-load compatibility patches
if [ "${'$'}1" = "-" ]; then
    # stdin mode (used by npm/npx wrappers)
    exec /usr/bin/node "${'$'}@"
else
    # Normal mode: inject patch via stdin, then run target script
    # Save current working directory for Node.js to restore
    export _NODE_WRAPPER_CWD="${'$'}(pwd)"
    exec /usr/bin/node - "${'$'}@" << 'NODEPATCH'
${patchCode}
// Restore working directory (lost in stdin mode)
if (process.env._NODE_WRAPPER_CWD) {
    try {
        process.chdir(process.env._NODE_WRAPPER_CWD);
    } catch (e) {
        console.error('Warning: Failed to restore cwd:', e.message);
    }
}
// Load target script if specified
if (process.argv.length > 2 && process.argv[2] !== '-') {
    var targetScript = process.argv[2];
    var path = require('path');
    // Normalize path: if not absolute and doesn't start with ./ or ../, prepend ./
    if (!path.isAbsolute(targetScript) && !targetScript.startsWith('./') && !targetScript.startsWith('../')) {
        targetScript = './' + targetScript;
    }
    // Resolve to absolute path
    targetScript = path.resolve(targetScript);
    process.argv = [process.argv[0], targetScript].concat(process.argv.slice(3));
    require(targetScript);
}
NODEPATCH
fi
""")
                    setExecutable(true, false)
                }

                Log.i(TAG, "Node/NPM path repair completed (heredoc stdin patch)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to repair node/npm paths", e)
            }

            writeLayoutVersion()
            _containerState.value = ContainerStateEnum.Running
            Log.d(TAG, "Container initialization completed successfully!")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Container initialization failed", e)
            _containerState.value = ContainerStateEnum.Error(formatContainerError(e))
            Result.failure(e)
        }
    }

    /**
     * 启动容器（从 Stopped 到 Running）
     * 如果从未初始化，会先执行初始化
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (_containerState.value) {
                is ContainerStateEnum.Running -> {
                    return@withContext Result.success(Unit) // 已经在运行
                }
                is ContainerStateEnum.Initializing -> {
                    return@withContext Result.failure(IllegalStateException("Already initializing"))
                }
                is ContainerStateEnum.NotInitialized -> {
                    // 需要初始化
                    return@withContext initialize()
                }
                is ContainerStateEnum.Stopped -> {
                    val layoutStatus = inspectLayoutStatus()
                    if (!layoutStatus.compatible) {
                        _containerState.value = ContainerStateEnum.NeedsRebuild(
                            layoutStatus.reason ?: "Container layout is incompatible. Rebuild is required."
                        )
                        return@withContext Result.failure(IllegalStateException("Container layout requires rebuild"))
                    }
                    // 从停止状态恢复
                    if (globalContainer == null) {
                        createGlobalContainer()
                    } else {
                        requiredSystemPaths().forEach { relative ->
                            File(systemLayerDir, relative).mkdirs()
                        }
                    }
                    _containerState.value = ContainerStateEnum.Running
                    return@withContext Result.success(Unit)
                }
                is ContainerStateEnum.NeedsRebuild -> {
                    return@withContext Result.failure(IllegalStateException("Container rebuild required"))
                }
                is ContainerStateEnum.Error -> {
                    // 错误后重试，需要重新初始化
                    _containerState.value = ContainerStateEnum.NotInitialized
                    return@withContext initialize()
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停止容器（Running → Stopped）
     * 保留 upper 层，只 kill 进程
     */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_containerState.value != ContainerStateEnum.Running) {
                return@withContext Result.failure(IllegalStateException("Container not running"))
            }
            
            // 终止当前进程（使用 Mutex 保护）
            processMutex.withLock {
                currentProcess?.destroyForcibly()
                currentProcess = null
            }
            killTrackedBackgroundProcesses()

            _containerState.value = ContainerStateEnum.Stopped
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 销毁容器（任意状态 → NotInitialized）
     * 删除容器系统层与运行时层，但保留 workspace / delivery / skills。
     */
    suspend fun destroy(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 停止进程（使用 Mutex 保护）
            processMutex.withLock {
                currentProcess?.destroyForcibly()
                currentProcess = null
            }
            killTrackedBackgroundProcesses()
            clearContainerRuntimeLayers(preserveRootfs = true)

            globalContainer = null
            _containerState.value = ContainerStateEnum.NotInitialized
            Log.d(TAG, "Container runtime destroyed, workspaces and skills preserved")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rebuildPreservingWorkspaces(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            processMutex.withLock {
                currentProcess?.destroyForcibly()
                currentProcess = null
            }
            killTrackedBackgroundProcesses()
            clearContainerRuntimeLayers(preserveRootfs = true)
            globalContainer = null
            _containerState.value = ContainerStateEnum.NotInitialized
            initialize()
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 执行 Python 代码
     * @param sandboxId 沙箱 ID（通常是 conversationId）
     */
    suspend fun executePython(
        sandboxId: String,
        code: String,
        packages: List<String> = emptyList()
    ): JsonObject {
        if (_containerState.value != ContainerStateEnum.Running) {
            return buildJsonObject {
                put("success", false)
                put("error", "Container not running. Current state: ${_containerState.value}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
        
        return try {
            // 确保沙箱目录存在
            val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")
            val deliveryDir = SandboxEngine.getDeliveryDir(context, sandboxId)
            sandboxDir.mkdirs()
            
            // 安装依赖
            if (packages.isNotEmpty()) {
                val pipResult = execInContainer(
                    sandboxId = sandboxId,
                    command = listOf("pip", "install") + packages,
                    timeoutMs = 120_000
                )
                
                if (pipResult.exitCode != 0) {
                    return buildJsonObject {
                        put("success", false)
                        put("error", "Failed to install packages: ${pipResult.stderr}")
                        put("exitCode", pipResult.exitCode)
                        put("stdout", pipResult.stdout)
                        put("stderr", pipResult.stderr)
                    }
                }
            }

            // 使用 Base64 编码写入脚本文件（避免 shell 注入）
            val scriptFile = "/tmp/script_${System.currentTimeMillis()}.py"
            val encodedCode = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val toolEnv = getToolEnvironment()
            val writeResult = execInContainer(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", "echo '$encodedCode' | base64 -d > $scriptFile"),
                env = toolEnv
            )

            if (writeResult.exitCode != 0) {
                return buildJsonObject {
                    put("success", false)
                    put("error", "Failed to write script: ${writeResult.stderr}")
                    put("exitCode", writeResult.exitCode)
                    put("stdout", writeResult.stdout)
                    put("stderr", writeResult.stderr)
                }
            }

            // 执行脚本
            val execResult = execInContainer(
                sandboxId = sandboxId,
                command = listOf("python3", scriptFile),
                env = toolEnv
            )

            buildJsonObject {
                put("success", execResult.exitCode == 0)
                put("exitCode", execResult.exitCode)
                put("stdout", execResult.stdout)
                put("stderr", execResult.stderr)  // 始终包含 stderr，即使为空
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("success", false)
                put("error", "Container execution error: ${e.message}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
    }

    /**
     * 执行 Shell 命令
     * @param sandboxId 沙箱 ID（通常是 conversationId）
     */
    suspend fun executeShell(
        sandboxId: String,
        command: String
    ): JsonObject {
        Log.d(TAG, "[ExecuteShell] ========== Command: $command ==========")
        Log.d(TAG, "[ExecuteShell] Container state: ${_containerState.value}")

        if (_containerState.value != ContainerStateEnum.Running) {
            Log.e(TAG, "[ExecuteShell] FAILED: Container not running!")
            return buildJsonObject {
                put("success", false)
                put("error", "Container not running. Current state: ${_containerState.value}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }

        return try {
            // 确保沙箱目录存在
            val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")
            sandboxDir.mkdirs()

            // 获取已安装工具的环境变量
            val toolEnv = getToolEnvironment()
            Log.d(TAG, "[ExecuteShell] Environment: $toolEnv")

            val execResult = execInContainer(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", command),
                env = toolEnv
            )

            Log.d(TAG, "[ExecuteShell] Result - exitCode=${execResult.exitCode}")
            Log.d(TAG, "[ExecuteShell] stdout: ${execResult.stdout.take(500)}")
            Log.d(TAG, "[ExecuteShell] stderr: ${execResult.stderr.take(500)}")

            buildJsonObject {
                put("success", execResult.exitCode == 0)
                put("exitCode", execResult.exitCode)
                put("stdout", execResult.stdout)
                put("stderr", execResult.stderr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ExecuteShell] Exception!", e)
            buildJsonObject {
                put("success", false)
                put("error", "Shell execution error: ${e.message}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
    }

    suspend fun listContainerDirectory(
        sandboxId: String,
        containerPath: String,
    ): List<ContainerDirectoryEntry> {
        val normalizedPath = normalizeContainerPath(containerPath)
        listHostVisibleContainerDirectory(sandboxId, normalizedPath)?.let { return it }

        if (_containerState.value != ContainerStateEnum.Running) return emptyList()

        val escaped = normalizedPath.replace("'", "'\"'\"'")
        val script = """
            target='$escaped'
            if [ ! -d "${'$'}target" ]; then
              exit 0
            fi
            find "${'$'}target" -mindepth 1 -maxdepth 1 -exec stat -c '%n\t%F\t%s\t%Y' {} \;
        """.trimIndent()
        val result = execInContainer(
            sandboxId = sandboxId,
            command = listOf("sh", "-c", script),
            env = getToolEnvironment(),
        )
        if (result.exitCode != 0) return emptyList()

        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                val fullPath = parts[0]
                ContainerDirectoryEntry(
                    name = fullPath.substringAfterLast('/').ifBlank { fullPath },
                    path = fullPath,
                    isDirectory = parts[1].contains("directory", ignoreCase = true),
                    size = parts[2].toLongOrNull() ?: 0L,
                    modified = parts[3].toLongOrNull()?.times(1000L) ?: 0L,
                )
            }
            .sortedWith(compareBy<ContainerDirectoryEntry>({ !it.isDirectory }, { it.name.lowercase() }))
            .toList()
    }

    suspend fun readContainerTextFile(
        sandboxId: String,
        containerPath: String,
        maxBytes: Int = 128 * 1024,
    ): String? {
        val normalizedPath = normalizeContainerPath(containerPath)
        val mountedHostFile = SandboxEngine.resolveHostFileForContainerPath(context, sandboxId, normalizedPath)
        if (mountedHostFile != null && mountedHostFile.exists() && mountedHostFile.isFile) {
            return runCatching { mountedHostFile.readText() }.getOrNull()
        }
        if (_containerState.value != ContainerStateEnum.Running) return null

        val escaped = normalizedPath.replace("'", "'\"'\"'")
        val script = """
            target='$escaped'
            if [ ! -f "${'$'}target" ]; then
              exit 0
            fi
            head -c $maxBytes "${'$'}target"
        """.trimIndent()
        val result = execInContainer(
            sandboxId = sandboxId,
            command = listOf("sh", "-c", script),
            env = getToolEnvironment(),
        )
        return if (result.exitCode == 0) result.stdout else null
    }

    fun resolveHostFileForContainerPath(
        sandboxId: String,
        containerPath: String,
    ): File? {
        val normalizedPath = normalizeContainerPath(containerPath)
        SandboxEngine.resolveHostFileForContainerPath(context, sandboxId, normalizedPath)?.let { return it }

        return when {
            normalizedPath == "/bin" -> File(systemLayerDir, "bin")
            normalizedPath.startsWith("/bin/") -> {
                resolveChildPath(File(systemLayerDir, "bin"), normalizedPath.removePrefix("/bin/"))
            }

            normalizedPath == "/sbin" -> File(systemLayerDir, "sbin")
            normalizedPath.startsWith("/sbin/") -> {
                resolveChildPath(File(systemLayerDir, "sbin"), normalizedPath.removePrefix("/sbin/"))
            }

            normalizedPath == "/root" -> File(systemLayerDir, "root")
            normalizedPath.startsWith("/root/") -> {
                resolveChildPath(File(systemLayerDir, "root"), normalizedPath.removePrefix("/root/"))
            }

            normalizedPath == "/tmp" -> File(systemLayerDir, "tmp")
            normalizedPath.startsWith("/tmp/") -> {
                resolveChildPath(File(systemLayerDir, "tmp"), normalizedPath.removePrefix("/tmp/"))
            }

            normalizedPath == "/usr" -> File(systemLayerDir, "usr")
            normalizedPath.startsWith("/usr/") -> {
                resolveChildPath(File(systemLayerDir, "usr"), normalizedPath.removePrefix("/usr/"))
            }

            normalizedPath == "/usr/local" -> File(systemLayerDir, "usr/local")
            normalizedPath.startsWith("/usr/local/") -> {
                resolveChildPath(File(systemLayerDir, "usr/local"), normalizedPath.removePrefix("/usr/local/"))
            }

            normalizedPath == "/usr/lib" -> File(systemLayerDir, "usr/lib")
            normalizedPath.startsWith("/usr/lib/") -> {
                resolveChildPath(File(systemLayerDir, "usr/lib"), normalizedPath.removePrefix("/usr/lib/"))
            }

            normalizedPath == "/etc" -> File(systemLayerDir, "etc")
            normalizedPath.startsWith("/etc/") -> {
                resolveChildPath(File(systemLayerDir, "etc"), normalizedPath.removePrefix("/etc/"))
            }

            normalizedPath == "/lib" -> File(systemLayerDir, "lib")
            normalizedPath.startsWith("/lib/") -> {
                resolveChildPath(File(systemLayerDir, "lib"), normalizedPath.removePrefix("/lib/"))
            }

            normalizedPath == "/var" -> File(systemLayerDir, "var")
            normalizedPath.startsWith("/var/") -> {
                resolveChildPath(File(systemLayerDir, "var"), normalizedPath.removePrefix("/var/"))
            }

            normalizedPath == "/" -> systemLayerDir
            else -> resolveChildPath(rootfsDir, normalizedPath.removePrefix("/"))
        }
    }

    suspend fun exportContainerFileToCache(
        sandboxId: String,
        containerPath: String,
        maxBytes: Int = 8 * 1024 * 1024,
    ): File? = withContext(Dispatchers.IO) {
        val normalizedPath = normalizeContainerPath(containerPath)
        resolveHostFileForContainerPath(sandboxId, normalizedPath)
            ?.takeIf { it.exists() && it.isFile }
            ?.let { return@withContext it }

        if (_containerState.value != ContainerStateEnum.Running) return@withContext null

        val escaped = normalizedPath.replace("'", "'\"'\"'")
        val sizeScript = """
            target='$escaped'
            if [ ! -f "${'$'}target" ]; then
              exit 11
            fi
            wc -c < "${'$'}target" | tr -d '[:space:]'
        """.trimIndent()
        val sizeResult = execInContainer(
            sandboxId = sandboxId,
            command = listOf("sh", "-c", sizeScript),
            env = getToolEnvironment(),
        )
        if (sizeResult.exitCode != 0) return@withContext null

        val size = sizeResult.stdout.trim().toLongOrNull() ?: return@withContext null
        if (size <= 0 || size > maxBytes) return@withContext null

        val contentScript = """
            target='$escaped'
            if [ ! -f "${'$'}target" ]; then
              exit 11
            fi
            base64 "${'$'}target" | tr -d '\n'
        """.trimIndent()
        val contentResult = execInContainer(
            sandboxId = sandboxId,
            command = listOf("sh", "-c", contentScript),
            env = getToolEnvironment(),
            timeoutMs = 120_000,
        )
        if (contentResult.exitCode != 0) return@withContext null

        val bytes = runCatching { Base64.decode(contentResult.stdout.trim(), Base64.DEFAULT) }.getOrNull()
            ?: return@withContext null
        if (bytes.size.toLong() != size) return@withContext null

        val extension = normalizedPath.substringAfterLast('.', "")
            .lowercase()
            .ifBlank { "bin" }
        val exportDir = File(context.cacheDir, "container-image-preview/$sandboxId").apply { mkdirs() }
        val fileName = "preview_${System.currentTimeMillis()}.$extension"
        val output = exportDir.resolve(fileName)
        output.writeBytes(bytes)
        return@withContext output
    }

    suspend fun getInstalledPackages(): ContainerInventorySnapshot = withContext(Dispatchers.IO) {
        if (_containerState.value !is ContainerStateEnum.Running &&
            _containerState.value !is ContainerStateEnum.Stopped
        ) {
            return@withContext ContainerInventorySnapshot(
                containerSizeBytes = getContainerSize(),
                layoutVersion = inspectLayoutStatus().version
            )
        }

        val apkPackages = runCatching {
            val result = execInContainer(
                sandboxId = "system",
                command = listOf("sh", "-c", "apk info 2>/dev/null | sort -u"),
                timeoutMs = 30_000,
            )
            if (result.exitCode == 0) {
                result.stdout.lines().mapNotNull { it.trim().takeIf(String::isNotBlank) }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        val pythonPackages = runCatching {
            val result = execInContainer(
                sandboxId = "system",
                command = listOf("pip", "list", "--format=freeze"),
                timeoutMs = 30_000,
                env = getToolEnvironment(),
            )
            if (result.exitCode == 0) {
                result.stdout.lines().mapNotNull { line ->
                    line.substringBefore("==").trim().takeIf(String::isNotBlank)
                }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        ContainerInventorySnapshot(
            apkPackages = apkPackages,
            pythonPackages = pythonPackages,
            containerSizeBytes = getContainerSize(),
            layoutVersion = inspectLayoutStatus().version,
        )
    }

    /**
     * 获取容器大小（用于统计展示）
     */
    fun getContainerSize(): Long {
        return calculateDirectorySize(containerDir)
    }

    /**
     * 基础 PATH（Alpine Linux 默认）
     */
    private val basePath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * 获取已安装工具的环境变量（用于容器命令执行）
     * 避免循环依赖，直接检查 upper 层目录
     */
    private fun getToolEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        val toolPaths = mutableListOf<String>()
        val upperLocalDir = File(systemLayerDir, "usr/local")

        Log.d(TAG, "[ToolEnv] Checking tools in: ${upperLocalDir.absolutePath}")
        Log.d(TAG, "[ToolEnv] Directory exists: ${upperLocalDir.exists()}")

        // Node.js
        val nodeExists = File(upperLocalDir, "node/bin/node").exists()
        Log.d(TAG, "[ToolEnv] Node.js exists: $nodeExists")
        if (nodeExists) {
            toolPaths.add("/usr/local/node/bin")
            env["NODE_HOME"] = "/usr/local/node"
        }

        // Go
        val goExists = File(upperLocalDir, "go/bin/go").exists()
        Log.d(TAG, "[ToolEnv] Go exists: $goExists")
        if (goExists) {
            toolPaths.add("/usr/local/go/bin")
            env["GOROOT"] = "/usr/local/go"
        }

        // OpenJDK
        val jdkDirs = upperLocalDir.listFiles { f -> f.isDirectory && f.name.startsWith("jdk") }
        Log.d(TAG, "[ToolEnv] JDK dirs: ${jdkDirs?.map { it.name }}")
        if (jdkDirs?.isNotEmpty() == true) {
            toolPaths.add("/usr/local/${jdkDirs[0].name}/bin")
            env["JAVA_HOME"] = "/usr/local/${jdkDirs[0].name}"
        }

        // Rust
        val rustExists = File(upperLocalDir, "rust/bin/rustc").exists()
        Log.d(TAG, "[ToolEnv] Rust exists: $rustExists")
        if (rustExists) {
            toolPaths.add("/usr/local/rust/bin")
            env["RUST_HOME"] = "/usr/local/rust"
        }

        // Python (包括 pip)
        val pythonBinDir = when {
            File(upperLocalDir, "python3/bin/python3").exists() -> "python3/bin"
            File(upperLocalDir, "python/bin/python3").exists() -> "python/bin"
            File(upperLocalDir, "python/bin/python").exists() -> "python/bin"
            else -> null
        }
        Log.d(TAG, "[ToolEnv] Python bin dir: $pythonBinDir")
        if (pythonBinDir != null) {
            val pythonHome = "/usr/local/${pythonBinDir.substringBefore("/")}"
            toolPaths.add("$pythonHome/bin")
            env["PYTHON_HOME"] = pythonHome
            // 确保 pip 能找到
            env["PIP_CACHE_DIR"] = "/tmp/pip-cache"
            Log.d(TAG, "[ToolEnv] Python configured: PYTHON_HOME=$pythonHome, pip available")
        }

        // Node.js 模块路径（确保 npm 可用）
        val nodePath = listOf("/usr/local/lib/node_modules", "/usr/lib/node_modules")
        env["NODE_PATH"] = nodePath.joinToString(":")

        // 组合 PATH：工具路径 + 基础 PATH（确保基础命令可用）
        val finalPath = if (toolPaths.isNotEmpty()) {
            toolPaths.joinToString(":") + ":" + basePath
        } else {
            basePath
        }
        env["PATH"] = finalPath

        Log.d(TAG, "[ToolEnv] Generated PATH: $finalPath")
        Log.d(TAG, "[ToolEnv] Full env: $env")

        return env
    }

    // ==================== Private Methods ====================

    private suspend fun createGlobalContainer() = withContext(Dispatchers.IO) {
        val workDir = File(containerDir, "work").apply { mkdirs() }
        requiredSystemPaths().forEach { relative ->
            File(systemLayerDir, relative).mkdirs()
        }

        globalContainer = ContainerState(
            id = "global",
            workDir = workDir.absolutePath,
            systemDir = systemLayerDir.absolutePath
        )
    }

    private fun clearContainerRuntimeLayers(preserveRootfs: Boolean) {
        if (systemLayerDir.exists()) {
            systemLayerDir.deleteRecursively()
        }
        if (legacyUpperDir.exists()) {
            legacyUpperDir.deleteRecursively()
        }
        if (legacyWorkDir.exists()) {
            legacyWorkDir.deleteRecursively()
        }
        val workDir = File(containerDir, "work")
        if (workDir.exists()) {
            workDir.deleteRecursively()
        }
        if (containerConfigDir.exists()) {
            containerConfigDir.deleteRecursively()
        }
        layoutVersionFile.delete()
        if (!preserveRootfs && rootfsDir.exists()) {
            rootfsDir.deleteRecursively()
        }
    }

    private fun seedSystemLayer() {
        requiredSystemPaths().forEach { relative ->
            File(systemLayerDir, relative).mkdirs()
        }
        seedSystemPath("bin")
        seedSystemPath("sbin")
        seedSystemPath("etc")
        seedSystemPath("lib")
        seedSystemPath("root")
        seedSystemPath("tmp")
        seedSystemPath("usr")
        seedSystemPath("var")
    }

    private fun seedSystemPath(relativePath: String) {
        val source = File(rootfsDir, relativePath)
        val target = File(systemLayerDir, relativePath)
        if (!source.exists() || target.listFiles()?.isNotEmpty() == true) {
            return
        }
        copyPathPreservingLinks(source.toPath(), target.toPath())
    }

    private fun copyPathPreservingLinks(source: Path, target: Path) {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return
        when {
            Files.isSymbolicLink(source) -> {
                val linkTarget = Files.readSymbolicLink(source)
                target.parent?.let { Files.createDirectories(it) }
                runCatching { Files.deleteIfExists(target) }
                Files.createSymbolicLink(target, linkTarget)
            }

            Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) -> {
                Files.createDirectories(target)
                Files.list(source).use { stream ->
                    stream.forEach { child ->
                        copyPathPreservingLinks(child, target.resolve(child.fileName.toString()))
                    }
                }
            }

            else -> {
                target.parent?.let { Files.createDirectories(it) }
                Files.copy(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
            }
        }
    }

    private fun killTrackedBackgroundProcesses() {
        backgroundProcesses.values.forEach { record ->
            runCatching { record.process?.destroyForcibly() }
            runCatching { record.stdoutJob?.cancel() }
            runCatching { record.stderrJob?.cancel() }
        }
        backgroundProcesses.clear()
    }

    private suspend fun execInContainer(
        sandboxId: String,
        command: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        env: Map<String, String> = emptyMap()
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val container = globalContainer
                ?: return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Global container not created"
                )

            val prootCmd = buildProotCommand(sandboxId, command, env, container)

            // 执行命令
            val processBuilder = ProcessBuilder(prootCmd)
            processBuilder.redirectErrorStream(false) // 分离 stdout 和 stderr

            // 设置环境变量
            val processEnv = processBuilder.environment()
            setupProcessEnvironment(processEnv, env)

            Log.d(TAG, "[ExecInContainer] ========== Executing command ==========")
            Log.d(TAG, "[ExecInContainer] Command: $command")
            Log.d(TAG, "[ExecInContainer] Full prootCmd: $prootCmd")
            Log.d(TAG, "[ExecInContainer] Final env PATH: ${processEnv["PATH"]}")
            Log.d(TAG, "[ExecInContainer] Full env: $processEnv")

            val process = processBuilder.start()

            // 使用 Mutex 保护 currentProcess 赋值
            processMutex.withLock {
                currentProcess = process
            }

            // 并行读取 stdout 和 stderr
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stdout", e)
                }
            }

            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderrBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stderr", e)
                }
            }

            stdoutThread.start()
            stderrThread.start()

            // 等待完成或超时
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

            // 等待读取线程完成
            stdoutThread.join(1000)
            stderrThread.join(1000)

            if (!finished) {
                process.destroyForcibly()
                cleanupResidualProcesses()
                // 清理 currentProcess
                processMutex.withLock {
                    if (currentProcess == process) {
                        currentProcess = null
                    }
                }
                return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = stdoutBuilder.toString(),
                    stderr = stderrBuilder.toString() + "\nExecution timed out after ${timeoutMs}ms"
                )
            }

            val exitCode = process.exitValue()

            // 使用 Mutex 保护 currentProcess 清理
            processMutex.withLock {
                if (currentProcess == process) {
                    currentProcess = null
                }
            }

            val result = ExecutionResult(
                exitCode = exitCode,
                stdout = stdoutBuilder.toString().trim(),
                stderr = stderrBuilder.toString().trim()
            )

            Log.d(TAG, "[ExecInContainer] ========== Execution completed ==========")
            Log.d(TAG, "[ExecInContainer] exitCode=$exitCode")
            Log.d(TAG, "[ExecInContainer] stdout: ${result.stdout.take(500)}")
            Log.d(TAG, "[ExecInContainer] stderr: ${result.stderr.take(500)}")

            result
        } catch (e: Exception) {
            // 清理 currentProcess
            processMutex.withLock {
                currentProcess = null
            }
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Execution error: ${e.message}"
            )
        }
    }

    private fun cleanupResidualProcesses() {
        try {
            val process = ProcessBuilder("pkill", "-f", "proot").start()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            // 忽略清理错误
        }
    }

    /**
     * 清理系统层临时文件，但保留已安装的工具与用户工作区。
     */
    suspend fun cleanupUpperLayer(): Result<CleanupResult> = withContext(Dispatchers.IO) {
        try {
            val upperDir = systemLayerDir
            if (!upperDir.exists()) {
                return@withContext Result.success(CleanupResult(0, 0, emptyList()))
            }

            var totalFreedBytes = 0L
            var totalFilesCleaned = 0
            val cleanedPaths = mutableListOf<String>()

            // 1. 清理 /tmp 目录（临时文件）
            val tmpDir = File(upperDir, "tmp")
            if (tmpDir.exists()) {
                val size = calculateDirectorySize(tmpDir)
                tmpDir.listFiles()?.forEach { it.deleteRecursively() }
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/tmp")
            }

            // 2. 清理 /var/cache 目录
            val cacheDir = File(upperDir, "var/cache")
            if (cacheDir.exists()) {
                val size = calculateDirectorySize(cacheDir)
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/var/cache")
            }

            // 3. 清理 pip 缓存
            val pipCacheDir = File(upperDir, "root/.cache/pip")
            if (pipCacheDir.exists()) {
                val size = calculateDirectorySize(pipCacheDir)
                pipCacheDir.deleteRecursively()
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/root/.cache/pip")
            }

            // 4. 清理 npm 缓存（如果安装了 Node.js）
            val npmCacheDir = File(upperDir, "root/.npm")
            if (npmCacheDir.exists()) {
                val size = calculateDirectorySize(npmCacheDir)
                npmCacheDir.deleteRecursively()
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/root/.npm")
            }

            Log.d(TAG, "[CleanupUpperLayer] Freed ${totalFreedBytes / 1024 / 1024}MB in $totalFilesCleaned directories")

            Result.success(CleanupResult(
                freedBytes = totalFreedBytes,
                cleanedCount = totalFilesCleaned,
                cleanedPaths = cleanedPaths
            ))
        } catch (e: Exception) {
            Log.e(TAG, "[CleanupUpperLayer] Cleanup failed", e)
            Result.failure(e)
        }
    }

    /**
     * 清理结果数据类
     */
    data class CleanupResult(
        val freedBytes: Long,
        val cleanedCount: Int,
        val cleanedPaths: List<String>
    ) {
        fun formatFreedSize(): String {
            return when {
                freedBytes < 1024 -> "$freedBytes B"
                freedBytes < 1024 * 1024 -> String.format("%.2f KB", freedBytes / 1024.0)
                freedBytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", freedBytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", freedBytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }

    private suspend fun extractPRootBinary() = withContext(Dispatchers.IO) {
        val prootBinary = File(prootDir, "proot")
        if (!prootBinary.exists()) {
            val arch = getDeviceArchitecture()
            val assetPath = "proot/proot-$arch"
            Log.d(TAG, "Extracting PRoot binary for architecture: $arch from $assetPath")
            
            try {
                context.assets.open(assetPath).use { input ->
                    prootBinary.outputStream().use { output ->
                        val copied = input.copyTo(output)
                        Log.d(TAG, "Copied $copied bytes to ${prootBinary.absolutePath}")
                    }
                }
                // 使用 chmod 命令设置可执行权限（Android 10+ 兼容性更好）
                try {
                    val process = Runtime.getRuntime().exec("chmod 755 ${prootBinary.absolutePath}")
                    val exitCode = process.waitFor()
                    Log.d(TAG, "Set executable permission via chmod, exit code: $exitCode")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set executable permission via chmod, trying setExecutable", e)
                    prootBinary.setExecutable(true)
                }
                Log.d(TAG, "PRoot binary ready, file exists: ${prootBinary.exists()}, size: ${prootBinary.length()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract PRoot binary from $assetPath", e)
                throw RuntimeException("Failed to extract PRoot binary for $arch: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "PRoot binary already exists at ${prootBinary.absolutePath}")
        }
    }

    private suspend fun extractAlpineRootfs() = withContext(Dispatchers.IO) {
        // 检查是否需要更新 rootfs（版本控制）
        val needUpdate = checkRootfsNeedsUpdate()

        if (!rootfsDir.exists() || rootfsDir.listFiles()?.isEmpty() == true || needUpdate) {
            if (needUpdate && rootfsDir.exists()) {
                Log.d(TAG, "Rootfs version mismatch or update required, deleting old rootfs...")
                rootfsDir.deleteRecursively()
            }

            Log.d(TAG, "Extracting Alpine rootfs to $rootfsDir")
            try {
                val arch = getDeviceArchitecture()
                if (arch != "aarch64") {
                    throw IllegalStateException("Only arm64 rootfs is bundled. Detected architecture: $arch")
                }
                val rootfsAssetName = resolveRootfsAssetPath()

                Log.d(TAG, "Loading rootfs from assets: $rootfsAssetName for architecture: $arch")
                context.assets.open(rootfsAssetName).use { input ->
                    extractTarGz(input, rootfsDir)
                }

                // 写入版本文件
                writeRootfsVersion()

                val fileCount = rootfsDir.walkTopDown().count()
                Log.d(TAG, "Alpine rootfs extracted successfully, total files: $fileCount")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract Alpine rootfs", e)
                throw RuntimeException("Failed to extract Alpine rootfs: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Alpine rootfs already exists at $rootfsDir")
        }
    }

    /**
     * 检查 rootfs 是否需要更新
     * 通过对比本地版本文件和代码中的版本号
     */
    private fun checkRootfsNeedsUpdate(): Boolean {
        val versionFile = File(rootfsDir, ROOTFS_VERSION_FILE)
        if (!versionFile.exists()) {
            Log.d(TAG, "Rootfs version file not found, needs update")
            return true
        }

        return try {
            val localVersion = versionFile.readText().trim().toIntOrNull() ?: 0
            val needsUpdate = localVersion < ROOTFS_VERSION
            if (needsUpdate) {
                Log.d(TAG, "Rootfs version outdated: local=$localVersion, required=$ROOTFS_VERSION")
            }
            needsUpdate
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read rootfs version, assuming needs update", e)
            true
        }
    }

    /**
     * 写入 rootfs 版本文件
     */
    private fun writeRootfsVersion() {
        try {
            val versionFile = File(rootfsDir, ROOTFS_VERSION_FILE)
            versionFile.writeText(ROOTFS_VERSION.toString())
            Log.d(TAG, "Rootfs version file written: $ROOTFS_VERSION")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write rootfs version file", e)
        }
    }

    /**
     * 纯 Java 实现的 tar/tar.gz 解压（不依赖系统 tar 命令）
     * 自动检测是否为 gzip 格式
     */
    private fun extractTarGz(input: java.io.InputStream, targetDir: File) {
        targetDir.mkdirs()

        // 检测是否为 gzip 格式（前两个字节是 0x1f 0x8b）
        val magic = ByteArray(2)
        input.mark(2)
        val magicRead = input.read(magic)
        input.reset()

        val isGzip = magicRead == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        Log.d(TAG, "Archive format detected: ${if (isGzip) "gzip" else "plain tar"}")

        val tarInput = if (isGzip) {
            java.util.zip.GZIPInputStream(input)
        } else {
            input
        }

        tarInput.use { tarIn ->
            extractTar(tarIn, targetDir)
        }
    }

    /**
     * 解压纯 tar 格式
     */
    private fun extractTar(input: java.io.InputStream, targetDir: File) {
        val buffer = ByteArray(8192)

        while (true) {
            // 读取 tar 头部（512 字节）
            val header = ByteArray(512)
            var bytesRead = 0
            while (bytesRead < 512) {
                val read = input.read(header, bytesRead, 512 - bytesRead)
                if (read == -1) break
                bytesRead += read
            }

            if (bytesRead < 512) break // 文件结束

            // 检查是否为空块（tar 结尾）
            if (header.all { it == 0.toByte() }) {
                // 检查下一个块是否也是空的
                val nextHeader = ByteArray(512)
                var nextBytesRead = 0
                while (nextBytesRead < 512) {
                    val read = input.read(nextHeader, nextBytesRead, 512 - nextBytesRead)
                    if (read == -1) break
                    nextBytesRead += read
                }
                if (nextHeader.all { it == 0.toByte() }) break
            }

            // 解析文件名（前 100 字节）
            val nameBytes = header.copyOfRange(0, 100)
            val name = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
            if (name.isEmpty()) continue

            // 解析文件大小（第 124-135 字节，八进制）
            val sizeBytes = header.copyOfRange(124, 136)
            val sizeStr = String(sizeBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
            val fileSize = if (sizeStr.isEmpty()) 0 else sizeStr.toLong(8)

            // 解析文件类型（第 156 字节）
            val typeFlag = header[156].toInt()

            val file = File(targetDir, name)

            when (typeFlag) {
                '5'.code -> {
                    // 目录
                    file.mkdirs()
                }
                '0'.code, 0 -> {
                    // 普通文件
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        var remaining = fileSize
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    // 设置可执行权限（如果 mode 中有执行位）
                    val modeBytes = header.copyOfRange(100, 108)
                    val mode = String(modeBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
                    if (mode.isNotEmpty()) {
                        val modeInt = mode.toInt(8)
                        if ((modeInt and 0b001001001) != 0) {
                            file.setExecutable(true)
                        }
                    }
                }
                '2'.code -> {
                    // 符号链接 - 读取链接目标并创建真正的符号链接
                    // 符号链接目标在 tar 头部的 157-256 字节（linkname 字段）
                    val linkNameBytes = header.copyOfRange(157, 257)
                    val linkName = String(linkNameBytes, Charsets.UTF_8).trimEnd('\u0000')
                    if (linkName.isNotEmpty()) {
                        file.parentFile?.mkdirs()
                        try {
                            android.system.Os.symlink(linkName, file.absolutePath)
                            Log.d(TAG, "Created symlink: ${file.absolutePath} -> $linkName")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to create symlink: ${file.absolutePath} -> $linkName, falling back to empty file", e)
                            file.createNewFile()
                        }
                    } else {
                        // 如果链接名为空，创建空文件占位
                        file.parentFile?.mkdirs()
                        file.createNewFile()
                    }
                }
                else -> {
                    // 其他类型，跳过内容
                    var remaining = fileSize
                    while (remaining > 0) {
                        val toSkip = minOf(buffer.size.toLong(), remaining).toInt()
                        val skipped = input.read(buffer, 0, toSkip)
                        if (skipped == -1) break
                        remaining -= skipped
                    }
                }
            }

            // 跳过填充到 512 字节边界的字节
            val padding = (512 - (fileSize % 512)) % 512
            var remainingPadding = padding
            while (remainingPadding > 0) {
                val skipped = input.skip(remainingPadding.toLong())
                if (skipped == 0L) break
                remainingPadding -= skipped.toInt()
            }
        }
    }

    private fun getDeviceArchitecture(): String {
        return when (android.system.Os.uname().machine) {
            "aarch64" -> "aarch64"
            "armv7l", "armv8l" -> "armv7a"
            "x86_64" -> "x86_64"
            "i686", "i386" -> "i686"
            else -> "aarch64"
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    // ==================== Auto Management ====================

    /**
     * App 启动时恢复容器状态
     * 如果 upper 目录存在且有效，恢复到 Stopped 状态
     * 调用此方法前应先调用 checkInitializationStatus() 确认已初始化
     *
     * @return 是否成功恢复状态
     */
    suspend fun restoreState(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!checkInitializationStatus()) {
                Log.d(TAG, "[RestoreState] Rootfs not initialized, cannot restore state")
                return@withContext false
            }

            val layoutStatus = inspectLayoutStatus()
            val workDir = File(containerDir, "work").apply { mkdirs() }

            when {
                layoutStatus.compatible -> {
                    if (globalContainer == null) {
                        globalContainer = ContainerState(
                            id = "global",
                            workDir = workDir.absolutePath,
                            systemDir = systemLayerDir.absolutePath
                        )
                    }
                    _containerState.value = ContainerStateEnum.Stopped
                    Log.d(TAG, "[RestoreState] Restored compatible container layout to Stopped")
                    true
                }

                layoutStatus.needsRebuild -> {
                    globalContainer = null
                    _containerState.value = ContainerStateEnum.NeedsRebuild(
                        layoutStatus.reason ?: "Container rebuild required"
                    )
                    Log.w(TAG, "[RestoreState] Container layout requires rebuild: ${layoutStatus.reason}")
                    true
                }

                else -> {
                    globalContainer = null
                    _containerState.value = ContainerStateEnum.NotInitialized
                    Log.d(TAG, "[RestoreState] No existing runtime layer to restore")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RestoreState] Failed to restore container state", e)
            false
        }
    }

    /**
     * 启用容器自动管理
     * - 应用进入前台时自动启动容器（如果启用且处于 Stopped 状态）
     * - 应用进入后台时自动停止容器
     *
     * @param enableContainerRuntime 用户是否启用了容器运行时功能
     */
    fun enableAutoManagement(enableContainerRuntime: Boolean) {
        // 更新当前设置
        currentEnableContainerRuntime = enableContainerRuntime
        Log.d(TAG, "[AutoManage] enableAutoManagement called with enableContainerRuntime=$enableContainerRuntime, autoManagementEnabled=$autoManagementEnabled")

        // 如果已经添加过 observer，不再重复添加
        if (autoManagementEnabled) {
            Log.d(TAG, "[AutoManage] Auto management already enabled, just updating setting to $enableContainerRuntime")
            return
        }

        autoManagementEnabled = true
        Log.d(TAG, "[AutoManage] Enabling container auto management (enableContainerRuntime=$enableContainerRuntime)")

        // 监听应用生命周期
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                Log.d(TAG, "[AutoManage] Lifecycle event: $event, currentEnableContainerRuntime=$currentEnableContainerRuntime")
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        // 应用进入前台
                        Log.d(TAG, "[AutoManage] App came to foreground, checking if should auto-init...")
                        Log.d(TAG, "[AutoManage] currentEnableContainerRuntime=$currentEnableContainerRuntime, state=${_containerState.value}")
                        if (currentEnableContainerRuntime) {
                            when (_containerState.value) {
                                is ContainerStateEnum.Stopped -> {
                                    Log.d(TAG, "[AutoManage] Auto-starting container from Stopped state")
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val result = start()
                                        Log.d(TAG, "[AutoManage] Auto-start result: $result")
                                    }
                                }
                                is ContainerStateEnum.NotInitialized -> {
                                    Log.d(TAG, "[AutoManage] Container not initialized, auto-initializing...")
                                    GlobalScope.launch(Dispatchers.IO) {
                                        Log.d(TAG, "[AutoManage] Calling initialize()...")
                                        val result = initialize()
                                        Log.d(TAG, "[AutoManage] Auto-initialize result: $result")
                                    }
                                }
                                else -> {
                                    Log.d(TAG, "Container state: ${_containerState.value}, no action needed")
                                }
                            }
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        // 应用进入后台
                        Log.d(TAG, "App went to background")
                        if (false && currentEnableContainerRuntime && _containerState.value == ContainerStateEnum.Running) {
                            Log.d(TAG, "Auto-stopping container")
                            GlobalScope.launch(Dispatchers.IO) {
                                stop()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        lifecycle.addObserver(observer)
        autoManagementObserver = observer
    }

    // ==================== Background Process Management ====================

    /**
     * 后台执行命令（非阻塞）
     *
     * @param sandboxId 沙箱ID
     * @param command 要执行的命令列表
     * @param processId 进程ID
     * @param stdoutFile 标准输出日志文件
     * @param stderrFile 标准错误日志文件
     * @param env 环境变量
     * @return ExecutionResult
     */
    suspend fun execInBackground(
        sandboxId: String,
        command: List<String>,
        processId: String,
        stdoutFile: File,
        stderrFile: File,
        env: Map<String, String> = emptyMap()
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val container = globalContainer ?: return@withContext ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Global container not created"
            )

            // 构建 PRoot 命令
            val prootCmd = buildProotCommand(sandboxId, command, env, container)

            Log.d(TAG, "[ExecInBackground] Starting process: $processId")
            Log.d(TAG, "[ExecInBackground] Command: ${command.joinToString(" ")}")

            // 创建进程
            val processBuilder = ProcessBuilder(prootCmd)
            processBuilder.redirectErrorStream(false)

            // 设置环境变量
            val processEnv = processBuilder.environment()
            setupProcessEnvironment(processEnv, env)

            val process = processBuilder.start()

            // 获取进程PID
            val pid = getProcessPid(process)

            // 启动异步线程读取输出并写入文件（带大小限制）
            val maxLogSize = BackgroundProcessManager.MAX_LOG_FILE_SIZE.toLong()

            val stdoutJob = GlobalScope.launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        stdoutFile.bufferedWriter().use { writer ->
                            var line: String?
                            var currentSize = 0L
                            while (reader.readLine().also { line = it } != null) {
                                val lineBytes = line!!.toByteArray().size + 1 // +1 for newline
                                currentSize += lineBytes

                                // 检查日志大小限制
                                if (currentSize > maxLogSize) {
                                    writer.write("[Log truncated: exceeded max size ${maxLogSize / 1024 / 1024}MB]")
                                    writer.newLine()
                                    writer.flush()
                                    Log.w(TAG, "[ExecInBackground] stdout log truncated for $processId (exceeded ${maxLogSize} bytes)")
                                    break
                                }

                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[ExecInBackground] Error reading stdout for $processId", e)
                }
            }

            val stderrJob = GlobalScope.launch(Dispatchers.IO) {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        stderrFile.bufferedWriter().use { writer ->
                            var line: String?
                            var currentSize = 0L
                            while (reader.readLine().also { line = it } != null) {
                                val lineBytes = line!!.toByteArray().size + 1 // +1 for newline
                                currentSize += lineBytes

                                // 检查日志大小限制
                                if (currentSize > maxLogSize) {
                                    writer.write("[Log truncated: exceeded max size ${maxLogSize / 1024 / 1024}MB]")
                                    writer.newLine()
                                    writer.flush()
                                    Log.w(TAG, "[ExecInBackground] stderr log truncated for $processId (exceeded ${maxLogSize} bytes)")
                                    break
                                }

                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[ExecInBackground] Error reading stderr for $processId", e)
                }
            }

            // 存储后台进程记录
            val record = BackgroundProcessRecord(
                processId = processId,
                process = process,
                stdoutJob = stdoutJob,
                stderrJob = stderrJob,
                createdAt = System.currentTimeMillis()
            )
            backgroundProcesses[processId] = record

            Log.d(TAG, "[ExecInBackground] Process started: $processId, PID: $pid")

            ExecutionResult(
                exitCode = 0,
                stdout = "Process started with PID: $pid",
                stderr = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "[ExecInBackground] Failed to start process: $processId", e)
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to start background process: ${e.message}"
            )
        }
    }

    /**
     * 终止后台进程
     */
    suspend fun killBackgroundProcess(processId: String): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val record = backgroundProcesses[processId]
                ?: return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Background process not found: $processId"
                )

            Log.d(TAG, "[KillBackgroundProcess] Killing process: $processId")

            // 销毁进程
            record.process?.destroyForcibly()

            // 等待进程结束
            record.process?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)

            // 取消输出读取Job
            record.stdoutJob?.cancel()
            record.stderrJob?.cancel()

            // 移除记录
            backgroundProcesses.remove(processId)

            Log.d(TAG, "[KillBackgroundProcess] Process killed: $processId")

            ExecutionResult(
                exitCode = 0,
                stdout = "Process killed successfully",
                stderr = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "[KillBackgroundProcess] Error killing process: $processId", e)
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Error: ${e.message}"
            )
        }
    }

    /**
     * 检查后台进程是否还在运行
     */
    fun isBackgroundProcessAlive(processId: String): Boolean {
        val record = backgroundProcesses[processId] ?: return false
        return record.process?.isAlive == true
    }

    /**
     * 获取后台进程的退出码
     */
    fun getBackgroundProcessExitCode(processId: String): Int? {
        val record = backgroundProcesses[processId] ?: return null
        val process = record.process ?: return null
        return if (!process.isAlive) {
            process.exitValue()
        } else {
            null
        }
    }

    /**
     * 清理已结束的后台进程
     */
    suspend fun cleanupFinishedBackgroundProcesses() = withContext(Dispatchers.IO) {
        val finished = mutableListOf<String>()

        backgroundProcesses.keys.forEach { processId ->
            val record = backgroundProcesses[processId]
            if (record?.process?.isAlive == false) {
                finished.add(processId)
                record.stdoutJob?.cancel()
                record.stderrJob?.cancel()
            }
        }

        finished.forEach { backgroundProcesses.remove(it) }

        if (finished.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${finished.size} finished background processes")
        }
    }

    /**
     * 获取Java进程的PID
     */
    private fun getProcessPid(process: Process): Int? {
        return try {
            // 通过反射获取PID（不同Android版本可能不同）
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get process PID", e)
            null
        }
    }

    /**
     * 设置进程环境变量
     */
    private fun setupProcessEnvironment(
        processEnv: MutableMap<String, String>,
        customEnv: Map<String, String>
    ) {
        val prootTmpDir = File(context.noBackupFilesDir, "proot-tmp").apply {
            mkdirs()
        }

        processEnv["HOME"] = "/root"
        processEnv["TMPDIR"] = "/tmp"
        processEnv["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
        processEnv["PREFIX"] = "/usr"
        processEnv["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        // termux-exec 会干预 execve，和 PRoot 叠加时可能导致 /bin/sh 无法启动。
        processEnv.remove("LD_PRELOAD")

        // 合并自定义环境变量
        processEnv.putAll(customEnv)
    }

    /**
     * 构建PRoot命令
     */
    private fun buildProotCommand(
        sandboxId: String,
        command: List<String>,
        env: Map<String, String>,
        container: ContainerState
    ): List<String> {
        val prootBinary = File(prootDir, "proot").absolutePath
        val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")
        val deliveryDir = SandboxEngine.getDeliveryDir(context, sandboxId)
        val skillLibraryDir = SandboxEngine.getSkillLibraryDir(context)

        return buildList {
            add(prootBinary)
            add("-0")

            // 绑定挂载系统目录（必要）
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")

            // 绑定挂载对话的沙箱目录到 /workspace
            add("-b")
            add("${sandboxDir.absolutePath}:/workspace")
            add("-b")
            add("${deliveryDir.absolutePath}:/delivery")
            add("-b")
            add("${skillLibraryDir.absolutePath}:/skills")

            // 覆盖系统可写层，确保 apk/pip/manual installs 都落在持久化系统层。
            add("-b")
            add("${container.systemDir}/bin:/bin")
            add("-b")
            add("${container.systemDir}/etc:/etc")
            add("-b")
            add("${container.systemDir}/lib:/lib")
            add("-b")
            add("${container.systemDir}/root:/root")
            add("-b")
            add("${container.systemDir}/sbin:/sbin")
            add("-b")
            add("${container.systemDir}/tmp:/tmp")
            add("-b")
            add("${container.systemDir}/usr:/usr")
            add("-b")
            add("${container.systemDir}/var:/var")

            // 根目录使用基础 rootfs（只读）- 必须在 -b 之后
            add("-R")
            add(rootfsDir.absolutePath)

            // 设置工作目录
            add("-w")
            add("/workspace")

            // 启用符号链接修复
            add("--link2symlink")

            // 执行的命令
            addAll(command)
        }
    }

    private fun normalizeContainerPath(path: String): String {
        if (path.isBlank()) return "/"
        val normalized = path.replace('\\', '/').replace(Regex("/+"), "/").trimEnd('/')
        return if (normalized.startsWith("/")) normalized.ifBlank { "/" } else "/$normalized"
    }

    private fun listHostVisibleContainerDirectory(
        sandboxId: String,
        normalizedPath: String,
    ): List<ContainerDirectoryEntry>? {
        return when (normalizedPath) {
            "/" -> mergeDirectoryEntries(
                baseDir = rootfsDir,
                baseContainerPath = "/",
                overrides = mapOf(
                    "bin" to File(systemLayerDir, "bin"),
                    "etc" to File(systemLayerDir, "etc"),
                    "lib" to File(systemLayerDir, "lib"),
                    "sbin" to File(systemLayerDir, "sbin"),
                    "tmp" to File(systemLayerDir, "tmp"),
                    "usr" to File(systemLayerDir, "usr"),
                    "var" to File(systemLayerDir, "var"),
                    "workspace" to SandboxEngine.getSandboxDir(context, sandboxId),
                    "delivery" to SandboxEngine.getDeliveryDir(context, sandboxId),
                    "skills" to File(context.filesDir, FileFolders.SKILLS),
                    "root" to File(systemLayerDir, "root"),
                ),
            )

            "/usr" -> mergeDirectoryEntries(
                baseDir = File(systemLayerDir, "usr"),
                baseContainerPath = "/usr",
            )

            else -> {
                val hostDir = resolveHostFileForContainerPath(sandboxId, normalizedPath)
                    ?.takeIf { it.exists() && it.isDirectory }
                    ?: return null
                hostDir.listFiles()
                    ?.map { file ->
                        val childPath = buildContainerChildPath(normalizedPath, file.name)
                        ContainerDirectoryEntry(
                            name = file.name,
                            path = childPath,
                            isDirectory = file.isDirectory,
                            size = if (file.isFile) file.length() else 0L,
                            modified = file.lastModified(),
                        )
                    }
                    ?.sortedWith(compareBy<ContainerDirectoryEntry>({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
            }
        }
    }

    private fun mergeDirectoryEntries(
        baseDir: File,
        baseContainerPath: String,
        overrides: Map<String, File> = emptyMap(),
    ): List<ContainerDirectoryEntry>? {
        if (!baseDir.exists() || !baseDir.isDirectory) return null

        val entries = linkedMapOf<String, File>()
        baseDir.listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { entries[it.name] = it }

        overrides.forEach { (name, file) ->
            if (file.exists()) {
                entries[name] = file
            }
        }

        return entries.entries
            .map { (name, file) ->
                ContainerDirectoryEntry(
                    name = name,
                    path = buildContainerChildPath(baseContainerPath, name),
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    modified = file.lastModified(),
                )
            }
            .sortedWith(compareBy<ContainerDirectoryEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun resolveChildPath(baseDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return baseDir
        val target = File(baseDir, relativePath)
        val canonicalBase = runCatching { baseDir.canonicalFile }.getOrNull() ?: return null
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return null
        return canonicalTarget.takeIf {
            it.path == canonicalBase.path || it.path.startsWith("${canonicalBase.path}${File.separator}")
        }
    }

    private fun buildContainerChildPath(parent: String, name: String): String {
        val normalizedParent = normalizeContainerPath(parent)
        return when (normalizedParent) {
            "/" -> "/$name"
            else -> "$normalizedParent/$name"
        }
    }
}

// ==================== Data Classes ====================

data class ContainerState(
    val id: String,
    val workDir: String,
    val systemDir: String
)

data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * 容器状态枚举（4状态）
 */
sealed class ContainerStateEnum {
    object NotInitialized : ContainerStateEnum()
    data class Initializing(val progress: Float) : ContainerStateEnum()
    object Running : ContainerStateEnum()
    object Stopped : ContainerStateEnum()
    data class NeedsRebuild(val reason: String) : ContainerStateEnum()
    data class Error(val message: String) : ContainerStateEnum()
}

/**
 * 后台进程记录（内部使用）
 *
 * @property processId 进程ID
 * @property process Java Process对象
 * @property stdoutJob stdout读取Job
 * @property stderrJob stderr读取Job
 * @property createdAt 创建时间戳
 */
data class BackgroundProcessRecord(
    val processId: String,
    val process: Process?,
    val stdoutJob: kotlinx.coroutines.Job?,
    val stderrJob: kotlinx.coroutines.Job?,
    val createdAt: Long
)
