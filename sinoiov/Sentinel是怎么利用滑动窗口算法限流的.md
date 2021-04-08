# Sentinel是怎么利用滑动窗口算法限流的



## 什么是滑动窗口算法

- ### 为什么要限流？

  ​	限流的意义其实无需多言了。最常用的场景下，流控是为了保护下游有限的资源不被流量冲垮，保证服务的可用性，一般允许流控的阈值有一定的弹性，偶尔的超量访问是可以接受的。

- ### 单线程下的限流算法：简单窗口

  ​	流控是为了限制指定时间间隔内能够允许的访问量，因此，最直观的思路就是基于一个给定的时间窗口，维护一个计数器用于统计访问次数，然后实现以下规则：

  - 如果访问次数小于阈值，则代表允许访问，访问次数 +1。

  - 如果访问次数超出阈值，则限制访问，访问次数不增。

  - 如果超过了时间窗口，计数器清零，并重置清零后的首次成功访问时间为当前时间。这样就确保计数器统计的是最近一个窗口的访问量。

- ### 多线程下的简单窗口

  ​	在现实应用中，往往是多个线程来同时申请配额，为了比较简洁地表达算法思路，示例代码里面都没有做并发同步控制。

  ​	以简单窗口的实现为例，要转换为多线程安全的流控算法，一种直接的办法是将 tryAcquire 方法设置为 synchronized。

  ​	当然一种感觉上更高效的办法也可以是修改读写变量的类型：

  ```java
  private volatile long lastReqTime = System.currentTimeMillis();
  private LongAdder counter = new LongAdder();
  ```

  ​	不过这样其实并不真正“安全”，设想以下的场景，两个线程 A、线程 B 前后脚尝试获取配额，#1 位置的判断条件满足后，会同时走到 #2 位置修改 lastReqTime 值，线程 B 的赋值会覆盖线程 A，导致时间窗口起始点向后偏移。同样的，位置 #3 和 #4 也会构成竞争条件。当然如果对流控的精度要求不高，这种竞争也是能接受的。

- ### 简单窗口算法的缺陷

  ​	简单窗口的流控实现非常简单，以 1 分钟允许 100 次访问为例，如果流量均匀保持 200 次/分钟的访问速率，系统的访问量曲线大概是这样的（按分钟清零）：

  ![640 (1)](C:\Users\admin\Desktop\640 (1).webp)

  ​	但如果流量并不均匀，假设在时间窗口开始时刻 0:00 有几次零星的访问，一直到 0:50 时刻，开始以 10 次/秒的速度请求，就会出现这样的访问量图线：

  ![640](C:\Users\admin\Desktop\640.png)

  ​	在临界的 20 秒内（0:50~1:10）系统承受的实际访问量是 200 次，换句话说，最坏的情况下，在窗口临界点附近系统会承受 2 倍的流量冲击，这就是简单窗口不能解决的临界突变问题。

- ### 滑动窗口算法

  ​	如何解决简单窗口算法的临界突变问题？既然一个窗口统计的精度低，那么可以把整个大的时间窗口切分成更细粒度的子窗口，每个子窗口独立统计。同时，每过一个子窗口大小的时间，就向右滑动一个子窗口。这就是滑动窗口算法的思路。

  ![640 (1)](C:\Users\admin\Desktop\640 (1).png)

  ​	如上图所示，将一分钟的时间窗口切分成 6 个子窗口，每个子窗口维护一个独立的计数器用于统计 10 秒内的访问量，每经过 10s，时间窗口向右滑动一格。

  回到简单窗口出现临界跳变的例子，结合上面的图再看滑动窗口如何消除临界突变。如果 0:50 到 1:00 时刻（对应灰色的格子）进来了 100 次请求，接下来 1:00~1:10 的 100 次请求会落到黄色的格子中，由于算法统计的是 6 个子窗口的访问量总和，这时候总和超过设定的阈值 100，就会拒绝后面的这 100 次请求。

## 深入源码，Sentinel是怎么实现滑动窗口算法

​	首先我们在项目里用了Sentinel的话都会使用一个`SentinelResource`这个注解来自定义限流规则、降级规则那我们就从这个注解入手来看看Sentinel是怎么工作的；

​	`SentinelResource`注解对应的是`SentinelResourceAspect`这个切面，在这个切面里：

```java
try {
        entry = SphU.entry(resourceName, resourceType, entryType, pjp.getArgs());
        Object result = pjp.proceed();
        return result;
     } catch (BlockException ex) {
    	//触发限流执行
        return handleBlockException(pjp, annotation, ex);
     } catch (Throwable ex) {
     	//触发降级熔断
        Class<? extends Throwable>[] exceptionsToIgnore = annotation.exceptionsToIgnore();
        // The ignore list will be checked first.
        if (exceptionsToIgnore.length > 0 && exceptionBelongsTo(ex, exceptionsToIgnore)) {
```

可以看出来，触发限流熔断降级是使用的抛出异常的方式，那么` entry = SphU.entry(resourceName, resourceType, entryType, pjp.getArgs());`这句代码就是在对给定资源执行规则检查；一路ctrl+T之后我们可以一直到`com.alibaba.csp.sentinel.CtSph`的`entryWithPriority`方法，：

```java
		private Entry entryWithPriority(ResourceWrapper resourceWrapper, int count, boolean prioritized, Object... args) throws BlockException {
         //新建上下文对象
        Context context = ContextUtil.getContext();
     ...
        ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);

        /*
         * 责任链的任务数量超过了 {@link Constants.MAX_SLOT_CHAIN_SIZE}设置的最大大小
         *所以不会进行规则检查
         */
        if (chain == null) {
            return new CtEntry(resourceWrapper, null, context);
        }
        Entry e = new CtEntry(resourceWrapper, chain, context);
        try {
            chain.entry(context, resourceWrapper, null, count, prioritized, args);
      ...
```

大概翻译了一下源码里的英文注释，可以从这个方法看出Sentinel使用的是责任链模式来进行规则检查的，` ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);`这句代码就是在初始化我们的责任链（不清楚责任链的同学可以百度谷歌一下）：

```java
 ProcessorSlot<Object> lookProcessChain(ResourceWrapper resourceWrapper) {
        ProcessorSlotChain chain = chainMap.get(resourceWrapper);
        if (chain == null) {
            synchronized (LOCK) {
                chain = chainMap.get(resourceWrapper);
                if (chain == null) {
                    // Entry size limit.
                    if (chainMap.size() >= Constants.MAX_SLOT_CHAIN_SIZE) {
                        return null;
                    }

                    chain = SlotChainProvider.newSlotChain();
                    Map<ResourceWrapper, ProcessorSlotChain> newMap = new HashMap<ResourceWrapper, ProcessorSlotChain>(
                        chainMap.size() + 1);
                    newMap.putAll(chainMap);
                    newMap.put(resourceWrapper, chain);
                    chainMap = newMap;
                }
            }
        }
        return chain;
    }
```

就是根据resource 从本地缓存中获取一个chain ，如果是null的话，就会加锁创建，可以看到还判断了一下这个chainMap大小不能超过6000，也就是一个服务中不能有6000resource，一般我们服务中也没达不到这么多。接着就是执行`SlotChainProvider.newSlotChain()`创建一个chain并缓存到map中。我们来看下这个chain是怎样创建的：

```java
     public static ProcessorSlotChain newSlotChain() {
        if (slotChainBuilder != null) {
            return slotChainBuilder.build();
        }

        // 用spi机制来加载
        slotChainBuilder = SpiLoader.loadFirstInstanceOrDefault(SlotChainBuilder.class, DefaultSlotChainBuilder.class);

        if (slotChainBuilder == null) {
            // Should not go through here.
            RecordLog.warn("[SlotChainProvider] Wrong state when resolving slot chain builder, using default");
            slotChainBuilder = new DefaultSlotChainBuilder();
        } else {
            RecordLog.info("[SlotChainProvider] Global slot chain builder resolved: "
                + slotChainBuilder.getClass().getCanonicalName());
        }
        return slotChainBuilder.build();
    }
```

 关键代码是：

```java
slotChainBuilder = SpiLoader.loadFirstInstanceOrDefault(SlotChainBuilder.class,DefaultSlotChainBuilder.class);
```

这里是用了java的SPI机制来加载实现了`SlotChainBuilder`这个接口的实现类，如果没有的话就返回默认的`DefaultSlotChainBuilder`这个类，这里用了建造者模式和SPI机制，并且这里也给了大家一个扩展修改的口子（用了Java的多态），如果想自定义改造Sentinel的话可以实现`SlotChainBuilder`这个接口来自己新建一个责任链；然后在默认的Builder里：

```java
public class DefaultSlotChainBuilder implements SlotChainBuilder {
    @Override
    public ProcessorSlotChain build() {
        ProcessorSlotChain chain = new DefaultProcessorSlotChain();

        // 注意：ProcessorSlot的实例应该不同，因为它们不是无状态的。
        List<ProcessorSlot> sortedSlotList = SpiLoader.loadPrototypeInstanceListSorted(ProcessorSlot.class);
        for (ProcessorSlot slot : sortedSlotList) {
            chain.addLast((AbstractLinkedProcessorSlot<?>) slot);
        }

        return chain;
    }
}
```

这里仍然是用了SPI机制来加载责任链的成员，每成员个类上面都有自定义注解`@SpiOrder`来定义每个成员的顺序，注意`ProcessorSlotChain`是一个链表结构，每个成员执行`fireEntry`方法就是在执行下一个节点的方法。有意思的是在早期版本中这里的代码时这样的：

```java
public class DefaultSlotChainBuilder implements SlotChainBuilder {
  public ProcessorSlotChain build() {
    DefaultProcessorSlotChain defaultProcessorSlotChain = new DefaultProcessorSlotChain();
    defaultProcessorSlotChain.addLast((AbstractLinkedProcessorSlot)new NodeSelectorSlot());
    defaultProcessorSlotChain.addLast((AbstractLinkedProcessorSlot)new ClusterBuilderSlot());
    defaultProcessorSlotChain.addLast((AbstractLinkedProcessorSlot)new LogSlot());
    defaultProcessorSlotChain.addLast((AbstractLinkedProcessorSlot)new StatisticSlot());
    defaultProcessorSlotChain.addLast((AbstractLinkedProcessorSlot)new SystemSlot());
    defaultProcessorSlotChain.addLast((AbstractLinkedProcessorSlot)new AuthoritySlot());
    defaultProcessorSlotChain.addLast((AbstractLinkedProcessorSlot)new FlowSlot());
    defaultProcessorSlotChain.addLast((AbstractLinkedProcessorSlot)new DegradeSlot());
    return (ProcessorSlotChain)defaultProcessorSlotChain;
  }
}
```

也就是说早期版本里这里时简单粗暴的add，后来优化之后用了SPI机制来加载；SPI机制在这里就不赘述了，总之` SpiLoader.loadPrototypeInstanceListSorted(ProcessorSlot.class);`这句代码会把`sentinel-core`这个jar包的META-INF目录中的`services/com.alibaba.csp.sentinel.slotchain.SlotChainBuilder`文件里的类都加载出来：

```
# Sentinel default ProcessorSlots
com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot
com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot
com.alibaba.csp.sentinel.slots.logger.LogSlot
com.alibaba.csp.sentinel.slots.statistic.StatisticSlot
com.alibaba.csp.sentinel.slots.block.authority.AuthoritySlot
com.alibaba.csp.sentinel.slots.system.SystemSlot
com.alibaba.csp.sentinel.slots.block.flow.FlowSlot
com.alibaba.csp.sentinel.slots.block.degrade.DegradeSlot
```

我们可以清楚的看到这里就是我们Sentinel责任链里的全部成员了，他们的作用和执行顺序如下：

- NodeSelectorSlot ，这个就是为往context中设置当前resource对应的DefaultNode。
- ClusterBuilderSlot 这个就是往context中设置ClusterNode。
- LogSlot 打印日志的，主要是异常日志处理
- StatisticSlot 这个slot很重要，指标收集作用
- AuthoritySlot 黑白名单检查
- SystemSlot  系统规则检查
- FlowSlot 流控检查。
- DegradeSlot 服务降级，熔断的检查。

我们本次比较关注的节点应该是：StatisticSlot  ;

在StatisticSlot  中`node.addPassRequest(count);`记录允许的请求数，包含秒和分钟两个维度：

```java
rollingCounterInSecond.addPass(count);
rollingCounterInMinute.addPass(count);
```

`rollingCounterInSecond`时具体记录用到的， 是`ArrayMetric`类实现了`Metric接口`。在ArrayMetric里`LeapArray<MetricBucket> data` 这个维护着真正的滑动窗口，``LeapArray`是抽象类，`ArrayMetric`里用到的实现类是`BucketLeapArray`，这里用到了模板模式：

```java
/**
 * <p>
 * Sentinel中统计度量的基本数据结构。
 * </p>
 * <p>
 * Leap数组采用滑动窗口算法对数据进行计数. 每个窗口包含 {@code windowLengthInMs}的 时间跨度,
 * 总的时间跨度是 {@link #intervalInMs}, 所以桶的总跨度是:
 * {@code sampleCount = intervalInMs / windowLengthInMs}.
 * </p>
 *
 * @param <T> type of statistic data
 * @author jialiang.linjl
 * @author Eric Zhao
 * @author Carpenter Lee
 */
public abstract class LeapArray<T> {
```

简单翻译了一下注释，总结一下总窗口时间跨度大小是 intervalInMs，滑动子窗口时间跨度大小是 windowLengthInMs，那么总的窗口数量就是sampleCount：`sampleCount = intervalInMs / windowLengthInMs`，总窗口数量和总窗口时间跨度大小是ArrayMetric的构造函数传进来的，具体是在`StatisticNode`里新建的：

```java
private transient volatile Metric rollingCounterInSecond = new ArrayMetric(SampleCountProperty.SAMPLE_COUNT,
    IntervalProperty.INTERVAL);
```
当前实现默认为 2，而总窗口大小默认是 1s，也就意味着默认的滑动窗口大小是 500ms;可以调用`SampleCountProperty.updateSampleCount`来调整总的窗口数量，以此来调整统计的精度。在`LeapArray`中有一个类型为`AtomicReferenceArray`线程安全的滑动窗口的数组 array，数组中每个元素即窗口以` WindowWrap<T>`表示，`WindowWrap`有三个属性：

- windowStart：滑动窗口的开始时间
- windowLength：滑动窗口的长度。
- value：滑动窗口记录的内容，泛型表示，关键的一类就是 MetricBucket，里面包含了一组 LongAdder 用于记录不同类型的数据，例如请求通过数、请求阻塞数、请求异常数等等。

滑动窗口算法的核心实现就是在`LeapAyyay`的`currentWindow`方法里：

- #### 根据当前时间获取滑动窗口的下标，当前毫秒数除以窗口大小数然后再对当前数组长度求余：

  ```java
  long timeId = timeMillis / windowLengthInMs;
  return (int)(timeId % array.length());
  ```
  
- #### 获取当前窗口的开始时间，当前时间减去当前时间对窗口大小求余的数：

  ```java
  windowStart = timeMillis - timeMillis % windowLengthInMs;
  ```

- #### 根据刚刚获取的窗口下标去数组里获取窗口元素

  ```java
  while (true) {
  WindowWrap<T> old = array.get(idx);
  ```

  1、若当前下标没有对应的窗口：

  ```java
   if (old == null) {
       /*
        *     B0       B1      B2    NULL      B4
        * ||_______|_______|_______|_______|_______||___
        * 200     400     600     800     1000    1200  timestamp
        *                             ^
        * 
        *                      time=888			
        *
        */
        WindowWrap<T> window = new WindowWrap<T>(windowLengthInMs
                          	, windowStart, newEmptyBucket(timeMillis));
        if (array.compareAndSet(idx, null, window)) {
                 return window;
         } else {
                  Thread.yield();
          		}
  ```

  尝试创建一个新的窗口元素，然后尝试通过CAS来创建新的窗口元素，如果更新成功则返回新建的窗口，如果CAS添加元素失败则自旋，等待下次线程开始的时候重新获取老窗口重新获取老的窗口。

  2、若获取到了窗口，这里有三种情况：

  - 若当前窗口的开始时间等于获取到的窗口的开始时间，则说明当前窗口就是对应的窗口：

    ```java
    else if (windowStart == old.windowStart()) {
        /*
         *     B0       B1      B2     B3      B4
         * ||_______|_______|_______|_______|_______||___
         * 200     400     600     800     1000    1200  timestamp
         *                             ^
         *                          time=888
         *            每个窗口大小200，所以开始时间等于888-888%200=800		
         */
        return old;
    ```

  - 如果获取到的窗口的开始时间晚于当前窗口的开始时间，则说明获取到的窗口已经过期，我们就把原来的窗口覆盖掉，注意接下来这里应该是两步操作：

    更新开始时间和重置值，整个步奏应该是原子的，所以需要用到锁来保证原子性：

    ```java
    else if (windowStart > old.windowStart()) {
                    /*
                     *   (old)
                     *             B0       B1      B2    NULL      B4
                     * |_______||_______|_______|_______|_______|_______||___
                     * ...    1200     1400    1600    1800    2000    2200  timestamp
                     *                              ^
                     *                           time=1676
                     *          原来窗口的开始时间: 400, 说明老的已经过期我们重置  
                     */
                    if (updateLock.tryLock()) {
                        try {
                            return resetWindowTo(old, windowStart);
                        } finally {
                            updateLock.unlock();
                        }
                    } else {
                        Thread.yield();
                    }
    ```

    `updateLock`是`ReentrantLock`锁，如果没有竞争到锁则自旋等待下次线程开始的时候重新获取老窗口重新重新获取老的窗口,注意`ReentrantLock` 默认是非公平锁，这里用的是默认的非公平锁，因为这里不关心获取锁的顺序但是关心性能，所以用非公平锁。为什么没有用`synchronized`呢？因为这里没有获取到锁的线程有可能是另外一个线程在操作同一个窗口，当前没有竞争到锁的话应该自旋然后再次尝试获取窗口，也许获取到的就是新的窗口了，而不是让没有获取到锁的线程阻塞一直等待获取锁。

  - 如果获取到的窗口的开始时间早于当前窗口的开始时间，当然了这种情况是不可能发生的，但是Sentinel为了严谨还是有这一种分支，这里就不再赘述。

- #### 就然后将该窗口的统计值 +1 

  这里就非常简单了：`wrap.value().addPass(count);`

最后用网上找到的一张图来总结Sentinel是怎么实现滑动窗口算法：

![640](C:\Users\admin\Desktop\640.webp)

## 

## 总结

​	到这里Sentinel是怎么实现滑动窗口算法的细节就差不多了，看完之后个人最有收获的反而不是本文的主题滑动窗口算法而是多线程、设计模式等Java基础知识的运用，可以看得出来Sentinel的作者已经是对多线程、设计模式等基础知识的运用已经是炉火纯青了。

​	总结一下本文出现了的基础知识：

- 设计模式：
  - 建造模式
  - 模板方法模式
  - 责任链模式
  - 策略模式
- 多线程：
  - `Synchronized`
  - `ReentrantLock`
  - `AtomicReferenceArray`
  - `LongAdder`
  - `volatile`
  - CAS
- JDK
  - SPI机制



在不同的场景下，适用的流控算法不尽相同。大多数情况下，使用 Sentinel 已经能很好地应对，起码能满足笔者自身的场景了，但 Sentinel 也并不是万能的，需要思考其他的流控方案，推荐阅读这篇关于流控算法的文章：https://puhaiyang.blog.csdn.net/article/details/110389766



[1]: https://mp.weixin.qq.com/s/joP22Z8zblcDBAV1keSdJw	"单机和分布式场景下，有哪些流控方案？"
[2]: https://blog.csdn.net/m0_38117859/article/details/108725369	"初探sentinel的Slot"

