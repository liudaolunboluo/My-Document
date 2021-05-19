# 假如没有ReentrantLock和AQS

## 前言



`ReentrantLock`是Java八股里经久不衰的话题，因为这个可以引出`synchronized`和AQS，笔者在某天突然想到要是这个世界上没有ReentrantLock和AQS的话，那么是不是笔者可以自己实现一个ReentrantLock呢？？



## 定义

​	我们本次目标就是实现一个**可重入非公平非共享的同步器**。我们首先在idea上新建一个java项目，然后新建一个lock类，就叫`ZyfLock`吧，然后我们要想实现同步器，那么根据现有的知识储备，我们需要一个线程安全的变量来标明这个同步器已经被持有了，那么这个变量是什么类型呢？首先可以想到boolean类型，取名叫`isHold`，但是我们的目标是可重入，虽然boolean类型也可以做到，比如说我再加一个属性`ownerThread`表示当前持有的线程然后获取锁的时候根据当前线程+`isHold`来保证可重入。但是我觉得换成int类型的更简单，用数字来表示获取和做到可重入。那么我们就换成int类型的`state`字段吧。然后虽然是int类型的但是我们还是需要持有者这个属性来保证开锁的似时候是持有者再执行，然后我们可以写下如下代码：

```java
/**
 * @author zhangyunfan
 * @version 1.0
 * @ClassName: ZyfLock
 * @Description: zyf的锁
 * @date 2021/5/14
 */
public class ZyfLock {

    private static AtomicInteger state = new AtomicInteger(0);
    
    private transient Thread exclusiveOwnerThread;
    
    //常量，表示没有持有的
    private static final int FREE=0;
    
    public Thread getExclusiveOwnerThread(){
        return exclusiveOwnerThread;
    }

    public void lock() {

    }

    public void unlock() {

    }

}
```



## 加锁

​	我们的同步器实现的第一个功能就是去加锁，加锁说白了就是在`state`为0的情况下对`state`做线程安全的加一操作，如果成功则成功获取到锁，如果没有成功则获取锁失败。为了保证线程安全上述对`state`加一的流程可以用CAS来做——期望值是0的情况下才变成1，否则就失败。获取到锁之后就是把持有者修改为当前线程:

```java
if (state.compareAndSet(0,1)) {
         exclusiveOwnerThread=Thread.currentThread();
   }
```



## 获取锁失败

加锁的代码很简单，那么既然有线程获取锁成功了，就有获取锁失败的线程（废话。。），那么获取失败的线程怎么处理呢？这里有两种方案：

1、 将当前线程获锁结果设置为失败，获取锁流程结束。

2、 存在某种排队等候机制，线程继续等待，仍然保留获取锁的可能，获取锁流程仍在继续。

其实这两种方案对应了两种情况：

1、线程获取不到锁立即处理其他流程，比如单机模式下秒杀的场景，其他线程拿不到资源就表示商品已经被抢完了应该立即提示而不是排队等候继续获取锁。也就是说这种场景对应不是所有线程都要获取到资源。

2、所有线程都必须获取到资源，比如说多线程下写日志的场景，每个线程都必须执行写日志要不然就丢失日志了。



针对方案1，我们可以提供`tryLock`方法，返回一个boolean类型的值，若`state`为0则直接上锁，如果不为0为了可重入性，要先判断拥有者是不是当前线程，如果是当前线程则`state`加一操作，如果不是直接返回false：

```java
  public boolean tryLock() {
        final Thread thread = Thread.currentThread();
        int currentSate = state.get();
        if (currentSate == FREE) {
            if (state.compareAndSet(0, 1)) {
                setExclusiveOwnerThread(thread);
                return true;
            }
        }
        if (thread == exclusiveOwnerThread) {
            //这里肯定只有一个线程能操作，所以为了性能就不用cas了
            state.set(state.get() + 1);
            return true;
        }
        return false;
    }
```

针对方案2，所谓的排队等候机制，那么这个“排队”肯定是在队列里了，之所以想到用队列，我们应该满足一个FIFO（first in first out）也就是先进先出，虽然说这里是非公平的但是进入队列的线程还是应该按照顺序来获取锁。所以我们定义一个`Node`类来表示这个队列中的节点。按照数据结构的基础知识，队列的节点必须要有前驱节点指针和后继节点指针，然后我们还是要设置一个owner让程序知道当前是哪个线程持有了这个节点，那么进入了队列里的线程应该做什么操作呢？我们可以参考Synchronized的锁升级机制——没有获取到锁的线程先自旋然后阻塞，那么就要给这个节点一个状态了，所以还需要一个状态的属性,然后考虑到前面的节点先出队，所以我们需要一个获取他的前驱节点的方法：

```java
    static final class Node{

        /** 当前节点在队列中的状态*/
        AtomicInteger waitStatus=new AtomicInteger(0);;

        /** 表示处于该节点的线程*/
        volatile Thread thread;

        /** 前驱指针*/
        volatile Node prev;

       /**
         * 返回前驱节点，没有的话抛出空指针异常
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null) {
                throw new NullPointerException();
            }
            else {
                return p;
            }
        }

        /** 后继指针*/
        volatile Node next;
    }
```

因为是队列（实际上是一个双向链表），所以我们是从尾部插入进去，所以我们还要给zyfLock这个类两个属性：头节点和尾节点。然后入队的时候分为两种情况：队列不为空和队列为空。我们先看第一种队列不为空的情况：

```java
 Node node = new Node(Thread.currentThread());
 Node concurrentTail=tail;
 //当前尾节点不为空说明队列不为空
 if(concurrentTail != null){
    node.prev=concurrentTail;
    tail=node;
    concurrentTail.next=node;
    return node;
        }
```

把当前新建节点的前驱节点指向当前尾节点，再把当前尾节点设置为新建节点，最后把上一个尾节点的下一个节点指向新建节点即可，非常简单的链表新建元素的代码。

针对第二种情况，我们需要新建一个双向链表，这里思考一下我们新建的节点要放在队列的第一个节点上吗？如果放在第一个节点里，因为是双向链表，出队的时候我们并不知道从哪里出队，因为你放的时候你知道这是第一个节点但是出队的时候你就不知道了啊，所以比较好的做法是头节点设置成虚拟节点也就是没有线程持有的节点。

既然我们决定第一个节点设置成虚节点，那我们最先入队的节点应该放在第二个节点里：

```java
 private Node initQueue(final Node node) {
                head = new Node();
                tail=head;
                node.prev=head;
                tail = node;
                head.next = node;
                return node;
            }  
    }
```

根据上面的思路我们的代码就是这样的，一次性初始化两个节点，一个头虚节点一个首个入队的节点。好吧其实有读者看到这里已经按捺不住的想喷笔者了，因为节点入队不是原子操作，所以会出现短暂的head != tail，此时Tail指向最后一个节点，而且Tail指向Head。如果Head没有指向Tail，这种情况下也需要将相关线程加入队列中,所以上述代码并不能解决这种问题。那么我们开始重构！根据知识储备，我们要保证一个变量在变化的时候线程安全，我们的手段就是：加锁；这里锁可以分为两类——乐观锁和悲观锁；悲观锁这里可以用synchronized，不过这里要是用synchronized的话我们在用到的地方都要被上锁，性能实在是太低了；那么我们就用乐观锁也就是CAS，这里Node是我们自己定义的所以不存在原子性的，所以我们要自己实现CAS了，根据知识储备CAS是调用CPU的CAS指令，这在hotspot虚拟机中完成，JVM提供了一个UnSafe类来给java程序调用，所以我们这里用UnSafe手动来CAS（出自AtomicInteger源码）。`unsafe.compareAndSwapObject`方法可以实现CAS，这个方法需要四个参数：当前对象、需要修改的变量的内存地址、预期原值、新值。其他的都很好拿到，这个内存地址我们只能用` unsafe.objectFieldOffset`来获取相对Java对象的“起始地址”的偏移量来获取地址：

```java
  private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long headOffset;
    private static final long tailOffset;

    static {
        try {
            headOffset = unsafe.objectFieldOffset
                    (ZyfLock.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                    (ZyfLock.class.getDeclaredField("tail"));

        } catch (Exception ex) {
            throw new Error(ex);
        }
    }
```

然后我们新增两个CAS方法：

```java
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }
```

来让头尾节点可以以CAS的方式创建。那么上述代码就应该修改如下：

```java
    Node node = new Node(Thread.currentThread());
        Node concurrentTail = tail;
        //当前尾节点不为空说明队列不为空
        if (concurrentTail != null) {
            node.prev = concurrentTail;
            if (compareAndSetTail(concurrentTail, node)) {
                concurrentTail.next = node;
                return node;
            }
        }
```

队列不为空的时候插入节点在设置尾节点的时候用CAS的方式插入，如果插入失败就继续往下走，那么我们在刚刚初始化队列的方法就不仅仅是初始化队列了，可能还要往队列里插入：

```java
   private Node initQueueOraddWaiterList(final Node node) {
       //自旋
        for (; ; ) {
            Node concurrentTail = tail;
            //再次检查，如果队列真的为空则初始化
            if (concurrentTail == null) {
                if (compareAndSetHead(new Node())) {
                    tail = head;
                }
            } else {
                //不为空就新建，和上面的代码一样
                node.prev = concurrentTail;
                if (compareAndSetTail(concurrentTail, node)) {
                    concurrentTail.next = node;
                    return node;
                }
            }
        }
    }
```

这里方法的名字就是新建或者插入了，首先这里先自旋，如果尾节点为空则新建一个虚节点，然后再次自旋就是插入我们的新节点了，因为这里还有可能是上一步CAS失败的节点所以不一定是新建节点或者插入第二个节点。

我们再回到获取锁失败的线程上，在获取锁失败之后最好是不要马上就进入队列，因为万一再次去获取锁就获取到了呢？所以这里可以用方案1的代码再次去获取一次锁，暂时性的代码如下：

```java
 public void lock() {
        if (state.compareAndSet(0, 1)) {
            setExclusiveOwnerThread(Thread.currentThread());
        } else {
            if (!tryLock()) {
                addWaiter();
            }
        }
    }
```



## 线程在队列里干什么

获取锁失败的线程进入队列了，那么这些线程在队列里应该干什么呢？首先根据基础知识储备和参考synchronized锁升级过程，我觉得这个线程应该首先自旋，去反复尝试获取锁，然后达到某种条件到时候让他阻塞停止自旋。我们一步一步来，首先完成自旋部分的代码。因为是队列所以应该是前面的节点去获取锁，由于第一个节点是虚节点，所以应该是从第二个节点开始尝试获取锁，所以我们的流程就是：传入刚刚新建的节点->获取节点的前节点判断是否是虚节点->若是虚节点则开始再次尝试获取锁->获取失败继续自旋等待，获取成功将当前锁的持有者变成这个节点的owner然后将前节点淘汰掉当前节点设置为虚节点：

```java
   private void acquireQueued(Node node){
        for(;;){
            Node prev=node.predecessor();
            //获取成功
            if(prev == head && tryLock()){
                //将当前节点设置为头虚节点
                head = node;
                node.thread = null;
                node.prev = null;
                //引用去掉，避免内存泄露
                prev.next=null;
            }
        }
    }
```

这里没有获取到锁的话有两种情况，如果是第二个节点没有获取到锁可能是这个时候有其他线程插队进来获取到了锁（这个满足我们的非公平的需求）或者这个节点压根就不是第二个节点。但是无论如何不可能一直这样自旋下去，因为一直没有获取到锁的话这样下去就会把CPU资源占完，所以我们需要在满足某种条件的时候让这个线程阻塞。首先我们不能让所有节点都被阻塞，这样的话就没有线程去竞争锁了，既然是队列，所以我们只让第二个节点去获取锁就好了，后面的节点就全部阻塞，我们的Node有一个属性是状态，我把最前面的节点的状态设置为准备好获取锁的状态让他一直获取锁，然后这个节点后面的节点就阻塞等待。所以我们需要一个方法去给这个节点的前面节点设置状态为已准备好的状态，然后再给一个方法用于挂起当前线程，阻塞调用栈返回当前线程的中断状态，阻塞调用栈这里我们有几个选择可以来实现阻塞线程：`Thread.sleep()`、`Object.wait()`、`LockSupport.park`，这三个的区别简单说下就是只有`LockSupport.park`可以手动唤醒，其他两个都是根据传入时间来唤醒的，这里肯定不能是指定时间的因为大家都不知道这里时间设置多久合适所以肯定是开锁的时候手动唤醒：

````java
 private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus.get();
        if (ws == Node.SIGNAL) {
            return true;
        } else {
            pred.waitStatus.compareAndSet(ws, Node.SIGNAL);
        }
        return false;
    }

    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        return Thread.interrupted();
    }
````

那么上面的代码就可以变成这样:

```java
private void acquireQueued(Node node) {
        for (; ; ) {
            Node prev = node.predecessor();
            //获取成功
            if (prev == head && tryLock()) {
                //将当前节点设置为头虚节点
                head = node;
                node.thread = null;
                node.prev = null;
                //引用去掉，避免内存泄露
                prev.next = null;
                return;
            }
             if (shouldParkAfterFailedAcquire(prev, node)) {
                    parkAndCheckInterrupt();
                }
        }
    }
```

但是这样有一个问题：这里并没有异常处理 啊，如果中断或者获取锁的时候发生了异常怎么办呢？所以我们需要增加一个`try-catch-finally`的代码片段来处理，但是这里可能发生的异常是我们在内部自己逻辑造成的异常，也是可以接受的异常所以就不捕获了，因为捕获之后也不可能抛出去也不可能记录所以就不捕获异常，只是去发生异常的时候做一个处理，所以我们改成`try-finally`来处理。我们在finally中来把这个节点修改为取消状态，然后给一个boolean变量'failed'，如果是成功获取到锁的线程这个变量就是false就不去改成取消状态:

```java
private void acquireQueued(Node node) {
        boolean failed=true;
        try {
            for (; ; ) {
                Node prev = node.predecessor();
                //获取成功
                if (prev == head && tryLock()) {
                    //将当前节点设置为头虚节点
                    head = node;
                    node.thread = null;
                    node.prev = null;
                    //引用去掉，避免内存泄露
                    prev.next = null;
                    failed=false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(prev, node)) {
                    parkAndCheckInterrupt();
                }
            }
        } finally {
            if(failed){
                cancelGetLock(node);
            }
        }
    }
```

然后来想想这个`cancelGetLock`也就是把节点改成取消状态要怎么实现。首先是考虑这个节点的前驱节点，如果前驱节点就是取消状态那么就继续往前找，直到找到一个不为取消状态的节点，然后将找到的Pred节点和当前Node关联，将当前节点设置为取消状态。当前节点在队列里的位置有三种情况：

(1) 当前节点是尾节点：将当前节点设置成取消状态的虚节点然后将前继节点设置为尾节点

(2) 当前节点是Head的后继节点：也就是最先获取锁的节点，这个时候就首先把自己设置成虚节点，后继节点指向断开，然后唤醒后继节点，相当于他的后继节点变成了最先获取锁的节点来开始获取锁

(3) 当前节点不是Head的后继节点，也不是尾节点：前继节点指向后继节点，相当于把这个节点去掉。

```java
 private void cancelGetLock(Node node) {
        // 将无效节点过滤
        if (node == null) {
            return;
        }
        // 设置该节点不关联任何线程，也就是虚节点
        node.thread = null;
        Node pred = node.prev;
        // 通过前驱节点，跳过取消状态的node
        while (pred.waitStatus.get() > 0) {
            node.prev = pred = pred.prev;
        }
        // 获取过滤后的前驱节点的后继节点
        Node predNext = pred.next;
        // 把当前node的状态设置为CANCELLED
        node.waitStatus.set(Node.CANCELLED);
        // 如果当前节点是尾节点，将从后往前的第一个非取消状态的节点设置为尾节点
        // 更新失败的话，则进入else，如果更新成功，将tail的后继节点设置为null
        if (node == tail && compareAndSetTail(node, pred)) {
            compareAndSetNext(pred, predNext, null);
        } else {
            int ws;
            // 如果当前节点不是head的后继节点，1:判断当前节点前驱节点的是否为SIGNAL，2:如果不是，则把前驱节点设置为SINGAL看是否成功
            // 如果1和2中有一个为true，再判断当前节点的线程是否为null
            // 如果上述条件都满足，把当前节点的前驱节点的后继指针指向当前节点的后继节点
            if (pred != head && ((ws = pred.waitStatus.get()) == Node.SIGNAL || (ws <= 0 && pred.waitStatus.compareAndSet(ws, Node.SIGNAL))) && pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus.get() <= 0) {
                    compareAndSetNext(pred, predNext, next);
                }
            } else {
                // 如果当前节点是head的后继节点，或者上述条件不满足，那就唤醒当前节点的后继节点
                unparkSuccessor(node);
            }
            //避免内存泄露
            node.next = node;
        }
    }
```

根据上述思路的代码如上（ps：这里是整个文章最难的部分主要是笔者数据结构稀烂所以看了很久才能理解，写的时候几乎是参考的AQS自己的源码的所以这里几乎是直接贴的是AQS的源码，这里是全文唯一一个笔者没有用自己代码的地方）。

这里涉及到了唤醒阻塞的节点的`unparkSuccessor`方法唤醒当前节点的下一个节点方法，首先如果当前节点是准备态我们需要把当前节点设置为初始态，否则在其他线程在执行队列获取锁方法的时候会再次把我们要唤醒的节点设置为阻塞，然后下一个节点也有可能是空节点、虚节点或者取消态节点，这个时候就需要遍历队列找出最近的一个正常节点，然后唤醒这个节点即可:

```java
    private void unparkSuccessor(Node node) {
        int ws = node.waitStatus.get();
        //如果当前节点是准备状态的话就改成初始化状态，避免后继节点又被阻塞
        if (ws == Node.SIGNAL) {
            node.waitStatus.compareAndSet(ws, 0);
        }
        Node needUnParkNode = node.next;
        //如果后继节点是空的或者是虚节点或者是取消态的节点
        if (needUnParkNode == null || needUnParkNode.thread == null || needUnParkNode.waitStatus.get() == Node.CANCELLED) {
            needUnParkNode = null;
            // 就从尾部节点开始找，到队首，找到队列第一个是准备态的节点。
            for (Node tailNode = tail; tailNode != null && tailNode != node; tailNode = tailNode.prev) {
                if (tailNode.waitStatus.get() <= 0) {
                    needUnParkNode = tailNode;
                }
            }
        }
        //唤醒节点
        if (needUnParkNode != null) {
            LockSupport.unpark(needUnParkNode.thread);
        }
    }
```

然后我们应该在刚刚把节点设置为阻塞状态的时候考虑可能有取消节点的情况，需要把取消状态的节点剔除出队列，我们应该在刚刚的`shouldParkAfterFailedAcquire`方法里去删除，因为如果是取消状态的节点就不能让他又被设置成就绪状态了。所以我们给一个取消状态，在刚刚`shouldParkAfterFailedAcquire`方法里处理这个取消状态的节点。

```java
private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus.get();
        if (ws == Node.SIGNAL) {
            return true;
        }
        if (ws == Node.CANCELLED) {
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus.get() == Node.CANCELLED);
            pred.next = node;
        } else {
            pred.waitStatus.compareAndSet(ws, Node.SIGNAL);
        }
        return false;
    }
```

如果线程是取消状态我们就循环向前查找取消节点，把取消节点从队列中剔除。

到这里，获取锁部分的代码就完成了，总结一下流程：

获取锁失败->再次尝试获取锁->往队列里增加节点->在队列里自旋获取锁->尝试获取失败之后阻塞线程等待唤醒->如果发生异常就标记为取消节点。那么我们上锁方法的完整代码就是：

```java
 public void lock() {
        if (state.compareAndSet(0, 1)) {
            setExclusiveOwnerThread(Thread.currentThread());
        } else {
            if (!tryLock()) {
                acquireQueued(addWaiter());
            }
        }
    }
```



## 开锁

开锁流程就非常简单了比起上面的来说，首先对`staet`减一，因为可重入性嘛，然后如果`state`为0的话说明这个锁应该被释放了，所以设置持有线程为空，然后唤醒通知下面一个节点通知他可以继续开始自旋获取锁了：

```java
  public void unLock() {
        //校验当前线程是否可以开锁
        if (Thread.currentThread() != getExclusiveOwnerThread()) {
            throw new IllegalArgumentException("开锁失败，请用该锁持有者来开锁");
        }
        if (state.get() == 0) {
            throw new IllegalArgumentException("开锁失败，本锁已经开了，不能重复开锁");
        }
        //state-1操作，因为可重入性
        int conCurrentState = state.get() - 1;
        //state为0说明所有锁都解开了，把持有者设置为空，然后唤醒头节点的下一个节点，通知来获取锁
        if (conCurrentState == 0) {
            setExclusiveOwnerThread(null);
            Node headNode = head;
            //结点不为空并且头结点的waitStatus不是初始化节点情况，解除线程挂起状态
            if (headNode != null && headNode.waitStatus.get() != 0) {
                unparkSuccessor(headNode);
            }
        }
        //设置state
        state.set(conCurrentState);
    }
```

这里解释一下为什么只有在结点不为空并且头结点的waitStatus不是初始化节点情况，解除线程挂起状态的情况下才去唤醒下一个节点，因为h == null Head还没初始化。初始情况下，head == null，第一个节点入队，Head会被初始化一个虚拟节点。所以说，这里如果还没来得及入队，就会出现head == null 的情况。

h != null && waitStatus == 0 表明后继节点对应的线程仍在运行中，不需要唤醒。

h != null && waitStatus < 0 表明后继节点可能被阻塞了，需要唤醒。



## 调试

其实上面代码中用到unsafe的时候就有读者会指出，自己代码里是不能用unsafe类的，因为classLoader里会去校验包名开头是否是`java.`不是的话会报错`java.lang.SecurityException: Unsafe`，当然了笔者挣扎过，比如改一下openJdk的源码，去掉这个校验或者用JDK9引入模块，但是觉得这样做其实并没有意义，因为本文的重点其实不是这个unSafe类，所以笔者在调试的时候就把上文用到过的unSafe类去掉了，自然而然CAS插入节点的部分也不能用CAS了，这算是本文的一个缺陷或者说是遗憾。我们先写下如下测试代码：

```java
public class ZyfLockTest {

    static int count = 0;

    public static void main(String[] args) throws InterruptedException {

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 10000; i++) {
                        count++;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        };
        Thread thread1 = new Thread(runnable);
        Thread thread2 = new Thread(runnable);
        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        System.out.println(count);
    }
}
```

多么经点的多线程入门的i++问题，自然而然的这样写的话结果肯定不是20000，然后我们加上我们刚刚实现的`zyfLock`：

```java
public class ZyfLockTest {

    static int count = 0;
    static ZyfLock zyfLock = new ZyfLock();

    public static void main(String[] args) throws InterruptedException {

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    zyfLock.lock();
                    for (int i = 0; i < 10000; i++) {
                        count++;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    zyfLock.unLock();
                }

            }
        };
        Thread thread1 = new Thread(runnable);
        Thread thread2 = new Thread(runnable);
        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        System.out.println(count);
    }
}
```

运行之后结果20000，顺利达到目标。



## 总结

​	其实上述代码就是笔者在读完`ReentrantLock`源码之后依葫芦画瓢写出来的，当然不是完全一样，比如在上锁的代码中线程被唤醒之后还要调用`Thread.currentThread().interrupt()`来中断线程，这部分笔者并未写入，这个属于Java提供的协作式中断知识，这里就不再赘述感兴趣的可以自己去查阅。

​	为什么会想到自己重新写一个呢，其实是因为最近在看AQS的源码，看了几遍之后发现理解并没有非常深入，笔者想起高中语文老师说过的：“看十遍不如写一遍”，于是自己动手写了一遍，写的过程中一直在惊叹ReentrantLock和AQS源码的惊奇、巧妙，这是以前只看源码无法理解的，因为只有在自己有一个方案再用JDK的方案来对比的之后才会发现自己方案的简单和JDK提供的方案的巧妙。



参考：[从ReentrantLock的实现看AQS的原理及应用]：https://tech.meituan.com/2019/12/05/aqs-theory-and-apply.html

[Java并发：深入浅出AQS之独占锁模式源码分析]：https://cloud.tencent.com/developer/article/1523008

