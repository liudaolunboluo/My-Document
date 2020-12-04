# 解决现场问题之定时任务为啥会阻塞

- 背景：河北地区减刑假释系统反馈不定期会出现每分钟定时同步数据的定时任务不执行的情况和定时扫描FTP上的压缩包不执行的情况，症状是数据同步的日志（我们每分钟定时同步数据的日志是单独拆出来的）和扫描FTP压缩包的日志长时间没有产生新的，并且数据也确实没有同步过去，并且每次重启系统之后就好使了。

- 代码：首先来看看这两个定时任务的配置：![image.png](http://bed.thunisoft.com:9000/ibed/2020/06/12/A6YklIW5h.png)

就是普普通通的Spring+Quartz的定时任务配置，定时扫描FTP压缩包的在前，同步数据的在后。定时扫描FTP定时任务十分钟一次同步数据定时任务一分钟一次。



- 分析：如果只是日志没有产生但是任务执行了（比如压缩包被扫描到了数据有同步过去等等）那么就应该是logback的原因，但是根据实际情况来看任务并没有执行，在查看了日志之后也没有发现异常。于是叫驻地dump了线程日志，查看了线程日志之后醍醐灌顶。

首先拿到线程日志搜索`Blocked` 看看哪些线程阻塞了（个人习惯不用工具，请勿模仿），于是发现了若干线程阻塞了，比如：![image.png](http://bed.thunisoft.com:9000/ibed/2020/06/12/A6Ypb77i5.png)

具体到代码这个方法是这样写的

``` java

  private synchronized void syncExec(String xzbm) {
        recvXmlPath = "/recv3/" + xzbm + "/xml";
        recvattachmentPath = "/recv3/" + xzbm + "/attachment";
        log.debug("查询协同平台ftp上{}目录下的数据,开始...", recvXmlPath);
        // 查询所有xml文件文件名
        List<String> fileNameList = queryXtptFtpFiles();
        if (CollectionUtils.isEmpty(fileNameList)) {
            log.info("未查询到协同平台的消息,等待下一次查询");
            return;

```

是sync的所以这里会阻塞，并且在等待`0x00000007527b88f8` 这把锁，搜索之后发现这个代码生成了大量的阻塞的线程并且都是在等待这把锁。继续搜索发现有个状态为`Runnable`的线程上的这把锁：![image.png](http://bed.thunisoft.com:9000/ibed/2020/06/12/A6Yscp4ML.png)

看堆栈信息，非常眼熟，笔者在去年解决过类似问题：http://artery.thunisoft.com/posts/detail/dced6f75b17d4e38b1843b441accc442

问题很简单就是FtpClient没有设置连接超时时间，在FtpClient内部connect方法实际上是调用了`java.net.Socket`的connect方法，在方法内部人家写得很清楚：

```java
/**
     * Connects this socket to the server with a specified timeout value.
     * A timeout of zero is interpreted as an infinite timeout. The connection
     * will then block until established or an error occurs.
     *
     * @param   endpoint the <code>SocketAddress</code>
     * @param   timeout  the timeout value to be used in milliseconds.
     * @throws  IOException if an error occurs during the connection
     * @throws  SocketTimeoutException if timeout expires before connecting
     * @throws  java.nio.channels.IllegalBlockingModeException
     *          if this socket has an associated channel,
     *          and the channel is in non-blocking mode
     * @throws  IllegalArgumentException if endpoint is null or is a
     *          SocketAddress subclass not supported by this socket
     * @since 1.4
     * @spec JSR-51
     */
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
```



` A timeout of zero is interpreted as an infinite timeout. The  will then block until established or an error occurs.` 意思就是零超时被解释为无限超时。连接，然后将阻塞，直到建立或发生错误

然后在FTPClient的内部

```java
 private static final int DEFAULT_CONNECT_TIMEOUT = 0;
    protected int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
```

连接超时时间默认是0的，所以不设置的话这里默认就是无限长的超时时间，所以一旦FTP出问题了话就会一直阻塞在这里（个人建议能否将FTPClient设置超时时间强制作为规范）。**当然了这个不是今天的重点**



### 今天的重点的是为什么一个定时任务发生问题阻塞在了哪里后面的定时任务没有执行？

只有确认了这个问题才能确定没有执行同步数据的任务是不是和这个异常有关系。



查阅了资料之后发现定时任务默认是多线程执行的，关键代码如下：

- 在`SchedulerFactoryBean`类中进行初始化操作

  ```java
  /**
   * Load and/or apply Quartz properties to the given SchedulerFactory.
   * @param schedulerFactory the SchedulerFactory to initialize
   */
  private void initSchedulerFactory(SchedulerFactory schedulerFactory) throws SchedulerException, IOException {
      if (!(schedulerFactory instanceof StdSchedulerFactory)) {
          if (this.configLocation != null || this.quartzProperties != null ||
                  this.taskExecutor != null || this.dataSource != null) {
              throw new IllegalArgumentException(
                      "StdSchedulerFactory required for applying Quartz properties: " + schedulerFactory);
          }
          // Otherwise assume that no initialization is necessary...
          return;
      }
  
      // ...
  
      // 此为需要关注的代码
      if (this.taskExecutor != null) {
          mergedProps.setProperty(StdSchedulerFactory.PROP_THREAD_POOL_CLASS,
                  LocalTaskExecutorThreadPool.class.getName());
      }
      else {
          // Set necessary default properties here, as Quartz will not apply
          // its default configuration when explicitly given properties.
          mergedProps.setProperty(StdSchedulerFactory.PROP_THREAD_POOL_CLASS, SimpleThreadPool.class.getName());
          mergedProps.setProperty(PROP_THREAD_COUNT, Integer.toString(DEFAULT_THREAD_COUNT));
      }
  
      // ...
  }
  ```

  逻辑是，如果taskExecutor属性有注入值，就使用指定的线程池，一般Spring是会配置线程池的，线程池的参数可以自行指定。如果taskExecutor未注入值，就使用org.quartz.simple.SimpleThreadPool线程池，DEFAULT_THREAD_COUNT的值为10，即该线程池的大小为10。 
  我们的配置文件中未指定taskExecutor的，所以线程池是SimpleThreadPool的实例对象，池的大小为10。

- 在`QuartzSchedulerThread`类中进行触发定时任务

```java
 public void run() {
    boolean lastAcquireFailed = false;
    while (!this.halted) {
      try {
        synchronized (this.pauseLock) {
          while (this.paused && !this.halted) {
            try {
              this.pauseLock.wait(100L);
            } catch (InterruptedException ignore) {}
          } 
          if (this.halted)
            break; 
        } 
        int availTreadCount = this.qsRsrcs.getThreadPool().blockForAvailableThreads();
        if (availTreadCount > 0) {
          Trigger trigger = null;
          long l1 = System.currentTimeMillis();
          this.signaled = false;
          try {
            trigger = this.qsRsrcs.getJobStore().acquireNextTrigger(this.ctxt, l1 + this.idleWaitTime);
            lastAcquireFailed = false;
          } catch (JobPersistenceException jpe) {
              ...
```

省略了一些代码，关键代码是` int availTreadCount = this.qsRsrcs.getThreadPool().blockForAvailableThreads();`

意思是获取线程池可用资源数，内部是

``` java
 public int blockForAvailableThreads() {
    synchronized (this.nextRunnableLock) {
      while ((this.availWorkers.size() < 1 || this.handoffPending) && !this.isShutdown) {
        try {
          this.nextRunnableLock.wait(500L);
        } catch (InterruptedException ignore) {}
      } 
      return this.availWorkers.size();
    } 
  }
```

 我们可以看到，这个方法是阻塞的，并且如果没有多余的资源了的话就会一直等待，直到有资源才会开启trigger也就是定时任务。到了这里可以已经基本上推导出为啥我的定时任务有一个阻塞了后面的就不会执行了。然后我在现场的线程日志了找到这段![image.png](http://bed.thunisoft.com:9000/ibed/2020/06/12/A6ZiXO9Lt.png)

可以看到，这个阻塞的获取可用资源数的方法是等待态，所以到了这里我们可以结案了。



### 总结：Spring+Quartz在默认情况是多线程执行的，默认的线程池是SimpleThreadPool大小是10，如果线程池满了的话下面的任务就会一直阻塞等到线程池有空位了为止，所以如果有某个定时任务内部是sync的并且因为某些原因他在内部又一直无限阻塞了的话，那么这个线程池就会被这个任务给占满，后面的定时任务就无法执行了。或者说这个线程池不是无限阻塞只是阻塞一段时间，那么这段时间内的其他任务也无法执行。



### 解决办法： 

 1、在配置文件内部的jobDetail里的指定concurrent属性为false，默认是true

2、避免出现无限阻塞的情况，在任务内部合理设置好超时时间。



