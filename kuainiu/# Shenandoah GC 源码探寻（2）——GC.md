# Shenandoah GC 源码探寻（2）——GC

在上一篇Shenandoah GC中我们看了Shenandoah的新对象分配代码， 今天我们再来看看Shenandoah的GC源码

本文因为存在大量源码，篇幅较长（约一万三千字），如果只是想简单了解的可以直奔最后一章总结。

## 1、GC代码入口

根据我们的基础知识，一般是在新对象分配失败的时候会去触发GC，那么我们就从新对象分配的代码里去寻找GC代码的入口。

在上一篇中：

`shenandoahHeap.cpp`的`allocate_memory`方法中：

```c++
//尝试分配内存。如果分配失败，那么会尝试多次，直到成功或者达到全局分配失败阈值。在每次尝试之间，都会调用 handle_alloc_failure 方法来处理分配失败。
    while (result == nullptr && tries <= ShenandoahFullGCThreshold) {
    tries++;
    control_thread()->handle_alloc_failure(req);
    result = allocate_memory_under_lock(req, in_new_region);
}
```

如果分配失败会有一个方法`handle_alloc_failure`来处理分配的情况，那么我们要寻找的东西就肯定在这个方法里面：

```c++
void ShenandoahControlThread::handle_alloc_failure(ShenandoahAllocRequest& req) {
    //获得当前堆实例
  ShenandoahHeap* heap = ShenandoahHeap::heap();
    //检查是否是java线程
  assert(current()->is_Java_thread(), "expect Java thread here");

  //尝试设置分配失败的GC
  if (try_set_alloc_failure_gc()) {
    // Only report the first allocation failure
    //在gc日志中打印
    log_info(gc)("Failed to allocate %s, " SIZE_FORMAT "%s",
                 req.type_string(),
                 byte_size_in_proper_unit(req.size() * HeapWordSize), proper_unit_for_byte_size(req.size() * HeapWordSize));

    // Now that alloc failure GC is scheduled, we can abort everything else
    //取消所有其他 GC ，因为分配失败GC在这个时间已经被调度了
    heap->cancel_gc(GCCause::_allocation_failure);
  }
    //MonitorLocker锁来上锁，处理线程安全问题
  MonitorLocker ml(&_alloc_failure_waiters_lock);
  //等待GC完成，在完成之前一直阻塞等待
  while (is_alloc_failure_gc()) {
    ml.`();
  }
}
```

我们可以看到，在当前分配线程中，会去修改是否启动GC的标志位，如果修改成功说明是当前线程启动的那么就会把其他GC都退出掉，因为当前分配失败GC的优先级最高，为什么最高？因为分配失败GC会影响应用线程，让应用线程等待，所以优先让分配失败GC先进行。

这里有两个点：

1、设置GC标志位

2、线程阻塞等待

第二点不属于今天的内容，后面有时间笔者在研究下，所以就不浪费时间了。

第一点中，我们在之前看G1的GC源码的时候知道G1触发GC是用的事件推送机制，但是Shenandoah似乎不是，而是用这种标志位的方式，我们看一下`try_set_alloc_failure_gc`代码：

```c++
bool ShenandoahControlThread::try_set_alloc_failure_gc() {
  return _alloc_failure_gc.try_set();
}
...
bool try_set() {
    if (is_set()) {
      return false;
    }
    ShenandoahSharedValue old = Atomic::cmpxchg(&value, (ShenandoahSharedValue)UNSET, (ShenandoahSharedValue)SET);
    return old == UNSET; // success
  }
```

就是一个简单的CAS，把一个变量从unset变为set，如果老值是unset那么说明本次修改成功，把old的unset改成了set，那么改了之后定然会有一个地方来读取这个标志位，然后触发GC，那么是哪里再读取呢？

首先我们处理失败分配的代码是在`shenandoahControlThread.cpp`这个类里面

这个类的头文件是这样描述这个类的：

```c++
// Periodic task is useful for doing asynchronous things that do not require (heap) locks,
// or synchronization with other parts of collector. These could run even when ShenandoahConcurrentThread
// is busy driving the GC cycle.
```

`GC cycle`我理解是GC循环或者说GC周期，也就是说这个类是和GC相关的，也就是说可能是这个类在扫描这个标志位，然后我们看`shenandoahControlThread.cpp`的构造方法：

```c++
ShenandoahControlThread::ShenandoahControlThread() :
  //构造函数
  ConcurrentGCThread(),
  //初始化成员变量
  _alloc_failure_waiters_lock(Mutex::safepoint-2, "ShenandoahAllocFailureGC_lock", true),
  _gc_waiters_lock(Mutex::safepoint-2, "ShenandoahRequestedGC_lock", true),
  _periodic_task(this),
  _requested_gc_cause(GCCause::_no_cause_specified),
  _degen_point(ShenandoahGC::_degenerated_outside_cycle),
  _allocs_seen(0) {
    //设置线程名和重置GC ID
  set_name("Shenandoah Control Thread");
  reset_gc_id();
  //创建和启动线程
  create_and_start();
  //注册周期任务
  _periodic_task.enroll();
  //注册周期任务通知任务
  if (ShenandoahPacing) {
    _periodic_pacer_notify_task.enroll();
  }
}
```

看到这里就非常清楚了，这个类吧自己注册为了一个周期任务，可以定时循环的执行某个方法，实现类似于java定时任务的功能，那么是哪个方法会周期执行呢？

这里我们忽略一下细节要不然就扯远了，这里查询到资料是会执行run方法（这个和jdk一样啊），那么`ShenandoahControlThread`也没有run方法啊，于是我们看到`ShenandoahControlThread`这个类继承了`ConcurrentGCThread`，在`ConcurrentGCThread`这个类中有run方法：

```c++
void ConcurrentGCThread::run() {
  // Wait for initialization to complete
  wait_init_completed();

  run_service();

  // Signal thread has terminated
  MonitorLocker ml(Terminator_lock);
  Atomic::release_store(&_has_terminated, true);
  ml.notify_all();
}
```

这里会执行`run_service`方法，执行完之后就会调用`notify_all`方法来唤醒所有`wait`住的线程。所以我们的关注点就是`ShenandoahControlThread`的`run_service()`方法，`run_service()`方法就是负责管理和调度 Shenandoah GC的核心方法。

由于`run_service()`太长了，小三百行，一行一行的看不现实，这里笔者就筛选一些重要的代码展示.

首先在方法里有一个while循环:

```c++
while (!in_graceful_shutdown() && !should_terminate()) {
...
}
```

如果不是被中断了，那么这个循环就会一直循环下去，我们和GC有关的操作都在这个循环里，所以我们的调度线程会一直运行。在while中：

```c++
// Figure out if we have pending requests.
//刚刚设置的，这里读取，就是看有没有分配失败的GC，这个优先级最高
bool alloc_failure_pending = _alloc_failure_gc.is_set();
//是否有手动触发的GC，就是调用system.gc的那个
bool is_gc_requested = _gc_requested.is_set();
```

这里因为我们的入口引导就是分配失败触发GC，所以我们就只看分配失败GC:

```c++
//如果是分配失败GC
if (alloc_failure_pending) {
  // Allocation failure takes precedence: we have to deal with it first thing
  log_info(gc)("Trigger: Handle Allocation Failure");
	//设置GC原因
  cause = GCCause::_allocation_failure;

  // Consume the degen point, and seed it with default value
  //记录降级点
  degen_point = _degen_point;
  _degen_point = ShenandoahGC::_degenerated_outside_cycle;
	//检查当前的垃圾回收类型是否为降级回收，并且应不应该为降级回收
  if (ShenandoahDegeneratedGC && heuristics->should_degenerate_cycle()) {
    heuristics->record_allocation_failure_gc();
    policy->record_alloc_failure_to_degenerated(degen_point);
    //设置GC模式为降级回收
    mode = stw_degenerated;
  } else {
    heuristics->record_allocation_failure_gc();
    policy->record_alloc_failure_to_full();
    //设置GC模式为全回收
    mode = stw_full;
  }

}
```

这里只是确定一个GC模式，要么是降级回收要么是全回收，取决原因之一就是jvm参数中的`ShenandoahDegeneratedGC`:

这个参数在之前笔者简单介绍  Shenandoah GC的时候讲到过：

![image-20250109105127687](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20250109105127687.png)

然后下面看真正执行GC的代码:

```c++
//是否要触发GC 
if (gc_requested) {
     ...

       //根据GC模式的不同执行不同的GC
      switch (mode) {
        case concurrent_normal:
          service_concurrent_normal_cycle(cause);
          break;
        case stw_degenerated:
          service_stw_degenerated_cycle(cause, degen_point);
          break;
        case stw_full:
          service_stw_full_cycle(cause);
          break;
        default:
          ShouldNotReachHere();
      }

      // If this was the requested GC cycle, notify waiters about it
  		//唤醒等待GC的线程
      if (explicit_gc_requested || implicit_gc_requested) {
        notify_gc_waiters();
      }

      // If this was the allocation failure GC cycle, notify waiters about it
  		//唤醒等待GC的线程并且告知他们GC失败
      if (alloc_failure_pending) {
        notify_alloc_failure_waiters();
      }

      // Report current free set state at the end of cycle, whether
      // it is a normal completion, or the abort.
  		//记录空闲集状态并更新堆占用信息：
      {
        ShenandoahHeapLocker locker(heap->lock());
        heap->free_set()->log_status();

        // Notify Universe about new heap usage. This has implications for
        // global soft refs policy, and we better report it every time heap
        // usage goes down.
        Universe::heap()->update_capacity_and_used_at_gc();

        // Signal that we have completed a visit to all live objects.
        Universe::heap()->record_whole_heap_examined_timestamp();
      }

      ...
        
      // Print GC stats for current cycle
  		//打印GC统计信息
      {
        LogTarget(Info, gc, stats) lt;
        if (lt.is_enabled()) {
          ResourceMark rm;
          LogStream ls(lt);
          heap->phase_timings()->print_cycle_on(&ls);
          if (ShenandoahPacing) {
            heap->pacer()->print_cycle_on(&ls);
          }
        }
      }
    ...
    } 
```

这里会根据我们的GC模式的不同来执行的不同的GC

这里简单介绍一下Shenandoah GC的GC模式，如上面代码所示一共有三种模式：

1. 正常回收（normal）：GC的过程通常按照初始标记、并发标记、再标记、并发转移、结束转移的步骤执行
2. 降级回收（degenerated）：在GC过程中，如果遇到内存分配失败，将进入降级回收。降级回收实质上是在STW中进行的并行回收
3. 全回收（full）：如果在降级回收中再次遇到内存分配失败的情况，将进入全回收。和G1的并行FGC非常类似。

这里在代码里这三种模式对应三种入口：

```c++
switch (mode) {
    case concurrent_normal:
      service_concurrent_normal_cycle(cause);
      break;
    case stw_degenerated:
      service_stw_degenerated_cycle(cause, degen_point);
      break;
    case stw_full:
      service_stw_full_cycle(cause);
      break;
    default:
      ShouldNotReachHere();
  }
```

这里我们以分配失败作为切入点，来找到了GC的入口，然后发现了一共有三种GC模式，下面我们就分别介绍这三种GC的源码



## 2、GC具体流程

首先在看源码之前我们要做一点知识储备，这样在看源码的时候才能和已有的知识挂钩，这样的源码看着才有意义，如果一无所知的情况下去看源码就会犯困。

```c++
// ................................................................................................
//
//                                    (immediate garbage shortcut)                Concurrent GC
//                             /-------------------------------------------\
//                             |                                           |
//                             |                                           |
//                             |                                           |
//                             |                                           v
// [START] ----> Conc Mark ----o----> Conc Evac --o--> Conc Update-Refs ---o----> [END]
//                   |                    |                 |              ^
//                   | (af)               | (af)            | (af)         |
// ..................|....................|.................|..............|.......................
//                   |                    |                 |              |
//                   |                    |                 |              |      Degenerated GC
//                   v                    v                 v              |
//               STW Mark ----------> STW Evac ----> STW Update-Refs ----->o
//                   |                    |                 |              ^
//                   | (af)               | (af)            | (af)         |
// ..................|....................|.................|..............|.......................
//                   |                    |                 |              |
//                   |                    v                 |              |      Full GC
//                   \------------------->o<----------------/              |
//                                        |                                |
//                                        v                                |
//                                      Full GC  --------------------------/
//
```

首先我们看一下normal gc的代码注释，这个很好的展现了整个normal GC的流程

如果不存在GC过程中af(allocation failure)的话，那么就是最简单的并发标记、并发疏散和并发更新对象引用，如果在过程中发生了分配失败，那么此时normal就会降级为降级回收，我们可以看到，降级回收和normal回收的区别就是降级回收的全部流程都是在stw中进行的，如果在降级回收中还是有分配失败的，那么就会降级为full gc对就是你想的那个full gc，虽然Shenandoah 不分代但是还是会有full gc这么一说

### 2.1、正常回收

我们直接看`service_concurrent_normal_cycle`方法：

```c++
void ShenandoahControlThread::service_concurrent_normal_cycle(GCCause::Cause cause) {
 //获取当前堆实例
ShenandoahHeap* heap = ShenandoahHeap::heap();
  //检查是否取消或处于降级状态如果是则不进行gc
if (check_cancellation_or_degen(ShenandoahGC::_degenerated_outside_cycle)) return;

GCIdMark gc_id_mark;
//初始化一个GC会话
ShenandoahGCSession session(cause);
//收集和跟踪此并发 GC 过程的统计信息。
TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());
//初始化了一个gc对象	
ShenandoahConcurrentGC gc;
//开始gc
if (gc.collect(cause)) {
  // Cycle is complete
  //gc完成，调用堆的启发式算法（heuristics）记录这次并发 GC 的成功。这样可以为后续的优化提供数据支持
  heap->heuristics()->record_success_concurrent();
  //记录 Shenandoah 策略的成功：在 Shenandoah 垃圾回收政策中记录此次并发 GC 的成功。这个记录可能用于调整未来的 GC 策略或算法
  heap->shenandoah_policy()->record_success_concurrent();
} else {
  //gc失败
  assert(heap->cancelled_gc(), "Must have been cancelled");
  //确定是退出GC还是需要降级
  check_cancellation_or_degen(gc.degen_point());
}
```

这里`ShenandoahConcurrentGC`就代表nomal的并发GC，所以核心就是`ShenandoahConcurrentGC.cpp`的`gc.collect(cause)`:

```c++
bool ShenandoahConcurrentGC::collect(GCCause::Cause cause) {
    //获取堆实例
    ShenandoahHeap *const heap = ShenandoahHeap::heap();
    ShenandoahBreakpointGCScope breakpoint_gc_scope(cause);

    // Reset for upcoming marking
    //对要标记的集合进行重置。
    entry_reset();

    // Start initial mark under STW
    //在stw下进行初始化标记
    vmop_entry_init_mark();

    {
        ShenandoahBreakpointMarkScope breakpoint_mark_scope(cause);
        // Concurrent mark roots
        //并发标记gc roots算法中的根节点
        entry_mark_roots();
        //检查gc是否需要退出，并且设置降级点，这里就是normalGC无法完成任务，需要降级了
        if (check_cancellation_and_abort(ShenandoahDegenPoint::_degenerated_outside_cycle)) return false;

        // Continue concurrent mark
        //继续并发标记
        entry_mark();
        //检查gc是否需要退出
        //检查gc是否需要退出，并且设置降级点，这里就是normalGC无法完成任务，需要降级了
        if (check_cancellation_and_abort(ShenandoahDegenPoint::_degenerated_mark)) return false;
    }

    // Complete marking under STW, and start evacuation
    //在stw下完成标记，并且开始清理
    vmop_entry_final_mark();

    // Concurrent stack processing
    //如果正在进行清理，则调用 entry_thread_roots() 处理可能存在的线程根对象。
    if (heap->is_evacuation_in_progress()) {
        entry_thread_roots();
    }

    // Process weak roots that might still point to regions that would be broken by cleanup
    //处理弱引用那部分，可能会因为清理过程而失效的对象。
    if (heap->is_concurrent_weak_root_in_progress()) {
        entry_weak_refs();
        entry_weak_roots();
    }

    // Final mark might have reclaimed some immediate garbage, kick cleanup to reclaim
    // the space. This would be the last action if there is nothing to evacuate.
    //进行早期清理，以回收未被标记的垃圾空间。
    entry_cleanup_early();
    //锁定堆并记录其当前状态，用于调试或监控目的。
    {
        ShenandoahHeapLocker locker(heap->lock());
        heap->free_set()->log_status();
    }

    // Perform concurrent class unloading
    //如果在并发状态下需要卸载类，则执行卸载操作。
    if (heap->unload_classes() &&
        heap->is_concurrent_weak_root_in_progress()) {
        entry_class_unloading();
    }

    // Processing strong roots
    // This may be skipped if there is nothing to update/evacuate.
    // If so, strong_root_in_progress would be unset.
    //如果在并发强根处理中，则进行相应的操作。
    if (heap->is_concurrent_strong_root_in_progress()) {
        entry_strong_roots();
    }

    // Continue the cycle with evacuation and optional update-refs.
    // This may be skipped if there is nothing to evacuate.
    // If so, evac_in_progress would be unset by collection set preparation code.
  	//是否在清理过程中，如果是则进入清理状态并进行引用更新。
    if (heap->is_evacuation_in_progress()) {
        // Concurrently evacuate
        //开始并发的清理过程，并检查是否有取消请求。
        entry_evacuate();
      	//如果在驱散过程中分配失败就是需要降级，就退出normal GC
        if (check_cancellation_and_abort(ShenandoahDegenPoint::_degenerated_evac)) return false;

        // Perform update-refs phase.
        //初始化更新引用，调用相应的更新函数，并再次检查取消请求。
        vmop_entry_init_updaterefs();
        entry_updaterefs();
      	//如果在更新依赖过程中分配失败就是需要降级，就退出normal GC
        if (check_cancellation_and_abort(ShenandoahDegenPoint::_degenerated_updaterefs)) return false;

        // Concurrent update thread roots
        //并发更新线程根
        entry_update_thread_roots();
      //如果在更新依赖过程中分配失败就是需要降级，就退出normal GC
        if (check_cancellation_and_abort(ShenandoahDegenPoint::_degenerated_updaterefs)) return false;
        //完成更新引用的操作
        vmop_entry_final_updaterefs();

        // Update references freed up collection set, kick the cleanup to reclaim the space.
        //进行最终清理，回收空间。
        entry_cleanup_complete();
    } else {
      //如果没有清理操作，则进行最终根处理。
        vmop_entry_final_roots();
    }
    //返回成功
    return true;
}
```

我们简化一下代码，流程应该是如下：

```c++
//在stw下进行初始化标记
vmop_entry_init_mark();

//并发标记gc roots算法中的根节点
entry_mark_roots();

//继续并发标记
entry_mark();

//在stw下完成标记，并且开始清理
vmop_entry_final_mark();

//进行早期清理，以回收未被标记的垃圾空间。
entry_cleanup_early();

//是否在清理过程中，如果是则进入清理状态并进行引用更新。
if (heap->is_evacuation_in_progress()) {
//开始并发的清理过程，并检查是否有取消请求。
entry_evacuate();

//初始化更新引用，调用相应的更新函数，并再次检查取消请求。
vmop_entry_init_updaterefs();
entry_updaterefs();

//并发更新线程根
entry_update_thread_roots();

//完成更新引用的操作
vmop_entry_final_updaterefs();

// Update references freed up collection set, kick the cleanup to reclaim the space.
//进行最终清理，回收空间。
entry_cleanup_complete();
} else {
//如果没有清理操作，则进行最终根处理。
vmop_entry_final_roots();
}
```

这个流程我们在[Shenandoah GC是什么](https://mp.weixin.qq.com/s/aaQhPkUp86zSdMCGSrzcIQ)中介绍过，这里再复习一下：

1. **Init Mark**（初始标记） 

   启动并发标记。它为并发标记准备堆和应用程序线程，然后扫描`GCRoots`。这是GC中的第一个STW，最主要的消费者是`GCRoots`扫描。因此，其持续时间取决于`GCRoots`大小。

2. **Concurrent Ma∂rking** （并发标记）

   遍历堆并跟踪可访问对象。此阶段与应用程序同时运行，其持续时间取决于堆中活动对象的数量和对象图的结构。由于应用程序在此阶段可以自由分配新数据，因此并发标记期间堆占用率会上升。

3. **Final Mark**（最终标记）

   通过清空所有待处理的标记/更新队列并重新扫描`GCRoots`来完成并发标记。它还通过确定要清空的区域（集合集）、预先清空一些根来初始化清空，并为下一阶段准备运行时。这项工作的一部分可以在**并发预清理**阶段并发完成。这是周期中的第二次STW，这里最主要的时间消费者是清空队列和扫描`GCRoots`。 

4. **Concurrent Cleanup**（并发清理）

   会回收即时垃圾区域 - 即并发标记后检测到的不存在活动对象的区域。

5. **Concurrent Evacuation**（并发疏散）

   将对象从收集集复制到其他区域。这是与其他 OpenJDK GC 的主要区别。此阶段再次与应用程序一起运行，因此应用程序可以自由分配。其持续时间取决于循环所选收集集的大小。

6. **Init Update Refs**（初始化更新引用）

   它几乎不做任何事情，只是确保所有 GC 和应用程序线程都已完成撤离，然后为下一阶段准备 GC。这是周期中的第三次STW，也是所有STW中最短的一次。

7. **Concurrent Update References**（并发更新引用）

   遍历堆，并更新对并发撤离期间移动的对象的引用。 这是与其他 OpenJDK GC 的主要区别。 它的持续时间取决于堆中的对象数量，而不是对象图结构，因为它线性扫描堆。此阶段与应用程序同时运行。

8. **Final Update Refs** （最终更新引用）

   通过重新更新现有`GCRoots`来完成更新引用阶段。它还会回收集合中的区域，因为现在堆不再有对（过时的）对象的引用。这是循环中的最后一次暂停，其持续时间取决于`GCRoots`的大小。

9. **Concurrent Cleanup** （并发清理回收）
   与用户线程并发执行，会待回收区域中的存活对象复制到其他未使用的`Region`区中去，然后会将原本的`Region`区全部清理并回收。这里使用的GC算法是并发标记-压缩（Concurrent Mark-Compact）算法

![image-20250109175250377](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20250109175250377.png)

这里我们挨个挨个的介绍也不现实，1是篇幅问题，2是笔者精力问题，3是大概能阅读到这里的读者都少至少了，如果全部写完那就真没人读了。所以笔者这里只挑选比较重要的方法讲解之，如果对其他方法有兴趣的读者可以自行下载jvm源码阅读。

这里第一个我们来看下

所以我们只看Shenandoah GC是如何来先对堆对象的根节点进行扫描的:

```c++
//并发标记gc roots算法中的根节点
entry_mark_roots();
```

```c++
void ShenandoahConcurrentGC::entry_mark_roots() {
  //获取堆对象
    ShenandoahHeap *const heap = ShenandoahHeap::heap();
  //设置一下GC日志信息什么的
    TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());
    const char *msg = "Concurrent marking roots";
    ShenandoahConcurrentPhase gc_phase(msg, ShenandoahPhaseTimings::conc_mark_roots);
    EventMark em("%s", msg);

    ShenandoahWorkerScope scope(heap->workers(),
                                ShenandoahWorkerPolicy::calc_workers_for_conc_marking(),
                                "concurrent marking roots");
		//尝试注入分配失败，这通常用于模拟内存分配失败的情况，可能是用于测试目的。这一行为可以帮助识别垃圾收集器在低内存情况下的表现。
    heap->try_inject_alloc_failure();
    op_mark_roots();
}
...
  
  void ShenandoahConcurrentGC::op_mark_roots() {
    _mark.mark_concurrent_roots();
}
```

所以代码会流转到`shenandoahConcurrentMark.cpp`的 `mark_concurrent_roots`方法:

```c++
void ShenandoahConcurrentMark::mark_concurrent_roots() {
  ShenandoahHeap* const heap = ShenandoahHeap::heap();
  assert(!heap->has_forwarded_objects(), "Not expected");

  TASKQUEUE_STATS_ONLY(task_queues()->reset_taskqueue_stats());
	//获取GC工作线程
  WorkerThreads* workers = heap->workers();
  //获取堆中的引用处理器实例，这个处理器负责处理对象之间的引用关系，对于标记过程来说很重要。
  ShenandoahReferenceProcessor* rp = heap->ref_processor();
  //为任务队列预留足够的空间，以容纳当前活跃工作线程的数量。这样可以提高后续任务分配的效率，避免动态扩容带来的性能损失。
  task_queues()->reserve(workers->active_workers());
  //创建并发标记根对象的任务。构造函数接受任务队列、引用处理器、标记开始的时间阶段，以及活跃工作线程的数量等参数。
  ShenandoahMarkConcurrentRootsTask task(task_queues(), rp, ShenandoahPhaseTimings::conc_mark_roots, workers->active_workers());
	//让工作线程运行任务
  workers->run_task(&task);
}
```

这个代码有那么点像java多线程提交任务，那么这里就是唤醒我们的GC工作线程来并发的标记根节点，然后核心是一个task:`ShenandoahMarkConcurrentRootsTask`，我们来看下这个task类的构造:

```c++
class ShenandoahMarkConcurrentRootsTask : public WorkerTask {
private:
  SuspendibleThreadSetJoiner          _sts_joiner;
  ShenandoahConcurrentRootScanner     _root_scanner;
  ShenandoahObjToScanQueueSet* const  _queue_set;
  ShenandoahReferenceProcessor* const _rp;

public:
  ShenandoahMarkConcurrentRootsTask(ShenandoahObjToScanQueueSet* qs,
                                    ShenandoahReferenceProcessor* rp,
                                    ShenandoahPhaseTimings::Phase phase,
                                    uint nworkers);
  void work(uint worker_id);
};
```

那么实际上应该是执行`work`方法，因为只有这一个方法。。。

```c++
void ShenandoahMarkConcurrentRootsTask::work(uint worker_id) {
    ShenandoahConcurrentWorkerSession worker_session(worker_id);
    ShenandoahObjToScanQueue *q = _queue_set->queue(worker_id);
    //创建标记引用闭包：实例化 ShenandoahMarkRefsClosure 对象 cl，它是一个闭包，用于在标记过程中处理对象的引用
    //这里有两个参数：q指向当前工作线程的对象扫描队列。,_rp就是刚刚创建的ShenandoahReferenceProcessor对象
    ShenandoahMarkRefsClosure cl(q, _rp);
    //调用根扫描器对象_root_scanner的roots_do方法，传入闭包 cl 和工作线程的 ID (worker_id)。该方法会使用 cl（闭包）来执行对根对象的扫描和标记。通过这个操作，关闭的对象引用将被处理并标记为活动，从而在垃圾收集过程中保留其存活状态。
    _root_scanner.roots_do(&cl, worker_id);
}
```

这里核心去扫描根节点的就是`_root_scanner`的`roots_do`方法：

```c++
void ShenandoahConcurrentRootScanner::roots_do(OopClosure *oops, uint worker_id) {
    ShenandoahHeap *const heap = ShenandoahHeap::heap();
    //初始化 CLD 到 Oop 闭包：创建 CLDToOopClosure 对象 clds_cl，用于将类加载器数据（Class Loader Data, CLD）中的对象映射到给定的对象闭包 oops，并声称这些数据强引用。
    CLDToOopClosure clds_cl(oops, ClassLoaderData::_claim_strong);

    // Process light-weight/limited parallel roots then
    //调用 _vm_roots 的 oops_do 方法，用于处理与JVM根对象相关的所有对象。这一步是并行处理的一部分，通常针对的是相对简单的引用。
    _vm_roots.oops_do(oops, worker_id);

    //如果允许卸载类
    if (heap->unload_classes()) {
        //调用 always_strong_cld_do 方法，处理 CLD 对象，并始终认为它们是强引用
        _cld_roots.always_strong_cld_do(&clds_cl, worker_id);
    } else {
        //进行正常的 CLD 对象处理。
        _cld_roots.cld_do(&clds_cl, worker_id);

        {
            ShenandoahWorkerTimingsTracker timer(_phase, ShenandoahPhaseTimings::CodeCacheRoots, worker_id);
            //用于处理代码缓存中的对象，传递 oops 和一个布尔值。parallel_blobs_do 方法处理这些代码缓存根对象。
            CodeBlobToOopClosure blobs(oops, !CodeBlobToOopClosure::FixRelocations);
            //代码缓存中的对象也被处理
            _codecache_snapshot->parallel_blobs_do(&blobs);
        }
    }

    // Process heavy-weight/fully parallel roots the last
    //该闭包用于处理 Java 线程对象。
    ShenandoahConcurrentMarkThreadClosure thr_cl(oops);
    //对 Java 线程中的对象执行相应的处理。
    _java_threads.threads_do(&thr_cl, worker_id);
}
```

这里代码比较复杂，我们先把源码分类，上面的代码可以粗略的认为是处理不同地方的根对象，那么这些地方是：

1、JVM相关

2、类加载器相关

3、代码缓存相关

4、Java线程相关

那么我们已知的根对象：

  - 虚拟机栈中引用的对象，如：线程中被调用的方法堆栈中使用的参数、局部变量、临时变量。
  - 方法区中类静态属性引用的对象。
  - 方法区中常量引用的对象，比如：字符串常量池里的引用。
  - 本地方法栈中JNI引用的对象。
  - Java虚拟机内部的引用，比如：基本数据类型对应的Class对象、常驻异常对象、系统类加载器，如：String、NullPointExcepition等。
  - 所有被同步锁持有的对象（synchronized关键字）。
  - 反映Java虚拟机内部情况的JMXBean、JVMTI中注册的回调、本地代码缓存等。

这里源码就可以和我们的知识点对应起来了，比如扫描java线程相关的就是为了扫描线程中被调用的方法堆栈中使用的参数、局部变量、临时变量，扫描JVM相关就是为了扫描方法区中静态属性和字符串常量池等引用的对象

这里每个地方的扫描就不细讲了，等下次读者想写根可达算法的时候再来细看吧。

接着我们看看标记：

```c++
//在stw下完成标记，并且开始清理
vmop_entry_final_mark();
```

```c++
void ShenandoahConcurrentGC::vmop_entry_final_mark() {
    ShenandoahHeap *const heap = ShenandoahHeap::heap();
    TraceCollectorStats tcs(heap->monitoring_support()->stw_collection_counters());
    ShenandoahTimingsTracker timing(ShenandoahPhaseTimings::final_mark_gross);

    heap->try_inject_alloc_failure();
    VM_ShenandoahFinalMarkStartEvac op(this);
    VMThread::execute(&op); // jump to entry_final_mark under safepoint
}
```

这里执行op操作的部分很眼熟，G1的GC就是这种方式触发的，我们直接看op内部的代码:

```c++
void VM_ShenandoahFinalMarkStartEvac::doit() {
  ShenandoahGCPauseMark mark(_gc_id, "Final Mark", SvcGCMarker::CONCURRENT);
  _gc->entry_final_mark();
}
```

这里还是回到了`shenandoahConcurrentGC.cpp`中：

```c++
void ShenandoahConcurrentGC::entry_final_mark() {
    const char *msg = final_mark_event_message();
    ShenandoahPausePhase gc_phase(msg, ShenandoahPhaseTimings::final_mark);
    EventMark em("%s", msg);

    ShenandoahWorkerScope scope(ShenandoahHeap::heap()->workers(),
                                ShenandoahWorkerPolicy::calc_workers_for_final_marking(),
                                "final marking");

    op_final_mark();
}
```

打印一些GC日志然后直接调用了`op_final_mark`方法：

```c++
void ShenandoahConcurrentGC::op_final_mark() {
    //获得堆实例
    ShenandoahHeap *const heap = ShenandoahHeap::heap();
    //确保在安全点上（安全点大家很熟悉了吧）
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Should be at safepoint");
    assert(!heap->has_forwarded_objects(), "No forwarded objects on this path");
    //当前路径上没有转发对象，确保在进行最终标记时，状态是正确的
    if (ShenandoahVerify) {
        heap->verifier()->verify_roots_no_forwarded();
    }
    //没有指令退出GC
    if (!heap->cancelled_gc()) {
        //完成标记阶段的操作
        _mark.finish_mark();
        assert(!heap->cancelled_gc(), "STW mark cannot OOM");

        // Notify JVMTI that the tagmap table will need cleaning.
        //通知 JVMTI（Java 虚拟机工具接口）需要清理标签映射表
        JvmtiTagMap::set_needs_cleaning();
        //准备堆区域和收集集，标记为并发处理
        heap->prepare_regions_and_collection_set(true /*concurrent*/);

        // Has to be done after cset selection
        //准备并发根对象，以便在后续的垃圾回收中使用
        heap->prepare_concurrent_roots();
        //收集集是否为空
        if (!heap->collection_set()->is_empty()) {
            //不为空则开始准备驱散
            //驱散之前验证
            if (ShenandoahVerify) {
                heap->verifier()->verify_before_evacuation();
            }
            //将堆的撤离状态设置为进行中
            heap->set_evacuation_in_progress(true);
            // From here on, we need to update references.
            //标记存在转发对象
            heap->set_has_forwarded_objects(true);

            // Verify before arming for concurrent processing.
            // Otherwise, verification can trigger stack processing.
            if (ShenandoahVerify) {
                heap->verifier()->verify_during_evacuation();
            }

            // Arm nmethods/stack for concurrent processing
            ShenandoahCodeRoots::arm_nmethods();
            ShenandoahStackWatermark::change_epoch_id();

            if (ShenandoahPacing) {
                heap->pacer()->setup_for_evac();
            }
        } else {
            //如果为空说明没有需要驱散的
            //调用验证器在并发标记后进行验证。
            if (ShenandoahVerify) {
                heap->verifier()->verify_after_concmark();
            }
            //全局验证
            if (VerifyAfterGC) {
                Universe::verify();
            }
        }
    }
}
```

我们可以看到在最终标记阶段，会执行确保在安全点上进行标记，处理转发对象，准备并发撤离，并在必要时进行验证等操作，这里执行最终标记是执行:

```c++
 _mark.finish_mark();
```

这个方法:

```c++
void ShenandoahConcurrentMark::finish_mark() {
    //检查安全点
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");
    assert(Thread::current()->is_VM_thread(), "Must by VM Thread");
    //执行标记阶段的具体工作
    finish_mark_work();
    assert(task_queues()->is_empty(), "Should be empty");
    TASKQUEUE_STATS_ONLY(task_queues()->print_taskqueue_stats());
    TASKQUEUE_STATS_ONLY(task_queues()->reset_taskqueue_stats());

    ShenandoahHeap *const heap = ShenandoahHeap::heap();
    //将堆的并发标记状态设置为 false，表示标记过程已完成。
    heap->set_concurrent_mark_in_progress(false);
    //调用堆的方法，标记上下文已完成，可能涉及清理或更新状态
    heap->mark_complete_marking_context();
    //结束标记
    end_mark();
}
```

实际上执行标记的方法是：`finish_mark_work`：

```c++
ShenandoahFinalMarkingTask task(this, &terminator, ShenandoahStringDedup::is_enabled());
heap->workers()->run_task(&task);
```

这个是用工作线程异步来做的，包装为了`ShenandoahFinalMarkingTask`，我们直接看这个task的work方法就行了：

```c++
void work(uint worker_id) {
    //获得堆实例
    ShenandoahHeap *heap = ShenandoahHeap::heap();
    //并行worker会话
    ShenandoahParallelWorkerSession worker_session(worker_id);
    //获取与堆相关的引用处理器。
    ShenandoahReferenceProcessor *rp = heap->ref_processor();
    //用于字符串去重
    StringDedup::Requests requests;

    // First drain remaining SATB buffers.
    //处理 SATB 缓冲区
    {
        //获取与当前工作线程 ID 相关的对象扫描队列。
        ShenandoahObjToScanQueue *q = _cm->get_queue(worker_id);
        //创建一个 SATB（Snapshot At The Beginning）缓冲区闭包，用于处理对象。
        ShenandoahSATBBufferClosure cl(q);
        //获取 SATB 标记队列集
        SATBMarkQueueSet &satb_mq_set = ShenandoahBarrierSet::satb_mark_queue_set();
        //对已完成的缓冲区应用闭包，处理其中的对象
        while (satb_mq_set.apply_closure_to_completed_buffer(&cl)) {}
        assert(!heap->has_forwarded_objects(), "Not expected");
        //创建一个标记引用的闭包
        ShenandoahMarkRefsClosure mark_cl(q, rp);
        //创建一个处理 SATB 和重新标记线程的闭包。
        ShenandoahSATBAndRemarkThreadsClosure tc(satb_mq_set,
                                                 ShenandoahIUBarrier ? &mark_cl : nullptr);
        //使用之前创建的闭包并行地执行线程操作
        Threads::possibly_parallel_threads_do(true /* is_par */, &tc);
    }
    //执行标记循环，传入工作线程 ID、终止符、引用处理器、可取消标志、去重标志和请求对象
    _cm->mark_loop(worker_id, _terminator, rp,
                   false /*not cancellable*/,
                   _dedup_string ? ENQUEUE_DEDUP : NO_DEDUP,
                   &requests);
    assert(_cm->task_queues()->is_empty(), "Should be empty");
}
```

这里就是用闭包在satb队列中来执行标记操作，这里简单介绍一下SATB：

>SATB（Snapshot-at-the-Beginning）是一种垃圾回收算法的实现方式，具体来说，它是一种标记阶段的策略。
>
>SATB 的工作原理
>快照：在标记阶段开始时，SATB 会创建一个快照，记录所有活动对象的引用。这意味着在标记过程中，任何新分配的对象或引用都不会影响当前的标记状态。
>
>标记阶段：在标记阶段，GC 会遍历所有的根对象（如栈、全局变量等），并根据快照中的引用来标记存活的对象。
>
>处理变化：由于在标记阶段可能会有新的对象被分配或引用被改变，SATB 机制会在标记过程中记录这些变化，以确保在标记完成后，所有存活的对象都能被正确识别。
>
>清理阶段：在标记完成后，GC 会清理未被标记的对象，释放它们占用的内存。
>
>优点
>低延迟：由于 SATB 允许在标记阶段进行并发操作，因此可以减少停顿时间，适合对延迟敏感的应用。
>准确性：通过快照机制，SATB 能够准确地识别存活对象，避免了在标记过程中遗漏新分配的对象。
>总结
>SATB 是 Shenandoah GC 中的一种重要机制，通过在标记阶段创建快照，确保了垃圾回收的准确性和低延迟特性。这使得 Shenandoah GC 能够在高并发环境中有效地管理内存。

简单来说就是给堆创建快照，然后标记就是利用这个快照来进行标记存活对象，这就是为什么有些标记可以不用stw的原因，因为依赖关系都在一开始记录好了，如果是在satb中标记的时候又变化的话，satb也会记录这些变化然后再来标记，这也为什么代码里会有satb的再次标记，然后再深入的代码笔者就不算讲了，再讲下去就可以出书了，有兴趣的读者可以顺着笔者的思路自行探究下去。



然后我们再看看清理：

```c++
// Final mark might have reclaimed some immediate garbage, kick cleanup to reclaim
// the space. This would be the last action if there is nothing to evacuate.
//进行早期清理，以回收未被标记的垃圾空间。
entry_cleanup_early();
```

这个地方就是清理没有在刚刚标记流程中被标记的部分，那为什么没有被标记还会被清理呢？

我们接着看代码：

```c++
void ShenandoahConcurrentGC::entry_cleanup_early() {
    //获取堆对象
    ShenandoahHeap *const heap = ShenandoahHeap::heap();
    //设置一下GC日志信息什么的
    TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());
    static const char *msg = "Concurrent cleanup";
    ShenandoahConcurrentPhase gc_phase(msg, ShenandoahPhaseTimings::conc_cleanup_early, true /* log_heap_usage */);
    EventMark em("%s", msg);

    // This phase does not use workers, no need for setup
    //尝试注入分配失败，这通常用于模拟内存分配失败的情况，可能是用于测试目的。这一行为可以帮助识别垃圾收集器在低内存情况下的表现。
    heap->try_inject_alloc_failure();
    op_cleanup_early();
}
```

结构和`entry_mark_roots`几乎一摸一样，最后是调用`op_cleanup_early`：

```c++
void ShenandoahConcurrentGC::op_cleanup_early() {
    ShenandoahHeap::heap()->free_set()->recycle_trash();
}
```

直接调用的`free set`的`recycle_trash`，在上文介绍对象分配的时候中我们简单介绍了一下`free set`，这里复习一下：

![image-20250114141257870](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20250114141257870.png)

这里为什么GC会有一部分在`free set`中进行为什么还要清理没有被标记的对象，截图里就已经有了答案，所以早期清理也就是说第一次清理是先去空闲集里把一些可以回收的部分先回收了，最大限度的释放空间：

```c++
void ShenandoahFreeSet::recycle_trash() {
    // lock is not reentrable, check we don't have it
    //确保当前线程没有持有堆的锁。这是为了避免死锁，因为该锁不可重入
    shenandoah_assert_not_heaplocked();
    //遍历堆
    for (size_t i = 0; i < _heap->num_regions(); i++) {
        //获取当前region堆指针
        ShenandoahHeapRegion *r = _heap->get_region(i);
        //是否是垃圾
        if (r->is_trash()) {
            //上锁，独占，防止回收时线程安全问题
            ShenandoahHeapLocker locker(_heap->lock());
            //尝试回收当前的垃圾区域
            try_recycle_trashed(r);
        }
        //自旋等待的操作，允许其他分配器线程获取锁。这有助于提高并发性能，避免在循环中占用 CPU 资源
        SpinPause(); // allow allocators to take the lock
    }
}
...
  void ShenandoahFreeSet::try_recycle_trashed(ShenandoahHeapRegion *r) {
    if (r->is_trash()) {
        //将当前区域的已用内存量从堆的总使用量中减去。r->used() 返回该区域当前使用的内存量。
        _heap->decrease_used(r->used());
        //标记为可用，允许后续的内存分配使用
        r->recycle();
    }
}
```

可以看到这里清理的代码很简单，就是把内存量减出来然后标记为可用，这样下次分配的时候这个region就是可以用的。

我们筛选出来最具代表性的：找根节点、最终标记和简单清理来看了normal GC的代码，笔者在这里抛砖引玉，有兴趣的读者可以自行阅读其他步骤的源码。



### 2.2、 降级回收

当我们在正常回收里发生了分配失败或者说在堆上分配对象失败的时候就会触发降级回收流程。

我们先看入口代码：

```c++
void ShenandoahControlThread::service_stw_degenerated_cycle(GCCause::Cause cause, ShenandoahGC::ShenandoahDegenPoint point) {
  assert (point != ShenandoahGC::_degenerated_unset, "Degenerated point should be set");

  GCIdMark gc_id_mark;
  ShenandoahGCSession session(cause);

  ShenandoahDegenGC gc(point);
  gc.collect(cause);

  ShenandoahHeap* const heap = ShenandoahHeap::heap();
  heap->heuristics()->record_success_degenerated();
  heap->shenandoah_policy()->record_success_degenerated();
}
```

这里和普通GC唯一的区别就是gc的类型是`ShenandoahDegenGC`，每个GC类型都会对应一个GC实现，所以我们去`ShenandoahDegenGC.cpp`中看`collect`方法：

```c++
bool ShenandoahDegenGC::collect(GCCause::Cause cause) {
  vmop_degenerated();
  return true;
}
...
void ShenandoahDegenGC::vmop_degenerated() {
  TraceCollectorStats tcs(ShenandoahHeap::heap()->monitoring_support()->full_stw_collection_counters());
  ShenandoahTimingsTracker timing(ShenandoahPhaseTimings::degen_gc_gross);
  VM_ShenandoahDegeneratedGC degenerated_gc(this);
  VMThread::execute(&degenerated_gc);
}
```

这里还是眼熟的用op操作，所以我们还是直接去`shenandoahVMOperations.hpp`中看看这个op的do_it方法：

```c++
void VM_ShenandoahDegeneratedGC::doit() {
  //记录GC信息
  ShenandoahGCPauseMark mark(_gc_id, "Degenerated GC", SvcGCMarker::CONCURRENT);
  _gc->entry_degenerated();
}
...
  void ShenandoahDegenGC::entry_degenerated() {
  //记录降级点
  const char* msg = degen_event_message(_degen_point);
  ShenandoahPausePhase gc_phase(msg, ShenandoahPhaseTimings::degen_gc, true /* log_heap_usage */);
  EventMark em("%s", msg);
  ShenandoahHeap* const heap = ShenandoahHeap::heap();

  ShenandoahWorkerScope scope(heap->workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_stw_degenerated(),
                              "stw degenerated gc");

  heap->set_degenerated_gc_in_progress(true);
  //执行具体的降级GC
  op_degenerated();
  heap->set_degenerated_gc_in_progress(false);
}
```

这里`op_degenerated`才是具体执行降级GC的地方:

```c++
void ShenandoahDegenGC::op_degenerated() {
    //获得堆
    ShenandoahHeap *const heap = ShenandoahHeap::heap();
    // Degenerated GC is STW, but it can also fail. Current mechanics communicates
    // GC failure via cancelled_concgc() flag. So, if we detect the failure after
    // some phase, we have to upgrade the Degenerate GC to Full GC.
    //清除退出GC标志
    heap->clear_cancelled_gc();

    ShenandoahMetricsSnapshot metrics;
    //进行指标快照，以便后续对比评估回收的性能
    metrics.snap_before();
    //根据降级点进行不同的操作
    switch (_degen_point) {
        // The cases below form the Duff's-like device: it describes the actual GC cycle,
        // but enters it at different points, depending on which concurrent phase had
        // degenerated.
        //在GC循环之外降级，也就是说normal GC还未启动就开始降级GC，这种一般是分配失败引起的，也就是堆出现了问题（如大量的大对象碎片或可用空间很少）
        case _degenerated_outside_cycle:
            // We have degenerated from outside the cycle, which means something is bad with
            // the heap, most probably heavy humongous fragmentation, or we are very low on free
            // space. It makes little sense to wait for Full GC to reclaim as much as it can, when
            // we can do the most aggressive degen cycle, which includes processing references and
            // class unloading, unless those features are explicitly disabled.
            //
            // Degenerated from concurrent root mark, reset the flag for STW mark
            //如果并发标记正在进行
            if (heap->is_concurrent_mark_in_progress()) {
                //则取消并发标记并将其状态设置为不在进行中
                ShenandoahConcurrentMark::cancel();
                heap->set_concurrent_mark_in_progress(false);
            }

            // Note that we can only do this for "outside-cycle" degens, otherwise we would risk
            // changing the cycle parameters mid-cycle during concurrent -> degenerated handover.
            //根据启发式算法设置是否卸载类的状态
            heap->set_unload_classes(heap->heuristics()->can_unload_classes());
            //重置相关状态
            op_reset();

            // STW mark
            //进行标记操作,全部stw
            op_mark();
            //如果是从标记阶段降级
        case _degenerated_mark:
            // No fallthrough. Continue mark, handed over from concurrent mark if
            // concurrent mark has yet completed
            //并发标记仍在进行中
            if (_degen_point == ShenandoahDegenPoint::_degenerated_mark &&
                heap->is_concurrent_mark_in_progress()) {
                //完成标记
                op_finish_mark();
            }
            assert(!heap->cancelled_gc(), "STW mark can not OOM");

            /* Degen select Collection Set. etc. */
            //准备疏散操作
            op_prepare_evacuation();
            //早期清理
            op_cleanup_early();
            //如果是如果在驱散过程中降级
        case _degenerated_evac:
            // If heuristics thinks we should do the cycle, this flag would be set,
            // and we can do evacuation. Otherwise, it would be the shortcut cycle.
            //疏散操作正在进行中
            if (heap->is_evacuation_in_progress()) {

                {
                    //同步固定区域的状态
                    heap->sync_pinned_region_status();
                    //清除集合的当前索引
                    heap->collection_set()->clear_current_index();

                    ShenandoahHeapRegion *r;
                    //遍历所有region
                    while ((r = heap->collection_set()->next()) != nullptr) {
                        //region被固定了
                        if (r->is_pinned()) {
                            //退出降级GC，并且执行full GC
                            heap->cancel_gc(GCCause::_shenandoah_upgrade_to_full_gc);
                            //降级失败
                            op_degenerated_fail();
                            return;
                        }
                    }

                    heap->collection_set()->clear_current_index();
                }
                //进行疏散操作
                op_evacuate();
                //如果存在退出GC信号
                if (heap->cancelled_gc()) {
                    //退化失败
                    op_degenerated_fail();
                    return;
                }
            }

            // If heuristics thinks we should do the cycle, this flag would be set,
            // and we need to do update-refs. Otherwise, it would be the shortcut cycle.
            //如果存在转发对象
            if (heap->has_forwarded_objects()) {
                //初始化引用更新操作
                op_init_updaterefs();
                assert(!heap->cancelled_gc(), "STW reference update can not OOM");
            }
            //更新依赖过程中降级
        case _degenerated_updaterefs:
            //如果存在转发对象
            if (heap->has_forwarded_objects()) {
                //处理转发对象的引用更新操作和根更新操作
                op_updaterefs();
                op_update_roots();
                assert(!heap->cancelled_gc(), "STW reference update can not OOM");
            }
            //如果允许类卸载
            if (ClassUnloading) {
                // Disarm nmethods that armed in concurrent cycle.
                // In above case, update roots should disarm them
                //处理N method
                ShenandoahCodeRoots::disarm_nmethods();
            }
            //全部清理
            op_cleanup_complete();
            break;
            //不应该到达默认情况，如果到达，说明代码逻辑有错误。
        default:
            ShouldNotReachHere();
    }
    //校验相关
    if (ShenandoahVerify) {
        heap->verifier()->verify_after_degenerated();
    }

    if (VerifyAfterGC) {
        Universe::verify();
    }
    //最后再次进行指标快照。
    metrics.snap_after();

    // Check for futility and fail. There is no reason to do several back-to-back Degenerated cycles,
    // because that probably means the heap is overloaded and/or fragmented.
    //检查回收操作是否取得良好进展
    if (!metrics.is_good_progress()) {
        //如果进展不太好，那么就降级为full gc
        heap->notify_gc_no_progress();
        heap->cancel_gc(GCCause::_shenandoah_upgrade_to_full_gc);
        op_degenerated_futile();
    } else {
        //通知GC进展，表示GC完成
        heap->notify_gc_progress();
    }
}
```



这里有一个降级点的概念，在前文中我们见到过多次：

在GC扫描任务的时候如果是分配失败触发的GC的时候设置：

```c++
//记录降级点
degen_point = _degen_point;
_degen_point = ShenandoahGC::_degenerated_outside_cycle;
```

在进行normal GC的时候随时都有可能设置降级点：

```c++
  	//如果在驱散过程中分配失败就是需要降级，就退出normal GC
    if (check_cancellation_and_abort(ShenandoahDegenPoint::_degenerated_evac)) return false;
...
    entry_updaterefs();
  	//如果在更新依赖过程中分配失败就是需要降级，就退出normal GC
    if (check_cancellation_and_abort(ShenandoahDegenPoint::_degenerated_updaterefs)) return false;
    ...
    entry_update_thread_roots();
  //如果在更新依赖过程中分配失败就是需要降级，就退出normal GC
    if (check_cancellation_and_abort(ShenandoahDegenPoint::_degenerated_updaterefs)) return false;
```

总之降级GC的本质就是根据不同的降级原因来做不同的处理，并且相比于normal GC他大部分操作都是stw的



### 2.3、Full回收

full gc顾名思义就是全回收，这个词并不陌生，在其他GC里也有full GC，不过那个是针对所有堆的所有代，但是SGC（实在不想打那么长的单词了，这里开始简称SGC）并没有划代，所以我们看看SGC的full GC是怎么个事儿：

```c++
void ShenandoahControlThread::service_stw_full_cycle(GCCause::Cause cause) {
  GCIdMark gc_id_mark;
  ShenandoahGCSession session(cause);

  ShenandoahFullGC gc;
  gc.collect(cause);

  ShenandoahHeap* const heap = ShenandoahHeap::heap();
  heap->heuristics()->record_success_full();
  heap->shenandoah_policy()->record_success_full();
}
```

这没什么好说的，和上面两个GC是一样的，这里用到的GC类就是`ShenandoahFullGC.cpp`,由于这里入口代码的逻辑和降级GC是一样的，走op那个流程，笔者就不过多赘述了，这里把代码放出来，让读者能有个整体概念：

```c++
bool ShenandoahFullGC::collect(GCCause::Cause cause) {
  vmop_entry_full(cause);
  // Always success
  return true;
}
...
void ShenandoahFullGC::vmop_entry_full(GCCause::Cause cause) {
  ShenandoahHeap* const heap = ShenandoahHeap::heap();
  TraceCollectorStats tcs(heap->monitoring_support()->full_stw_collection_counters());
  ShenandoahTimingsTracker timing(ShenandoahPhaseTimings::full_gc_gross);

  heap->try_inject_alloc_failure();
  VM_ShenandoahFullGC op(cause, this);
  VMThread::execute(&op);
}
...
  void VM_ShenandoahFullGC::doit() {
  ShenandoahGCPauseMark mark(_gc_id, "Full GC", SvcGCMarker::FULL);
  _full_gc->entry_full(_gc_cause);
}
...
  void ShenandoahFullGC::entry_full(GCCause::Cause cause) {
  static const char* msg = "Pause Full";
  ShenandoahPausePhase gc_phase(msg, ShenandoahPhaseTimings::full_gc, true /* log_heap_usage */);
  EventMark em("%s", msg);

  ShenandoahWorkerScope scope(ShenandoahHeap::heap()->workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_fullgc(),
                              "full gc");

  op_full(cause);
}
```

最后`op_full`就是最后执行full gc的地方:

```c++
void ShenandoahFullGC::op_full(GCCause::Cause cause) {
  ShenandoahMetricsSnapshot metrics;
  //进行指标快照，以便后续对比评估回收的性能
  metrics.snap_before();

  // Perform full GC
  //进行full gc
  do_it(cause);

  //最后进行指标快照
  metrics.snap_after();

  //检查回收操作是否取得良好进展
  if (metrics.is_good_progress()) {
    //如果好就通知
    ShenandoahHeap::heap()->notify_gc_progress();
  } else {
    // Nothing to do. Tell the allocation path that we have failed to make
    // progress, and it can finally fail.
    //因为已经是最后的full gc了如果不好的话也不能做什么，通知然后最后可能会认为本次GC失败
    ShenandoahHeap::heap()->notify_gc_no_progress();
  }
}
```

这里`do_it`就是最终的执行full gc的方法了：

```c++
void ShenandoahFullGC::do_it(GCCause::Cause gc_cause) {
    //获取堆对象
    ShenandoahHeap *heap = ShenandoahHeap::heap();

    if (ShenandoahVerify) {
        heap->verifier()->verify_before_fullgc();
    }

    if (VerifyBeforeGC) {
        Universe::verify();
    }

    // Degenerated GC may carry concurrent root flags when upgrading to
    // full GC. We need to reset it before mutators resume.
    //如果是降级来的话，可能会有并发根标记的记号，这里都设置为false
    heap->set_concurrent_strong_root_in_progress(false);
    heap->set_concurrent_weak_root_in_progress(false);

    //设置正在full gc中
    heap->set_full_gc_in_progress(true);

    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "must be at a safepoint");
    assert(Thread::current()->is_VM_thread(), "Do full GC only while world is stopped");

    {
        //记录 Full GC 堆转储准备阶段的时间
        ShenandoahGCPhase phase(ShenandoahPhaseTimings::full_gc_heapdump_pre);
        //进行准备工作
        heap->pre_full_gc_dump(_gc_timer);
    }

    {
        ShenandoahGCPhase prepare_phase(ShenandoahPhaseTimings::full_gc_prepare);
        // Full GC is supposed to recover from any GC state:

        // a0. Remember if we have forwarded objects
        //是否有转发对象
        bool has_forwarded_objects = heap->has_forwarded_objects();

        // a1. Cancel evacuation, if in progress
        //正在驱散
        if (heap->is_evacuation_in_progress()) {
            //取消驱散
            heap->set_evacuation_in_progress(false);
        }
        assert(!heap->is_evacuation_in_progress(), "sanity");

        // a2. Cancel update-refs, if in progress
        //正在更新饮引用
        if (heap->is_update_refs_in_progress()) {
            //一起停止了
            heap->set_update_refs_in_progress(false);
        }
        assert(!heap->is_update_refs_in_progress(), "sanity");

        // b. Cancel concurrent mark, if in progress
        //正在并发标记
        if (heap->is_concurrent_mark_in_progress()) {
            //并发标记只有normal GC才会有，这里就退出normal gc并且设置并发标记为flase
            ShenandoahConcurrentGC::cancel();
            heap->set_concurrent_mark_in_progress(false);
        }
        assert(!heap->is_concurrent_mark_in_progress(), "sanity");

        // c. Update roots if this full GC is due to evac-oom, which may carry from-space pointers in roots.
        //如果存在转发对象
        if (has_forwarded_objects) {
            //更新根信息
            update_roots(true /*full_gc*/);
        }
        //重置标记位图
        // d. Reset the bitmaps for new marking
        heap->reset_mark_bitmap();
        assert(heap->marking_context()->is_bitmap_clear(), "sanity");
        assert(!heap->marking_context()->is_complete(), "sanity");

        // e. Abandon reference discovery and clear all discovered references.
        ShenandoahReferenceProcessor *rp = heap->ref_processor();
        //放弃部分引用发现并清除已发现的引用
        rp->abandon_partial_discovery();

        // f. Sync pinned region status from the CP marks
        //同步固定区域状态
        heap->sync_pinned_region_status();

        //初始化
        // The rest of prologue:
        _preserved_marks->init(heap->workers()->active_workers());

        assert(heap->has_forwarded_objects() == has_forwarded_objects, "This should not change");
    }
    //如果使用了TLAB
    if (UseTLAB) {
        //回收TLAB
        heap->gclabs_retire(ResizeTLAB);
        heap->tlabs_retire(ResizeTLAB);
    }
    //内存屏障
    OrderAccess::fence();
    //阶段 1:标记阶段，标记可达对象就是存活对象
    phase1_mark_heap();

    // Once marking is done, which may have fixed up forwarded objects, we can drop it.
    // Coming out of Full GC, we would not have any forwarded objects.
    // This also prevents resolves with fwdptr from kicking in while adjusting pointers in phase3.
    //标记堆中不再有转发对象，
    heap->set_has_forwarded_objects(false);
    //设置 Full GC 的移动操作正在进行。
    heap->set_full_gc_move_in_progress(true);

    // Setup workers for the rest
    //内存屏障确认顺序
    OrderAccess::fence();

    // Initialize worker slices
    //把GC工作线程分组
    ShenandoahHeapRegionSet **worker_slices = NEW_C_HEAP_ARRAY(ShenandoahHeapRegionSet*, heap->max_workers(), mtGC);
    //为每个 GC 工作线程分配一个 ShenandoahHeapRegionSet，用于存储线程负责的堆区域。
    for (uint i = 0; i < heap->max_workers(); i++) {
        worker_slices[i] = new ShenandoahHeapRegionSet();
    }

    {
        // The rest of code performs region moves, where region status is undefined
        // until all phases run together.
        //上锁
        ShenandoahHeapLocker lock(heap->lock());
        //阶段 2：计算目标地址。为存活对象计算新的存储位置
        phase2_calculate_target_addresses(worker_slices);
        //内存屏障
        OrderAccess::fence();
        //阶段 3：更新引用。调整所有指针，使其指向对象的新位置
        phase3_update_references();
        //阶段 4：压缩对象。将存活对象移动到新的位置，释放未使用的内存
        phase4_compact_objects(worker_slices);
    }

    {
        // Epilogue
        //恢复并释放在 GC 过程中保存的对象标记
        _preserved_marks->restore(heap->workers());
        _preserved_marks->reclaim();
    }

    // Resize metaspace
    //调整元空间（Metaspace）的大小，以适应当前的内存需求
    MetaspaceGC::compute_new_size();

    // Free worker slices
    //释放为 GC 工作线程分配的堆区域集合
    for (uint i = 0; i < heap->max_workers(); i++) {
        delete worker_slices[i];
    }
    FREE_C_HEAP_ARRAY(ShenandoahHeapRegionSet*, worker_slices);
    //清除 Full GC 的标志，表示 Full GC 已完成
    heap->set_full_gc_move_in_progress(false);
    heap->set_full_gc_in_progress(false);
    //如果启用了验证选项，在 Full GC 完成后再次验证堆或 JVM 的内存状态
    if (ShenandoahVerify) {
        heap->verifier()->verify_after_fullgc();
    }

    if (VerifyAfterGC) {
        Universe::verify();
    }
    //记录 Full GC 的时间阶段（full_gc_heapdump_post），并执行堆的后处理操作
    {
        ShenandoahGCPhase phase(ShenandoahPhaseTimings::full_gc_heapdump_post);
        heap->post_full_gc_dump(_gc_timer);
    }
}
```

可以看到full GC最先把堆分成多份，然后给每个工作线程一份，每个工作线程都会去处理一部分堆，整个full gc的过程分为

四个阶段：

阶段 1:标记阶段，标记可达对象就是存活对象

阶段 2：计算目标地址。为存活对象计算新的存储位置

阶段 3：更新引用。调整所有指针，使其指向对象的新位置

阶段 4：压缩对象。将存活对象移动到新的位置，释放未使用的内存

最后full gc完成之后还会去调整元空间的大小来适应堆的内存需求，这个笔者也不确定是不是只有SGC才会有，有兴趣的读者可以下去确认一下



## 3、总结

整个Shenandoah GC 分为了三种模式：普通GC、降级GC和全GC，他们之间的关系可以用代码注释了一张图来概括：

```c++
// ................................................................................................
//
//                                    (immediate garbage shortcut)                Concurrent GC
//                             /-------------------------------------------\
//                             |                                           |
//                             |                                           |
//                             |                                           |
//                             |                                           v
// [START] ----> Conc Mark ----o----> Conc Evac --o--> Conc Update-Refs ---o----> [END]
//                   |                    |                 |              ^
//                   | (af)               | (af)            | (af)         |
// ..................|....................|.................|..............|.......................
//                   |                    |                 |              |
//                   |                    |                 |              |      Degenerated GC
//                   v                    v                 v              |
//               STW Mark ----------> STW Evac ----> STW Update-Refs ----->o
//                   |                    |                 |              ^
//                   | (af)               | (af)            | (af)         |
// ..................|....................|.................|..............|.......................
//                   |                    |                 |              |
//                   |                    v                 |              |      Full GC
//                   \------------------->o<----------------/              |
//                                        |                                |
//                                        v                                |
//                                      Full GC  --------------------------/
//
```

普通GC都是不用stw的同步去做的，降级GC可能都是stw也有可能部分stw，这个取决于降级点，如果是在GC之外的降级点就是标记、驱散和更新引用都是stw的，full GC全部都是stw的。但是注意，普通GC和降级GC都是回收部分堆，full gc是针对全部堆，在代码里full gc可是把全部堆都分给了工作线程。最后我们简单对比一下这三种GC模式：

| **特性**       | **Normal GC**              | **Full GC**                      | **Degenerated GC**         |
| -------------- | -------------------------- | -------------------------------- | -------------------------- |
| **触发条件**   | 堆内存接近阈值、周期性触发 | 内存耗尽、降级回收失败、手动触发 | 并发回收失败、对象分配失败 |
| **暂停时间**   | 短（毫秒级）               | 长（STW，暂停所有线程）          | 较短（部分暂停线程）       |
| **回收范围**   | 部分堆                     | 整个堆                           | 当前回收任务               |
| **是否压缩堆** | 否                         | 是                               | 否                         |
| **性能影响**   | 低                         | 高                               | 较低                       |
| **堆碎片**     | 可能存在                   | 无（整理堆内存）                 | 可能存在（不整理堆内存）   |
| **适用场景**   | 常规回收，低延迟           | 最后手段，确保堆一致性           | 权衡机制，尽量避免 Full GC |

最后在GC开始的时候选择GC模式的代码在`src/hotspot/share/gc/shenandoah/shenandoahControlThread.cpp`中的`run_serivce()`方法中，感兴趣的读者朋友可以自行挖掘