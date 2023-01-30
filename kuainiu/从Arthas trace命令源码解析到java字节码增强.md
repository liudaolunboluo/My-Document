# 从Arthas trace命令源码解析



arthas的trace命令应该是一个非常实用的命令，他可以打印出指定类的指定方法的方法栈中的所有方法的运行时间:

```shell
`---ts=2023-01-18 15:23:52;thread_name=http-nio-8085-exec-1;id=27;is_daemon=true;priority=5;TCCL=org.springframework.boot.web.embedded.tomcat.TomcatEmbeddedWebappClassLoader@2a50b32d
    `---[291.091792ms] com.kuainiu.ai.platform.train.service.TrainProfileService:getJupyterConfig()
        +---[0.00% 0.008167ms ] com.kuainiu.ai.platform.train.enums.RedisKeyEnum:getKey() #107
        `---[87.97% 256.081291ms ] com.kuainiu.ai.platform.train.service.TrainProfileService:hgetall() #107
```

就像这样，新版本还可以打印出占比时间。那么trace命令的究竟是如何实现的呢？

首先阅读本篇需要先阅读之前笔者的——[Arthas 命令执行流程解析](https://mp.weixin.qq.com/s/CA7JTseAnsu8I7BI_enFdQ)

通过上篇文章我们知道了arthas命令执行的大概逻辑，那么我们可以很轻松的找出trace命令对应的类——`TraceCommand`，我们可以看到`TraceCommand`是继承了`EnhancerCommand`类的，所有需要字节码增强的命令都会继承这个类，所以我们的trace命令的原理也是基于字节码增强的，所以我们需要首先来看看`EnhancerCommand`类。

根据上文我们可以知道每个Command的proecss方法就是执行命令的入口，所以我们直接看`EnhancerCommand`类的process方法：

````java
 		@Override
    public void process(final CommandProcess process) {
        // ctrl-C support
        process.interruptHandler(new CommandInterruptHandler(process));
        // q exit support
        process.stdinHandler(new QExitHandler(process));

        // 开始增强
        enhance(process);
    }
````

```java
 protected void enhance(CommandProcess process) {
        Session session = process.session();
   			//如果已加锁，直接返回
        if (!session.tryLock()) {
            String msg = "someone else is enhancing classes, pls. wait.";
            process.appendResult(new EnhancerModel(null, false, msg));
            process.end(-1, msg);
            return;
        }
        EnhancerAffect effect = null;
   			//获取锁
        int lock = session.getLock();
        try {
          	//从session中获取Instrumentation
            Instrumentation inst = session.getInstrumentation();
          	//获取命令的通知监听器，这里底层是调用的子类自己的方法，trace是TraceAdviceListener和PathTraceAdviceListener，根据有没有路径匹配符决定
            AdviceListener listener = getAdviceListenerWithId(process);
            if (listener == null) {
                logger.error("advice listener is null");
                String msg = "advice listener is null, check arthas log";
                process.appendResult(new EnhancerModel(effect, false, msg));
                process.end(-1, msg);
                return;
            }
            boolean skipJDKTrace = false;
          	//专门针对trace的，是否忽略jdk自己的方法，有个参数就是skipJDKTrace，默认是true，如果是false就会打印出jdk方法的执行时间
            if(listener instanceof AbstractTraceAdviceListener) {
                skipJDKTrace = ((AbstractTraceAdviceListener) listener).getCommand().isSkipJDKTrace();
            }
						//对目标类进行增强，新建一个增强器
            Enhancer enhancer = new Enhancer(listener, listener instanceof InvokeTraceable, skipJDKTrace, getClassNameMatcher(), getClassNameExcludeMatcher(), getMethodNameMatcher());
            // 注册通知监听器
            process.register(listener, enhancer);
          	//增强
            effect = enhancer.enhance(inst);
						//增强失败代码，省略，大概就是报错和打印可能的原因
           ...

            // 这里做个补偿,如果在enhance期间,unLock被调用了,则补偿性放弃
            if (session.getLock() == lock) {
                if (process.isForeground()) {
                    process.echoTips(Constants.Q_OR_CTRL_C_ABORT_MSG + "\n");
                }
            }
            process.appendResult(new EnhancerModel(effect, true));

            //异步执行，在AdviceListener中结束
        } catch (Throwable e) {
            String msg = "error happens when enhancing class: "+e.getMessage();
            logger.error(msg, e);
            process.appendResult(new EnhancerModel(effect, false, msg));
            process.end(-1, msg);
        } finally {
            if (session.getLock() == lock) {
                // enhance结束后解锁
                process.session().unLock();
            }
        }
    }
```

增强的核心代码就是` effect = enhancer.enhance(inst);`，我们接着看`Enhancer`的`enhance`方法。

```java
  public synchronized EnhancerAffect enhance(final Instrumentation inst) throws UnmodifiableClassException {
        // 获取需要增强的类集合
        this.matchingClasses = GlobalOptions.isDisableSubClass
                ? SearchUtils.searchClass(inst, classNameMatcher)
                : SearchUtils.searchSubClass(inst, SearchUtils.searchClass(inst, classNameMatcher));
        // 过滤掉无法被增强的类
        List<Pair<Class<?>, String>> filtedList = filter(matchingClasses);
        if (!filtedList.isEmpty()) {
            for (Pair<Class<?>, String> filted : filtedList) {
                logger.info("ignore class: {}, reason: {}", filted.getFirst().getName(), filted.getSecond());
            }
        }
        affect.setTransformer(this);

        try {
            ArthasBootstrap.getInstance().getTransformerManager().addTransformer(this, isTracing);

            // 批量增强
            if (GlobalOptions.isBatchReTransform) {
                final int size = matchingClasses.size();
                final Class<?>[] classArray = new Class<?>[size];
                arraycopy(matchingClasses.toArray(), 0, classArray, 0, size);
                if (classArray.length > 0) {
                    inst.retransformClasses(classArray);
                    logger.info("Success to batch transform classes: " + Arrays.toString(classArray));
                }
            } else {
                // for each 增强
                for (Class<?> clazz : matchingClasses) {
                    try {
                        inst.retransformClasses(clazz);
                        logger.info("Success to transform class: " + clazz);
                    } catch (Throwable t) {
...
    }
```

上面的代码有点复杂我们单独说，首先参数中有一个`Instrumentation`，那么Instrumentation是怎么来的有什么用呢？这里我们复习一下arthas的启动流程，arthas启动的时候会通过JVM的`VirtualMachineDescriptor`类attach到目标java进程上，attach成功之后就返回一个`VirtualMachine`对象，然后通过`VirtualMachine`对象的`loadAgent`方法来植入我们的agent，那么这个时候我们就可以获取到`Instrumentation`对象了，忘记了的同学可以看看：[Arthas源码分析—启动源码分析](https://mp.weixin.qq.com/s/9bbfmeIEirtOYtEdPKGf2A)

所以我们是通过attach目标java进程然后强制加载agent之后拿到的Instrumentation，我们可以通过Instrumentation进行字节码增强等操作。Instrumentation类有一个方法——retransformClasses，这个retransformClasses的作用就是可以在已存在的字节码文件上修改后再替换，所以上面代码中的批量增强和for each增强的核心代码都是：`inst.retransformClasses(classArray);`增强我们的目标类。只是一个是class数组一个是单个class而已。

`Enhancer`实现了`ClassFileTransformer`接口，这个是java agent的类，作用是在类的字节码载入jvm前会调用ClassFileTransformer的transform方法，那么这个时候是怎么执行到`Enhancer`类的transform方法的呢？，核心代码是这句

```java
   ArthasBootstrap.getInstance().getTransformerManager().addTransformer(this, isTracing);
```

TransformerManager是一个管理类，他是在ArthasBootstrap初始化的时候new出来的，那么在TransformerManager的构造方法里：

```java
public TransformerManager(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;

        classFileTransformer = new ClassFileTransformer() {

            @Override
            public byte[] transform() {
              
              for (ClassFileTransformer classFileTransformer : reTransformers) {
                    byte[] transformResult = classFileTransformer.transform(loader, className, classBeingRedefined,
                            protectionDomain, classfileBuffer);
                    if (transformResult != null) {
                        classfileBuffer = transformResult;
                    }
                }

                for (ClassFileTransformer classFileTransformer : watchTransformers) {
                   ...
                }

                for (ClassFileTransformer classFileTransformer : traceTransformers) {
                 ...
                }

                return classfileBuffer;
            }

        };
        instrumentation.addTransformer(classFileTransformer, true);
    }
```

就是新建了一个classFileTransformer然后注册到instrumentation，所以我们在inst.retransformClasses之后执行到就是这里这个自定义的classFileTransformer的transform方法。

这里我简化了一下代码，为了大家看着方便，这里其实就是三个for循环，把三个Transformers里的Transformer拿出来执行他们自己的transform方法，然后返回。然后我们的transform就是在` ArthasBootstrap.getInstance().getTransformerManager().addTransformer(this, isTracing);`这个代码的`addTransformer(this, isTracing)`注册进去的，这里传入的是this，所以最后调用回来了，调用的就是`Enhancer`的transform方法。

这里之所以要绕这么大一个圈子的原因就是要统一管理classFileTransformer，要不然到处都在`instrumentation.addTransformer`

那么我们就继续看`Enhancer`的`tranform`方法，这里使用的是ASM框架来做的字节码增强。由于`Enhancer`的`tranform`方法特别长和复杂，为了省略篇幅就简单说明下他的作用（感兴趣的同学可以看其他博主写的解析，非常清晰：https://mp.weixin.qq.com/s?__biz=MzkyMjIzOTQ3NA==&mid=2247484612&idx=1&sn=f6901c5794cf372d8561b6f494c596f3&source=41#wechat_redirect），`tranform`方法使用了alibaba的bytekit字节码处理工具（https://github.com/alibaba/bytekit）会在待插装的代码的特定位置插装一个函数，如果是trace命令会在方法栈中的其他方法中也插装特定的函数，会转发到SpyAPI了里来，SpyAPI有三个方法：`atExit`、`atEnter`、`atExceptionExit`分别对应执行前、执行后和异常的时候，还有两个特殊一点的——`atBeforeInvoke`、`atAfterInvoke`分别代表在被其他方法调用前和其他方法调用后，这就和aop、拦截器呼应上了，也就是说我们通过这三个方法来在指定的方法执行前、执行后、异常时执行指定的方法，这三个方法大差不差，所以我们就看atEnter方法：

```java
@Override
    public void atEnter(Class<?> clazz, String methodInfo, Object target, Object[] args) {
        ClassLoader classLoader = clazz.getClassLoader();

        String[] info = StringUtils.splitMethodInfo(methodInfo);
        String methodName = info[0];
        String methodDesc = info[1];
        // TODO listener 只用查一次，放到 thread local里保存起来就可以了！
        List<AdviceListener> listeners = AdviceListenerManager.queryAdviceListeners(classLoader, clazz.getName(),
                methodName, methodDesc);
        if (listeners != null) {
            for (AdviceListener adviceListener : listeners) {
                try {
                    if (skipAdviceListener(adviceListener)) {
                        continue;
                    }
                    adviceListener.before(clazz, methodName, methodDesc, target, args);
                } catch (Throwable e) {
                    logger.error("class: {}, methodInfo: {}", clazz.getName(), methodInfo, e);
                }
            }
        }
```



这里就是很简单的查询listener然后调用listener的before方法就可以了，那么listener是啥？listener是在transform方法注册的

```java
   // enter/exist 总是要插入 listener
                AdviceListenerManager.registerAdviceListener(inClassLoader, className, methodNode.name, methodNode.desc,
                        listener);
```

也就是在增强之前注册的，那么回到我们的trace命令，traceCommand提供了两个listener：

```java
 @Override
    protected AdviceListener getAdviceListener(CommandProcess process) {
        if (pathPatterns == null || pathPatterns.isEmpty()) {
            return new TraceAdviceListener(this, process, GlobalOptions.verbose || this.verbose);
        } else {
            return new PathTraceAdviceListener(this, process);
        }
    }
```

这里才走到trace命令的核心逻辑里来，不过这里就简单了，我们就看listener就可以了

TraceAdviceListener和PathTraceAdviceListener；所以这里的listener就是PathTraceAdviceListener或者TraceAdviceListener

pathPatterns参数对应的是：

```java
 @Option(shortName = "p", longName = "path", acceptMultipleValues = true)
    @Description("path tracing pattern")
    public void setPathPatterns(List<String> pathPatterns) {
        this.pathPatterns = pathPatterns;
    }
```

TraceAdviceListener和PathTraceAdviceListener的区别就是TraceAdviceListener比PathTraceAdviceListener多了三个方法——invokeBeforeTracing、invokeAfterTracing、invokeThrowTracing，意思是被其他类调用的时候调用，arthas会在被观测类中的给其他调用的方法都插装入这三个inoke相关的方法，所以笔者大胆猜测PathTraceAdviceListener也就是加了-p参数的只会打印指定路径的这个类的这个方法的耗时并不会去打印方法栈中其他方法的耗时，而TraceAdviceListener则可以打印其他类的耗时。

那么我们看TraceAdviceListener就行了。TraceAdviceListener的enter等方法均在其父类——`AbstractTraceAdviceListener`中，我们先看enter方法：

```java
  @Override
    public void before(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args)
            throws Throwable {
        TraceEntity traceEntity = threadLocalTraceEntity(loader);
        traceEntity.tree.begin(clazz.getName(), method.getName(), -1, false);
        traceEntity.deep++;
        // 开始计算本次方法调用耗时
        threadLocalWatch.start();
    }

```

这里就是新建了一个TraceEntity，这里TraceEntity就，回传入一个类加载器，TraceEntity会保存在threadLocal中。然后就调用begin()方法，初始化一个节点到本次traceEntity的结果树中，然后就开始计时，开始计时就是获取当前时间并且保存在threadlocal中。

然后在afterReturning也就是返回之前:

```java
  @Override
    public void afterReturning(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                               Object returnObject) throws Throwable {
        threadLocalTraceEntity(loader).tree.end();
        final Advice advice = Advice.newForAfterReturning(loader, clazz, method, target, args, returnObject);
        finishing(loader, advice);
    }
```

会调用之前开始的时候新建的traceEntity的结束方法，表明这个trace节点已经结束。

在finishing方法中：

```java
 private void finishing(ClassLoader loader, Advice advice) {
        // 本次调用的耗时
        TraceEntity traceEntity = threadLocalTraceEntity(loader);
        if (traceEntity.deep >= 1) { 
            traceEntity.deep--;
        }
        if (traceEntity.deep == 0) {
            double cost = threadLocalWatch.costInMillis();
            try {
                boolean conditionResult = isConditionMet(command.getConditionExpress(), advice, cost);
                if (this.isVerbose()) {
                    process.write("Condition express: " + command.getConditionExpress() + " , result: " + conditionResult + "\n");
                }
                if (conditionResult) {
                    // 满足输出条件
                    process.times().incrementAndGet();
                    process.appendResult(traceEntity.getModel());

                    // 是否到达数量限制
                    if (isLimitExceeded(command.getNumberOfLimit(), process.times().get())) {
                        // TODO: concurrency issue to abort process
                        abortProcess(process, command.getNumberOfLimit());
                    }
                }
            } catch (Throwable e) {
                logger.warn("trace failed.", e);
                process.end(1, "trace failed, condition is: " + command.getConditionExpress() + ", " + e.getMessage()
                              + ", visit " + LogUtil.loggingFile() + " for more details.");
            } finally {
                threadBoundEntity.remove();
            }
        }
    }
```

这里比较复杂一点，因为被观测的方法中还有其他方法，在运行其他方法的时候会先执行invokeBeforeTracing、invokeAfterTracing、invokeThrowTracing这三个方法，然后才是before、after，所以在其他方法执行之前都会在invokeBeforeTracing中执行：

```java
  threadLocalTraceEntity(classLoader).tree.begin(tracingClassName, tracingMethodName, tracingLineNumber, true);
```

也就是说拿到被观测的方法的树，然后加入到这个树的节点中去。然后再执行这些方法自己的before、after等方法最后所有方法都执行完了再去finsh里打印最终的结果。



## 总结

从trace命令出发我们研究了arthas的字节码增强命令的相关原理和实现，这里简单总结一下——就是通过javaagent的Instrumentation对象的addTransformer方法来注册transform，然后通过Instrumentation对象的retransformClasses方法来修改我们类的字节码，在transform方法中，通过arthas自己的bitKit工具包来修改增强字节码，插装指定的SpyAPI来实现在指定的方法之前之后执行指定的函数，在SpyApi中又调用了每个Command自己的listener来实现Command自己的逻辑。这里以trace为例抛砖引玉，大家可以自己再去看看例如watch命令这种需要字节码增强的命令。

对arthas的字节码插装感兴趣的朋友可以阅读：https://mp.weixin.qq.com/s?__biz=MzkyMjIzOTQ3NA==&mid=2247484612&idx=1&sn=f6901c5794cf372d8561b6f494c596f3&source=41#wechat_redirect

来详细了解arthas在transform方法中是怎么插装函数的

对字节码增强技术有兴趣的朋友可以阅读：https://tech.meituan.com/2019/09/05/java-bytecode-enhancement.html

来详细了解什么是字节码增强如何自己实现字节码增强