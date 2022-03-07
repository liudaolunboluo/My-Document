# 我给JDK提了一个BUG——当使用futureTask时且拒绝策略会放弃新任务的时候可能会无限阻塞



## 问题

大概上上周我们的服务灰度发布（相当于预发环境），我有一个xxl-job的任务新上线（xxl-job是一个轻量级分布式任务调度平台），这个任务功能就是扫描我们的每个内容的排行榜然后把排行榜（redis的zset）里排名前列并且满足某种条件的用户加载到redis里缓存里，刚刚上线的时候全量执行然后后面每半个小时加载增量的。灰度之后运维会帮忙执行job，我以为这次发布会和之前所有的发布一样顺利，突然运维找过来说我这个job怎么还没执行完，已经执行了20多分钟了，我当时就很奇怪，按理来说不应该啊，在测试环境这个job瞬间就执行完了，线上环境数据会多点但是不至于说要20分钟啊，然后叫运维马上杀掉了任务然后去节点dump了线程日志，线程日志显示任务是阻塞在了FutureTask的get方法里的awaitDone方法里。



## 排查过程

我在任务里是用了多线程并发的去扫描不同的排行榜，简单来说是用了java的callAble和futureTask来等待任务的返回并且把结果打印出来（实际上用的是guava的successfulAsList，不过和jdk原生的原理都差不多这里这个东西也不是重点）。首先我们的线程池是自定义的线程池，线程池代码如下：

```java
 ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
                30,
                50,
                10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(
                100),
                Executors.defaultThreadFactory(), new ThreadPoolExecutor.DiscardPolicy());
```

根据设置这个线程池可运行最多50个任务然后可以有100个排队的，所以当任务数大于150之后就会触发拒绝策略，拒绝策略是拒绝后面的任务（其实现在想来这个策略不太合理）。futureTask的get如下： 

```java
public V get() throws InterruptedException, ExecutionException {
        int s = state;
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        return report(s);
    }
```

这里会判断futureTask的状态，如果futureTask的状态小于等于完成态那么就在awaitDone方法里自旋判断，首先futureTask的状态有如下几个：

```java
  	private volatile int state;
    private static final int NEW          = 0;
    private static final int COMPLETING   = 1;
    private static final int NORMAL       = 2;
    private static final int EXCEPTIONAL  = 3;
    private static final int CANCELLED    = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED  = 6;
```

也就是说小于等于完成状态的只有完成状态和初始状态，那么我们在awaitDone方法里看看:

```java
 private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        long startTime = 0L;    // Special value 0L means not yet parked
        WaitNode q = null;
        boolean queued = false;
   			//自旋
        for (;;) {
            int s = state;
          	//如果大于完成态就是已经执行完那么就把线程设置为空然后直接返回
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null;
                return s;
            }
          //如果是完成状态那么就把CPU让出来给其他任务
            else if (s == COMPLETING)
                Thread.yield();
          //如果线程被中断了就报错
            else if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }
          //第一次进来这里q肯定是null的所以要初始化这个等待节点
            else if (q == null) {
                if (timed && nanos <= 0L)
                    return s;
                q = new WaitNode();
            }
            else if (!queued)
              //CAS的方式构建阻塞waiters栈
                queued = WAITERS.weakCompareAndSet(this, q.next = waiters, q);
            else if (timed) {
                final long parkNanos;
                if (startTime == 0L) { // first time
                    startTime = System.nanoTime();
                    if (startTime == 0L)
                        startTime = 1L;
                    parkNanos = nanos;
                } else {
                    long elapsed = System.nanoTime() - startTime;
                    if (elapsed >= nanos) {
                        removeWaiter(q);
                        return state;
                    }
                    parkNanos = nanos - elapsed;
                }
                // nanoTime may be slow; recheck before parking
                if (state < COMPLETING)
                    LockSupport.parkNanos(this, parkNanos);
            }
            else
              //阻塞当前获取FutureTask类执行结果的线程
                LockSupport.park(this);
        }
    }
```

这里的大概意思就是根据任务的状态来执行对应的操作，我们的线程日志是显示线程卡在了`LockSupport.park(this);`这一行，有源码可知代码能够走到这里来说明我们的任务的状态是NEW也就是初始化状态。这里被阻塞了的线程按理来说会在futureTask的run方法里被唤醒，这里再简单说下futureTask的执行原理，他有一个run方法，run内部才是去调用的call方法，然后调用完了之后会用CAS把任务的状态设置为完成态然后再设置成NORMOL也就是最终态，然后再把上文中的调用park方法了的线程唤醒。综合原理和我们遇到的现象来看应该就是futureTask没有执行run方法也就是说这个任务一直没有被执行所以就没有去更改状态所以一直都是NEW的初始状态。那么为什么一直没有执行呢？排查到这里我又想了一下为什么测试环境不会出问题而线上出问题，对比了一下两个环境到不同点发现线上环境数据远远多于测试环境但是也不是特别多大概测试环境会有80个任务而线上环境会有200多个任务，那么线上环境和测试环境的不同点在于线上环境会触发我们的拒绝策略，那么问题可能会是拒绝策略导致的？我们看下我们设置的拒绝策略`ThreadPoolExecutor.DiscardPolicy()`的源码：

```java
 public static class DiscardPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardPolicy}.
         */
        public DiscardPolicy() { }

        /**
         * Does nothing, which has the effect of discarding task r.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }
```

看到源码就有结论了，这个拒绝策略内部什么事情都不会做并且作者认为这里什么都不做就启到了丢弃的作用，也许在runnable里是这样操作没有任何问题，但是在callable里就有大问题了，因为这里什么都没有做所以导致futureTask的状态一直是NEW并且没有任何地方会去修改，所以就会一直被阻塞住。问题找到了，这里最快的解决方法是：1、扩大工作队列的长度让线上环境不会执行拒绝策略也不会有任务被拒绝；2、修改拒绝策略。 对比了两个方案的修改工作量以及这种全量的只会执行一次的背景我们选择了修改工作队列，这里直接把工作队列改成300个让其不会触发拒绝策略，然后让运维帮忙重新执行这次执行了大概几分钟就完成了。



## 给JDK提BUG

问题看到这里，我个人认为是JDK的问题因为这里拒绝策略不应该什么都不做，应该把futureTask的状态改成异常的状态，和朋友讨论了一下并且在网上搜了一下发现不止我一个人遇到这问题：

`http://ifeve.com/%E7%BA%BF%E7%A8%8B%E6%B1%A0%E4%BD%BF%E7%94%A8futuretask%E6%97%B6%E5%80%99%E9%9C%80%E8%A6%81%E6%B3%A8%E6%84%8F%E7%9A%84%E4%B8%80%E7%82%B9%E4%BA%8B/`

`https://stackoverflow.com/questions/70051689/when-using-a-thread-pool-call-futureget-and-the-program-hangs`

那么我觉得这就是一个JDK的bug，于是我去`https://bugreport.java.com/bugreport/hotspot_form.do?submit=Submit+your+Bug+Report` 这里给JDK提了一个bug，大意就是使用了futureTask并且拒绝策略是丢弃新任务的情况下可能会出现无限阻塞。隔了一周之后，oracle回复了我：

![1161646493774_.pic](/Users/zhangyunfan/Desktop/1161646493774_.pic.jpg)

oracle确认了这个问题的确是一个bug，并且分配了bug id，下面回复的oracle工作人员表示自己在JDK8到17均可以复现都为阻塞。bug地址为：`https://bugs.java.com/bugdatabase/view_bug.do?bug_id=JDK-8282291`