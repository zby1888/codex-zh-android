package io.github.zby1888.codexzh

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class TerminalCell(val text:String,val width:Int,val foreground:Int?,val background:Int?,val flags:Int)
internal data class TerminalFrame(
    val rows:Int,val columns:Int,val cursorRow:Int,val cursorColumn:Int,
    val cursorVisible:Boolean,val cells:List<List<TerminalCell>>,
    val scrollback:List<List<TerminalCell>> = emptyList()
) {
    companion object {
        fun blank(rows:Int,columns:Int):TerminalFrame {
            val cell=TerminalCell(" ",1,null,null,0)
            return TerminalFrame(rows,columns,0,0,false,List(rows){List(columns){cell}})
        }
    }
}

internal class NativePtyBridge {
    init { System.loadLibrary("codex_terminal") }
    external fun nativeCreate(shellPath:String,arguments:Array<String>,workingDirectory:String,environmentKeys:Array<String>,environmentValues:Array<String>,rows:Int,columns:Int):Long
    external fun nativeRead(handle:Long,buffer:ByteArray,timeoutMillis:Int):Int
    external fun nativeWrite(handle:Long,bytes:ByteArray):Int
    external fun nativeResize(handle:Long,rows:Int,columns:Int):Boolean
    external fun nativeSnapshot(handle:Long):ByteArray
    external fun nativeScrollbackSnapshot(handle:Long):ByteArray
    external fun nativeExitCode(handle:Long):Int
    external fun nativeClose(handle:Long)
}

internal class PtyTerminalSession(private val paths:RuntimePaths) {
    private val bridge=NativePtyBridge()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val cleanupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val nativeMutex=Mutex()
    @Volatile private var handle=0L
    private var readerJob:Job?=null
    @Volatile private var scrollbackCache:List<List<TerminalCell>> = emptyList()

    var frame by mutableStateOf(TerminalFrame.blank(24,80)); private set
    var isRunning by mutableStateOf(false); private set
    var isRootSession by mutableStateOf(false); private set
    var message by mutableStateOf("终端尚未启动"); private set
    var workingDirectory by mutableStateOf<String?>(null); private set
    var rows:Int=24; private set
    var columns:Int=80; private set

    suspend fun start(workingDirectory:String,rows:Int=this.rows,columns:Int=this.columns,rootSuPath:String?=null) {
        CrashLogManager.recordEvent(paths.appFiles, "terminal.start.requested")
        check(!isRunning && handle==0L){"终端会话已经启动"}
        val cwd=File(workingDirectory).canonicalFile
        check(cwd.isDirectory){"项目目录不存在或不是文件夹："+cwd.name}
        require(rows in 8..80 && columns in 20..160){"终端窗口大小不受支持"}
        val environment=paths.environment().toMutableMap().apply {
            put("PWD",cwd.absolutePath)
            put("TERM","xterm-256color")
            put("COLORTERM","truecolor")
            put("PS1","\\u@codex:\\w\\$ ")
            put("HISTFILE",File(paths.home,".bash_history").absolutePath)
        }
        val isRoot = !rootSuPath.isNullOrBlank()
        val executable: String
        val arguments: Array<String>
        if (!isRoot) {
            val launchCommand = paths.commandFor(paths.bash, listOf("-i"))
            executable = launchCommand.first()
            arguments = launchCommand.toTypedArray()
            if (launchCommand.first() != paths.bash.absolutePath) {
                environment["TERMUX_EXEC__PROC_SELF_EXE"] = paths.bash.absolutePath
            }
        } else {
            val su = File(rootSuPath!!)
            check(su.isFile && su.canExecute()) { "Root 管理器提供的 su 路径已不可用，请重新申请授权" }
            // Keep root shell state separate from the normal app user and Codex credentials.
            val rootHome = File(paths.home, ".root-shell")
            check(rootHome.isDirectory || rootHome.mkdirs()) { "无法创建独立 Root Shell 主目录" }
            environment["HOME"] = rootHome.absolutePath
            environment["TERMUX__HOME"] = rootHome.absolutePath
            environment["HISTFILE"] = File(rootHome, ".bash_history").absolutePath
            environment.remove("CODEX_HOME")
            // Keep the system su binary away from the private runtime loader paths.
            // The private library path is restored only inside the root-owned Bash command.
            environment.remove("LD_PRELOAD")
            val privateLibraryPath = environment.remove("LD_LIBRARY_PATH").orEmpty()
            val privatePath = environment["PATH"].orEmpty()
            environment["PATH"] = "/system/bin:/system/xbin:/sbin"
            environment["SHELL"] = "/system/bin/sh"
            val exportedKeys = listOf(
                "PREFIX", "TERMUX_PREFIX", "TERMUX__PREFIX", "TERMUX__ROOTFS",
                "TERMUX__HOME", "TERMUX_ANDROID_HOME", "TERMUX_APP__PACKAGE_NAME",
                "HOME", "HISTFILE", "TMPDIR", "TERM", "LANG", "LC_ALL"
            )
            val rootCommand = buildString {
                append("cd -- ").append(shellQuote(cwd.absolutePath)).append(" || exit 126; ")
                exportedKeys.forEach { key ->
                    environment[key]?.let { value ->
                        append("export ").append(key).append('=').append(shellQuote(value)).append("; ")
                    }
                }
                append("export PATH=").append(shellQuote(privatePath)).append("; ")
                append("export SHELL=").append(shellQuote(paths.bash.absolutePath)).append("; ")
                append("unset CODEX_HOME LD_PRELOAD; export LD_LIBRARY_PATH=")
                    .append(shellQuote(privateLibraryPath)).append("; ")
                append("export PS1='root@codex:\\w# '; exec ")
                    .append(paths.commandFor(paths.bash, listOf("-i")).joinToString(" ", transform = ::shellQuote))
            }
            executable = su.absolutePath
            // This is a fixed su operation; paths are shell-quoted and no user command is interpolated.
            arguments = arrayOf(executable, "-c", rootCommand)
        }

        scrollbackCache=emptyList()
        val newHandle=try {
            nativeMutex.withLock {
                CrashLogManager.recordEvent(paths.appFiles, "terminal.native_create.begin")
                withContext(Dispatchers.IO) {
                    bridge.nativeCreate(executable,arguments,cwd.absolutePath,
                        environment.keys.toTypedArray(),environment.values.toTypedArray(),rows,columns)
                }
            }
        } catch (error: Throwable) {
            CrashLogManager.recordEvent(paths.appFiles, "terminal.native_create.threw")
            throw error
        }
        CrashLogManager.recordEvent(paths.appFiles, if (newHandle == 0L) "terminal.native_create.zero_handle" else "terminal.native_create.returned")
        check(newHandle!=0L){"无法创建交互式 PTY"}
        this.rows=rows; this.columns=columns; handle=newHandle
        this.workingDirectory=cwd.absolutePath
        try {
            frame=decodeSnapshot(nativeMutex.withLock {
                withContext(Dispatchers.IO) { bridge.nativeSnapshot(newHandle) }
            })
        } catch (error: Exception) {
            handle=0L
            nativeMutex.withLock { runCatching { bridge.nativeClose(newHandle) } }
            throw error
        }
        isRootSession=isRoot
        isRunning=true
        message=if(isRoot) "已启动独立 Root Bash · "+cwd.name else "已启动应用私有 Bash · "+cwd.name
        readerJob=scope.launch {
            CrashLogManager.recordEvent(paths.appFiles, "terminal.reader.started")
            readLoop(newHandle)
        }
    }

    private fun shellQuote(value:String):String = "'" + value.replace("'","'\\''") + "'"

    fun sendLine(value:String)=sendInput(value+"\r")
    fun sendInput(value:String) {
        val current=handle
        if(current==0L || !isRunning || value.isEmpty()) return
        val bytes=value.toByteArray(Charsets.UTF_8)
        scope.launch {
            try {
                val written=nativeMutex.withLock {
                    if(handle!=current) null else bridge.nativeWrite(current,bytes)
                } ?: return@launch
                if(handle==current && written!=bytes.size) publishError(current,"终端输入没有完整写入")
            } catch(error:Exception) { publishError(current,error.message?:"终端写入失败") }
        }
    }

    fun resize(rows:Int,columns:Int) {
        val safeRows=rows.coerceIn(8,80); val safeColumns=columns.coerceIn(20,160)
        if(this.rows==safeRows && this.columns==safeColumns) return
        this.rows=safeRows; this.columns=safeColumns
        val current=handle
        if(current==0L) { frame=TerminalFrame.blank(safeRows,safeColumns); return }
        scope.launch {
            val next=nativeMutex.withLock {
                if(handle!=current || !bridge.nativeResize(current,safeRows,safeColumns)) null
                else runCatching { decodeSnapshot(bridge.nativeSnapshot(current)) }.getOrNull()
            }
            if(next!=null) withContext(Dispatchers.Main.immediate){if(handle==current) frame=next}
        }
    }

    /** Fetch libvterm's bounded history only when the user starts scrolling. */
    fun loadScrollback() {
        val activeHandle=handle
        if(activeHandle==0L)return
        scope.launch {
            try {
                val loaded:List<List<TerminalCell>>?=nativeMutex.withLock {
                    if(handle!=activeHandle) null
                    else decodeScrollback(bridge.nativeScrollbackSnapshot(activeHandle))
                }
                if(loaded!=null) {
                    scrollbackCache=loaded
                    withContext(Dispatchers.Main.immediate) {
                        if(handle==activeHandle) frame=frame.copy(scrollback=loaded)
                    }
                }
            } catch(error:Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if(handle==activeHandle) message=error.message?:"读取终端滚动缓冲失败"
                }
            }
        }
    }

    fun changeDirectory(path:String) {
        val cwd=runCatching{File(path).canonicalPath}.getOrNull()?:return
        if(!File(cwd).isDirectory || !isRunning) return
        sendLine("cd -- '"+cwd.replace("'","'\\''")+"'")
        workingDirectory=cwd
        message="已发送切换目录指令："+File(cwd).name
    }

    fun stop() {
        val current=handle
        if(current==0L)return
        CrashLogManager.recordEvent(paths.appFiles, "terminal.stop.requested")
        handle=0L
        isRootSession=false
        val reader=readerJob
        readerJob=null
        reader?.cancel()
        isRunning=false
        message="正在关闭终端会话…"
        cleanupScope.launch {
            reader?.join()
            nativeMutex.withLock { runCatching { bridge.nativeClose(current) } }
        }
    }
    fun closeInBackground(){stop();scope.cancel()}

    private suspend fun readLoop(activeHandle:Long) {
        val buffer=ByteArray(16*1024)
        try {
            while(currentCoroutineContext().isActive && handle==activeHandle) {
                val result=nativeMutex.withLock {
                    val count=bridge.nativeRead(activeHandle,buffer,120)
                    val snapshot=if(count>0) runCatching { decodeSnapshot(bridge.nativeSnapshot(activeHandle)) }.getOrNull() else null
                    val code=if(count<0 || count==0) bridge.nativeExitCode(activeHandle) else NATIVE_RUNNING
                    val finalSnapshot=if(code!=NATIVE_RUNNING) {
                        runCatching { decodeSnapshot(bridge.nativeSnapshot(activeHandle)) }.getOrNull()
                    } else null
                    Triple(count,code,snapshot?:finalSnapshot)
                }
                val (count,code,nextFrame)=result
                if(count>0 && nextFrame!=null) {
                    withContext(Dispatchers.Main.immediate){if(handle==activeHandle) frame=nextFrame}
                }
                if(count<0) {
                    finish(activeHandle,if(code==NATIVE_RUNNING) "PTY 输入流已关闭" else "Shell 已退出，退出码："+code,nextFrame)
                    return
                }
                if(code!=NATIVE_RUNNING) {
                    finish(activeHandle,"Shell 已退出，退出码："+code,nextFrame)
                    return
                }
            }
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){
            CrashLogManager.recordEvent(paths.appFiles, "terminal.read_loop.exception")
            finish(activeHandle,error.message?:"终端读取失败")
        }
    }

    private suspend fun finish(activeHandle:Long,status:String,finalFrame:TerminalFrame?=null) {
        CrashLogManager.recordEvent(paths.appFiles, "terminal.finish")
        withContext(Dispatchers.Main.immediate) {
            if(handle==activeHandle) {
                handle=0L; isRunning=false; isRootSession=false; message=status
                if(finalFrame!=null) frame=finalFrame
            }
        }
        nativeMutex.withLock { runCatching { bridge.nativeClose(activeHandle) } }
    }
    private suspend fun publishError(activeHandle:Long,detail:String) {
        withContext(Dispatchers.Main.immediate){if(handle==activeHandle)message=detail}
    }

    private fun decodeSnapshot(bytes:ByteArray):TerminalFrame {
        val input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining()>=28){"终端画面数据不完整"}
        require(input.int==SNAPSHOT_MAGIC){"终端画面标记不正确"}
        require(input.int==SNAPSHOT_VERSION){"终端画面版本暂不支持"}
        val rowCount=input.int; val columnCount=input.int
        val cursorRow=input.int; val cursorColumn=input.int; val cursorVisible=input.int!=0
        require(rowCount in 1..80 && columnCount in 1..160){"终端窗口尺寸不合法"}
        require(input.remaining()==rowCount*columnCount*40){"终端画面数据长度不正确"}
        val cells=List(rowCount){
            List(columnCount){
                val codePoints=IntArray(6){input.int}
                val width=input.int; val flags=input.int; val fg=input.int; val bg=input.int
                val text=buildString{codePoints.forEach{cp->if(cp>0 && Character.isValidCodePoint(cp))appendCodePoint(cp)}}
                TerminalCell(text,width.coerceIn(0,2),fg.takeUnless{it==DEFAULT_COLOR},bg.takeUnless{it==DEFAULT_COLOR},flags)
            }
        }
        return TerminalFrame(rowCount,columnCount,cursorRow.coerceIn(0,rowCount-1),
            cursorColumn.coerceIn(0,columnCount-1),cursorVisible,cells,scrollbackCache)
    }

    private fun decodeScrollback(bytes:ByteArray):List<List<TerminalCell>> {
        val input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining()>=16){"终端滚动缓冲数据不完整"}
        require(input.int==SNAPSHOT_MAGIC){"终端滚动缓冲标记不正确"}
        require(input.int==SCROLLBACK_VERSION){"终端滚动缓冲版本暂不支持"}
        val lineCount=input.int
        val columnCount=input.int
        require(lineCount in 0..MAX_SCROLLBACK_LINES && columnCount in 20..160) {
            "终端滚动缓冲尺寸不合法"
        }
        require(input.remaining()==lineCount*columnCount*40){"终端滚动缓冲数据长度不正确"}
        return List(lineCount) {
            List(columnCount) {
                val codePoints=IntArray(6){input.int}
                val width=input.int
                val flags=input.int
                val fg=input.int
                val bg=input.int
                val text=buildString {
                    codePoints.forEach { cp ->
                        if(cp>0 && Character.isValidCodePoint(cp))appendCodePoint(cp)
                    }
                }
                TerminalCell(text,width.coerceIn(0,2),
                    fg.takeUnless{it==DEFAULT_COLOR},bg.takeUnless{it==DEFAULT_COLOR},flags)
            }
        }
    }

    private companion object {
        const val SNAPSHOT_MAGIC=0x435A5452
        const val SNAPSHOT_VERSION=1
        const val SCROLLBACK_VERSION=1
        const val MAX_SCROLLBACK_LINES=500
        const val DEFAULT_COLOR=-1
        const val NATIVE_RUNNING=Int.MIN_VALUE
    }
}
