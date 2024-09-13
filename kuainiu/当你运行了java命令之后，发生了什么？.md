# 当你执行了java命令之后，发生了什么？

相信各位java开发对java命令不会陌生，从学习java的第一天开始我们就会使用java命令，比如运行一个测试类，又或者用`java -jar`来启动我们的服务，那么你清楚当你输入java命令之后到底发生了什么吗？java是如何执行我们的代码以及如何初始化jvm的？本文笔者就将带领大家来看看java启动命令的底层原理，本文源码基于JDK11



## 1.java命令入口

我们知道java命令是在我们下载的jdk源码的bin目录下，是一个可执行文件，无论我们在windows还是linux还是unix都需要去配置`java_home`，而`java_home`指向的就是这个java可执行文件，根据我们的基础知识，这个命令的源代码入口就在我们的`main.c`的`main`方法中，所以我们直接打开jdk源码里的：`java.base/share/native/launcher/main.c`这个文件，这个文件的main方法就是我们整个`java`的入口文件:

```c
#ifdef JAVAW  //window平台入口

char **__initenv;

int WINAPI
WinMain(HINSTANCE inst, HINSTANCE previnst, LPSTR cmdline, int cmdshow)
{
    int margc;
    char** margv;
    int jargc;
    char** jargv;
    const jboolean const_javaw = JNI_TRUE;

    __initenv = _environ;

#else /* JAVAW */
JNIEXPORT int
main(int argc, char **argv) //linxu入口
{
    int margc;
    char** margv;
    int jargc;
    char** jargv;
    const jboolean const_javaw = JNI_FALSE;
#endif /* JAVAW */ 
    {
      ... //主要做一些校验和参数构建
     //核心函数就是JLI_Launch
    return JLI_Launch(margc, margv,
                   jargc, (const char**) jargv,
                   0, NULL,
                   VERSION_STRING,
                   DOT_VERSION,
                   (const_progname != NULL) ? const_progname : *margv,
                   (const_launcher != NULL) ? const_launcher : *margv,
                   jargc > 0,
                   const_cpwildcard, const_javaw, 0);
}
```

我们可以看到在main中做的工作不多，主要还是调用`JLI_Launch`，这个方法在`main.c`中引用的：

```c
#include "defines.h"
```

也就是`defines.h`中:

```c
#include "java.h"
```

也就是说方法具体的实现就在`java.c`中：

```c
/**
 *
 * @param argc 参数个数
 * @param argv 命令参数
 * @param jargc java args参数个数
 * @param jargv java args参数
 * @param appclassc class path数量
 * @param appclassv class path
 * @param fullversion 完整版本号
 * @param dotversion 版本号
 * @param pname 程序名称
 * @param lname 启动器名称
 * @param javaargs 是否有 Java 参数。
 * @param cpwildcard 指示类路径是否包含通配符
 * @param javaw 是否是 Windows 平台的 javaw
 * @param ergo 一个整数，通常是不使用的
 * @return
 */
JLI_Launch(int argc, char **argv,              
           int jargc, const char **jargv,          
           int appclassc, const char **appclassv,  
           const char *fullversion,                
           const char *dotversion,                
           const char *pname,                      
           const char *lname,                    
           jboolean javaargs,                   
           jboolean cpwildcard,                   
           jboolean javaw,                        
           jint ergo                               
) {
  //启动模式，定义在java.h中
    int mode = LM_UNKNOWN;
    char *what = NULL;
  //java中的main类
    char *main_class = NULL;

  ...
 		//初始化启动器
    InitLauncher(javaw);
    //选择jre的版本,这个函数实现的功能比较简单，就是选择正确的jre版本来作为即将运行java程序的版本。
    //选择的方式
    //1.环境变量设置了_JAVA_VERSION_SET，那么代表已经选择了jre的版本，不再进行选择
    //2.可以从jar包中读取META-INF/MAINFEST.MF中获取,查看mian_class
    //3.运行时给定的参数来搜索不同的目录选择jre_restrict_search
    //最终会解析出一个真正需要的jre版本并且判断当前执行本java程序的jre版本是不是和这个版本一样，如果不一样调用linux的execv函数终止当前进出并且使用新的jre版本重新运行这个java程序，但是进程ID不会改变。
    SelectVersion(argc, argv, &main_class);

    CreateExecutionEnvironment(&argc, &argv,
                               jrepath, sizeof(jrepath),
                               jvmpath, sizeof(jvmpath),
                               jvmcfg, sizeof(jvmcfg));

    if (!IsJavaArgs()) {
        SetJvmEnvironment(argc, argv);
    }

    ifn.CreateJavaVM = 0;
    ifn.GetDefaultJavaVMInitArgs = 0;

    if (JLI_IsTraceLauncher()) {
        start = CounterGet();
    }
		//动态加载JVM_DLL这个共享库，把hotspot的接口爆露出来，例如JNI_CreateJavaVM函数
    if (!LoadJavaVM(jvmpath, &ifn)) {
        return (6);
    }

   ...
   //解析参数
    if (!ParseArguments(&argc, &argv, &mode, &what, &ret, jrepath)) {
        return (ret);
    }

    /* 如果是java -jar这种方式启动，就重写一下class path */
    if (mode == LM_JAR) {
        SetClassPath(what);    
    }

	...

  //jvm初始化
    return JVMInit(&ifn, threadStackSize, argc, argv, mode, what, ret);
}
```

这里去掉了一些不太重要的代码，然后可以看到就是加载一些资源和参数的解析，这里简单说下`LoadJavaVM`,这里只是把JVM的动态库加载好，便于后面真正去初始化jvm的时候调用。这里简单介绍一下jvm动态库：

 >Java 虚拟机（Java Virtual Machine，JVM）的动态库是一个包含 JVM 实现的动态链接库（DLL或SO文件），它负责在特定操作系统上执行 Java 程序。这个动态库通常包含了 JVM 的核心功能，如字节码解释、即时编译、内存管理、线程管理、垃圾回收等。
 >
 >在不同的操作系统上，Java 虚拟机的动态库文件有不同的命名约定：
 >
 >- 在 Windows 系统上，Java 虚拟机的动态库是 jvm.dll文件
 >- 在 Linux 和 Unix 系统上，Java 虚拟机的动态库是 `libjvm.so`文件
 >- 在MacOs上，java虚拟机的动态库就是libjvm.dylib文件
 >
 >这些动态库文件是 Java 开发环境（JDK）的一部分，它们提供了 Java 程序在特定操作系统上运行所需的基本功能。通过加载和执行这些动态库文件，操作系统能够创建一个 Java 运行时环境，使得 Java 程序可以在不同的平台上实现跨平台性。

jvm动态库也是java跨平台能力的关键

再重点说一下mode启动模式。也就是如何启动，在java.h中有如下枚举:

```C
enum LaunchMode { 
    LM_UNKNOWN = 0,
    LM_CLASS,
    LM_JAR,
    LM_MODULE,
    LM_SOURCE
};
```

分别代表：初始化状态也表示未知不合法的启动模式；直接启动`javac`编译过的class文件；启动jar包，这个是我们最常用的模式；启动module模块，jdk9新增的，这个平时我们开发中用到的很少；直接加载java源文件，这个是jdk11新增的，jep地址：https://openjdk.org/jeps/330

这里简单说下最后一种启动模式，jdk11新增的直接运行java文件，意味着我们可以这样来运行一个java文件了：`java XX.java`再也不用去用`javac`编译一次然后再运行了，这里简单说下一下原理就是`java XX.java`实际上被程序转换为了`java -m jdk.compiler/com.sun.tools.javac.launcher.Main --add-modules=ALL-DEFAULT XX.java`也就是在内存中去编译一次你的代码，然后再执行，在`ParseArguments`中可以看到:

```c
if (mode == LM_SOURCE) {
    AddOption("--add-modules=ALL-DEFAULT", NULL);
    *pwhat = SOURCE_LAUNCHER_MAIN_ENTRY;
    // adjust (argc, argv) so that the name of the source file
    // is included in the args passed to the source launcher
    // main entry class
    *pargc = argc + 1;
    *pargv = argv - 1;
}
```

这里就是去做了一个替换，实际上java命令启动的是`com.sun.tools.javac.launcher.Main`然后把我们的java文件当作参数传递给了`com.sun.tools.javac.launcher.Main`

然后我们顺着往下看,`JVMInit`这个方法并不在`java.c`中，而是在`java_md.c`，`java_md.c`是是 Java 虚拟机（JVM）中的一个文件，通常是用于处理 Java 虚拟机与特定操作系统相关的功能和特性的文件之一。在 JVM 的源代码中，通常会有一些特定于操作系统的文件，用于处理与操作系统交互的功能，例如文件 I/O、线程管理、内存管理等。也就是说不通操作系统有不同的实现，比如windows、linux、unix、mac等，这里`JVMInit`方法在大多数操作系统中的实现都一样的，如下，只有mac的不一样，这里就不过多赘述了：

```c
int
JVMInit(InvocationFunctions* ifn, jlong threadStackSize,
        int argc, char **argv,
        int mode, char *what, int ret)
{
    ShowSplashScreen();
    return ContinueInNewThread(ifn, threadStackSize, argc, argv, mode, what, ret);
}
```

然后在`ContinueInNewThread`中：

```c
int ContinueInNewThread(InvocationFunctions *ifn, jlong threadStackSize,
                    int argc, char **argv,
                    int mode, char *what, int ret) {

...

        rslt = CallJavaMainInNewThread(threadStackSize, (void *) &args);
        /* If the caller has deemed there is an error we
         * simply return that, otherwise we return the value of
         * the callee
         */
        return (ret != 0) ? ret : rslt;
    }
```

可以看到这里其实调用了一个`CallJavaMainInNewThread`顾名思义就是在新的线程里调用`JavaMain`，这里`CallJavaMainInNewThread`也是在`java_md.c`中实现的，因为每个操作系统初始化线程的方式都不一样，这里我们以linux为例：

```c
CallJavaMainInNewThread(jlong stack_size, void* args) {
      int rslt;
  //不是solaris系统
#ifndef __solaris__
  ...
     //眼熟吗？我们在java Thread如何创建线程的时候介绍过，这里就是创建一个线程，然后传入ThreadJavaMain函数作为创建好的callback
    if (pthread_create(&tid, &attr, ThreadJavaMain, args) == 0) {
        void* tmp;
      //阻塞等待JavaMain方法执行完成，这里主线程就阻塞住了，然后实际上是另外一个线程在执行java了
        pthread_join(tid, &tmp);
        rslt = (int)(intptr_t)tmp;
    } else {
       //如果线程创建失败，比如内存满了，那么就在当前线程内执行，不过因为后面的create_vm会创建大量线程，所以也不一定会成功，但是试试吧
        rslt = JavaMain(args);
    }

    pthread_attr_destroy(&attr);
 //  如果是solaris系统
#else /* __solaris__ */
    thread_t tid;
    long flags = 0;
    if (thr_create(NULL, stack_size, ThreadJavaMain, args, flags, &tid) == 0) {
        void* tmp;
        thr_join(tid, NULL, &tmp);
        rslt = (int)(intptr_t)tmp;
    } else {
        /* See above. Continue in current thread if thr_create() failed */
        rslt = JavaMain(args);
    }
#endif /* !__solaris__ */
    return rslt;
}
```

这个方法的 核心就是创建一线程执行`JavaMain`方法然后阻塞等待这个线程执行完毕，这里对oracle的`solaris`单独处理了。这段代码如果你前面了解过java线程底层创建的话理解起来就非常的简单了。

`JavaMain`方法其实还是在我们`java.c`中，这个方法就是我们最核心的java启动方法：

```c
int
JavaMain(void *_args) {
   //构造参数
    JavaMainArgs *args = (JavaMainArgs *) _args;
    int argc = args->argc;
    char **argv = args->argv;
    int mode = args->mode;
    char *what = args->what;
  	//当前虚拟机导致的函数指针
    InvocationFunctions ifn = args->ifn;
		//虚拟机指针
    JavaVM *vm = 0;
    JNIEnv * env = 0;
  	//main函数class
    jclass mainClass = NULL;
  	//正在启动的实际应用程序类
    jclass appClass = NULL; // actual application class being launched
    jmethodID mainID;
    jobjectArray mainArgs;
    int ret = 0;
    jlong start = 0, end = 0;
		//window、类unix为空实现，macos特别处理，注册线程
    RegisterThread();

    /* 初始化虚拟机 */
    start = CounterGet();
    if (!InitializeJVM(&vm, &env, &ifn)) {
        JLI_ReportErrorMessage(JVM_ERROR1);
        exit(1);
    }

   ...
   //加载当前的主类
    mainClass = LoadMainClass(env, mode, what);
  //检查是否指定main class， 不存在则退出虚拟
    CHECK_EXCEPTION_NULL_LEAVE(mainClass);
...

    /* 组装main函数参数 */
    mainArgs = CreateApplicationArgs(env, argv, argc);
    CHECK_EXCEPTION_NULL_LEAVE(mainArgs);

    if (dryRun) {
        ret = 0;
        LEAVE();
    }

    //jvm启动钩子
    PostJVMInit(env, appClass, vm);
    CHECK_EXCEPTION_LEAVE(1);

    /*
     * The LoadMainClass not only loads the main class, it will also ensure
     * that the main method's signature is correct, therefore further checking
     * is not required. The main method is invoked here so that extraneous java
     * stacks are not in the application stack trace.
     */
  	//执行我们的main函数
    mainID = (*env)->GetStaticMethodID(env, mainClass, "main",
                                       "([Ljava/lang/String;)V");
    ...
		//main方法执行完毕，JVM退出
    LEAVE();
}
```

可以看到这里入口函数其实就做了四件事：

- 启动JVM虚拟机
- 加载包含main函数的主类
- 执行main函数
- main函数执行完毕退出

所以我们按照这个顺序来看

## 2.JVM虚拟机启动

这里启动虚拟机的函数就是`InitializeJVM`：

```c
static jboolean
InitializeJVM(JavaVM **pvm, JNIEnv **penv, InvocationFunctions *ifn) {
    JavaVMInitArgs args;
    jint r;

  ...

    r = ifn->CreateJavaVM(pvm, (void **) penv, &args);
    JLI_MemFree(options);
    return r == JNI_OK;
}
```

这里调用的`CreateJavaVM`函数，刚刚在进入入口函数之前，我们加载了Java虚拟机动态库，这个`CreateJavaVM`就是这个动态库提供的函数。所以我们可以在`jni.cpp`中找到这个函数：

```c++
_JNI_IMPORT_OR_EXPORT_ jint JNICALL JNI_CreateJavaVM(JavaVM **vm, void **penv, void *args) {
  jint result = JNI_ERR;
  // On Windows, let CreateJavaVM run with SEH protection
#ifdef _WIN32
  __try {
#endif
    result = JNI_CreateJavaVM_inner(vm, penv, args);
#ifdef _WIN32
  } __except(topLevelExceptionFilter((_EXCEPTION_POINTERS*)_exception_info())) {
    // Nothing to do.
  }
#endif
  return result;
}
```

这里直接调用了`JNI_CreateJavaVM_inner`：

```c++
static jint JNI_CreateJavaVM_inner(JavaVM **vm, void **penv, void *args) {
  HOTSPOT_JNI_CREATEJAVAVM_ENTRY((void **) vm, penv, args);
	//初始化结果值
  jint result = JNI_ERR;
  DT_RETURN_MARK(CreateJavaVM, jint, (const jint&)result);

  // CAS用来同步
  //平台使用 GCC 内置的 __sync_lock_test_and_set 来实现同步、
  // 但是，__sync_lock_test_and_set 并不能保证在所有架构上都能达到我们的要求。
  // 在所有架构上都能实现。 因此，在依赖它之前，我们要检查它是否能正常工作。
#if defined(ZERO) && defined(ASSERT)
  {
    jint a = 0xcafebabe;
    jint b = Atomic::xchg((jint) 0xdeadbeef, &a);
    void *c = &a;
    void *d = Atomic::xchg(&b, &c);
    assert(a == (jint) 0xdeadbeef && b == (jint) 0xcafebabe, "Atomic::xchg() works");
    assert(c == &b && d == &a, "Atomic::xchg() works");
  }
#endif // ZERO && ASSERT

  // At the moment it's only possible to have one Java VM,
  // since some of the runtime state is in global variables.

  // We cannot use our mutex locks here, since they only work on
  // Threads. We do an atomic compare and exchange to ensure only
  // one thread can call this method at a time

  // We use Atomic::xchg rather than Atomic::add/dec since on some platforms
  // the add/dec implementations are dependent on whether we are running
  // on a multiprocessor, and at this stage of initialization the os::is_MP
  // function used to determine this will always return false. Atomic::xchg
  // does not have this problem.
  //cas保证只启动一个JVM
  if (Atomic::xchg(1, &vm_created) == 1) {
    return JNI_EEXIST;   // already created, or create attempt in progress
  }

  // If a previous creation attempt failed but can be retried safely,
  // then safe_to_recreate_vm will have been reset to 1 after being
  // cleared here. If a previous creation attempt succeeded and we then
  // destroyed that VM, we will be prevented from trying to recreate
  // the VM in the same process, as the value will still be 0.
  //避免重复创建JVM
  if (Atomic::xchg(0, &safe_to_recreate_vm) == 0) {
    return JNI_ERR;
  }

  assert(vm_created == 1, "vm_created is true during the creation");

  /**
   * Certain errors during initialization are recoverable and do not
   * prevent this method from being called again at a later time
   * (perhaps with different arguments).  However, at a certain
   * point during initialization if an error occurs we cannot allow
   * this function to be called again (or it will crash).  In those
   * situations, the 'canTryAgain' flag is set to false, which atomically
   * sets safe_to_recreate_vm to 1, such that any new call to
   * JNI_CreateJavaVM will immediately fail using the above logic.
   */
  bool can_try_again = true;
	//创建虚拟机的核心方法
  result = Threads::create_vm((JavaVMInitArgs*) args, &can_try_again);
  //创建成功
  if (result == JNI_OK) {
    JavaThread *thread = JavaThread::current();
    assert(!thread->has_pending_exception(), "should have returned not OK");
    /* thread is thread_in_vm here */
    *vm = (JavaVM *)(&main_vm);
    *(JNIEnv**)penv = thread->jni_environment();
//JVMCI（Java Virtual Machine Compiler Interface）是 Java 虚拟机编译器接口的缩写。它是 JDK 9 引入的一个重要特性，旨在为 Java 虚拟机提供一个灵活的、模块化的编译器接口。通过 JVMCI，开发人员可以轻松地实现和集成新的即时编译器（JIT 编译器）或优化技术到 Java 虚拟机中。
#if INCLUDE_JVMCI
    //如果启用了JVMCI,说明集成了自定义的JIT编译器
    if (EnableJVMCI) {
      if (UseJVMCICompiler) {
        // JVMCI is initialized on a CompilerThread
        if (BootstrapJVMCI) {
          JavaThread* THREAD = thread;
          JVMCICompiler* compiler = JVMCICompiler::instance(true, CATCH);
          compiler->bootstrap(THREAD);
          if (HAS_PENDING_EXCEPTION) {
            HandleMark hm;
            vm_exit_during_initialization(Handle(THREAD, PENDING_EXCEPTION));
          }
        }
      }
    }
#endif

    //在GC之前记录启动的时间
    RuntimeService::record_application_start();
		//JVMTI（Java Virtual Machine Tool Interface）是 Java 虚拟机工具接口的缩写。它是 Java 虚拟机提供的一种标准接口，允许开发人员编写各种类型的工具，用于监控、调试和分析 Java 应用程序的运行时行为。通过 JVMTI，开发人员可以编写各种类型的工具，包括调试器、性能分析器、代码覆盖率工具等，来监视和干预 Java 应用程序在虚拟机中的执行过程。这些工具可以帮助开发人员诊断和解决应用程序中的问题，优化性能，以及进行各种形式的运行时分析。
    // Notify JVMTI
    if (JvmtiExport::should_post_thread_life()) {
       JvmtiExport::post_thread_start(thread);
    }

    //发送启动事件
    post_thread_start_event(thread);

#ifndef PRODUCT
     //根据配置加载类路径中所有的类
    if (CompileTheWorld) ClassLoader::compile_the_world();
    if (ReplayCompiles) ciReplay::replay(thread);

    // Some platforms (like Win*) need a wrapper around these test
    // functions in order to properly handle error conditions.
    VMError::test_error_handler();
    if (ExecuteInternalVMTests) {
      InternalVMTests::run();
    }
#endif

   ...
     
  return result;

}
```

这里其实核心代码是`Threads::create_vm`，但是看了源码发现还有隐藏的知识点：`JVMTI`和`JVMCI`，即允许用户自己监控jvm以及自己实现JIT编译器

`Threads::create_vm`就在`Thread.cpp`中：

```c++
jint Threads::create_vm(JavaVMInitArgs *args, bool *canTryAgain) {
    extern void JDK_Version_init();


    VM_Version::early_initialize();

    //检查版本
    if (!is_supported_jni_version(args->version)) return JNI_EVERSION;

    //在 Java 虚拟机（JVM）中，Thread Local Storage（TLS，线程本地存储）是一种机制，允许每个线程都有自己的本地变量，这些变量对于该线程是私有的，其他线程无法直接访问。TLS 提供了一种在多线程环境下存储线程私有数据的方式。
    ThreadLocalStorage::init();

    // 初始化系统输出流模块
    ostream_init();

    // 处理java启动属性.
    Arguments::process_sun_java_launcher_properties(args);

    // 初始化系统环境，例如：获取当前的进程pid、获取系统时钟、设置内存页大小
    // 获取cpu数、获取物理内存大小
    os::init();

    //记录时间
    TraceVmCreationTime create_vm_timer;
    create_vm_timer.start();

    //初始化系统属性
    Arguments::init_system_properties();

    // So that JDK version can be used as a discriminator when parsing arguments
    JDK_Version_init();

    // Update/Initialize System properties after JDK version number is known
    Arguments::init_version_specific_system_properties();

    // Make sure to initialize log configuration *before* parsing arguments
    LogConfiguration::initialize(create_vm_timer.begin_time());

    // Parse arguments
    // 解析参数
    jint parse_result = Arguments::parse(args);
    if (parse_result != JNI_OK) return parse_result;

    os::init_before_ergo();

    jint ergo_result = Arguments::apply_ergo();
    if (ergo_result != JNI_OK) return ergo_result;

    // Final check of all ranges after ergonomics which may change values.
    if (!JVMFlagRangeList::check_ranges()) {
        return JNI_EINVAL;
    }

    // Final check of all 'AfterErgo' constraints after ergonomics which may change values.
    bool constraint_result = JVMFlagConstraintList::check_constraints(JVMFlagConstraint::AfterErgo);
    if (!constraint_result) {
        return JNI_EINVAL;
    }

    JVMFlagWriteableList::mark_startup();

    if (PauseAtStartup) {
        os::pause();
    }
    //正式开始启动虚拟机
    HOTSPOT_VM_INIT_BEGIN();

    // Timing (must come after argument parsing)
    TraceTime timer("Create VM", TRACETIME_LOG(Info, startuptime));

    // Initialize the os module after parsing the args
    jint os_init_2_result = os::init_2();
    if (os_init_2_result != JNI_OK) return os_init_2_result;

#ifdef CAN_SHOW_REGISTERS_ON_ASSERT
                                                                                                                            // Initialize assert poison page mechanism.
  if (ShowRegistersOnAssert) {
    initialize_assert_poison();
  }
#endif // CAN_SHOW_REGISTERS_ON_ASSERT

    SafepointMechanism::initialize();

    jint adjust_after_os_result = Arguments::adjust_after_os();
    if (adjust_after_os_result != JNI_OK) return adjust_after_os_result;

    // Initialize output stream logging
    ostream_init_log();

    // Convert -Xrun to -agentlib: if there is no JVM_OnLoad
    // Must be before create_vm_init_agents()
    if (Arguments::init_libraries_at_startup()) {
        convert_vm_init_libraries_to_agents();
    }

    // Launch -agentlib/-agentpath and converted -Xrun agents
    if (Arguments::init_agents_at_startup()) {
        create_vm_init_agents();
    }

    // Initialize Threads state
    _thread_list = NULL;
    _number_of_threads = 0;
    _number_of_non_daemon_threads = 0;

    // Initialize global data structures and create system classes in heap
    vm_init_globals();

#if INCLUDE_JVMCI
                                                                                                                            if (JVMCICounterSize > 0) {
    JavaThread::_jvmci_old_thread_counters = NEW_C_HEAP_ARRAY(jlong, JVMCICounterSize, mtInternal);
    memset(JavaThread::_jvmci_old_thread_counters, 0, sizeof(jlong) * JVMCICounterSize);
  } else {
    JavaThread::_jvmci_old_thread_counters = NULL;
  }
#endif // INCLUDE_JVMCI

    //新建一个java线程,设置主线程状态为运行在jvm里面
    JavaThread *main_thread = new JavaThread();
    //初始化这个主线程                                                                                                                    //
    main_thread->set_thread_state(_thread_in_vm);

    main_thread->initialize_thread_current();
    // must do this before set_active_handles
    main_thread->record_stack_base_and_size();
    main_thread->register_thread_stack_with_NMT();
    main_thread->set_active_handles(JNIHandleBlock::allocate_block());

    if (!main_thread->set_as_starting_thread()) {
        vm_shutdown_during_initialization(
                "Failed necessary internal allocation. Out of swap space");
        main_thread->smr_delete();
        *canTryAgain = false; // don't let caller call JNI_CreateJavaVM again
        return JNI_ENOMEM;
    }

    // Enable guard page *after* os::create_main_thread(), otherwise it would
    // crash Linux VM, see notes in os_linux.cpp.
    main_thread->create_stack_guard_pages();

    // 初始化对象监控器，就是sync那玩意儿
    ObjectMonitor::Initialize();

    //  初始化全局模块
    jint status = init_globals();
  //启动失败
    if (status != JNI_OK) {
        main_thread->smr_delete();
        *canTryAgain = false; // don't let caller call JNI_CreateJavaVM again
        return status;
    }

    JFR_ONLY(Jfr::on_create_vm_1();)

    // 全局缓存
    main_thread->cache_global_variables();

 ...



    // Always call even when there are not JVMTI environments yet, since environments
    // may be attached late and JVMTI must track phases of VM execution
    JvmtiExport::enter_start_phase();

    // 唤醒 JVMTI agents 通知jvm以及启动
    JvmtiExport::post_vm_start();

...

    return JNI_OK;
}
```

除去一些准备工作之外，核心就是` init_globals();`方法，这个方法在`init.cpp`中，恰好，在笔者之前的文章[如何自己手动实现一个JVM GC](https://mp.weixin.qq.com/s/-mML0oQwCGPmoCLA7YWhRQ) 中曾经提到过`init.cpp`，因为那会儿要研究堆是如何初始化的来找入口然后自己实现GC的初始化逻辑，在这篇文章中对整个堆的初始化和GC初始化介绍的比较清楚，大家感兴趣的可以看看这篇文章。

这里再简单介绍一下，因为前文的重点在于GC初始化，`init_globals`初始化 JVM 的全局状态。它包含了一系列的初始化操作，用于准备 JVM 运行时环境所需的各种子系统和数据结构：

```c++
jint init_globals() {
  //创建一个 HandleMark 对象，用于管理句柄。
  HandleMark hm;
  //初始化管理、字节码、类加载器等子系统
  management_init();
  bytecodes_init();
  classLoader_init1();
  //对于JVM版本、编译测量初始化
  compilationPolicy_init();
  //初始化 JVM 的代码缓存（Code Cache）。代码缓存是 JVM 中用于存储已编译代码的内存区域，主要包含了经过即时编译（JIT Compilation）生成的本地机器代码。
  codeCache_init();
  VM_Version_init();
  os_init_globals();
  //初始化 JVM 的 stub routines（存根例程）。存根例程是一些在运行时由 JVM 动态生成的辅助代码片段，用于执行特定的操作或处理某些特殊情况。
  stubRoutines_init1();
  //我们初始化堆、MetaSpace就是在这里，也就是JVM核心的内存空间
  jint status = universe_init();  // dependent on codeCache_init and
                                  // stubRoutines_init1 and metaspace_init.
  //如果universe没有初始化成功就退出，
  if (status != JNI_OK)
    return status;
	//初始化内存屏障、解释器、计数器等子系统
  gc_barrier_stubs_init();   // depends on universe_init, must be before interpreter_init
  interpreter_init();        // before any methods loaded
  invocationCounter_init();  // before any methods loaded
  accessFlags_init();
  templateTable_init();
  InterfaceSupport_init();
  VMRegImpl::set_regName();  // need this before generate_stubs (for printing oop maps).
  SharedRuntime::generate_stubs();
  universe2_init();  // dependent on codeCache_init and stubRoutines_init1
  //初始化 Java 类、引用处理器、JNI 句柄等
  javaClasses_init();// must happen after vtable initialization, before referenceProcessor_init
  referenceProcessor_init();
  jni_handles_init();
#if INCLUDE_VM_STRUCTS
  vmStructs_init();
#endif // INCLUDE_VM_STRUCTS

  vtableStubs_init();
  InlineCacheBuffer_init();
  compilerOracle_init();
  dependencyContext_init();
//初始化编译器代理。
  if (!compileBroker_init()) {
    return JNI_EINVAL;
  }
	//universe初始化完成的通知.
  if (!universe_post_init()) {
    return JNI_ERR;
  }
  stubRoutines_init2(); // note: StubRoutines need 2-phase init
  //生成方法句柄的适配器
  MethodHandles::generate_adapters();
...

  return JNI_OK;
}
```

这里比较核心的就是`Universe`，我们之前就知道了堆的初始化就是在`Universe`中：

>在 Java 虚拟机（JVM）的内部实现中，Universe 是一个重要的概念，它代表了 JVM 中的整个运行时环境。Universe 是 JVM 的核心部分之一，负责管理 JVM 的各种数据结构、线程、类加载、垃圾回收等运行时元素。
>
>以下是 Universe 在 JVM 中的一些重要作用和功能：
>
>1. **类加载和存储**：Universe 负责加载、链接和存储 Java 类的信息。它管理类的加载过程，包括类的解析、初始化和存储，以便在程序运行时能够正确地访问和调用类的方法和字段。
>2. **内存管理**：Universe 管理 JVM 的内存分配和回收。它负责分配堆内存、栈内存、方法区等内存空间，以及执行垃圾回收操作来释放不再使用的对象和内存空间。
>3. **线程管理**：Universe 管理 JVM 中的线程，包括线程的创建、调度、同步等操作。它确保多线程程序能够正确地运行，并处理线程之间的并发访问和通信。
>4. **运行时数据结构**：Universe 维护 JVM 运行时数据结构，如方法区、堆、栈、常量池等。它管理这些数据结构的创建、访问和更新，以支持 Java 程序的运行和执行。
>5. **优化和性能调优**：Universe 可能还涉及 JVM 的优化和性能调优，包括即时编译（JIT 编译）、代码优化、内联优化等，以提高程序的执行效率和性能。
>
>总的来说，Universe 是 JVM 的核心部分，负责管理 JVM 的运行时环墩，包括类加载、内存管理、线程管理、运行时数据结构等方面的功能。它是 JVM 的基础架构之一，为 Java 程序的正确执行和高效运行提供了必要的支持。

总之`Universe`可以看作是JVM的内存管理模块

## 3.加载包含main函数的主类

JVM启动完成之后，下一步就是加载运行我们的代码了：

```c
mainClass = LoadMainClass(env, mode, what);
```

```c
static jclass
LoadMainClass(JNIEnv *env, int mode, char *name) {
    jmethodID mid;
    jstring str;
    jobject result;
    jlong start = 0, end = 0;
    jclass cls = GetLauncherHelperClass(env);
    NULL_CHECK0(cls);
    if (JLI_IsTraceLauncher()) {
        start = CounterGet();
    }
    NULL_CHECK0(mid = (*env)->GetStaticMethodID(env, cls,
                                                "checkAndLoadMain",
                                                "(ZILjava/lang/String;)Ljava/lang/Class;"));

    NULL_CHECK0(str = NewPlatformString(env, name));
    NULL_CHECK0(result = (*env)->CallStaticObjectMethod(env, cls, mid,
                                                        USE_STDERR, mode, str));

    if (JLI_IsTraceLauncher()) {
        end = CounterGet();
        printf("%ld micro seconds to load main class\n",
               (long) (jint) Counter2Micros(end - start));
        printf("----%s----\n", JLDEBUG_ENV_ENTRY);
    }

    return (jclass) result;
}
```

这里在`GetLauncherHelperClass`中加载的是：

```c
NULL_CHECK0(helperClass = FindBootStrapClass(env,
                                             "sun/launcher/LauncherHelper"));
```

就是`LauncherHelper`，然后接着执行这个类的`checkAndLoadMain`方法,`LauncherHelper`是 JDK 内部使用的一个辅助类，用于启动 Java 应用程序是一个java类，所以我们又回到了java中了（对比C和C++ java代码确实很简单）：

```java
public static Class<?> checkAndLoadMain(boolean printToStderr,
                                        int mode,
                                        String what) {
    initOutput(printToStderr);

    Class<?> mainClass = null;
  //根据当前模式
    switch (mode) {
        //moudel和源码模式，上文说到了源码模式其实就是转换为了moudle模式，只不过module就是编译的，所以这里两个模式是运行一个方法
        case LM_MODULE: case LM_SOURCE:
            mainClass = loadModuleMainClass(what);
            break;
        default:
        //不是moudle和源码模式
            mainClass = loadMainClass(mode, what);
            break;
    }

    // record the real main class for UI purposes
    // neither method above can return null, they will abort()
    appClass = mainClass;

    /*
     * Check if FXHelper can launch it using the FX launcher. In an FX app,
     * the main class may or may not have a main method, so do this before
     * validating the main class.
     */
    if (JAVAFX_FXHELPER_CLASS_NAME_SUFFIX.equals(mainClass.getName()) ||
        doesExtendFXApplication(mainClass)) {
        // Will abort() if there are problems with FX runtime
        FXHelper.setFXLaunchParameters(what, mode);
        mainClass = FXHelper.class;
    }
	//做校验和直接返回
    validateMainClass(mainClass);
    return mainClass;
}
```

可以看到逻辑相当简单，这里就是根据不同的模式去加载不同的主类，这里moudle和源码的就不多看了，用的很少，我们重点看`loadMainClass`:

```java
private static Class<?> loadMainClass(int mode, String what) {
    // get the class name
    String cn;
  //再次根据class文件和jar包来区分，如果是class那么就是当前参数就是主类名称
    switch (mode) {
        case LM_CLASS:
            cn = what;
            break;
        case LM_JAR:
        //jar包的情况单独获取
            cn = getMainClassFromJar(what);
            break;
        default:
            // should never happen
            throw new InternalError("" + mode + ": Unknown launch mode");
    }

    // 找到我们的主类
    cn = cn.replace('/', '.');
    Class<?> mainClass = null;
  //找到类加载器
    ClassLoader scl = ClassLoader.getSystemClassLoader();
    try {
        try {
          //加载主类，我们获取到的主类是一个字符串的类名称，这里要用类加载器来加载
            mainClass = Class.forName(cn, false, scl);
        } catch (NoClassDefFoundError | ClassNotFoundException cnfe) {
            if (System.getProperty("os.name", "").contains("OS X")
                    && Normalizer.isNormalized(cn, Normalizer.Form.NFD)) {
                try {
                    // On Mac OS X since all names with diacritical marks are
                    // given as decomposed it is possible that main class name
                    // comes incorrectly from the command line and we have
                    // to re-compose it
                    String ncn = Normalizer.normalize(cn, Normalizer.Form.NFC);
                    mainClass = Class.forName(ncn, false, scl);
                } catch (NoClassDefFoundError | ClassNotFoundException cnfe1) {
                    abort(cnfe1, "java.launcher.cls.error1", cn,
                            cnfe1.getClass().getCanonicalName(), cnfe1.getMessage());
                }
            } else {
                abort(cnfe, "java.launcher.cls.error1", cn,
                        cnfe.getClass().getCanonicalName(), cnfe.getMessage());
            }
        }
    } catch (LinkageError le) {
        abort(le, "java.launcher.cls.error6", cn,
                le.getClass().getName() + ": " + le.getLocalizedMessage());
    }
  //加载成功的主类直接返回
    return mainClass;
}
```

 这里我们看看`getMainClassFromJar`，看看我们的jar包到底是如何加载的：

```java
static String getMainClassFromJar(String jarname) {
    String mainValue;
  //用了JarFile这个类来加载我们的jar包，我们的jar包就是这样被加载到内存中
    try (JarFile jarFile = new JarFile(jarname)) {
      //获取MANIFEST.MF文件，
        Manifest manifest = jarFile.getManifest();
        if (manifest == null) {
            abort(null, "java.launcher.jar.error2", jarname);
        }
        Attributes mainAttrs = manifest.getMainAttributes();
        if (mainAttrs == null) {
            abort(null, "java.launcher.jar.error3", jarname);
        }

        // 在MANIFEST.MF文件中获取main-class属性
        mainValue = mainAttrs.getValue(MAIN_CLASS);
      //为空报错
        if (mainValue == null) {
            abort(null, "java.launcher.jar.error3", jarname);
        }
				//是否存在agent
        // Launcher-Agent-Class (only check for this when Main-Class present)
        String agentClass = mainAttrs.getValue(LAUNCHER_AGENT_CLASS);
        if (agentClass != null) {
            ModuleLayer.boot().findModule("java.instrument").ifPresent(m -> {
                try {
                    String cn = "sun.instrument.InstrumentationImpl";
                    Class<?> clazz = Class.forName(cn, false, null);
                    Method loadAgent = clazz.getMethod("loadAgent", String.class);
                    loadAgent.invoke(null, jarname);
                } catch (Throwable e) {
                    if (e instanceof InvocationTargetException) e = e.getCause();
                    abort(e, "java.launcher.jar.error4", jarname);
                }
            });
        }

        // Add-Exports and Add-Opens
        String exports = mainAttrs.getValue(ADD_EXPORTS);
        if (exports != null) {
            addExportsOrOpens(exports, false);
        }
        String opens = mainAttrs.getValue(ADD_OPENS);
        if (opens != null) {
            addExportsOrOpens(opens, true);
        }

       //javaFx相关，不用管
        if (mainAttrs.containsKey(
                new Attributes.Name(JAVAFX_APPLICATION_MARKER))) {
            FXHelper.setFXLaunchParameters(jarname, LM_JAR);
            return FXHelper.class.getName();
        }
			//拿到main class名称
        return mainValue.trim();
    } catch (IOException ioe) {
        abort(ioe, "java.launcher.jar.error1", jarname);
    }
    return null;
}
```

这里有一个基础的知识点，我们的java jar包都要有一个`MANIFEST.MF`,这个文件在`META-INFO`这个目录下，里面会存放我们程序的主类，如果你是springboot，那么也会有这个文件，只不过主类是spring的：

```
Manifest-Version: 1.0
Created-By: Maven JAR Plugin 3.2.2
Build-Jdk-Spec: 11
Implementation-Title: train-server
Implementation-Version: 1.0-SNAPSHOT
Main-Class: org.springframework.boot.loader.JarLauncher
Start-Class: com.XXX.XXXApplication
Spring-Boot-Version: 2.6.13
Spring-Boot-Classes: BOOT-INF/classes/
Spring-Boot-Lib: BOOT-INF/lib/
Spring-Boot-Classpath-Index: BOOT-INF/classpath.idx
Spring-Boot-Layers-Index: BOOT-INF/layers.idx
```

springboot的`MANIFEST.MF`文件里有一个main主类就是`org.springframework.boot.loader.JarLauncher`，所以springboot的启动类就是`org.springframework.boot.loader.JarLauncher`，然后还记录了一个`Start-Class`这个就是我们代码里指定的启动类,关于springboot的启动原理这里不赘述，感兴趣的读者可以去阅读小马哥的《Spring Boot编程思想》，里面第二章就是讲解springboot启动原理的。

简单总结一下这里就直接通过class或者jar包里的`MANIFEST.MF`文件记录的jar包来作为我们的主类，然后用类加载器来加载这个主类，返回给启动程序，下面就是启动程序运行这个main主类的main方法了.



## 4.执行main函数并且退出

最后两部分很简单了，就合并在一起说：

```c
  /*
     * The LoadMainClass not only loads the main class, it will also ensure
     * that the main method's signature is correct, therefore further checking
     * is not required. The main method is invoked here so that extraneous java
     * stacks are not in the application stack trace.
     */
    mainID = (*env)->GetStaticMethodID(env, mainClass, "main",
                                       "([Ljava/lang/String;)V");
    CHECK_EXCEPTION_NULL_LEAVE(mainID);

    /* Invoke main method. */
    (*env)->CallStaticVoidMethod(env, mainClass, mainID, mainArgs);
```

这里就是用JNI的方式调用主类的main方法，非常的简单

```C
#define LEAVE() \
    do { \
    //让当前Main线程同启动线程断联
        if ((*vm)->DetachCurrentThread(vm) != JNI_OK) { \
            JLI_ReportErrorMessage(JVM_ERROR2); \
            ret = 1; \
        } \
        if (JNI_TRUE) { \
        //创建一个新的名为DestroyJavaVM的线程，让该线程等待所有的非后台进程退出，并在最后执行(*vm)->DestroyJavaVM方法
            (*vm)->DestroyJavaVM(vm); \
            return ret; \
        } \
    } while (JNI_FALSE)
```



## 5.总结

这里用一张图来总结整个java执行的流程：

![java启动](/Users/zhangyunfan/Downloads/java启动.png)