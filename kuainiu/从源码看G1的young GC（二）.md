# 从源码看G1的young GC（二）

再上一篇文章中我们讲述了从对象分配到GC的流程，主要是如何触发GC，本篇文章将会继续讲述如何执行GC

由于GC涉及到了非常多的细节和知识点，比如Cset，Rset，SATB等等，由于大家基本不会涉及到JVM开发所以笔者对这些知识点会一笔带过不会深究，起码在这篇文章里不会，本篇文章只会粗略带大家看一下G1的Young GC整体流程，然后看一下一些八股老演员的代码实现，仅此而已。



再上一篇的文章中，我们最后的GC入口代码为：

```c++
g1h->do_collection_pause_at_safepoint(_target_pause_time_ms);
```

今天我们就从这里开始。

开始之前我们先简单回顾一下G1的GC流程：

> 1. 初始标记阶段（Initial Marking）：该阶段是STW（Stop-The-World）的，它标记出所有的根对象以及直接与之关联的对象，包括被各个处理器缓存的引用。
> 2. 并发标记阶段（Concurrent Marking）：该阶段是并发执行的，它通过并发地遍历对象图，标记所有被根对象可达的存活对象。由于该阶段与应用程序并发执行，所以其对应用程序的停顿时间很短。
> 3. 最终标记阶段（Final Marking）：该阶段是STW的，它标记在并发标记阶段发生变化的对象，以及并发标记阶段结束时，还存活的跨区域对象的存活状态。
> 4. 并发清理阶段（Concurrent Cleaning）：该阶段是并发执行的，它的主要任务是回收空闲的Heap Region，并将其中存活的对象复制到空闲的Heap Region中。
> 5. 并发整理阶段（Concurrent Compacting）：该阶段是并发执行的，它的主要任务是将存活对象从多个Heap Region中复制到一个或多个新的连续Heap Region中，以便为应用程序提供连续的内存空间。
> 6. 增量回收阶段（Incremental Collection）：该阶段是在应用程序运行期间的多个时间片内并发执行的，它的主要任务是在上述各个阶段之间执行回收操作，以进一步减少应用程序的停顿时间。

好的，有了这些基本概念，我们再来看代码

## 1、开始前的准备

到了`do_collection_pause_at_safepoint`也不是直接就是GC真正开始，还有一点GC之前的准备工作：

```c++
  //在 VM 线程的安全点处断言，确保当前线程在 VM 线程上运行且在安全点上  
  assert_at_safepoint_on_vm_thread();
  // 使用断言确保垃圾回收不可重入，即不允许在进行垃圾回收时再次触发垃圾回收
  guarantee(!is_gc_active(), "collection is not reentrant");
  //判断是否有线程再临界区，如果有则舍弃本次gc,并把need_gc参数设置为true
  //这里是我们刚刚提到的gc_locker
  if (GCLocker::check_active_before_gc()) {
    return false;
  }

  //注册垃圾回收的开始时间，用于计时统计
  _gc_timer_stw->register_gc_start();

	//通过 GCIdMark 和 _gc_tracer_stw（垃圾回收追踪器）报告垃圾回收的开始，包括垃圾回收的原因和开始时间
  GCIdMark gc_id_mark;
  _gc_tracer_stw->report_gc_start(gc_cause(), _gc_timer_stw->gc_start());

	//创建一个 SvcGCMarker 对象，用于标记 GC 类型为 MINOR
  SvcGCMarker sgcm(SvcGCMarker::MINOR);
  //创建一个 ResourceMark 对象，用于管理资源的生命周期
  ResourceMark rm;
  // 通知 G1 收集器的策略（g1_policy）垃圾回收已经开始
  g1_policy()->note_gc_start();
  //等待根区域的扫描完成，确保根区域中的对象已经被正确扫描
  wait_for_root_region_scanning();

  //印垃圾回收前的堆信息和堆区域信息
  print_heap_before_gc();
  print_heap_regions();
  //使用垃圾回收追踪器（_gc_tracer_stw）追踪垃圾回收前的堆状态
  trace_heap_before_gc(_gc_tracer_stw);
```

这里比较重要的代码是` wait_for_root_region_scanning();`

`wait_for_root_region_scanning`方法的作用是等待 G1 垃圾收集器中的 CM（Concurrent Marking）线程完成对根区域的扫描。

那么什么是根区域扫描呢？在初始标记暂停结束后，年轻代收集也完成的对象复制到Survivor的工作，应用线程开始活跃起来。此时为了保证标记算法的正确性，所有新复制到Survivor分区的对象，都需要被扫描并标记成根，这个过程称为根分区扫描(Root Region Scanning)，同时扫描的Suvivor分区也被称为根分区(Root Region)。根分区扫描必须在下一次年轻代垃圾收集启动前完成(并发标记的过程中，可能会被若干次年轻代垃圾收集打断)，因为每次GC会产生新的存活对象集合。

可以理解为这个是上一次GC之后开始运行的，直到下一次GC前要完成，这里的根就是我们可达性算法里的gc root

这个方法会阻塞当前线程，直到 CM 线程完成对根区域的扫描。如果等待成功，代码会计算等待的时间，并将其记录到日志中，用于统计和调优。

总之这个方法为了保证在进行垃圾回收之前，所有的根区域都已经完成扫描，以确保垃圾回收的准确性和完整性。

G1的官网上是这样介绍根区域扫描的：

>**Root region scanning phase**: The G1 GC scans survivor regions marked during the initial marking phase for references to the old generation and marks the referenced objects. This phase runs concurrently with the application (not STW) and must complete before the next STW young garbage collection can start.

接下来：

```c++
 
  if (!_cm_thread->should_terminate()) {
   //这段代码的作用是检查并决定是否启动并发标记（concurrent mark）。首先，代码检查并发标记线程（_cm_thread）是否应该终止，如果不应该终止，则继续执行。然后，代码调用 g1_policy()->decide_on_conc_mark_initiation() 方法来决定是否启动并发标记。在这个决策过程中，将根据当前的情况来判断是否需要执行初始标记（initial mark）阶段的垃圾回收。
    g1_policy()->decide_on_conc_mark_initiation();
  }

  // 断言检查，确保在执行初始标记阶段的垃圾回收时，不会同时进行混合垃圾回收（mixed GC）。断言条件要求，如果当前处于初始标记阶段，则必须处于仅年轻代（young-only）阶段
  assert(!collector_state()->in_initial_mark_gc() ||
          collector_state()->in_young_only_phase(), "sanity");

  // 确保在标记或重建（mark or rebuild）过程中不会进行混合垃圾回收。断言条件要求，如果当前处于标记或重建过程中，则必须处于仅年轻代阶段
  assert(!collector_state()->mark_or_rebuild_in_progress() || collector_state()->in_young_only_phase(), "sanity");

 //记录当前的垃圾回收是否是初始标记阶段。根据 collector_state()->in_initial_mark_gc() 的返回值，将布尔值赋给 should_start_conc_mark 变量，用于后续的处理。
  bool should_start_conc_mark = collector_state()->in_initial_mark_gc();
```

就是一系列状态的检查，以为后面的操作做准备。

然后：

```c++
// 用于存储关于垃圾回收过程中的疏散（evacuation）信息
  {
    EvacuationInfo evacuation_info;

    if (collector_state()->in_initial_mark_gc()) {
      //检查当前是否处于初始标记（initial mark）阶段的垃圾回收。如果是初始标记阶段，代码会执行一些操作，包括增加旧对象标记循环计数器（0()）和设置垃圾回收原因（gc_cause()）。

      increment_old_marking_cycles_started();
      _cm->gc_tracer_cm()->set_gc_cause(gc_cause());
    }
		//报告年轻代收集的类型（yc_type）.收集和记录与年轻代收集相关的统计信息。
    _gc_tracer_stw->report_yc_type(collector_state()->yc_type());

    //记录垃圾回收期间的 CPU 时间。
    GCTraceCPUTime tcpu;

    //指定 G1HeapVerifier 的验证类型
    G1HeapVerifier::G1VerifyType verify_type;
    //格式化缓冲区，用于存储与垃圾回收相关的字符串，主要用来日志显示
    FormatBuffer<> gc_string("Pause Young ");
    //根据垃圾回收的状态和阶段，验证类型也会根据状态和阶段变化
    if (collector_state()->in_initial_mark_gc()) {
      gc_string.append("(Concurrent Start)");
      verify_type = G1HeapVerifier::G1VerifyConcurrentStart;
    } else if (collector_state()->in_young_only_phase()) {
      if (collector_state()->in_young_gc_before_mixed()) {
        gc_string.append("(Prepare Mixed)");
      } else {
        gc_string.append("(Normal)");
      }
      verify_type = G1HeapVerifier::G1VerifyYoungNormal;
    } else {
      gc_string.append("(Mixed)");
      verify_type = G1HeapVerifier::G1VerifyMixed;
    }
    //进行垃圾回收的时间跟踪和日志记录。它使用 gc_string 作为日志消息，并传递 gc_cause() 和 true 作为其他参数。
    GCTraceTime(Info, gc) tm(gc_string, NULL, gc_cause(), true);

```

这里的`gc_cause()`返回的就是我们再上一篇文章中说到的初始化jvm操作类的时候传递进去的：`GCCause::_g1_inc_collection_pause`：

```c++
VM_G1CollectForAllocation op(word_size,
                               gc_count_before,
                               gc_cause,
                               false, 
                               g1_policy()->max_pause_time_ms());
```

然后又是在上一篇文章中的操作类—`VM_G1CollectForAllocation`的`doit`方法中赋值给我们的G1 heap类的：

```c++
void VM_G1CollectForAllocation::doit() {
...
  GCCauseSetter x(g1h, _gc_cause);
...
}

class GCCauseSetter : StackObj {
  CollectedHeap* _heap;
  GCCause::Cause _previous_cause;
 public:
  GCCauseSetter(CollectedHeap* heap, GCCause::Cause cause) {
    _heap = heap;
    _previous_cause = _heap->gc_cause();
    _heap->set_gc_cause(cause);
  }

  ~GCCauseSetter() {
    _heap->set_gc_cause(_previous_cause);
  }
};
```

然后：

```c++
//计算活动工作线程的数量，并传递总工作线程数、活动工作线程数和非守护线程数。返回的结果存储在 active_workers 变量中。  
uint active_workers = AdaptiveSizePolicy::calc_active_workers(workers()->total_workers(),
                                                                  workers()->active_workers(),
        
 //更新活动工作线程的数量，这里的工作线程指的就是gc的工作线程
                                                              Threads::number_of_non_daemon_threads());
    active_workers = workers()->update_active_workers(active_workers);
    log_info(gc,task)("Using %u workers of %u for evacuation", active_workers, workers()->total_workers());

		//用于跟踪垃圾回收期间的统计信息
    TraceCollectorStats tcs(g1mm()->incremental_collection_counters());
		//跟踪内存管理器的统计信息
    TraceMemoryManagerStats tms(&_memory_manager, gc_cause(),
                                collector_state()->yc_type() == Mixed /* allMemoryPoolsAffected */);

		//用于在垃圾回收期间进行堆转换
    G1HeapTransition heap_transition(this);
		//获取垃圾回收前的堆使用字节数，这里底层是用的used_in_alloc_regions方法，也就是直接根据被分配了的region来计算堆使用了的大小
    size_t heap_used_bytes_before_gc = used();

    // Don't dynamically change the number of GC threads this early.  A value of
    // 0 is used to indicate serial work.  When parallel work is done,
    // it will be set.

    { // Call to jvmpi::post_class_unload_events must occur outside of active GC
      //正式开始启动GC，调用构造函数把堆的_is_gc_active设置为true
      IsGCActiveMark x;
			//进行GC前奏工作。它包括一些断言检查、打印摘要信息、更新计数器、执行准备工作等操作。这些操作有助于在垃圾回收过程中进行监控、调试和性能分析
      gc_prologue(false);

      //如果启用了 VerifyRememberedSets，则进行“验证记忆集（Remembered Set）”的操作。这里使用了 VerifyRegionRemSetClosure 对象和 heap_region_iterate() 函数来遍历堆的区域，并进行验证操作
      if (VerifyRememberedSets) {
        log_info(gc, verify)("[Verifying RemSets before GC]");
        VerifyRegionRemSetClosure v_cl;
        heap_region_iterate(&v_cl);
      }
			//在GC之前执行一些验证
      _verifier->verify_before_gc(verify_type);

      //验证，之后正式开始GC
      _verifier->check_bitmaps("GC Start");
```

这里就是正式开始GC前的最后的操作了，注意在`IsGCActiveMark x;`中就是设置heap的`_is_gc_active`也就是函数开头的` guarantee(!is_gc_active(), "collection is not reentrant");`这个断言，这里之后这个`is_gc_active`函数就返回为true了。这里开头的不能重入，笔者大胆猜测，应该是不允许在GC开始之后再向堆里创建对象的时候开始GC了。

这里还有一个隐藏的知识点就是G1如何统计使用了的堆大小，这里就是直接获取所有分配了大小的region：

```c++
size_t MutatorAllocRegion::used_in_alloc_regions() {
  size_t used = 0;
  HeapRegion* hr = get();
  if (hr != NULL) {
    used += hr->used();
  }

  hr = _retained_alloc_region;
  if (hr != NULL) {
    used += hr->used();
  }
  return used;
}
```

然后加上 在 GC 暂停期间，除当前分配区域外的所有区域中使用的字节数，如果处理存档分配范围的类的大小不为空就再加上处理存档分配范围的类：

```c++
size_t G1CollectedHeap::used() const {
  size_t result = _summary_bytes_used + _allocator->used_in_alloc_regions();
  if (_archive_allocator != NULL) {
    result += _archive_allocator->used();
  }
  return result;
}
```

## 2、正式开始GC

```c++
//当编译器是 COMPILER2 或 JVMCI 时清空派生指针表。
#if COMPILER2_OR_JVMCI
      DerivedPointerTable::clear();
#endif

     //在 STW（Stop-The-World）引用处理器中启用引用发现。
      _ref_processor_stw->enable_discovery();

      {
       //在其生命周期内临时关闭 CM（Concurrent Marking）引用处理器的引用发现功能。
        NoRefDiscovery no_cm_discovery(_ref_processor_cm);

        //释放当前的分配区域（alloc region）
        _allocator->release_mutator_alloc_region();

        //记录垃圾回收暂停开始的时间
        double sample_start_time_sec = os::elapsedTime();
        g1_policy()->record_collection_pause_start(sample_start_time_sec);

        ///在进行初始标记（initial mark）的垃圾回收时，执行初始标记阶段的一些准备工作。
        if (collector_state()->in_initial_mark_gc()) {
          concurrent_mark()->pre_initial_mark();
        }

        //最终确定要进行垃圾回收的集合
        g1_policy()->finalize_collection_set(target_pause_time_ms, &_survivor);

        //设置集合（collection set）的区域数量
        evacuation_info.set_collectionset_regions(collection_set()->region_length());
```



这里有个知识点Cset：Collection Set（CSet），它记录了GC要收集的Region集合，集合里的Region可以是任意年代的。选择合适的 Region 放入 CSet 是为了让 G1 达到用户期望的合理的停顿时间

这里比较重要的就是；`g1_policy()->finalize_collection_set(target_pause_time_ms, &_survivor);`确定我们本次GC要收集的region集合:

```c++
void G1Policy::finalize_collection_set(double target_pause_time_ms, G1SurvivorRegions* survivor) {
  double time_remaining_ms = _collection_set->finalize_young_part(target_pause_time_ms, survivor);
  _collection_set->finalize_old_part(time_remaining_ms);
}
```

这里有两个参数：目标暂停时间和S区指针，先确定年轻代的回收region然后确定老年代回收region，我们一个一个的简单看下:

```c++
double G1CollectionSet::finalize_young_part(double target_pause_time_ms, G1SurvivorRegions* survivors) {
  //记录函数开始的时间
  double young_start_time_sec = os::elapsedTime();

  //完成增量建立
  finalize_incremental_building();

  //一些校验
 。。。

//计算S区和eden区的长度，然后用来初始化到_collection_set对象里的_eden_region_length和_survivor_region_length属性
  uint survivor_region_length = survivors->length();
  uint eden_region_length = _g1h->eden_regions_count();
  init_region_lengths(eden_region_length, survivor_region_length);

  //验证年轻代集合索引。
  verify_young_cset_indices();

  //把S区设置为eden，他们现在都是年轻代了
  survivors->convert_to_eden();

  //设置_bytes_used_before，暂停之前Cset中的字节数
  _bytes_used_before = _inc_bytes_used_before;
  time_remaining_ms = MAX2(time_remaining_ms - _inc_predicted_elapsed_time_ms, 0.0);

 ...

  // 设置记录的根扫描（recorded root scanning）长度
  set_recorded_rs_lengths(_inc_recorded_rs_lengths);

  //记录年轻代选择时间
  double young_end_time_sec = os::elapsedTime();
  phase_times()->record_young_cset_choice_time_ms((young_end_time_sec - young_start_time_sec) * 1000.0);

  return time_remaining_ms;
}
```

 这里可以说一下如何把S区的region转换为eden：

```c++
void HeapRegion::set_eden_pre_gc() {
  report_region_type_change(G1HeapRegionTraceType::Eden);
  _type.set_eden_pre_gc();
}
```

就是每个region都有一个类型，这样把类型修改为Eden就行了，不过是在GC前置的时候设置，这里要区别为直接设置为eden。

值得注意的是，我们以前的八股中，YGC有一个流程是把eden区和S0/1的存活对象复制到S0/1区上，这里G1的做法是把S区直接变成eden。

然后确定S和eden的length都是在Eden和S区的Region类中进行的——`G1EdenRegions.hpp`和`G1SurvivorRegions.cpp`：

```c++
  uint eden_regions_count() const {
    return _eden.length();
  }

uint G1SurvivorRegions::length() const {
  return (uint)_regions->length();
}
```

eden region中length就是简单的在add之后len++，所以可以简单理解为这里的长度就是region的数量

简单总结一下：选择年轻代中的需要回收的region的逻辑就是：把年轻代里所有的region都放到Cset，这里放到Cset的方式是把长度存到Cset中，然后把所有S区region设置为eden，视为都为年轻代。

选择老年代region的逻辑就很简单了:

```c++
void G1CollectionSet::finalize_old_part(double time_remaining_ms) {
  double non_young_start_time_sec = os::elapsedTime();
  double predicted_old_time_ms = 0.0;

  if (collector_state()->in_mixed_phase()) {
  ...
```

如果是mixGC才会选择老年代的region，由于本次是young gc所以老年代没有region选择。

确定了要最终收集的垃圾集合之后就是确定区域数量了:`evacuation_info.set_collectionset_regions(collection_set()->region_length());`

首先`region_length`方法就是刚刚上一步设置的，年轻代的length就是S区长度加上eden区长度：

```c++
  uint region_length() const       { return young_region_length() +
                                            old_region_length(); }
  uint young_region_length() const { return eden_region_length() +
                                            survivor_region_length(); }
```

所以这一步就是把上一步确定的Cset的数量设置给`evacuation_info`，`evacuation_info`就是上面初始化的局部变量用于存储关于垃圾回收过程中的疏散（evacuation）信息

然后：

```c++
				//用于确保记忆集（remembered sets）是最新的。在调用 register_humongous_regions_with_cset() 函数之前，需要确保记忆集是最新的，因为在 register_humongous_regions_with_cset() 函数中会使用记忆集来选择急切回收的候选对象。如果记忆集不是最新的，可能会错过需要处理的一些条目。
        g1_rem_set()->cleanupHRRS();

				//注册H region到Cset中，也就是说YGC会处理部分H region
        register_humongous_regions_with_cset();

        assert(_verifier->check_cset_fast_test(), "Inconsistency in the InCSetState table.");

        //确保 CSet 中不包含任何指向对象的引用。这是为了确保 CSet 的最终状态是正确的。
        _cm->verify_no_cset_oops();

        if (_hr_printer.is_active()) {
          G1PrintCollectionSetClosure cl(&_hr_printer);
          _collection_set.iterate(&cl);
        }

        //初始化 GC 分配区域，GC 分配区域是用于分配GC中产生的新对象的内存区域
        _allocator->init_gc_alloc_regions(evacuation_info);

				//执行实际的集合集合清理（evacuation）之前进行一些准备工作。
        G1ParScanThreadStateSet per_thread_states(this, workers()->active_workers(), collection_set()->young_region_length());
        pre_evacuate_collection_set();

        //真正的GC代码
        evacuate_collection_set(&per_thread_states);

				//集合集合清理过程之后进行一些后续处理工作，包括处理回收信息（evacuation_info）和更新线程状态（per_thread_states）等。
        post_evacuate_collection_set(evacuation_info, &per_thread_states);

```

这段代码的信息量很多，我们一个一个理：

第一个知识点——记忆集（remembered sets）：

记忆集（remembered sets）是辅助GC过程的一种结构，典型的空间换时间工具，和Card Table有些类似, 逻辑上说每个Region都有一个RSet，RSet记录了其他Region中的对象引用本Region中对象的关系，属于points-into结构（谁引用了我的对象）在GC的时候，对于old->young和old->old的跨代对象引用，只要扫描对应的CSet中的RSet即可.RSet其实是一个Hash Table，Key是别的Region的起始地址，Value是一个集合，里面的元素是Card Table的Index。简单来说Rset是用于解决跨region引用问题,在g1中只有老年代引用新生代的对象会被记录到Rset中,这样在youngGC中就可以避免扫描整个比较大的老年代减少开销，而只用处理记忆集合就好

下图表示了RSet、Card和Region的关系

![image-20230919182700896](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20230919182700896.png)

上图中有三个Region，每个Region被分成了多个Card，在不同Region中的Card会相互引用，Region1中的Card中的对象引用了Region2中的Card中的对象，蓝色实线表示的就是points-out的关系，而在Region2的RSet中，记录了Region1的Card，即红色虚线表示的关系，这就是points-into。 而维系RSet中的引用关系靠post-write barrier和Concurrent refinement threads来维护

`register_humongous_regions_with_cset`中，我们可以看到YGC除了会收集年轻代的region也会处理H region:

```c++
void G1CollectedHeap::register_humongous_regions_with_cset() {
  //是否启用大对象急切回收，如果没有启用那么就不处理大对象
  if (!G1EagerReclaimHumongousObjects) {
    g1_policy()->phase_times()->record_fast_reclaim_humongous_stats(0.0, 0, 0);
    return;
  }
  //函数获取当前的计数器值，用于计算注册H region到 Collection Set 所花费的时间。
  double time = os::elapsed_counter();

  // 代码创建了一个 RegisterHumongousWithInCSetFastTestClosure 对象 cl，并使用 heap_region_iterate() 函数对堆中的每个区域进行遍历。RegisterHumongousWithInCSetFastTestClosure 是一个闭包对象，用于收集回收候选巨型对象的信息并将其注册到 Collection Set 中。
  RegisterHumongousWithInCSetFastTestClosure cl;
  heap_region_iterate(&cl);

  time = ((double)(os::elapsed_counter() - time) / os::elapsed_frequency()) * 1000.0;
  g1_policy()->phase_times()->record_fast_reclaim_humongous_stats(time,
                                                                  cl.total_humongous(),
   //是否存在候选回收的H region                                                            
  _has_humongous_reclaim_candidates = cl.candidate_humongous() > 0;

  //将所有记忆集（remembered set）中的条目刷新到全局 DCQS（Dirty Card Queue Set）中进行重新检查。
  cl.flush_rem_set_entries();
}
```

`G1EagerReclaimHumongousObjects`是G1的JVM参数，默认是true。

简单来说这里遍历堆搜集的H region主要就是有任何引用指向的，这里主要是依靠记忆集Rset来完成。

`pre_evacuate_collection_set`就是开启GC之前最后的准备工作，他会做这些工作：重置标志、禁用热点卡缓存、准备记录集、断言保留标记集为空，以及在初始标记阶段清除已标记的类加载器数据并记录时间。这些准备工作有助于确保垃圾回收的正确执行和性能优化。

`evacuate_collection_set`就是真正执行GC的代码了：

```c++
void G1CollectedHeap::evacuate_collection_set(G1ParScanThreadStateSet* per_thread_states) {
  // 设置当前垃圾回收的失败频繁模式（G1EvacuationFailureALot）。这是一个用于调试和测试目的的宏，用于模拟失败的情况
  NOT_PRODUCT(set_evacuation_failure_alot_for_current_gc();)

  //检查脏卡片队列集合（dirty card queue set）的已完成缓冲区数量是否为0
  assert(dirty_card_queue_set().completed_buffers_num() == 0, "Should be empty");

  //获取 G1 垃圾回收策略对象的阶段时间（phase_times）。
  G1GCPhaseTimes* phase_times = g1_policy()->phase_times();

  //开始结束时间
  double start_par_time_sec = os::elapsedTime();
  double end_par_time_sec;

  {
    //获取活动工作线程的数量并且激活GC工作线程
    const uint n_workers = workers()->active_workers();
    //清理根集处理器
    G1RootProcessor root_processor(this, n_workers);
    //youngGC任务类
    G1ParTask g1_par_task(this, per_thread_states, _task_queues, &root_processor, n_workers);

    print_termination_stats_hdr();

    //执行并行任务，类似于java的submit Runnable，阻塞，直到 G1ParTask 执行完成
    workers()->run_task(&g1_par_task);
    //记录时间
    end_par_time_sec = os::elapsedTime();

    // Closing the inner scope will execute the destructor
    // for the G1RootProcessor object. We record the current
    // elapsed time before closing the scope so that time
    // taken for the destructor is NOT included in the
    // reported parallel time.
  }

  //计算并行任务的时间
  double par_time_ms = (end_par_time_sec - start_par_time_sec) * 1000.0;
  phase_times->record_par_time(par_time_ms);

  double code_root_fixup_time_ms =
        (os::elapsedTime() - end_par_time_sec) * 1000.0;
  phase_times->record_code_root_fixup_time(code_root_fixup_time_ms);
}
```

这里先简单介绍几个知识点：

+ dirty card : 脏卡，在g1中每个region被分成若干个card,card用于映射一块内存块，其中不止有一个对象，当card有一个及以上对象的字段存在跨代引用时就被标记为脏

- dirty card queue : 脏卡队列，再执行引用赋值语句时再写屏障中会将判断当前赋值是否跨代，如果跨代则将其对应的card标记为脏并加入dirty card queue中，后续会由其他线程异步处理更新到rset中。

- dirty card queue set : 脏卡队列集合，其中由所有脏卡队列，当一个脏卡队列满时会再其中记录，youngGC时会将脏卡集合队列中满的脏卡队列更新到rset中。

这里涉及到的就是g1的写屏障和记忆集合，卡表等知识点，以后有机会笔者再专门介绍下相关知识，这里我们先简单了解下。

在`worker()->run_task(&g1_par_task)`中，会阻塞当前线程，等待工作线程执行完毕：

```c++
  void coordinator_execute_on_workers(AbstractGangTask* task, uint num_workers) {
    MutexLockerEx ml(_monitor, Mutex::_no_safepoint_check_flag);

    _task        = task;
    _num_workers = num_workers;

    // 通知所有等待在该互斥锁上的线程可以开始工作。
    _monitor->notify_all();

    // 等待工作线程完成任务。循环条件是 _finished 小于 _num_workers，即还有工作线程没有完成任务。在循环中，代码调用 _monitor 对象的 wait() 函数，使当前线程等待在互斥锁上。no_safepoint_check 参数表示在等待期间不进行安全点检查。
    while (_finished < _num_workers) {
      _monitor->wait(/* no_safepoint_check */ true);
    }

    //将 _task、_num_workers、_started 和 _finished 四个成员变量重置为初始值，以便下次执行任务时重新设置。

    _task        = NULL;
    _num_workers = 0;
    _started     = 0;
    _finished    = 0;
  }
```

所以我们直接看`G1ParTask`的`work`方法就行了：

```c++
  void work(uint worker_id) {
    //检查当前的工作线程 ID 是否超过了工作线程数量 _n_workers，如果超过了，则说明当前线程不需要执行工作，直接返回。
    if (worker_id >= _n_workers) return;  // no work needed this round

    //记录开始时间
    double start_sec = os::elapsedTime();
    _g1h->g1_policy()->phase_times()->record_time_secs(G1GCPhaseTimes::GCWorkerStart, worker_id, start_sec);

    {
      //管理本地资源和句柄的生命周期。这样可以确保在方法执行结束时，本地资源和句柄会被正确释放。
      ResourceMark rm;
      HandleMark   hm;

      //用于处理引用类型的对象。
      ReferenceProcessor*             rp = _g1h->ref_processor_stw();

      //获取当前工作线程的扫描线程状态 G1ParScanThreadState 对象。
      G1ParScanThreadState*           pss = _pss->state_for_worker(worker_id);
      //将 rp 设置为 pss 的引用发现器，用于在扫描阶段发现引用对象。
      pss->set_ref_discoverer(rp);

      //这行代码记录开始执行强根（Strong Roots）扫描的时间。
      double start_strong_roots_sec = os::elapsedTime();

      //执行根扫描，将根对象的引用复制到新的区域。
      _root_processor->evacuate_roots(pss, worker_id);

      // We pass a weak code blobs closure to the remembered set scanning because we want to avoid
      // treating the nmethods visited to act as roots for concurrent marking.
      // We only want to make sure that the oops in the nmethods are adjusted with regard to the
      // objects copied by the current evacuation.
      //处理记录集中的引用对象
      _g1h->g1_rem_set()->oops_into_collection_set_do(pss, worker_id);

      //记录撤离（Evacuation）阶段的时间和尝试次数。
      double strong_roots_sec = os::elapsedTime() - start_strong_roots_sec;

      double term_sec = 0.0;
      size_t evac_term_attempts = 0;
      {
        double start = os::elapsedTime();
        //执行撤离阶段的操作
        G1ParEvacuateFollowersClosure evac(_g1h, pss, _queues, &_terminator);
        //执行撤离阶段的操作。
        evac.do_void();

        //获取撤离阶段的尝试次数和时间
        evac_term_attempts = evac.term_attempts();
        term_sec = evac.term_time();
        double elapsed_sec = os::elapsedTime() - start;

        //记录各个阶段的时间统计信息。
        G1GCPhaseTimes* p = _g1h->g1_policy()->phase_times();
        p->add_time_secs(G1GCPhaseTimes::ObjCopy, worker_id, elapsed_sec - term_sec);
        p->record_time_secs(G1GCPhaseTimes::Termination, worker_id, term_sec);
        p->record_thread_work_item(G1GCPhaseTimes::Termination, worker_id, evac_term_attempts);
      }

      assert(pss->queue_is_empty(), "should be empty");

      //以下都是和GC日志关联的
...
```

这里我们可以简单的分为三个阶段：

1、清理根集—`evacuate_roots`

2、处理 RSet—`oops_into_collection_set_do`

3、对象复制—`G1ParEvacuateFollowersClosure::do_void`

我们一个一个说

#### 1、清理根集

在清理根集中会从这些 GC Root 出发寻找存活对象。以线程栈为例，G1 会扫描虚拟机所有 JavaThread 和 VMThread 的线程栈中的每一个栈帧，找到其中的对象引用，代码片段如下：

```c++
	G1EvacuationRootClosures* closures = pss->closures();
	//处理 Java 根对象的引用。
  process_java_roots(closures, phase_times, worker_i);

  // This is the point where this worker thread will not find more strong CLDs/nmethods.
  // Report this so G1 can synchronize the strong and weak CLDs/nmethods processing.
  if (closures->trace_metadata()) {
    worker_has_discovered_all_strong_classes();
  }

	//处理虚拟机根对象的引用。
  process_vm_roots(closures, phase_times, worker_i);
	//处理字符串表的根对象引用。
  process_string_table_roots(closures, phase_times, worker_i);
```

所以这里主要是完成三件事：

- 处理java根
- 处理jvm根
- 处理string table根

我们重点看java跟对象引用：

```c++
void G1RootProcessor::process_java_roots(G1RootClosures* closures,
                                         G1GCPhaseTimes* phase_times,
                                         uint worker_i) {
  // Iterating over the CLDG and the Threads are done early to allow us to
  // first process the strong CLDs and nmethods and then, after a barrier,
  // let the thread process the weak CLDs and nmethods.
  {
    //跟踪 CLDG（ClassLoaderDataGraph）根对象处理阶段的时间，并将其记录到 G1GCPhaseTimes 对象中的 CLDGRoots 阶段
    G1GCParPhaseTimesTracker x(phase_times, G1GCPhaseTimes::CLDGRoots, worker_i);
    //是否需要处理 CLDG 根对象
    if (!_process_strong_tasks.is_task_claimed(G1RP_PS_ClassLoaderDataGraph_oops_do)) {
      //所有已加载的类的对象引用（
      d::roots_cld_do(closures->strong_clds(), closures->weak_clds());
    }
  }

  {
    //跟踪线程根对象处理阶段的时间，并将其记录到 G1GCPhaseTimes 对象中的 ThreadRoots 阶段。
    G1GCParPhaseTimesTracker x(phase_times, G1GCPhaseTimes::ThreadRoots, worker_i);
    //检查是否有多个工作线程，以确定是否要并行处理线程的根对象。
    bool is_par = n_workers() > 1;
    Threads::possibly_parallel_oops_do(is_par,
                                       closures->strong_oops(),
                                       closures->strong_codeblobs());
  }
}
```



这里CLDG（ClassLoaderDataGraph）是和ClassLoaderData有关的

ClassLoaderData是指 Java 虚拟机中的一个数据结构， 负责初始化并销毁一个ClassLoader实例对应的Metaspace，是在垃圾回收过程中被认为是根的对象，即不可被回收的对象。这些对象通常包括：

1. 类加载器（ClassLoader）对象：类加载器对象本身是一个根对象，因为它们是类加载过程的起点，负责加载其他类和资源。
2. 类对象（Class）：已加载的类对象通常也被视为根对象，因为它们是应用程序的基础组成部分。
3. 方法区中的常量池（Constant Pool）：常量池中的字符串、类引用等对象也被视为根对象，因为它们被类对象引用。
4. 类加载器数据（ClassLoaderData）对象：CLDG 中的类加载器数据对象也是根对象，因为它们代表了类加载器的状态和相关信息。

也就是我们通常所说的可以作为GC root的根节点的对象

ClassLoaderDataGraph相当于ClassLoaderData的一个管理类，方便遍历所有的ClassLoaderData

然后重点代码就是：

```c++
Threads::possibly_parallel_oops_do(is_par,
                                       closures->strong_oops(),
                                       closures->strong_codeblobs());
```

我们进去可以看到：

```c++
void Threads::possibly_parallel_threads_do(bool is_par, ThreadClosure* tc) {
  int cp = Threads::thread_claim_parity();
   // 遍历所有java线程
  ALL_JAVA_THREADS(p) {
    if (p->claim_oops_do(is_par, cp)) {
      //tc= ParallelOopsDoThreadClosure tc(f, cf);这里最终会执行f
      tc->do_thread(p);
    }
  }
   //遍历虚拟机线程
  VMThread* vmt = VMThread::vm_thread();
  if (vmt->claim_oops_do(is_par, cp)) {
    //同上
    tc->do_thread(vmt);
  }
}

ParallelOopsDoThreadClosure为：
public:
  ParallelOopsDoThreadClosure(OopClosure* f, CodeBlobClosure* cf) : _f(f), _cf(cf) {}
  void do_thread(Thread* t) {
    t->oops_do(_f, _cf);
  }
};
...
  然后点进去：
  
void Thread::oops_do(OopClosure* f, CodeBlobClosure* cf) {
  if (active_handles() != NULL) {
    active_handles()->oops_do(f);
  }
  // Do oop for ThreadShadow
  f->do_oop((oop*)&_pending_exception);
  handle_area()->oops_do(f);
  ...
```

所以最后调用的就是`f->do_oop`即f的`do_oop`方法。

这里传递的f就是：` closures->strong_oops()`，f就是`closures->strong_oops()`，这里的`closures`就是:

```c++
G1EvacuationRootClosures* closures = pss->closures();
...
G1EvacuationRootClosures* closures() { return _closures; }
```

可以看到这里就是需要`pss`的`_closures`属性，pss初始化的时候（上文中有，就是在G1parTask的work方法里）:

```c++
 G1ParScanThreadState*           pss = _pss->state_for_worker(worker_id);
...
  G1ParScanThreadState* G1ParScanThreadStateSet::state_for_worker(uint worker_id) {
  assert(worker_id < _n_workers, "out of bounds access");
  if (_states[worker_id] == NULL) {
    _states[worker_id] = new G1ParScanThreadState(_g1h, worker_id, _young_cset_length);
  }
  return _states[worker_id];
}
...
  G1ParScanThreadState的构造函数中：
_closures = G1EvacuationRootClosures::create_root_closures(this, _g1h);
...
最后：
G1EvacuationRootClosures* G1EvacuationRootClosures::create_root_closures(G1ParScanThreadState* pss, G1CollectedHeap* g1h) {
  G1EvacuationRootClosures* res = NULL;
  if (g1h->collector_state()->in_initial_mark_gc()) {
    if (ClassUnloadingWithConcurrentMark) {
      res = new G1InitialMarkClosures<G1MarkPromotedFromRoot>(g1h, pss);
    } else {
      res = new G1InitialMarkClosures<G1MarkFromRoot>(g1h, pss);
    }
  } else {
    res = new G1EvacuationClosures(g1h, pss, g1h->collector_state()->in_young_only_phase());
  }
  return res;
}
```

由于目前已经不是初始标记阶段了，所以这里返回的就是`G1EvacuationClosures`，在`G1EvacuationClosures`中：

```c++
OopClosure* strong_oops() { return &_closures._oops; }
...
G1ParCopyClosure<G1BarrierNone, Mark> _oops;
```

所以这里` closures->strong_oops()`最终返回的就是：`G1ParCopyClosure`

然后在`G1ParCopyClosure`的`do_oop`方法中：

```c++
 virtual void do_oop(oop* p)       { do_oop_work(p); }
```

所以最后看`G1ParCopyClosure`的`do_oop_work`方法：

```c++
void G1ParCopyClosure<barrier, do_mark_object>::do_oop_work(T* p) {
  T heap_oop = RawAccess<>::oop_load(p);

  if (CompressedOops::is_null(heap_oop)) {
    return;
  }
...
  const InCSetState state = _g1h->in_cset_state(obj);
  // 如果对象属于CSet
  if (state.is_in_cset()) {
    oop forwardee;
    markOop m = obj->mark_raw();
    // 如果已经复制过则直接返回复制后的新地址
    if (m->is_marked()) {
      forwardee = (oop) m->decode_pointer();
    } else {
      //否则将它复制到Survivor Region，返回新地址
      forwardee = _par_scan_state->copy_to_survivor_space(state, obj, m);
    }
    assert(forwardee != NULL, "forwardee should not be NULL");
    //修改根集中指向该对象的引用，指向Survivor中复制后的对象
    RawAccess<IS_NOT_NULL>::oop_store(p, forwardee);
    if (do_mark_object != G1MarkNone && forwardee != obj) {
      // If the object is self-forwarded we don't need to explicitly
      // mark it, the evacuation failure protocol will do so.
      mark_forwarded_object(obj, forwardee);
    }

  ...
}
```

这里我们看部分代码（实在是太多了，快写晕过去了），这里只用看核心代码：`copy_to_survivor_space`。它将 Eden Region 中年龄小于 15 的对象移动到 Survivor Region，年龄大于等于 15 的对象移动到 Old Region。之前根集中的引用指向 Eden Region 对象，对这些引用应用 G1ParCopyClosure 之后，Eden Region 的对象会被复制到 SurvivorRegion，所以根集的引用也需要相应改变指向：

![image-20230920155227979](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20230920155227979.png)



`copy_to_survivor_space` 在移动对象后还会用 `G1ScanEvacuatedObjClosure` 处理对象的成员，如果成员也属于 `CSet`，则将它们放入一个 `G1ParScanThreadState` 队列，等待第三阶段将它们复制到 `Survivor`。总结来说，第一阶段会将根集直接可达的对象复制到 `Survivor `，并将这些对象的成员放入队列，然后更新根集指向。这里复制region的分配涉及搭配PLAB（注意不是之前的TLAB）

>G1（Garbage-First）垃圾回收器中的PLAB（Partial Load Barrier）是一种用于提高垃圾回收性能的技术。PLAB 是一块固定大小的内存区域，用于存储应用程序线程在执行期间访问的对象引用。它的目的是减少垃圾回收器扫描的内存范围，从而提高扫描的效率。
>
>在 G1 垃圾回收器中，堆被划分为多个大小相等的区域（Region），每个区域都有一个对应的 PLAB。当应用程序线程需要分配对象时，它会从自己的 PLAB 中获取一部分空间来进行分配。这样做的好处是减少了线程与垃圾回收器之间的同步开销，因为每个线程都有自己的 PLAB，不会与其他线程发生竞争。
>
>PLAB 的大小是根据应用程序线程的分配行为和堆的状态进行动态调整的。如果一个线程的 PLAB 中的空间用尽，它将从共享的全局空闲列表中获取新的 PLAB。而当一个线程长时间没有分配对象时，它的 PLAB 的大小会减小，以释放不再需要的空间。
>
>通过使用 PLAB，G1 垃圾回收器能够更高效地进行扫描和标记存活对象的操作，减少了全局扫描的范围，提高了垃圾回收的吞吐量。同时，PLAB 还可以减少线程之间的同步开销，提高多线程并发垃圾回收的效率。

代码就不贴了，太多了，感觉甚至可以单独开一篇来讲了

处理虚拟机根对象引用的时候主要完成的是：

- 处理JVM内部使用的引用（Universe和SystemDictionary）
- 处理JNI句柄
- 处理对象锁的引用
- 处理java.lang.management管理和监控相关类的引用
- 处理JVMTI（JVM Tool Interface）的引用
- 处理AOT静态编译的引用

最后处理stringTable就是JVM字符串哈希表的引用，这里的stringTable就是我们字符串的intern方法进入的那个字符串常量池



#### 2、处理 RSet

我们看第二个逻辑：`oops_into_collection_set_do`:

```c++
void G1RemSet::oops_into_collection_set_do(G1ParScanThreadState* pss, uint worker_i) {
  update_rem_set(pss, worker_i);
  scan_rem_set(pss, worker_i);;
}
```

第一阶段标记了从 GC Root 到 eden 的对象，对于从老年代都到eden 的对象，则需要借助 RSet，这一步由 G1ParTask 的 G1RemSet::oops_into_collection_set_do 完成，它包括更新 RSet（update_rem_set）和扫描 RSet（scan_rem_set）两个过程。

scan_rem_set 遍历 CSet 中的所有 Region，找到引用者并将其作为起点开始标记存活对象。



#### 3、对象复制

经过前面的步骤后，YGC 发现的所有存活对象都会位于 G1ParScanThreadState 队列。对象复制负责将队列中的所有存活对象复制到 Survivor Region 或者晋升到 Old Region

```c++
G1ParEvacuateFollowersClosure evac(_g1h, pss, _queues, &_terminator);
evac.do_void();
```

然后：

```c++
void G1ParEvacuateFollowersClosure::do_void() {
  G1ParScanThreadState* const pss = par_scan_state();
  pss->trim_queue();
  do {
    pss->steal_and_trim_queue(queues());
  } while (!offer_termination());
}
```

接下来：

```c++
void G1ParScanThreadState::steal_and_trim_queue(RefToScanQueueSet *task_queues) {
  StarTask stolen_task;
  while (task_queues->steal(_worker_id, &_hash_seed, stolen_task)) {
    assert(verify_task(stolen_task), "sanity");
    dispatch_reference(stolen_task);

    // We've just processed a reference and we might have made
    // available new entries on the queues. So we have to make sure
    // we drain the queues as necessary.
    trim_queue();
  }
}
...
inline void G1ParScanThreadState::dispatch_reference(StarTask ref) {
  assert(verify_task(ref), "sanity");
  if (ref.is_narrow()) {
    deal_with_reference((narrowOop*)ref);
  } else {
    deal_with_reference((oop*)ref);
  }
}
...
inline void G1ParScanThreadState::deal_with_reference(oop* ref_to_scan) {
  if (!has_partial_array_mask(ref_to_scan)) {
    do_oop_evac(ref_to_scan);
  } else {
    do_oop_partial_array(ref_to_scan);
  }
}

```

最后我们只用关注`do_oop_evac`即可:

```c++
template <class T> void G1ParScanThreadState::do_oop_evac(T* p) {
  // // 只复制位于CSet的存活对象
  oop obj = RawAccess<IS_NOT_NULL>::oop_load(p);

  // Although we never intentionally push references outside of the collection
  // set, due to (benign) races in the claim mechanism during RSet scanning more
  // than one thread might claim the same card. So the same card may be
  // processed multiple times, and so we might get references into old gen here.
  // So we need to redo this check.
  const InCSetState in_cset_state = _g1h->in_cset_state(obj);
  if (in_cset_state.is_in_cset()) {
    //将对象复制到Survivor Region（或晋升到Old Region）
    markOop m = obj->mark_raw();
    if (m->is_marked()) {
      obj = (oop) m->decode_pointer();
    } else {
      obj = copy_to_survivor_space(in_cset_state, obj, m);
    }
    RawAccess<IS_NOT_NULL>::oop_store(p, obj);
  } else if (in_cset_state.is_humongous()) {
    _g1h->set_humongous_is_live(obj);
  } else {
    assert(in_cset_state.is_default(),
           "In_cset_state must be NotInCSet here, but is " CSETSTATE_FORMAT, in_cset_state.value());
  }

  assert(obj != NULL, "Must be");
  // 如果复制后的Region和复制前的Region相同，直接返回
  if (!HeapRegion::is_in_same_region(p, obj)) {
    // 如果复制前Region是老年代，现在复制到Survivor/Old Region，
    HeapRegion* from = _g1h->heap_region_containing(p);
    // 则会产生跨代引用，需要更新RSet
    update_rs(from, p, obj);
  }
}
```

这里我们可以认为就是实现八股中YGC的复制算法的地方了。对象复制是 YGC 的最后一步，在这之后新生代所有存活对象都被移动到 Survivor Region 或者晋升到 Old Region，之前的 Eden 空间可以被回收（Reclaim）。另外，YGC 复制算法相当于做了一次堆碎片的清理工作，如整理 Eden Region 可能存在的碎片。

总之YGC核心就是`G1ParTask`。和YGC相关的操作都是在里面完成的。



## 3、收尾工作

至此youngGC的停顿还没有解除，还需要做一些收尾工作：

```c++
  ......

        //释放清理重建cset,surviving_young_words
        free_collection_set(g1_policy()->collection_set(), evacuation_info);
        g1_policy()->clear_collection_set();
        cleanup_surviving_young_words();
        g1_policy()->start_incremental_cset_building();
        clear_cset_fast_test();
        //重置youngLis
        _young_list->reset_sampled_info();
        _young_list->reset_auxilary_lists();

        .....
        //初始化Eden区申请region
        init_mutator_alloc_region();

        {
          //扩容策略
          size_t expand_bytes = g1_policy()->expansion_amount();
          if (expand_bytes > 0) {
            size_t bytes_before = capacity();
            if (!expand(expand_bytes)) {
             ......
            }
          }
        }
        ...
 
  return true;
```

这里就选一些简单的代码列一下，主要是对YGC中用到一些集合进行重置，计算扩容策略,记录统计和日志信息等等，具体就不进行展开。有兴趣的读者可以自行翻阅代码学习。



## 4、总结

YGC代码非常非常复杂，也很多，如果要想全部理清楚看完既没必要也很复杂，我们可以简单的把YGC分为五个阶段：

1.扫描根节点 

2.更新Rset 

3.处理Rset 

4.复制对象 

5.处理引用

也就是在G1的GC中都是对Rset做处理的，这个是之前不太了解的，所有的YGC操作都是围绕着YGC进行的。

虽然可以简单归纳但是很多细节并不是那么简单。比如根节点引用的对象是先复制，之后再处理跨代引用的对象赋值，再比如更新和处理Rset其实就是先将其加入一个queue中，之后再一起复制等等，就像笔者再看源码以前，一直以为进入Rset中的card都是脏card,其实进入Rset中的card本质是脏card但是其脏的标记已经被清除。

本期读者读到后面也是一头雾水，慢慢理才有了一点头绪，核心复制算法的内容实在是太多了所以讲的比较简略，感觉也没必要单独开一期了，G1的内容应该就到此为止了，所以读者读不懂也没关系，只要知道YGC有个Cset用来回收，然后YGC只是一个task进行回收，就行了
