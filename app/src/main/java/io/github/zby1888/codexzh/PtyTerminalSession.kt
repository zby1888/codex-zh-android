package io.github.zby1888.codexzh

import kotlinx.coroutines.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class TerminalCell(val text:String,val width:Int,val foreground:Int?,val background:Int?,val flags:Int)
internal data class TerminalFrame(
    val rows:Int,val columns:Int,val cursorRow:Int,val cursorColumn:Int,
    val cursorVisible:Boolean,val cells:List<List<TerminalCell>>
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
    external fun nativeCreate(shellPath:String,workingDirectory:String,environmentKeys:Array<String>,environmentValues:Array<String>,rows:Int,columns:Int):Long
    external fun nativeRead(handle:Long,buffer:ByteArray,timeoutMillis:Int):Int
    external fun nativeWrite(handle:Long,bytes:ByteArray):Int
    external fun nativeResize(handle:Long,rows:Int,columns:Int):Boolean
    external fun nativeSnapshot(handle:Long):ByteArray
    external fun nativeExitCode(handle:Long):Int
    external fun nativeClose(handle:Long)
}

internal class PtyTerminalSession(private val paths:RuntimePaths) {
    private val bridge=NativePtyBridge()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var handle=0L
    private var readerJob:Job?=null

    var frame by mutableStateOf(TerminalFrame.blank(24,80)); private set
    var isRunning by mutableStateOf(false); private set
    var message by mutableStateOf("终端尚未启动"); private set
    var workingDirectory by mutableStateOf<String?>(null); private set
    var rows:Int=24; private set
    var columns:Int=80; private set

    suspend fun start(workingDirectory:String,rows:Int=this.rows,columns:Int=this.columns) {
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
        val newHandle=withContext(Dispatchers.IO) {
            bridge.nativeCreate(paths.bash.absolutePath,cwd.absolutePath,
                environment.keys.toTypedArray(),environment.values.toTypedArray(),rows,columns)
        }
        check(newHandle!=0L){"无法创建交互式 PTY"}
        this.rows=rows; this.columns=columns; handle=newHandle
        this.workingDirectory=cwd.absolutePath
        frame=decodeSnapshot(withContext(Dispatchers.IO){bridge.nativeSnapshot(newHandle)})
        isRunning=true
        message="已启动应用私有 Bash · "+cwd.name
        readerJob=scope.launch{readLoop(newHandle)}
    }

    fun sendLine(value:String)=sendInput(value+"\r")
    fun sendInput(value:String) {
        val current=handle
        if(current==0L || !isRunning || value.isEmpty()) return
        val bytes=value.toByteArray(Charsets.UTF_8)
        scope.launch {
            try {
                if(bridge.nativeWrite(current,bytes)!=bytes.size) publishError(current,"终端输入没有完整写入")
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
            if(bridge.nativeResize(current,safeRows,safeColumns) && handle==current) {
                val next=runCatching{decodeSnapshot(bridge.nativeSnapshot(current))}.getOrNull()
                if(next!=null) withContext(Dispatchers.Main.immediate){if(handle==current) frame=next}
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
        handle=0L; readerJob?.cancel(); readerJob=null; isRunning=false
        message="正在关闭终端会话…"
        Thread({runCatching{bridge.nativeClose(current)}},"codex-private-pty-close")
            .apply{isDaemon=true}.start()
    }
    fun closeInBackground(){stop();scope.cancel()}

    private suspend fun readLoop(activeHandle:Long) {
        val buffer=ByteArray(16*1024)
        try {
            while(currentCoroutineContext().isActive && handle==activeHandle) {
                val count=bridge.nativeRead(activeHandle,buffer,120)
                if(count>0) {
                    val next=decodeSnapshot(bridge.nativeSnapshot(activeHandle))
                    withContext(Dispatchers.Main.immediate){if(handle==activeHandle) frame=next}
                } else if(count<0) {
                    val code=bridge.nativeExitCode(activeHandle)
                    finish(activeHandle,if(code==NATIVE_RUNNING) "PTY 输入流已关闭" else "Shell 已退出，退出码："+code)
                    return
                }
                val code=bridge.nativeExitCode(activeHandle)
                if(code!=NATIVE_RUNNING) {
                    val finalFrame=runCatching{decodeSnapshot(bridge.nativeSnapshot(activeHandle))}.getOrNull()
                    finish(activeHandle,"Shell 已退出，退出码："+code,finalFrame)
                    return
                }
            }
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){finish(activeHandle,error.message?:"终端读取失败")}
    }

    private suspend fun finish(activeHandle:Long,status:String,finalFrame:TerminalFrame?=null) {
        withContext(Dispatchers.Main.immediate) {
            if(handle==activeHandle) {
                handle=0L; isRunning=false; message=status
                if(finalFrame!=null) frame=finalFrame
            }
        }
        withContext(Dispatchers.IO){runCatching{bridge.nativeClose(activeHandle)}}
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
            cursorColumn.coerceIn(0,columnCount-1),cursorVisible,cells)
    }

    private companion object {
        const val SNAPSHOT_MAGIC=0x435A5452
        const val SNAPSHOT_VERSION=1
        const val DEFAULT_COLOR=-1
        const val NATIVE_RUNNING=Int.MIN_VALUE
    }
}
