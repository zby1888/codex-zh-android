#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <vterm.h>

namespace {
constexpr uint32_t kMagic=0x435A5452, kVersion=1, kDefaultColor=UINT32_MAX;
constexpr jint kRunning=INT32_MIN;

struct Terminal {
    int master=-1; pid_t child=-1; int rows=24,columns=80;
    VTerm* vt=nullptr; VTermScreen* screen=nullptr;
    bool cursorVisible=true,reaped=false,closing=false;
    int exitCode=0;
    std::mutex mutex;
};

// JNI never receives a raw Terminal* anymore. A Java coroutine may already have
// captured a handle when stop()/finish() starts closing the PTY. The registry
// keeps the object alive for in-flight calls and makes late calls harmless.
std::mutex gRegistryMutex;
std::unordered_map<jlong,std::shared_ptr<Terminal>> gTerminals;
std::atomic<jlong> gNextHandle{1};

jlong registerTerminal(const std::shared_ptr<Terminal>& terminal) {
    const jlong handle=gNextHandle.fetch_add(1,std::memory_order_relaxed);
    if(handle<=0) return 0;
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gTerminals.emplace(handle,terminal);
    return handle;
}
std::shared_ptr<Terminal> findTerminal(jlong handle) {
    if(handle<=0)return {};
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const auto it=gTerminals.find(handle);
    return it==gTerminals.end()?std::shared_ptr<Terminal>{}:it->second;
}
std::shared_ptr<Terminal> removeTerminal(jlong handle) {
    if(handle<=0)return {};
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const auto it=gTerminals.find(handle);
    if(it==gTerminals.end())return {};
    auto terminal=std::move(it->second);
    gTerminals.erase(it);
    return terminal;
}
void throwState(JNIEnv* env,const char* message) {
    jclass type=env->FindClass("java/lang/IllegalStateException");
    if(type)env->ThrowNew(type,message);
}
std::string fromJava(JNIEnv* env,jstring value) {
    if(!value)return {};
    const char* chars=env->GetStringUTFChars(value,nullptr);
    if(!chars)return {};
    std::string result(chars); env->ReleaseStringUTFChars(value,chars); return result;
}
void resetSignals() {
    struct sigaction action{}; action.sa_handler=SIG_DFL; sigemptyset(&action.sa_mask);
    const int signals[]={SIGHUP,SIGINT,SIGQUIT,SIGTERM,SIGPIPE,SIGTSTP,SIGTTIN,SIGTTOU,SIGCHLD};
    for(int number:signals)sigaction(number,&action,nullptr);
}
int termProperty(VTermProp property,VTermValue* value,void* user) {
    if(property==VTERM_PROP_CURSORVISIBLE && value)
        static_cast<Terminal*>(user)->cursorVisible=value->boolean!=0;
    return 1;
}
void put32(std::vector<uint8_t>& out,uint32_t value) {
    out.push_back(value&0xff); out.push_back((value>>8)&0xff);
    out.push_back((value>>16)&0xff); out.push_back((value>>24)&0xff);
}
uint32_t colorRgb(VTermScreen* screen,VTermColor color,bool isDefault) {
    if(isDefault)return kDefaultColor;
    vterm_screen_convert_color_to_rgb(screen,&color);
    return (static_cast<uint32_t>(color.rgb.red)<<16)|
           (static_cast<uint32_t>(color.rgb.green)<<8)|color.rgb.blue;
}
void flushResponses(Terminal* terminal) {
    char buffer[1024];
    for(;;) {
        const size_t count=vterm_output_read(terminal->vt,buffer,sizeof(buffer));
        if(!count)break;
        size_t offset=0;
        while(offset<count) {
            const ssize_t n=write(terminal->master,buffer+offset,count-offset);
            if(n>0)offset+=static_cast<size_t>(n);
            else if(n<0 && errno==EINTR)continue;
            else break;
        }
    }
}
void reap(Terminal* terminal) {
    if(terminal->reaped || terminal->child<=0)return;
    int status=0; const pid_t result=waitpid(terminal->child,&status,WNOHANG);
    if(result==terminal->child) {
        terminal->reaped=true;
        terminal->exitCode=WIFEXITED(status)?WEXITSTATUS(status):
            (WIFSIGNALED(status)?128+WTERMSIG(status):255);
    } else if(result<0 && errno==ECHILD) {
        terminal->reaped=true; terminal->exitCode=255;
    }
}
std::vector<uint8_t> makeSnapshot(Terminal* terminal) {
    std::vector<uint8_t> out;
    out.reserve(28+static_cast<size_t>(terminal->rows)*terminal->columns*40);
    VTermPos cursor{}; vterm_state_get_cursorpos(vterm_obtain_state(terminal->vt),&cursor);
    put32(out,kMagic); put32(out,kVersion); put32(out,terminal->rows);
    put32(out,terminal->columns); put32(out,cursor.row); put32(out,cursor.col);
    put32(out,terminal->cursorVisible?1:0);
    for(int row=0;row<terminal->rows;++row)for(int col=0;col<terminal->columns;++col) {
        VTermScreenCell cell{}; const VTermPos pos{row,col};
        if(!vterm_screen_get_cell(terminal->screen,pos,&cell))memset(&cell,0,sizeof(cell));
        for(uint32_t cp:cell.chars)put32(out,cp);
        put32(out,std::max(0,static_cast<int>(cell.width)));
        uint32_t flags=0;
        if(cell.attrs.bold)flags|=1U;
        if(cell.attrs.italic)flags|=1U<<1;
        if(cell.attrs.underline)flags|=1U<<2;
        if(cell.attrs.reverse)flags|=1U<<3;
        if(cell.attrs.strike)flags|=1U<<4;
        if(cell.attrs.blink)flags|=1U<<5;
        put32(out,flags);
        put32(out,colorRgb(terminal->screen,cell.fg,VTERM_COLOR_IS_DEFAULT_FG(&cell.fg)));
        put32(out,colorRgb(terminal->screen,cell.bg,VTERM_COLOR_IS_DEFAULT_BG(&cell.bg)));
    }
    return out;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_zby1888_codexzh_NativePtyBridge_nativeCreate(
    JNIEnv* env,jobject,jstring shellValue,jobjectArray argumentValues,jstring cwdValue,
    jobjectArray keys,jobjectArray values,jint rows,jint columns) {
    const std::string shell=fromJava(env,shellValue),cwd=fromJava(env,cwdValue);
    if(shell.empty()||cwd.empty()){throwState(env,"Shell 路径或工作目录为空");return 0;}
    if(rows<8||rows>80||columns<20||columns>160){throwState(env,"终端窗口大小不受支持");return 0;}

    // Keep executable arguments as an argv vector. Never serialize user input
    // into a shell command just to start a PTY child process.
    if(!argumentValues){throwState(env,"Shell 启动参数为空");return 0;}
    const jsize argumentCount=env->GetArrayLength(argumentValues);
    if(argumentCount<1){throwState(env,"Shell 启动参数为空");return 0;}
    std::vector<std::string> argumentStorage; argumentStorage.reserve(argumentCount);
    for(jsize i=0;i<argumentCount;++i) {
        auto value=static_cast<jstring>(env->GetObjectArrayElement(argumentValues,i));
        const std::string argument=fromJava(env,value);
        if(value)env->DeleteLocalRef(value);
        if(argument.empty()||argument.find('\0')!=std::string::npos) {
            throwState(env,"Shell 启动参数包含不合法内容");return 0;
        }
        argumentStorage.push_back(argument);
    }
    if(argumentStorage.front()!=shell) {
        throwState(env,"Shell 启动参数中的程序路径与可执行文件不一致");return 0;
    }
    std::vector<char*> argv; argv.reserve(argumentStorage.size()+1);
    for(auto& argument:argumentStorage)argv.push_back(argument.data());
    argv.push_back(nullptr);

    const jsize count=env->GetArrayLength(keys);
    if(count!=env->GetArrayLength(values)){throwState(env,"环境变量列表长度不一致");return 0;}
    std::vector<std::string> envStorage; envStorage.reserve(count);
    for(jsize i=0;i<count;++i) {
        auto key=static_cast<jstring>(env->GetObjectArrayElement(keys,i));
        auto value=static_cast<jstring>(env->GetObjectArrayElement(values,i));
        const std::string k=fromJava(env,key),v=fromJava(env,value);
        if(key)env->DeleteLocalRef(key); if(value)env->DeleteLocalRef(value);
        if(k.empty()||k.find('=')!=std::string::npos||k.find('\0')!=std::string::npos||v.find('\0')!=std::string::npos) {
            throwState(env,"终端环境变量包含不合法内容");return 0;
        }
        envStorage.push_back(k+"="+v);
    }
    std::vector<char*> envp; envp.reserve(envStorage.size()+1);
    for(auto& entry:envStorage)envp.push_back(entry.data()); envp.push_back(nullptr);

    auto terminal=std::make_shared<Terminal>();
    const int master=posix_openpt(O_RDWR|O_NOCTTY|O_CLOEXEC|O_NONBLOCK);
    if(master<0){throwState(env,"无法打开 Android PTY 主设备");return 0;}
    if(grantpt(master)!=0||unlockpt(master)!=0){close(master);throwState(env,"无法初始化 Android PTY 设备");return 0;}
    char slavePath[128]{};
    if(ptsname_r(master,slavePath,sizeof(slavePath))!=0){close(master);throwState(env,"无法取得 PTY 从设备路径");return 0;}
    const int slave=open(slavePath,O_RDWR|O_NOCTTY|O_CLOEXEC);
    if(slave<0){close(master);throwState(env,"无法打开 PTY 从设备");return 0;}

    struct termios settings{};
    if(tcgetattr(slave,&settings)==0) {
        settings.c_iflag|=ICRNL|IXON;
#ifdef IUTF8
        settings.c_iflag|=IUTF8;
#endif
        settings.c_oflag|=OPOST|ONLCR;
        settings.c_lflag|=ISIG|ICANON|ECHO|ECHOE|ECHOK;
        settings.c_cflag|=CREAD|CS8;
        settings.c_cc[VMIN]=1;settings.c_cc[VTIME]=0;
        tcsetattr(slave,TCSANOW,&settings);
    }
    struct winsize ws{};ws.ws_row=static_cast<unsigned short>(rows);ws.ws_col=static_cast<unsigned short>(columns);
    ioctl(slave,TIOCSWINSZ,&ws);

    const pid_t child=fork();
    if(child<0){close(slave);close(master);throwState(env,"创建 PTY Shell 子进程失败");return 0;}
    if(child==0) {
        close(master);
        if(setsid()<0||ioctl(slave,TIOCSCTTY,0)<0)_exit(126);
        if(dup2(slave,STDIN_FILENO)<0||dup2(slave,STDOUT_FILENO)<0||dup2(slave,STDERR_FILENO)<0)_exit(126);
        if(slave>STDERR_FILENO)close(slave);
        if(chdir(cwd.c_str())!=0)_exit(126);
        resetSignals();
        execve(shell.c_str(),argv.data(),envp.data());_exit(127);
    }
    close(slave);
    terminal->master=master;terminal->child=child;terminal->rows=rows;terminal->columns=columns;
    terminal->vt=vterm_new(rows,columns);
    if(!terminal->vt){
        kill(child,SIGKILL);
        int status=0;
        while(waitpid(child,&status,0)<0&&errno==EINTR){}
        close(master);
        throwState(env,"创建 VT 状态解析器失败");
        return 0;
    }
    vterm_set_utf8(terminal->vt,1);
    terminal->screen=vterm_obtain_screen(terminal->vt);
    vterm_screen_enable_altscreen(terminal->screen,1);
    vterm_screen_set_damage_merge(terminal->screen,VTERM_DAMAGE_ROW);
    VTermScreenCallbacks callbacks{};callbacks.settermprop=termProperty;
    vterm_screen_set_callbacks(terminal->screen,&callbacks,terminal.get());
    vterm_screen_reset(terminal->screen,1);
    try {
        const jlong handle=registerTerminal(terminal);
        if(handle==0) {
            kill(child,SIGKILL);
            int status=0;
            while(waitpid(child,&status,0)<0&&errno==EINTR){}
            close(master);
            vterm_free(terminal->vt);
            terminal->vt=nullptr;
            terminal->screen=nullptr;
            throwState(env,"无法分配 PTY 会话句柄");
            return 0;
        }
        return handle;
    } catch(const std::exception&) {
        kill(child,SIGKILL);
        int status=0;
        while(waitpid(child,&status,0)<0&&errno==EINTR){}
        close(master);
        vterm_free(terminal->vt);
        terminal->vt=nullptr;
        terminal->screen=nullptr;
        throwState(env,"无法登记 PTY 会话");
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_zby1888_codexzh_NativePtyBridge_nativeRead(JNIEnv* env,jobject,jlong handle,jbyteArray output,jint timeout) {
    auto term=findTerminal(handle);if(!term||!output)return -1;
    std::lock_guard<std::mutex> lock(term->mutex);
    if(term->closing||term->master<0||term->reaped)return -1;
    const jsize capacity=env->GetArrayLength(output);if(capacity<=0)return 0;
    jbyte* bytes=env->GetByteArrayElements(output,nullptr);if(!bytes)return -1;
    struct pollfd descriptor{term->master,static_cast<short>(POLLIN|POLLHUP|POLLERR),0};
    int ready;do{ready=poll(&descriptor,1,std::max(0,std::min(timeout,1000)));}while(ready<0&&errno==EINTR);
    jint result=0;
    if(ready<0)result=-1;
    else if(ready>0) {
        ssize_t n;do{n=read(term->master,bytes,static_cast<size_t>(capacity));}while(n<0&&errno==EINTR);
        if(n>0){vterm_input_write(term->vt,reinterpret_cast<const char*>(bytes),static_cast<size_t>(n));vterm_screen_flush_damage(term->screen);flushResponses(term.get());result=static_cast<jint>(n);}
        else if(n==0||(n<0&&errno==EIO))result=-1;
        else if(errno==EAGAIN||errno==EWOULDBLOCK)result=0;
        else result=-1;
    }
    env->ReleaseByteArrayElements(output,bytes,0);return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_zby1888_codexzh_NativePtyBridge_nativeWrite(JNIEnv* env,jobject,jlong handle,jbyteArray input) {
    auto term=findTerminal(handle);if(!term||!input)return -1;
    std::lock_guard<std::mutex> lock(term->mutex);
    if(term->closing||term->master<0||term->reaped)return -1;
    const jsize length=env->GetArrayLength(input);if(length==0)return 0;
    jbyte* bytes=env->GetByteArrayElements(input,nullptr);if(!bytes)return -1;
    size_t offset=0;
    while(offset<static_cast<size_t>(length)) {
        const ssize_t n=write(term->master,reinterpret_cast<char*>(bytes)+offset,static_cast<size_t>(length)-offset);
        if(n>0)offset+=static_cast<size_t>(n);
        else if(n<0&&errno==EINTR)continue;
        else if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK)){struct pollfd p{term->master,POLLOUT,0};if(poll(&p,1,1000)>0)continue;break;}
        else break;
    }
    env->ReleaseByteArrayElements(input,bytes,JNI_ABORT);return static_cast<jint>(offset);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_zby1888_codexzh_NativePtyBridge_nativeResize(JNIEnv*,jobject,jlong handle,jint rows,jint columns) {
    auto term=findTerminal(handle);
    if(!term||rows<8||rows>80||columns<20||columns>160)return JNI_FALSE;
    std::lock_guard<std::mutex> lock(term->mutex);if(term->closing||term->master<0||term->reaped)return JNI_FALSE;
    struct winsize ws{};ws.ws_row=static_cast<unsigned short>(rows);ws.ws_col=static_cast<unsigned short>(columns);
    if(ioctl(term->master,TIOCSWINSZ,&ws)!=0)return JNI_FALSE;
    term->rows=rows;term->columns=columns;vterm_set_size(term->vt,rows,columns);vterm_screen_flush_damage(term->screen);
    return JNI_TRUE;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_zby1888_codexzh_NativePtyBridge_nativeSnapshot(JNIEnv* env,jobject,jlong handle) {
    auto term=findTerminal(handle);if(!term)return nullptr;
    std::lock_guard<std::mutex> lock(term->mutex);if(term->closing||!term->vt||!term->screen)return nullptr;
    const auto bytes=makeSnapshot(term.get());if(bytes.size()>static_cast<size_t>(INT32_MAX))return nullptr;
    jbyteArray result=env->NewByteArray(static_cast<jsize>(bytes.size()));
    if(result&&!bytes.empty())env->SetByteArrayRegion(result,0,static_cast<jsize>(bytes.size()),reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_zby1888_codexzh_NativePtyBridge_nativeExitCode(JNIEnv*,jobject,jlong handle) {
    auto term=findTerminal(handle);if(!term)return 255;
    std::lock_guard<std::mutex> lock(term->mutex);
    if(term->closing)return 255;
    reap(term.get());return term->reaped?term->exitCode:kRunning;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_zby1888_codexzh_NativePtyBridge_nativeClose(JNIEnv*,jobject,jlong handle) {
    auto term=removeTerminal(handle);if(!term)return;
    {
        std::lock_guard<std::mutex> lock(term->mutex);
        if(term->closing)return;
        term->closing=true;
        if(term->child>0&&!term->reaped) {
            kill(-term->child,SIGHUP);
            for(int i=0;i<10&&!term->reaped;++i){reap(term.get());usleep(20000);}
            if(!term->reaped)kill(-term->child,SIGTERM);
            for(int i=0;i<10&&!term->reaped;++i){reap(term.get());usleep(20000);}
            if(!term->reaped){kill(-term->child,SIGKILL);int status=0;while(waitpid(term->child,&status,0)<0&&errno==EINTR){}term->reaped=true;}
        }
        if(term->master>=0){close(term->master);term->master=-1;}
        if(term->vt){vterm_free(term->vt);term->vt=nullptr;term->screen=nullptr;}
    }
}
