# volatile究竟是怎么保证一致性的

​	众所周知Java里volatile关键字的作用就是可以保证有序性和可见性的，所谓的可见性的就是多个线程读到的同一个的变量的值总是一样的，比如下面一段代码：

```java
 private static boolean aBoolean = false;
    public static void main(String[] args) {
        new Thread(() -> run()).start();
        System.out.println("main is in");
        while (!aBoolean) {
        }
        System.out.println("over");
    }
    private static void run() {
        aBoolean = true;
        System.out.println("flag now is true");
    }
```

aBoolean变量如果没有加volatile修饰的话那么程序就会一直执行下去直到CPU 100%。但是如果加了volatile修饰的话就不会，这个就是一致性或者说叫可见性，两个线程读到的aBoolean变量的值是一样的。那么volatile是怎么实现的呢？？？

​	JVMS也就是JVM规范上对volatile关键字的描述很简单就是`cannot be cached`，Java语言规范jls的描述就是:

> A field may be declared volatile, in which case the Java Memory Model ensures that all threads see a consistent value for the variable.

总结一下就是volatile关键字可以保证变量不被缓存所以可以实现都能看到变量的一致值。那么这个是怎么实现的呢？JVMS里的缓存又是什么意思呢？带着打破沙锅问到底的精神，我们今天来研究一下。

​	要搞清楚volatile怎么去解决问题，我们就要从这个问题是怎么产生的角度来分析，问题就是：为什么我们Java程序中会在多线程的情况下产生数据一致性问题？我们回顾一下大学里学到的计算机基础，线程是在CPU上执行的，所以这个问题可以拆解成：为什么CPU和CPU之间会产生数据一致性问题？这个就要从CPU内存模式讲起了



## CPU内存模型

​		现代的CPU比内存系统快很多，2006年的CPU可以在一纳秒之内执行10条指令，但是需要多个十纳秒去从内存读取一个数据，这里面产生了至少两个数量级的速度差距。在这样的问题下，CPU缓存应运而生。现代的CPU多核技术，都会有几级缓存，老的CPU会有两级内存（L1和L2），新的CPU会有三级内存（L1，L2，L3 ），如下图所示：

![](https://coolshell.cn/wp-content/uploads/2020/02/cache.architecture.png)

其中：

- L1缓存分成两种，一种是指令缓存，一种是数据缓存。L2缓存和L3缓存不分指令和数据。
- L1和L2缓存在每一个CPU核中，L3则是所有CPU核心共享的内存。
- L1、L2、L3的越离CPU近就越小，速度也越快，越离CPU远，速度也越慢。
- L1缓存之上就是我们寄存器了，所有数据的计算读写都是在这里发生的。

再往后面就是内存，内存的后面就是硬盘。我们来看一些他们的速度：

- L1 的存取速度：**4 个CPU时钟周期**
- L2 的存取速度： **11 个CPU时钟周期**
- L3 的存取速度：**39 个CPU时钟周期**
- RAM内存的存取速度**：107 个CPU时钟周期**

我们可以看到，L1的速度是RAM的27倍，但是L1/L2的大小基本上也就是KB级别的，L3会是MB级别的。

我们的数据就从内存向上，先到L3，再到L2，再到L1，最后到寄存器进行CPU计算。为什么会设计成三层？这里有下面几个方面的考虑：

>1、一个方面是物理速度，如果要更大的容量就需要更多的晶体管，除了芯片的体积会变大，更重要的是大量的晶体管会导致速度下降，因为访问速度和要访问的晶体管所在的位置成反比，也就是当信号路径变长时，通信速度会变慢。这部分是物理问题。
>
>2、多核技术中，数据的状态需要在多个CPU中进行同步，并且，我们可以看到，cache和RAM的速度差距太大，所以，多级不同尺寸的缓存有利于提高整体的性能。

有好就有坏，这个CPU缓存虽然能够提高性能但是也会带来两个问题，当然了在我们分布式系统的缓存中也产生这两个问题（可以看到知识实际上是通用的）：

1、缓存命中问题

2、缓存一致性问题

举个例子：当某一个数据在多个处于“运行”状态的线程中进行读写共享时（例如ThreadA、ThreadB和ThreadC），第一个问题是多个线程可能在多个独立的CPU内核中“同时”修改数据A，导致系统不知应该以哪个数据为准；第二个问题是由于ThreadA进行数据A的修改后没有即时写会内存ThreadB和ThreadC也没有即时拿到新的数据A，导致ThreadB和ThreadC对于修改后的数据不可见。这就是缓存一致性问题。

## CPU缓存一致性问题

对于主流的CPU来说，缓存的写操作基本上是两种策略

- 一种是Write Back，写操作只要在cache上，然后再flush到内存上。
- 一种是Write Through，写操作同时写到cache和内存上。

这两种策略的区别就是一个只写缓存，一个同时写缓存和内存，从上文可以看到内存读写速度比起最慢的L3缓存来说都慢了快四倍，所以为了提高性能，主流的CPU（如：Intel Core i7/i9）采用的是Write Back的策略。

好了，现在问题来了，如果有一个数据 x 在 CPU 第0核的缓存上被更新了，那么其它CPU核上对于这个数据 x 的值也要被更新，这就是缓存一致性的问题。（当然，对于我们上层的程序我们不用关心CPU多个核的缓存是怎么同步的，这对上层的代码来说都是透明的）

一般来说，在CPU硬件上，会有两种方法来解决这个问题。

- **Directory 协议**。这种方法的典型实现是要设计一个集中式控制器，它是主存储器控制器的一部分。其中有一个目录存储在主存储器中，其中包含有关各种本地缓存内容的全局状态信息。当单个CPU Cache 发出读写请求时，这个集中式控制器会检查并发出必要的命令，以在主存和CPU Cache之间或在CPU Cache自身之间进行数据同步和传输。
- **Snoopy 协议**。这种协议更像是一种数据通知的总线型的技术。CPU Cache通过这个协议可以识别其它Cache上的数据状态。如果有数据共享的话，可以通过广播机制将共享数据的状态通知给其它CPU Cache。这个协议要求每个CPU Cache 都可以**“窥探”**数据事件的通知并做出相应的反应。如下图所示，有一个Snoopy Bus的总线。

因为Directory协议是一个中心式的，会有性能瓶颈，而且会增加整体设计的复杂度。而Snoopy协议更像是微服务+消息通讯，所以，现在基本都是使用Snoopy的总线的设计。说到这里笔者感觉这个地方其实和分布式中的一致性有很多相似性，但是也有不同的地方，就是在CPU的世界里不存在分布式系统那样的网络问题，我们只关心数据的状态就好了。现在有很多CPU状态协议，我们今天就只谈其中一个最有名的协议——MESI协议。（这个名字和前段时间那个34岁中年危机失业的足球运动员没有半毛钱关系）

## MESI协议

>**MESI协议**是一个基于失效的[缓存一致性](https://zh.wikipedia.org/wiki/缓存一致性)协议，是支持回写（write-back）缓存的最常用协议。也称作**伊利诺伊协议** (Illinois protocol，因为是在[伊利诺伊大学厄巴纳-香槟分校](https://zh.wikipedia.org/wiki/伊利诺伊大学厄巴纳-香槟分校)被发明的[[1\]](https://zh.wikipedia.org/wiki/MESI协议#cite_note-1))。与写通过（write through）缓存相比，回写缓冲能节约大量带宽。总是有“脏”（dirty）状态表示缓存中的数据与主存中不同。MESI协议要求在缓存不命中（miss）且数据块在另一个缓存时，允许缓存到缓存的数据复制。

​																										——维基百科

MESI协议对内存数据访问的控制类似于读写锁，它使得针对同一地址的读内存操作是并发的，而针对同一地址的写内存操作是独占的。

之所以叫 MESI，是因为这套方案把一个缓存行（cache line）区分出四种不同的状态标记，他们分别是 **Modified、Exclusive、Shared 和 Invalid**。

| 状态                     | 描述                                                         | 监听任务                                                     | 状态转换                                                     |
| ------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| M 修改 (Modified)        | 该Cache line有效，数据被修改了，和内存中的数据不一致，数据只存在于本Cache中。 | 缓存行必须时刻监听所有试图读该缓存行相对就主存的操作，这种操作必须在缓存将该缓存行写回主存并将状态变成S（共享）状态之前被延迟执行。 | 当被写回主存之后，该缓存行的状态会变成独享（exclusive)状态。 |
| E 独享、互斥 (Exclusive) | 该Cache line有效，数据和内存中的数据一致，数据只存在于本Cache中。 | 缓存行也必须监听其它缓存读主存中该缓存行的操作，一旦有这种操作，该缓存行需要变成S（共享）状态。 | 当CPU修改该缓存行中内容时，该状态可以变成Modified状态        |
| S 共享 (Shared)          | 该Cache line有效，数据和内存中的数据一致，数据存在于很多Cache中。 | 缓存行也必须监听其它缓存使该缓存行无效或者独享该缓存行的请求，并将该缓存行变成无效（Invalid）。 | 当有一个CPU修改该缓存行时，其它CPU中该缓存行可以被作废（变成无效状态 Invalid）。 |
| I 无效 (Invalid)         | 该Cache line无效。                                           | 无                                                           | 无                                                           |

他们之间转换图示如下：

![](https://coolshell.cn/wp-content/uploads/2020/02/MESI.png)

很复杂，https://www.scss.tcd.ie/Jeremy.Jones/VivioJS/caches/MESIHelp.htm  这个网页可以去看一下动画实例，也许会好点。

不同CPU之间也是需要沟通的，这里的沟通是通过在消息总线上传递message实现的。这些在总线上传递的消息有如下几种：

- Read ：带上数据的物理内存地址发起的读请求消息；
- Read Response：Read 请求的响应信息，内部包含了读请求指向的数据；
- Invalidate：该消息包含数据的内存物理地址，意思是要让其他如果持有该数据缓存行的 CPU 直接失效对应的缓存行；
- Invalidate Acknowledge：CPU 对Invalidate 消息的响应，目的是告知发起 Invalidate 消息的CPU，这边已经失效了这个缓存行啦；
- Read Invalidate：这个消息其实是 Read 和 Invalidate 的组合消息，与之对应的响应自然就是一个Read Response 和 一系列的 Invalidate Acknowledge；
- Writeback：该消息包含一个物理内存地址和数据内容，目的是把这块数据通过总线写回内存里。

举个例子：

现在有 cpu0 cpu1 变量a

现在cpu0对a赋值 a=1

假如变量a不在cpu0 缓存中，则需要发送 Read Invalidate 信号，再等待此信号返回Read Response和Invalidate Acknowledge，之后再写入量到缓存中。

假如变量a在cpu0 缓存中，如果该量的状态是 Modified 则直接更改发送Writeback 最后修改成Exclusive。而如果是 Shared 则需要发送 Invalidate 消息让其它 CPU 感知到这一更改后再更改。

- 一般情况下，CPU 在对某个缓存行修改之前务必得让其他 CPU 持有的相同数据缓存行失效，这是基于 Invalidate Acknowledge 消息反馈来判断的；
- 缓存行为 M 状态，意味着该缓存行指向的物理内存里的数据，一定不是最新；
- 在修改变量之前，如果CPU持有该变量的缓存，且为 E 状态，直接修改；若状态为 S ，需要在总线上广播 Invalidate；若CPU不持有该缓存行，则需要广播 Read Invalidate。

当然了如果MESI协议就这样的话那肯定会有很多问题，比如说当相当一部分 CPU 持有相同的数据时（S 状态），如果其中有一个 CPU 要对其进行修改，则需要等待其他 CPU 将其共同持有的数据失效，那么这里就会有空等期（stall），这对于频率很高的CPU来说，简直不能接受。

​	那么基于这个空等期的问题，引入了Store buffers来解决

![](http://1.bp.blogspot.com/-zGovYT4Dzc0/Uta4pb9rQhI/AAAAAAAABn4/p5DbQio-K2w/s1600/Store+Buffer.JPG)

Store buffers就是一个缓存区，在数据被写到CPU之前先写到这个缓存区中，然后自己就可以去处理别的事情了，就避免了空窗期。

但是Store buffers并不完美，它会产生一个新的问题，就是数据会在被写入CPU缓存之前会在缓冲区呆一会儿，如果这个时候再来读这个数据就会去CPU缓存里去读，读出来的自然就是老的数据了。这个问题其实很好解决，就是读CPU缓存数据之前先去判断Store buffers里是否有数据，如果有则直接用Store buffers的数据。

![](http://www.researchgate.net/profile/Paul_Mckenney/publication/228824849/figure/fig4/AS:340743597117458@1458251012215/Caches-With-Store-Forwarding.png)

对于同一个 CPU 而言，在读取 数据的时候，如若发现 Store Buffer 中有尚未写入到缓存的数据 ，则直接从 Store Buffer 中读取。这就保证了，逻辑上代码执行顺序，也保证了可见性。

但是这个方案只能解决单CPU下的问题，如果是多CPU的情况，上面这种方案就没法解决了。我们举个例子,我们有两个方法：

```java
public void foo(void){
 a = 1;
 b = 1;
}

public void bar(void){
 while (b == 0) continue;
 assert(a == 1);
}
```

假设上面的 foo 方法被 CPU 0 执行，bar 方法被 CPU 1 执行，也就是我们常说的多线程环境。试想，即便在多线程环境下，foo 和 bar 如若严格按照理想的顺序执行，是无论如何都不会出现 assert failed 的情况的。但往往事与愿违，这种看似很诡异的且有一定几率发生的 assert failed ，结合上面所说的 Store Buffer 就一点都不难理解了。

我们来还原 assert failed 的整个过程，假设 a,b 初始值为 0 ，a 被 CPU0 和 CPU1 共同持有，b 被 CPU0 独占；

1、CPU0 处理 a=1 之前发送 Invalidate 消息给 CPU1 ，并将其放入 Store Buffer ，尚未及时刷入缓存；

2、CPU 0 转而处理 b=1 ，此时 b=1 直接被刷入缓存；
3、CPU 1 发出 Read 消息读取 b 的值，发现 b 为 1 ，跳出 while 语句；

4、CPU 1 发出 Read 消息读取 a 的值，因为此时a的新值1还在CPU0中的 Store Buffer中，所有在CPU1的缓存里发现 a 却为旧值 0，assert failed。

那么怎么解决呢？我们先自己思考一下，引起上面的问题，无非就是CPU1没有发现a的值修改了，以为a的值还没有修改所以用的就是自己缓存里的老值，那么能不能让在CPU0修改b的值之前等待a的值推送到主内存，让其他CPU能知道这个变化之后再来处理b的值，这样子CPU1读的时候就可以读到最新的值了。

如果你能想到这个解决方案，那么恭喜你，你和那些大佬们想法一样，这个解决方案就叫做 Memory Barrier（内存屏障）。借助内存屏障可以很好地保证了顺序一致性。当然了这个方案也会有问题，比如说store buffer里面存的数据过多，CPU还是会有空等的现象，这个时候就引入了Invalidate Queues来解决，Invalidate Queues就是把需要失效的数据物理地址存储起来，根据这个物理地址，我们可以对缓存行的失效行为 “延后执行” 。这样做的好处上面也说过，又一次释放了 CPU 的发挥空间，但是还是会可能会出现上面的问题，这个时候就需要更多的内存屏障来解决了。

内存屏障包含的语义有些“重”，既包含了 Store Buffer 的 flush，又包含了 Invalidate Queue 的等待环节，但现实场景下，我们可能只需要与其中一个数据结构打交道即可。于是，CPU 的设计者把 smp_mb 屏障进一步拆分，一分为二，把内存屏障分成读内存屏障和写内存屏障。他们分别的语义也相应做了简化：

- **smp_wmb(StoreStore)**：执行后需等待 Store Buffer 中的写入变更 flush 完全到缓存后，后续的写操作才能继续执行，保证执行前后的写操作对其他 CPU 而言是顺序执行的；
- **smp_rmb(LoadLoad)**：执行后需等待 Invalidate Queue 完全应用到缓存后，后续的读操作才能继续执行，保证执行前后的读操作对其他 CPU 而言是顺序执行的；



## Java中怎么解决一致性问题

好了，绕了一大圈，我们终于绕回来了，上文的内容可以总结成——内存屏障是可以解决一致性问题的。

那么Java是如何实现自己的内存屏障的？抽象上看 JVM 涉及到的内存屏障有四种：

| 屏障类型            | 指令示例                 | 说明                                                         |
| :------------------ | :----------------------- | :----------------------------------------------------------- |
| LoadLoad Barriers   | Load1;LoadLoad;Load2     | 该屏障确保Load1数据的装载先于Load2及其后所有装载指令的的操作 |
| StoreStore Barriers | Store1;StoreStore;Store2 | 该屏障确保Store1立刻刷新数据到内存(使其对其他处理器可见)的操作先于Store2及其后所有存储指令的操作 |
| LoadStore Barriers  | Load1;LoadStore;Store2   | 确保Load1的数据装载先于Store2及其后所有的存储指令刷新数据到内存的操作 |
| StoreLoad Barriers  | Store1;StoreLoad;Load2   | 该屏障确保Store1立刻刷新数据到内存的操作先于Load2及其后所有装载装载指令的操作。它会使该屏障之前的所有内存访问指令(存储指令和访问指令)完成之后,才执行该屏障之后的内存访问指令 |

这四种内存屏障对应不同的处理平台，比如说常见的x86平台下，只有 StoreLoad 才有具体的指令对应，而其他三个屏障均是 no-op (空操作)。在x86下的StoreLoad又有三个指令可以实现：mfence、cpuid、 locked insn，他们都能很好地实现 StoreLoad 的屏障效果。但毕竟不可能同时用三种指令，这里可能意思是，三种均能达到效果，具体实现交由 JVM 设计者决断。我们可以写一段代码，查看下JVM采用的是哪一种命令。上面的指令都是汇编命令，众所周知Java代码最后会编译成汇编命令执行，所以我们只要看一下volatile对应的汇编指令是哪个就可以了。实例代码：

```java
public class VolatileTest {

    volatile static int a = 1;

    public static void main(String[] args) {
        test();
    }

    public static void test() {
        a++;
    }
}

```

执行命令`javac VolatileTest.java && java -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly`然后可以看到：

```tex
  0x000000010dfedef8: lock addl $0x0,(%rsp)
  0x000000010dfedefd: cmpl   $0x0,-0x32f1197(%rip)        # 0x000000010acfcd70
                                                ;   {external_word}
  0x000000010dfedf07: jne    0x000000010dfedf1b
```

lock指令用于在多处理器中执行指令时对共享内存的独占使用。它的副作用是能够将当前处理器对应缓存的内容刷新到内存，并使其他处理器对应的缓存失效。另外还提供了有序的指令无法越过这个内存屏障的作用。

简单来说，这句指令的作用就是保证了可见性以及内存屏障。

- 执行 a 的写操作后执行到 StoreLoad 内存屏障；
- 发出 Lock 指令，锁总线 或 a 的缓存行，那么其他 CPU 不能对已上锁的缓存行有任何操作；
- 让其他 CPU 持有的 a 的缓存行失效；
- 将 a 的变更写回主内存，保证全局可见；

上面执行完后，该 CPU 方可执行后续操作。



当一个CPU进行写入时，首先会给其它CPU发送Invalid消息，然后把当前写入的数据写入到Store Buffer中。然后异步在某个时刻真正的写入到Cache中。当前CPU核如果要读Cache中的数据，需要先扫描Store Buffer之后再读取Cache。但是此时其它CPU核是看不到当前核的Store Buffer中的数据的，要等到Store Buffer中的数据被刷到了Cache之后才会触发失效操作。而当一个CPU核收到Invalid消息时，会把消息写入自身的Invalidate Queue中，随后异步将其设为Invalid状态。和Store Buffer不同的是，当前CPU核心使用Cache时并不扫描Invalidate Queue部分，所以可能会有极短时间的脏读问题。MESI协议，可以保证缓存的一致性，但是无法保证实时性。所以我们需要通过内存屏障在执行到某些指令的时候强制刷新缓存来达到一致性。

但是MESI只是一种抽象的协议规范，在不同的cpu上都会有不同的实现，对于x86架构来说，store buffer是FIFO，写入顺序就是刷入cache的顺序。但是对于ARM/Power架构来说，store buffer并未保证FIFO，因此先写入store buffer的数据，是有可能比后写入store buffer的数据晚刷入cache的

而对于JAVA而言，他必须要屏蔽各个处理器的差异，所以才有了java内存模型(JMM),volatile只是内存模型的一小部分，实现了变量的可见性和禁止指令重排序优化的功能。整个内存模型必须要实现可见性，原子性，和有序性。而volatile实现了其中的可见性和有序性。



一句话简单总结就是：**java虚拟机在实现volatile关键字的时候，是写入了一条lock 前缀的汇编指令。lock 前缀的汇编指令会强制写入主存，也可避免前后指令的CPU重排序，并及时让其他核中的相应缓存行失效，从而利用MESI达到符合预期的效果。**



再精简一下就是：**volatile的底层实现，满足了MESI的触发条件，才让变量有了缓存一致性**



参考：

【如何验证volatile的可见性】 https://mp.weixin.qq.com/s/QsgvO4y7yrDySjxo5VRbFQ

【与程序员相关的CPU缓存知识】 https://coolshell.cn/articles/20793.html

【hwViewForSwHackers】 http://www.puppetmastertrading.com/images/hwViewForSwHackers.pdf

【cpu缓存和volatile】 https://www.cnblogs.com/xmzJava/p/11417943.html

