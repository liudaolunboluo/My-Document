# Arthas 命令执行流程解析

 上文我们讲了arthas的启动原理，今天我们来简单讲讲arthas的命令运行流程。

## 1、启动时初始化命令执行器

我们先回顾一下上文讲arthas启动的时候是怎么去监听命令行输入的，首先在我们的启动核心类的`ArthasBootstrap`里面有一段特别长的代码取初始化shellServer，当时这个shellServer不是启动的重点所以没有具体讲，这里我们先回顾下这段代码：

```java
ShellServerOptions options = new ShellServerOptions()
                            .setInstrumentation(instrumentation)
                            .setPid(PidUtils.currentLongPid())
                            .setWelcomeMessage(ArthasBanner.welcome());
            if (configure.getSessionTimeout() != null) {
                options.setSessionTimeout(configure.getSessionTimeout() * 1000);
            }

            this.httpSessionManager = new HttpSessionManager();
            this.securityAuthenticator = new SecurityAuthenticatorImpl(configure.getUsername(), configure.getPassword());
            shellServer = new ShellServerImpl(options);
            List<String> disabledCommands = new ArrayList<String>();
          ...
            BuiltinCommandPack builtinCommands = new BuiltinCommandPack(disabledCommands);
            List<CommandResolver> resolvers = new ArrayList<CommandResolver>();
            resolvers.add(builtinCommands)

          ...

            shellServer.listen(new BindHandler(isBindRef));
```

这里实际上我们重点关注三行代码即可：

```java
 shellServer = new ShellServerImpl(options);
 ...
 BuiltinCommandPack builtinCommands = new BuiltinCommandPack(disabledCommands);
 ...
 shellServer.listen(new BindHandler(isBindRef));
```

第一行是new一个shellServer，说明这个shellSver的实现类是`ShellServerImpl`，然后第二行代码是在初始化所有的命令，在`BuiltinCommandPack`的构造函数里有：

```java
  public BuiltinCommandPack(List<String> disabledCommands) {
        initCommands(disabledCommands);
    }

  ...

    private void initCommands(List<String> disabledCommands) {
        List<Class<? extends AnnotatedCommand>> commandClassList = new ArrayList<Class<? extends AnnotatedCommand>>(32);
        commandClassList.add(HelpCommand.class);
        commandClassList.add(AuthCommand.class);
        commandClassList.add(KeymapCommand.class);
        commandClassList.add(SearchClassCommand.class);
        commandClassList.add(SearchMethodCommand.class);
        commandClassList.add(ClassLoaderCommand.class);
  ...
     		commandClassList.add(StopCommand.class);

        for (Class<? extends AnnotatedCommand> clazz : commandClassList) {
            Name name = clazz.getAnnotation(Name.class);
            if (name != null && name.value() != null) {
                if (disabledCommands.contains(name.value())) {
                    continue;
                }
            }
            commands.add(Command.create(clazz));
        }
```

这里会把所有的命令的对应的类都加载到缓存里后面会用到，这里可以看到每个命令都对应一个Command对象，注意最后放入缓存的对象是`AnnotatedCommandImpl`，上面的每个命令的Command只是这个类的一个属性。然后第三行代码就是初始化监听命令策略，在里面有

```java
termServer.termHandler(new TermServerTermHandler(this));
termServer.listen(handler);
```

设置term处理器和监听器，接下来看下`TelnetTermServer`中的`listen`方法，termServer有几个实现类比如Http、命令行等，这里以`TelnetTermServer`命令行为例讲解，在listen方法中：

```java
 bootstrap = new NettyTelnetTtyBootstrap().setHost(hostIp).setPort(port);
        try {
            bootstrap.start(new Consumer<TtyConnection>() {
                @Override
                public void accept(final TtyConnection conn) {
                    termHandler.handle(new TermImpl(Helper.loadKeymap(), conn));
                }
            }).get(connectionTimeout, TimeUnit.MILLISECONDS);
           ...
```

这里会调用调用的是 `NettyTelnetBootstrap` 的 `start`方法，主要是通过 `netty` 来启动网络服务，注意这里使用的是阿里巴巴自己的termd来h实现的，也就是说arthas命令行的核心是用的termd，github地址为：https://github.com/alibaba/termd，感兴趣的读者可以自行了解，反正这个库就是支持java命令行的,然后在监听到有命令的时候会调用`termHandler.handle`来处理，这里的`termHandler`的实现类是上一步设置的`TermServerTermHandler`，这个这个类的`handle`方法中是：

```java
 @Override
    public void handle(Term term) {
        shellServer.handleTerm(term);
    }
```

所以我们又回到了`ShellServerImpl`类，上文的侧重点主要是启动流程所以这里讲的比较简单，这里补充一下，然后我们在看`ShellServerImpl`的`handleTerm`方法：

```java
 ShellImpl session = createShell(term);
        tryUpdateWelcomeMessage();
        session.setWelcome(welcomeMessage);
        session.closedFuture.setHandler(new SessionClosedHandler(this, session));
        session.init();
        sessions.put(session.id, session); // Put after init so the close handler on the connection is set
        session.readline(); // Now readline
```

这里上文有讲到，我们重点是最后一行的`session.readline(); `这里是在监听处理输入的，然后在里面有：

```java
public void readline() {
        term.readline(prompt, new ShellLineHandler(this),
                new CommandManagerCompletionHandler(commandManager));
    }
```

上文有提到过，但是上文这里有个地方讲错了，这一步实际上是设计termd的两个处理器——命令行处理器和完成处理器，也就是termd的`requestHandler`和`completionHandler`，实际上这里读取命令输入是用的termd的`Readline`类的`readLine`方法

```java
public void readline(TtyConnection conn, String prompt, Consumer<String> requestHandler, Consumer<Completion> completionHandler)
```

这里就指定了命令请求处理器和命令完成处理器。

## 2、命令执行



两个处理器——我们对应的就是`ShellLineHandler`和`CommandManagerCompletionHandler`分别在输入的时候执行和完成的时候执行，会执行`requestHandler和`completionHandler`的`accept方法，这里会把上文两个Handler封装一下，调用accept的时候实际上就是调用Handler的handle方法，所以我们执行命令的最终入口就是`ShellLineHandler`的`handle`方法：

```java
@Override
    public void handle(String line) {
        if (line == null) {
            // EOF
            handleExit();
            return;
        }

        List<CliToken> tokens = CliTokens.tokenize(line);
        CliToken first = TokenUtils.findFirstTextToken(tokens);
        if (first == null) {
            // For now do like this
            shell.readline();
            return;
        }

        String name = first.value();
        if (name.equals("exit") || name.equals("logout") || name.equals("q") || name.equals("quit")) {
            handleExit();
            return;
        } else if (name.equals("jobs")) {
            handleJobs();
            return;
        } else if (name.equals("fg")) {
            handleForeground(tokens);
            return;
        } else if (name.equals("bg")) {
            handleBackground(tokens);
            return;
        } else if (name.equals("kill")) {
            handleKill(tokens);
            return;
        }

        Job job = createJob(tokens);
        if (job != null) {
            job.run();
        }
    }
```

首先会去解析输入的命令，一般可能你的命令带参数比如trace命令会带上类和方法名，所以这里要把命令从输入中解析出来，可以看到如果是是exit,logout,quit,jobs,fg,bg,kill等arthas本身的运行命令直接执行，如果是其他命令就创建一个job来执行。下面会到`JobControllerImpl`的`createJob`的方法里：

```java
 @Override
    public Job createJob(InternalCommandManager commandManager, List<CliToken> tokens, Session session, JobListener jobHandler, Term term, ResultDistributor resultDistributor) {
        checkPermission(session, tokens.get(0));
        int jobId = idGenerator.incrementAndGet();
        StringBuilder line = new StringBuilder();
        for (CliToken arg : tokens) {
            line.append(arg.raw());
        }
        boolean runInBackground = runInBackground(tokens);
        Process process = createProcess(session, tokens, commandManager, jobId, term, resultDistributor);
        process.setJobId(jobId);
        JobImpl job = new JobImpl(jobId, this, process, line.toString(), runInBackground, session, jobHandler);
        jobs.put(jobId, job);
        return job;
    }
```

这里重点关注` Process process = createProcess(session, tokens, commandManager, jobId, term, `这一行代码会根据输入找到命令然后封装成Process然后下面再把Process包装成job，我们先看怎么封装成Process：

```java
 private Process createProcess(Session session, List<CliToken> line, InternalCommandManager commandManager, int jobId, Term term, ResultDistributor resultDistributor) {
        try {
            ListIterator<CliToken> tokens = line.listIterator();
            while (tokens.hasNext()) {
                CliToken token = tokens.next();
                if (token.isText()) {
                    // check before create process
                    checkPermission(session, token);
                    Command command = commandManager.getCommand(token.value());
                    if (command != null) {
                        return createCommandProcess(command, tokens, jobId, term, resultDistributor);
                    } else {
                        throw new IllegalArgumentException(token.value() + ": command not found");
                    }
                }
            }
...
```

这里会用到刚刚解析出来的命令的字符串去`InternalCommandManager`里找到相应的命令，getCommand就是在刚刚启动加载的命令缓存里去找到相应的命令也就是`AnnotatedCommandImpl`对象。然后在`createCommandProcess`方法里封装成Proccess对象，这里代码很长就不赘述了，反正就是把Command对象封装一下，有的命令会有特殊处理，比如watch命令会有管道符"|"这里要处理一下，然后热更新命令会有文件等参数也需要处理一下，总之在`createCommandProcess`方法里：

```java
ProcessImpl process = new ProcessImpl(command, remaining, command.processHandler(), ProcessOutput, resultDistributor);
```

这里就是完成的把命令封装成了process。然后再把Process放到job里，就完成初始化job等流程了。

在job初始化完毕之后就是调用job的`run`方法来执行了：

```java
 @Override
    public Job run(boolean foreground) {
        actualStatus = ExecStatus.RUNNING;
        if (statusUpdateHandler != null) {
            statusUpdateHandler.handle(ExecStatus.RUNNING);
        }
        process.setSession(this.session);
        process.run(foreground);

        if (this.status() == ExecStatus.RUNNING) {
            if (foreground) {
                jobHandler.onForeground(this);
            } else {
                jobHandler.onBackground(this);
            }
        }
        return this;
    }
```

在job的run里面实际上是在执行process的run方法：

```java
 if (processStatus != ExecStatus.READY) {
            throw new IllegalStateException("Cannot run proces in " + processStatus + " state");
        }

        processStatus = ExecStatus.RUNNING;
        processForeground = fg;
        foreground = fg;
        startTime = new Date();

        // Make a local copy
        final Tty tty = this.tty;
        if (tty == null) {
            throw new IllegalStateException("Cannot execute process without a TTY set");
        }

        process = new CommandProcessImpl(this, tty);
        if (resultDistributor == null) {
            resultDistributor = new TermResultDistributorImpl(process, ArthasBootstrap.getInstance().getResultViewResolver());
        }

        final List<String> args2 = new LinkedList<String>();
        for (CliToken arg : args) {
            if (arg.isText()) {
                args2.add(arg.value());
            }
        }

        CommandLine cl = null;
        try {
            if (commandContext.cli() != null) {
                if (commandContext.cli().parse(args2, false).isAskingForHelp()) {
                    appendResult(new HelpCommand().createHelpDetailModel(commandContext));
                    terminate();
                    return;
                }

                cl = commandContext.cli().parse(args2);
                process.setArgs2(args2);
                process.setCommandLine(cl);
            }
        } catch (CLIException e) {
            terminate(-10, null, e.getMessage());
            return;
        }

        if (cacheLocation() != null) {
            process.echoTips("job id  : " + this.jobId + "\n");
            process.echoTips("cache location  : " + cacheLocation() + "\n");
        }
        Runnable task = new CommandProcessTask(process);
        ArthasBootstrap.getInstance().execute(task);
    }
```

这里重点看这行` Runnable task = new CommandProcessTask(process);`就是初始化一个runable，然后丢到线程池里执行，那么在`CommandProcessTask`的run方法里：

```java
  @Override
        public void run() {
            try {
                handler.handle(process);
            } catch (Throwable t) {
                logger.error("Error during processing the command:", t);
                process.end(1, "Error during processing the command: " + t.getClass().getName() + ", message:" + t.getMessage()
                        + ", please check $HOME/logs/arthas/arthas.log for more details." );
            }
        }
```

执行的就是handler的handle方法，这个handler就是在上一步把Command封装成Process对象的时候：

```java
ProcessImpl process = new ProcessImpl(command, remaining, command.processHandler(), ProcessOutput, resultDistributor);
```

放进来的，这个handler就是Command的processHandler属性，所以我们看Command缓存的`AnnotatedCommandImpl`类:

```java
 private Handler<CommandProcess> processHandler = new ProcessHandler();
 ...
   private class ProcessHandler implements Handler<CommandProcess> {
        @Override
        public void handle(CommandProcess process) {
            process(process);
        }
    }
...
  private class ProcessHandler implements Handler<CommandProcess> {
        @Override
        public void handle(CommandProcess process) {
            process(process);
        }
    }
...
private void process(CommandProcess process) {
        AnnotatedCommand instance;
        try {
            instance = clazz.newInstance();
        } catch (Exception e) {
            process.end();
            return;
        }
        CLIConfigurator.inject(process.commandLine(), instance);
        instance.process(process);
        UserStatUtil.arthasUsageSuccess(name(), process.args());
    }
    
```

这里实际上就是在执行`AnnotatedCommandImpl`的process方法，而process方法就是在执行每种命令Command的process方法![WX20220220-155929](/Users/zhangyunfan/Desktop/WX20220220-155929.png)

这里有一个EnhancerCommand比较特殊，如果是需要字节码增强的命令都是这个类的子类比如trace、watch，其他的命令就是简单的不需要字节码增强的命令。



## 3、总结

总结一下arthas命令执行的流程是：

1、启动的时候把命令的Command对象封装成`AnnotatedCommandImpl`类，放入到缓存中

2、利用Termd框架来监听和处理命令行的输入

3、监听到输入时先解析出输入的命令，如果是Arthas服务的命令就直接执行如果不是就封装成job

4、在job里会根据输入的命令找到缓存里的`AnnotatedCommandImpl`对，然后封装成Process对象，再把Process放入到job里

5、初始化job完毕之后执行job的run方法，job到run里面在执行Process的run方法，最后Process的run方法内部就是调用命令Command类的process方法

所以我们如果要查看某个命令的原理只要找到对应的Command类看他的process方法就可以了，后面我们会抽几个比较实用的命令看看他们的原理。