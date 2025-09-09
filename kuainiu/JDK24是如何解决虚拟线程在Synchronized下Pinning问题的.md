# JDK24是如何解决虚拟线程在Synchronized下Pinning问题（1）——轻量级锁如何解决

在上文[今天来谈一谈Java的虚拟线程](https://mp.weixin.qq.com/s/BZVfsQ_dloUNdIG5Mpq9rA) 中笔者介绍了虚拟线程在使用Synchronized之后会产生Pinning问题，这点在虚拟线程的jep中也有说道：

>There are two scenarios in which a virtual thread cannot be unmounted during blocking operations because it is *pinned* to its carrier:
>
>1. When it executes code inside a `synchronized` block or method

所以在jdk21中，作者建议大家如果要使用虚拟线程的话就别使用Synchronized了，而是修改为juc的ReentrantLock

然而在今年（2025年）3月18日发布的JDK24中，发布特性中就有：

>- **[JEP 491](https://openjdk.org/jeps/491):** Synchronize Virtual Threads without Pinning — 提高使用同步方法和语句的 Java 代码和库的可扩展性，帮助开发人员提高工作效率。该功能允许虚拟线程释放其底层平台线程，让开发人员能够访问更多的虚拟线程来管理其应用的工作负载。

也就是说，在JDK24中，jdk的开发者已经解决了Synchronize的Pinning问题，那么笔者这里就好奇了，由于之前深入了解过Synchronize的源码：[深入JVM源码分析Synchronized实现原理](https://mp.weixin.qq.com/s/FfMsz2rkI3V_pqU7AnnLDQ) 以及虚拟线程的源码，所以笔者很清楚为什么Synchronize会固定平台线程的原因：

就是因为Synchronize非常依赖于线程本身，比如轻量级锁的时候basicLockObject就是在线程的栈上创建的，然后写到对象头里，也就是说和载体线程是绑定的，如果更换了载体线程就要重复的去更改锁记录，jvm就要区分哪些是争抢锁造成的更改锁记录哪些是由于更换了载体线程造成的更改，这几乎无法完成，重量级锁的时候monitorObject的owner是载体线程无法绑定到虚拟线程，而在ReentrantLock中则不会，因为ReentrantLock中的owner可以是虚拟线程，然后用了一个state(ObjectMonitor中也有类似的变量_recursions)来标识是否锁有拥有者和重入次数：

![image-20250620104028004](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20250620104028004.png)

也就是说ReentrantLock是和虚拟线程绑定的，即使虚拟线程更换了载体线程也不会有问题，而Synchronize是绑定的平台线程，所以Synchronize也能更换平台线程那锁的安全性就失效了，也就是说如果其他虚拟线程切换到了这个平台线程上就可以获取到上一个虚拟线程在这个线程上申请到的锁了，所以只能让这个虚拟线程固定在这个平台线程上了。

所以笔者非常好奇，jdk24是如何解决这个问题呢？

接下来让我们深入到jdk24的源码中来寻找答案，我们分别从Synchronize的轻量级锁和重量级锁来看，我们会先介绍jdk24中轻量级锁、重量锁的上锁逻辑，然后再来看他是如何避免虚拟线程的Pinning问题，由于篇幅问题，本文会率先介绍轻量级锁，然后 下一篇再说重量级锁（如果两个都写到一篇文章里大概有一万多字了，太长了没人有耐心读完）





## 1、轻量级锁上锁过程

我们有过阅读Synchronize源码的经验，就知道Synchronize的入口在哪里了有不清楚的读者可以阅读上文中笔者的那篇Synchronize源码解读，我们直接看`interpreterRuntime.cpp`的`monitorenter`方法：

```c++
JRT_ENTRY_NO_ASYNC(void, InterpreterRuntime::monitorenter(JavaThread* current, BasicObjectLock* elem))
#ifdef ASSERT
  current->last_frame().interpreter_frame_verify_monitor(elem);
#endif
 ...
  ObjectSynchronizer::enter(h_obj, elem->lock(), current);
 ...
```

就是调用了`ObjectSynchronizer`的`enter`方法：

```c++
inline void ObjectSynchronizer::enter(Handle obj, BasicLock* lock, JavaThread* current) {
  assert(current == Thread::current(), "must be");

  if (LockingMode == LM_LIGHTWEIGHT) {
    LightweightSynchronizer::enter(obj, lock, current);
  } else {
    enter_legacy(obj, lock, current);
  }
}
```

这里要好好说道说道了，`LockingMode`是jvm参数，他的定义如下：

```c++
product(int, LockingMode, LM_LIGHTWEIGHT,                                 \
        "(Deprecated) Select locking mode: "                              \
        "0: (Deprecated) monitors only (LM_MONITOR), "                    \
        "1: (Deprecated) monitors & legacy stack-locking (LM_LEGACY), "   \
        "2: monitors & new lightweight locking (LM_LIGHTWEIGHT, default)") \
        range(0, 2)    
```

是Synchronize的锁定模式，最早是jdk21正式发布的参数，可以看到有三种模式：

- 只有monitor（只有重量级锁）
- 传统模式（就是以前的basicLocakObject作为轻量级锁的那一套）
- lightweight模式（Jdk21新增模式，轻量级Synchronize模式）

值得注意的是在jdk21中缺省配置是`LM_LEGACY`而在jdk24中变成了`LM_LIGHTWEIGHT`，也就是默认就是轻量级模式了（顺带提提一下jdk26会彻底删除`LM_LEGACY`模式）

也就是说默认的话jdk24走的是轻量级模式，我们直接进入`LightweightSynchronizer`中的`enter`方法查看：

```c++
void LightweightSynchronizer::enter(Handle obj, BasicLock* lock, JavaThread* current) {
  assert(LockingMode == LM_LIGHTWEIGHT, "must be");
  assert(current == JavaThread::current(), "must be");

    // 检查对象是否基于值类型（如 Integer、String 等），这类对象不支持同步操作
  if (obj->klass()->is_value_based()) {
    ObjectSynchronizer::handle_sync_on_value_based_class(obj, current);
  }
  // 管理锁状态的缓存
  CacheSetter cache_setter(current, lock);

  // Used when deflation is observed. Progress here requires progress
  // from the deflator. After observing that the deflator is not
  // making progress (after two yields), switch to sleeping.
  // 控制自旋，避免长时间等待，初始0最多2次
  SpinYield spin_yield(0, 2);
  //是否锁降级
  bool observed_deflation = false;
  // 线程特有的锁栈
  LockStack& lock_stack = current->lock_stack();
  // 锁栈没有满且当前对象的锁在锁栈里说明是重入的情况直接成功
  if (!lock_stack.is_full() && lock_stack.try_recursive_enter(obj())) {
    // Recursively fast locked
    return;
  }
  //如果栈里面有对象就发生膨胀
  if (lock_stack.contains(obj())) {
      //膨胀为重量级锁但是不获取锁
    ObjectMonitor* monitor = inflate_fast_locked_object(obj(), ObjectSynchronizer::inflate_cause_monitor_enter, current, current);
    //使用monitor的enter方法获取控制器
    bool entered = monitor->enter(current);
    assert(entered, "recursive ObjectMonitor::enter must succeed");
    // 在获取重量级锁锁成功后，将锁信息缓存到线程本地
    cache_setter.set_monitor(monitor);
    return;
  }
  //如果不是重入并且锁栈上没有这个对象说明是第一次进入，循环获取轻量级锁
  while (true) {
    // Fast-locking does not use the 'lock' argument.
    // Fast-lock spinning to avoid inflating for short critical sections.
    // The goal is to only inflate when the extra cost of using ObjectMonitors
    // is worth it.
    // If deflation has been observed we also spin while deflation is ongoing.
    //尝试通过 CAS 操作获取轻量级锁
    if (fast_lock_try_enter(obj(), lock_stack, current)) {
      return;
    }
    //自旋等待锁释放（短时间等待）
    else if (UseObjectMonitorTable && fast_lock_spin_enter(obj(), lock_stack, current, observed_deflation)) {
      return;
    }
    //锁降级，自旋等待
    if (observed_deflation) {
      spin_yield.wait();
    }
    //锁膨胀：如果轻量级锁获取失败，膨胀为重量级锁
    ObjectMonitor* monitor = inflate_and_enter(obj(), ObjectSynchronizer::inflate_cause_monitor_enter, current, current);
    if (monitor != nullptr) {
        // 在获取重量级锁锁成功后，将锁信息缓存到线程本地
      cache_setter.set_monitor(monitor);
      return;
    }

    // If inflate_and_enter returns nullptr it is because a deflated monitor
    // was encountered. Fallback to fast locking. The deflater is responsible
    // for clearing out the monitor and transitioning the markWord back to
    // fast locking.
    //inflate_and_enter返回空说明可以降级成轻量级锁了
    observed_deflation = true;
  }
}
```

代码很长，有耐心的读者可以一行一行的看，没耐心的读者可以看下笔者的总结，在轻量级锁的enter中，存在一个每个Java线程独有的锁栈：`lock_stack`	结构：

```C++
private:
  LockStack _lock_stack;
```

就是一个存放锁记录的栈（至于为什么用栈就是因为锁也是要先进后出的）

- 第一种情况：如果锁栈中存在这个对象并且在栈顶位置，那么就是可重入的情况，再次把对象压入栈中代表重入次数+1，关键代码：`lock_stack.try_recursive_enter(obj())`:

```c++
inline bool LockStack::try_recursive_enter(oop o) {
  if (!VM_Version::supports_recursive_lightweight_locking()) {
    return false;
  }
  verify("pre-try_recursive_enter");


  assert(!is_full(), "precond");
	//栈顶为空或者不是当前对象则false
  int end = to_index(_top);
  if (end == 0 || _base[end - 1] != o) {
    // Topmost oop does not match o.
    verify("post-try_recursive_enter");
    return false;
  }

  //栈顶就是当前对象，就再次压入当前对象到栈内，这里就用+=
  _base[end] = o;
  _top += oopSize;
  verify("post-try_recursive_enter");
  return true;
}
```

- 第二种情况，如果锁栈内存在这个对象但是在非栈顶位置，那么就锁升级，关键代码：`lock_stack.contains(obj())`:

```c++
inline bool LockStack::contains(oop o) const {
  verify("pre-contains");

  // Can't poke around in thread oops without having started stack watermark processing.
  assert(StackWatermarkSet::processing_started(get_thread()), "Processing must have started!");

  int end = to_index(_top);
  for (int i = end - 1; i >= 0; i--) {
    //有当前对象
    if (_base[i] == o) {
      verify("post-contains");
      return true;
    }
  }
  return false;
}
```

注意了，这里有个问题，为什么同样的元素在栈顶就是可重入，不在栈顶但是存在就要锁升级呢？大家想想，如果当前对象在锁栈里但是不在栈顶说明什么？说明锁定这个对象之后又锁定了其他对象，然后又重入的锁定这个对象，这种情况就是锁嵌套：

```java
synchronized (lock1) {
  ...
    synchronized (lock2) {
    ...
        synchronized (lock1) { 
          ...
        }
    }
}
```

轻量级锁无法高效处理这种复杂的嵌套结构，因此需要膨胀为重量级锁（`ObjectMonitor`），后者通过全局计数器管理重入，不依赖锁栈，从这点来说对大家写代码也有一定的启发，比如尽量的避免sync的嵌套

- 第三种情况：当前对象没有进入过锁栈，也就是第一次轻量级锁，那么就循环的获取锁，关键代码：

```c++
inline bool LightweightSynchronizer::fast_lock_try_enter(oop obj, LockStack& lock_stack, JavaThread* current) {
    // 获取对象的mark word
  markWord mark = obj->mark();
  // 没有被锁定
  while (mark.is_unlocked()) {
      // 当前线程的锁栈有足够的空间
    ensure_lock_stack_space(current);
    // 对锁栈做一些校验，比如不是满的，比如不包含当前对象
    assert(!lock_stack.is_full(), "must have made room on the lock stack");
    assert(!lock_stack.contains(obj), "thread must not already hold the lock");
    // Try to swing into 'fast-locked' state.
    //构造轻量级锁标记，也就是一个新的markWord值，就是在原 Mark Word 的内容中追加了指向锁记录的指针
    //这里不会传入任何值给markword，所以这里只是标记了被锁定了，不会给对象头写是被谁锁定的
    markWord locked_mark = mark.set_fast_locked();
    //原始的markWord
    markWord old_mark = mark;
    //cas的方式去把新的markWord值写入到对象头中
    mark = obj->cas_set_mark(locked_mark, old_mark);
    // 返回值和老值相同说明cas成功那么就成功获取到了轻量级锁，就把当前对象压入到锁栈中
    if (old_mark == mark) {
      // Successfully fast-locked, push object to lock-stack and return.
      lock_stack.push(obj);
      return true;
    }
  }
  //如果被锁定了就返回false
  return false;
}
```

可以看到这里只有当对象上锁了才会离开这个方法，无论这个锁是不是当前线程上的，如果是当前线程上的则上锁成功外部直接返回，如果是非当前线程上的则进入第四种情况

- 第四种情况：如果对象被其他线程上了轻量级锁，则自旋等到轻量级锁释放（自旋等到锁释放是不是很熟悉？当然了这个逻辑在jdk21中都是没有的，是jdk22新增的，在jdk轻量级锁失败都表明发生了竞争从而膨胀为重量级锁，现在在轻量级锁下也可以自旋等待，但是为什么前面的jdk没有呢？）：

```c++
else if (UseObjectMonitorTable && fast_lock_spin_enter(obj(), lock_stack, current, observed_deflation)) {
  return;
}
```

这里`fast_lock_spin_enter`就是自旋等到重新竞争轻量级锁的方法，那为什么要加一个条件：`UseObjectMonitorTable`呢？首先我们要搞清楚，`UseObjectMonitorTable`是什么：

```c++
product(bool, UseObjectMonitorTable, false, DIAGNOSTIC,                   \
        "With Lightweight Locking mode, use a table to record inflated "  \
        "monitors rather than the first word of the object.")  
```

这个就是`全局监视器表`，启用这个配置之后我们熟悉的`ObjectMonitor`也就是重量级锁的对象监视器是从这个全局的监视器表中分配而不是传统把对象头直接存储指针，那么为什么在自旋等待轻量级锁释放的时候需要启用全局监视器表呢？我们直接看`UseObjectMonitorTable`方法：

```c++
bool LightweightSynchronizer::fast_lock_spin_enter(oop obj, LockStack &lock_stack, JavaThread *current, bool observed_deflation) {
    assert(UseObjectMonitorTable, "must be");
    // Will spin with exponential backoff with an accumulative O(2^spin_limit) spins.
    //基于 CPU 核心数确定最大自旋次数
    const int log_spin_limit = os::is_MP() ? LightweightFastLockingSpins : 1;
    const int log_min_safepoint_check_interval = 10;
    //要上锁对象的mark word
    markWord mark = obj->mark();
    //判断当前情况能不能自旋
    const auto should_spin = [&]() {
        //如果对象没有监视器说明还没有膨胀为重量级锁，则可以自旋
        if (!mark.has_monitor()) {
            // Spin while not inflated.
            return true;
            //如果存在锁降级
        } else if (observed_deflation) {
            // Spin while monitor is being deflated.
            //从全局监视器表中获取该对象的监视器
            ObjectMonitor *monitor = ObjectSynchronizer::read_monitor(current, obj, mark);
            //为空则说明降级完成，此时不存在重量级锁，或者正在被异步降级这种情况直接返回true给should_spin，表示可以自旋再次获取轻量级锁
            //如果有监视器但是监视器没有降级，那就不自旋等待了，返回到外层判断是不是应该降级
            return monitor == nullptr || monitor->is_being_async_deflated();
        }
        // Else stop spinning.
        //这里就是有监视器并且没有降级的场景
        return false;
    };
    // Always attempt to lock once even when safepoint synchronizing.
    bool should_process = false;
    //小于根据硬件计算出的的最大自旋次数
    for (int i = 0; should_spin() && !should_process && i < log_spin_limit; i++) {
        // Spin with exponential backoff.
        //每次循环自旋次数按指数增长
        const int total_spin_count = 1 << i;
        const int inner_spin_count = MIN2(1 << log_min_safepoint_check_interval, total_spin_count);
        const int outer_spin_count = total_spin_count / inner_spin_count;
        //指数退避自旋策略
        for (int outer = 0; outer < outer_spin_count; outer++) {
            should_process = SafepointMechanism::should_process(current);
            if (should_process) {
                // Stop spinning for safepoint.
                break;
            }
            for (int inner = 1; inner < inner_spin_count; inner++) {
                // 执行空转，消耗CPU时间
                SpinPause();
            }
        }
        //每次自旋都用这个方法获取轻量级锁，和外层一致
        if (fast_lock_try_enter(obj, lock_stack, current)) return true;
    }
    //自旋达到最大次数也无法获取到锁，那就返回，外面来升级
    return false;
}
```

可以看到，这里要自旋的条件是当前对象没有控制器，或者控制器正在降级和降级完毕，那么如何判断这个监视器有没有降级或者降级完毕呢？答案就是使用刚刚说到的全局监视器表，只有全局监视器表能够确保获取到的监视器状态的正确性，也就给自旋等待获取轻量级锁提供了条件。

- 第五种情况就是升级成重量级锁了：

```c++
ObjectMonitor *monitor = inflate_and_enter(obj(), ObjectSynchronizer::inflate_cause_monitor_enter, current, current);
if (monitor != nullptr) {
    // 在获取重量级锁锁成功后，将锁信息缓存到线程本地
    cache_setter.set_monitor(monitor);
    return;
}
```

重量级锁我们放在后面说

总结一下轻量级锁的上锁逻辑就是：如果当前对象在最近一次上过了轻量级锁那么就是可重入直接把锁放到锁栈里，如果这个对象最近上了锁但是不是这个对象的锁，那么就是锁嵌套的情况，升级为重量级锁，如果锁栈里没有这个对象的锁就是首次进入，此时尝试上轻量级锁，如果锁被其他线程占用了，那么就在启用了全局监视器的条件下自旋等待重新获取轻量级锁，最后如果自旋上轻量级锁也失败了就升级为重量级锁。

## 2、轻量级锁的情况下是如何解决虚拟线程Pinning的问题

了解完了轻量级锁上锁逻辑之后，我们看看轻量级锁如何解决Pinning问题。

首先，我们先不看源码，我们自己分析一下如何解决然后再来看源码证实我们的猜想

我们知道，虚拟线程的原理就是在线程阻塞的时候freeze出栈然后把平台线程释放出去，等唤醒的时候又把frzze的栈thaw回到平台线程上，这样可以最大程度的让平台线程一直在工作，那么我们的`lock_stack`锁栈里存放的是被轻量级锁锁定的对象，也就是说这个锁栈虽然属于当前线程，但是没有存放任何和当前线程有关的东西比如线程ID，那么这个锁栈如果一起被栈freeze起来就行了，然后被唤醒的时候thaw给新的平台线程也不会产生锁的安全性问题，因为锁栈不和线程产生关联，无乱哪个平台线程来存放都是一样的，这样不就解决了pinning的问题了吗？

我们接下来来看下源码验证我们的猜想。

在笔者以前的文章[今天来谈一谈Java的虚拟线程](https://mp.weixin.qq.com/s/BZVfsQ_dloUNdIG5Mpq9rA)中讲过虚拟线程的源码， 不太熟悉的读者可以去复习下这篇文章，anyway，我们在线程阻塞的时候去freeze栈的入口是在方法`gen_continuation_yield`中，然后这个方法因为有大量的汇编所以所在的类是根据平台的来的，如果我们就看x86的源码就行了`sharedRuntime_x86_64.cpp`:

```c++
static void gen_continuation_yield(MacroAssembler* masm,
                                   const VMRegPair* regs,
                                   OopMapSet* oop_maps,
                                   int& frame_complete,
                                   int& stack_slots,
                                   int& compiled_entry_offset) {
 ...
  address the_pc = __ pc();

  frame_complete = the_pc - start;

 ...
  __ set_last_Java_frame(rsp, rbp, the_pc, rscratch1);
  __ movptr(c_rarg0, r15_thread);
  __ movptr(c_rarg1, rsp);
  __ call_VM_leaf(Continuation::freeze_entry(), 2);
  
  ...
```

这里重点就是`__ call_VM_leaf(Continuation::freeze_entry(), 2);`意思是调用jvm的方法`Continuation::freeze_entry()`最后就是进到了`continuationFreezeThaw.cpp`的`freeze_internal`方法，这个方法笔者在以前的文章中讲过，直接在虚拟线程那篇文章里搜索`freeze_internal`就行了，总之，核心的freeze代码就是这：

```c++
 Freeze<ConfigT> freeze(current, cont, sp, preempt);
...
freeze_result res = fast ? freeze.try_freeze_fast() : freeze.freeze_slow();
```

我们先看`Freeze`这个类的构造函数：

```c++
FreezeBase::FreezeBase(JavaThread* thread, ContinuationWrapper& cont, intptr_t* frame_sp, bool preempt) :
...

  if (LockingMode != LM_LIGHTWEIGHT) {
    _monitors_in_lockstack = 0;
  } else {
    _monitors_in_lockstack = _thread->lock_stack().monitor_count();
  }
}
```

我们不看其他的，只看这里这个对`_monitors_in_lockstack`的赋值，如果当前锁模式不是轻量级锁则这个就为0，因为只有轻量级锁模式才会用到锁栈，然后就是根据当前线程来获取锁栈的数量：

```c++
inline int LockStack::monitor_count() const {
  int end = to_index(_top);
  assert(end <= CAPACITY, "invariant");
  return end;
}
```

其实就是获取这个锁栈的长度，记住这个`_monitors_in_lockstack` 后面会用到的

然后我们看frzze的方法，在以前的文章里笔者讲过fast和slow的区别：

>是否快速挂起的区别就是，快速挂起是直接分配一个新的栈块，并在其中进行快速挂起操作失败就会进行非快速挂起，非快速挂起会递归的尝试递归地挂起栈帧

所以我们先看fast：

```c++
freeze_result Freeze<ConfigT>::try_freeze_fast() {
  ...
	//分配一个新的栈块
  stackChunkOop chunk = allocate_chunk(cont_size() + frame::metadata_words + _monitors_in_lockstack, _cont.argsize() + frame::metadata_words_at_top);
  //尝试在新的栈块上freeze
  if (freeze_fast_new_chunk(chunk)) {
    return freeze_ok;
  }
...
  return freeze_slow();
}
```

所以是在`freeze_fast_new_chunk`中：

```c++
bool FreezeBase::freeze_fast_new_chunk(stackChunkOop chunk) {
...
  freeze_fast_copy(chunk, chunk_start_sp CONT_JFR_ONLY(COMMA true));

  return true;
}
```

然后中`freeze_fast_copy`：

```c++
void FreezeBase::freeze_fast_copy(stackChunkOop chunk, int chunk_start_sp CONT_JFR_ONLY(COMMA bool chunk_is_allocated)) {
 ...
  if (_monitors_in_lockstack > 0) {
    freeze_lockstack(chunk);
  }

 ...
}
```

终于看到锁栈相关的了，上文中讲到了`_monitors_in_lockstack`这个变量的初始化逻辑，就是在构造函数中初始化的，这个大于0说明当前线程存在锁栈，就需要把锁栈也freeze起来：

```c++
void FreezeBase::freeze_lockstack(stackChunkOop chunk) {
  assert(chunk->sp_address() - chunk->start_address() >= _monitors_in_lockstack, "no room for lockstack");

  _thread->lock_stack().move_to_address((oop*)chunk->start_address());
  chunk->set_lockstack_size(checked_cast<uint8_t>(_monitors_in_lockstack));
  chunk->set_has_lockstack(true);
}
```

这里就是很简单的把锁栈移动到新创建的栈块中来，从而达到freeze锁栈的作用

当然了在slow freeze中也会调用这个方法，他会在递归feeze栈中调用`recurse_freeze_java_frame`然后在`finalize_freeze`中这样调用：

```c++
  if (_monitors_in_lockstack > 0) {
    freeze_lockstack(chunk);
  }
```

然后毋庸置疑的会在thaw的时候重新把这个freeze在chunk栈块中的锁栈移动到当前平台线程下了：

```c++
void ThawBase::thaw_lockstack(stackChunkOop chunk) {
  int lockStackSize = chunk->lockstack_size();
  assert(lockStackSize > 0 && lockStackSize <= LockStack::CAPACITY, "");

  oop tmp_lockstack[LockStack::CAPACITY];
  chunk->transfer_lockstack(tmp_lockstack, _barriers);
  //从chunk中移动到平台线程中
  _thread->lock_stack().move_from_address(tmp_lockstack, lockStackSize);

  chunk->set_lockstack_size(0);
  chunk->set_has_lockstack(false);
}
```





## 3、总结

在jdk24的Synchronized轻量级锁中，用到了锁栈这个数据结构，会存放锁定对象的oop指针，锁栈里有这个对象的oop指针就说明这个对象被锁定了，然后会在对象的对象头上写有这个对象锁记录的指针，注意这里不会记录任何锁，只是记录这个对象被锁定了，然后被谁锁定的要通过锁栈来，然后在虚拟线程中，阻塞的时候会把锁栈和栈一起freeze到chunk中，被唤醒之后一起又thaw到新的平台线程中