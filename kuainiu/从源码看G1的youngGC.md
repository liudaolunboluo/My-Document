# 从源码看G1的young GC（一）

关于G1我们已经写了两篇了，分别介绍了G1的年轻代的伸缩、G1新对象的分配，当然了还有一个和G1关系不大不小的TLAB，由于最近笔者都在忙着写需求(react)和帮机器学习的同事搞新服务和新框架（python），没有什么其他的灵感，于是这个月的文章继续水一篇G1的文章，今天就让我们来看看G1的young GC。不过由于G1的YGC特别多，所以笔者初步会分成两篇来讲。本篇文章主要是讲什么时候会触发YGC以及怎么触发YGC。

提示：本篇文章需要前面三篇文章的基础——《从G1源码来看young GC什么时候发生》、《G1新对象分配源码分析》、《从jvm源码看TLAB》

## 什么时候触发YGC

我们在以前的文章——《从G1源码来看young GC什么时候发生》的时候已经通过源码知道了，G1什么是时候发生young gc，暨：

> G1的young gc触发的时机要分为两种情况：
>
> 1、如果没有开启自适应策略，那么就是eden区满了之后立即开始young gc
>
> 2、如果开启了自适应策略，YoungGC并不是Eden区放满了就会马上触发，而是会去判断当前新生代是否可以扩充，如果可以则直接新建新生代的region不会发生young gc，如果不可以则young gc，而能不能扩充取决于上一次young gc中对于可停顿预测模型的计算的结果来的

在本篇文章我们可以简单归纳为eden区满了就进行young gc。

那么根据以前的文章G1的新对象分配和TLAB我们可以得知，JVM在新分配对象的时候有两种方式：TLAB和直接在堆上分配：

```c++
HeapWord* MemAllocator::mem_allocate(Allocation& allocation) const {
  if (UseTLAB) {
    HeapWord* result = allocate_inside_tlab(allocation);
    if (result != NULL) {
      return result;
    }
  }
  return allocate_outside_tlab(allocation);
}
```

根据我们前面两篇文章TLAB和G1对象分配的内容可以得知，这两种分配的方法走到后面的情况应该是：

1、如果是TLAB分配，如果TLAB需要新建（如果是已存在的TLAB，内存空间已经申请好了，不存在触发young gc的情况），那么最后就是在`memAllocator.cpp`中：

```
//创建新的TLAB
mem = _heap->allocate_new_tlab(min_tlab_size, new_tlab_size, &allocation._allocated_tlab_size);
```

这里根据每个GC来调用不同的，我们这里是G1，所以`allocate_new_tlab`方法实际上就是调用的是`g1CollectedHeap.cpp`：

```c++
HeapWord* G1CollectedHeap::allocate_new_tlab(size_t min_size,
                                             size_t requested_size,
                                             size_t* actual_size) {
  assert_heap_not_locked_and_not_at_safepoint();
  assert(!is_humongous(requested_size), "we do not allow humongous TLABs");

  return attempt_allocation(min_size, requested_size, actual_size);
}
```

2、如果是在TLAB之外分配就是在`memAllocator.cpp`中：

```c++
HeapWord* mem = _heap->mem_allocate(_word_size, &allocation._overhead_limit_exceeded);
```

这里和上文一样调用的`g1CollectedHeap.cpp`：

```c++
HeapWord*
G1CollectedHeap::mem_allocate(size_t word_size,
                              bool*  gc_overhead_limit_was_exceeded) {
  assert_heap_not_locked_and_not_at_safepoint();

  if (is_humongous(word_size)) {
    return attempt_allocation_humongous(word_size);
  }
  size_t dummy = 0;
  return attempt_allocation(word_size, word_size, &dummy);
}
```

所以这里分配新对象的时候两种情况都指向了一个方法：`attempt_allocation`（这里还有一个大对象分配，但是大对象直接进老年代也就是分配在H region上和我们本文的young gc没有关系）

在前面的文章G1的新对象分配的时候这个方法我们讲过，不熟悉的读者可以去复习这篇文章，`attempt_allocation`方法有两种情况，直接分配和慢速分配，慢速分配是在直接分配失败之后的，这里直接看慢速分配就行了：

```c++
HeapWord* G1CollectedHeap::attempt_allocation_slow(size_t word_size) {

  //不成功，如果尝试分配次数达到阈值（默认值是2次）则返回失败。
  for (uint try_count = 1, gclocker_retry_count = 0; /* we'll return */; try_count += 1) {
    bool should_try_gc;
    uint gc_count_before;

    {
      MutexLockerEx x(Heap_lock);
      //首先尝试对堆分区进行加锁分配，成功则返回，
      result = _allocator->attempt_allocation_locked(word_size);
      if (result != NULL) {
        return result;
      }

      if (GCLocker::is_active_and_needs_gc() && g1_policy()->can_expand_young_list()) {
        // No need for an ergo message here, can_expand_young_list() does this when
        // it returns true.
        //不成功，则判定是否可以对新生代分区进行扩展，如果可以扩展则扩展后再分配TLAB，成功则返回
        result = _allocator->attempt_allocation_force(word_size);
        if (result != NULL) {
          return result;
        }
    ...

    if (should_try_gc) {
      bool succeeded;
      //不成功，判定是否可以进行垃圾回收，如果可以进行垃圾回收后再分配，成功则返回，这句代码也就是执行youngGC的地方
      result = do_collection_pause(word_size, gc_count_before, &succeeded,
                                   GCCause::_g1_inc_collection_pause);
    ...
```

这里就是我们触发YGC的时刻，也就是对象分配失败且年轻代不会再变化了之后。



## young GC如何触发

我们直接看`do_collection_pause`方法：

```c++
HeapWord* G1CollectedHeap::do_collection_pause(size_t word_size,
                                               uint gc_count_before,
                                               bool* succeeded,
                                               GCCause::Cause gc_cause) {
  //安全点确认
  assert_heap_not_locked_and_not_at_safepoint();
  VM_G1CollectForAllocation op(word_size,
                               gc_count_before,
                               gc_cause,
                               false, 
                               g1_policy()->max_pause_time_ms());
  VMThread::execute(&op);

  HeapWord* result = op.result();
  bool ret_succeeded = op.prologue_succeeded() && op.pause_succeeded();
  assert(result == NULL || ret_succeeded,
         "the result should be NULL if the VM did not succeed");
  *succeeded = ret_succeeded;

  assert_heap_not_locked();
  return result;
}
```
其中：
```c++
 VM_G1CollectForAllocation op(word_size, //待分配的对象大小
                               gc_count_before, //之前的GC次数，用来计数的
                               gc_cause,//GC原因
                               false, //是否老年代并发收集，我觉得可以理解为是否mix gc
                               g1_policy()->max_pause_time_ms()// G1 垃圾回收策略的对象，
                             );
```

这一步就是在初始化我们的GC操作类，首先我们来看一下构造参数然后再来介绍一下这个GC操作类

上面的参数中，GC原因这个参数是维护在一个枚举：`gcCause.hpp`里的：

```c++
enum Cause {
    /* public */
    _java_lang_system_gc,
    _full_gc_alot,
    _scavenge_alot,
    _allocation_profiler,
    _jvmti_force_gc,
    _gc_locker,
    _heap_inspection,
    _heap_dump,
    _wb_young_gc,
    _wb_conc_mark,
    _wb_full_gc,
    ...
```

可以看出这里就是造成GC的原因，这里是`GCCause::_g1_inc_collection_pause`即G1的时候若分配不下触发的GC的cause

` g1_policy()->max_pause_time_ms()`就是获取当前 G1 垃圾回收器的最大暂停时间的设置值（以毫秒为单位）。这个值可以用于监控和调整垃圾回收器的行为，以满足应用程序的性能和响应时间需求，这个会给G1的可停顿预测模型使用。

然后我们再来介绍一下hotspot的重点知识点——`VM_Operation`,首先在hotspot中有关于jvm的操作类：`VM_Operation`，在 HotSpot 虚拟机中，`VM_Operation` 是一种表示虚拟机操作的概念。它用于表示在虚拟机内部执行的一些重要操作，例如垃圾回收、类加载、编译等。`VM_Operation` 是一个抽象类，它的子类表示不同类型的虚拟机操作。`VM_Operation` 的子类包含了各种虚拟机操作的具体实现。每个子类都提供了执行虚拟机操作所需的逻辑和行为。这些操作通常是在虚拟机的特权模式下执行的，因此具有更高的权限和访问级别。`VM_Operation`的类别大概有四种：

```c++
class VM_Operation: public CHeapObj<mtInternal> {
 public:
  enum Mode {
    _safepoint,       // blocking,        safepoint, vm_op C-heap allocated
    _no_safepoint,    // blocking,     no safepoint, vm_op C-Heap allocated
    _concurrent,      // non-blocking, no safepoint, vm_op C-Heap allocated
    _async_safepoint  // non-blocking,    safepoint, vm_op C-Heap allocated
  };
```

可以看到这里四种分别为：安全点、不是安全点、并发、异步安全点，其中安全点和不是安全点两种都是阻塞的，这里`VM_Operation`的默认模式是：

```c++
  virtual Mode evaluation_mode() const            { return _safepoint; }
```

这里定义在虚拟函数中，也就是默认的就是安全点，如果子类没有重写那么默认就是属于安全点。



这里代码中的是`VM_G1CollectForAllocation`，可以在源码中看到他是继承自`VM_CollectForAllocation`的，`VM_CollectForAllocation`又继承自`VM_GC_Operation`，也就是说这个操作类就是负责GC的，这里`VM_GC_Operation`就是`VM_Operation`的子类代表所有的GC操作，所以`VM_CollectForAllocation`和其子类都是内存分配失败触发垃圾回收，然后尝试分配内存的操作(看名字就是知道了，为了分配收集)，所以我们可以理解为`VM_G1CollectForAllocation`就是G1的 GC的操作类：

```c++
class VM_G1CollectForAllocation: public VM_CollectForAllocation {
private:
  bool      _pause_succeeded; //回收成功的标志

  bool         _should_initiate_conc_mark; //本次gc是不是老年代并发gc，也就是通常所说的mix gc
  bool         _should_retry_gc; //是否应该重试gc
  double       _target_pause_time_ms; //暂停时间，应该是用来可预测停顿模型的
  uint         _old_marking_cycles_completed_before; //以前完成的旧标记周期
public:
  VM_G1CollectForAllocation(size_t         word_size,
                            uint           gc_count_before,
                            GCCause::Cause gc_cause,
                            bool           should_initiate_conc_mark,
                            double         target_pause_time_ms);
  virtual VMOp_Type type() const { return VMOp_G1CollectForAllocation; }
  virtual bool doit_prologue(); //重写VM_Operation的方法，表示执行之前的一些准备工作
  virtual void doit(); //重写VM_Operation的方法，真正的操作类的逻辑
  virtual void doit_epilogue();//完成之后的一些操作
  virtual const char* name() const {
    return "G1 collect for allocation";
  }
  bool should_retry_gc() const { return _should_retry_gc; }
  bool pause_succeeded() { return _pause_succeeded; }
};
```

紧接着就是执行这个GC操作类：

```c++
 VMThread::execute(&op);
```

VM_Thread 就是大家平时说的 JVM线程，只有一个实例，也就是虚拟机创建过程中只会被创建一次（C++层面），并且在虚拟机销毁的时候被销毁。具体的作用是 开启一个无限循环（while (true)）, 然后不断地从一个 VM_Operation 队列中取出 VM_Operation 并且执行，如果没有 VM_Operation 就等待一会

所以这里的作用就是保存到VMThread的队列中，以便后面执行，这里还有个情况是每个线程都可以通过 `VMThread::execute `把 `VM_Operation` 放入队列,但是实际上有些时候只用一个入队就够了,比如这里的GC操作可能会被多个Java线程入队，但实际只用GC一次就够了

具体execute内部代码就不用看了，根据基本知识，vm操作类有三个和操作流程有关的方法：

- doit：具体执行VM_Operation的方法，通过evaluate方法调用，子类不能改写evaluate方法的实现
- doit_prologue：用于执行准备工作，当Java线程调用`VMThread::execute((VM_Operation*)`执行某个`VM_Operation`时会先执行doit_prologue，如果该方法返回true才会执行evaluate方法，否则被取消不执行，在代码中evaluate是负责执行doit方法的，并且子类不能重写，他的逻辑就在父类——VM_Operation中。
- doit_epilogue：用于执行某些依赖于VM_Operation执行结果的动作，当VM_Operation执行完成，Java线程会调用doit_epilogue方法一次。

所以我们重点只需要关注`VM_G1CollectForAllocation`的doit方法就好了。也就是说我们young gc的核心逻辑代码就在我们的`VM_G1CollectForAllocation`的doit方法中。所以我们直接看`VM_G1CollectForAllocation`的doit方法就行了：

```c++
void VM_G1CollectForAllocation::doit() {
  //获取当前G1的堆
  G1CollectedHeap* g1h = G1CollectedHeap::heap();
  assert(!_should_initiate_conc_mark || g1h->should_do_concurrent_full_gc(_gc_cause),
      "only a GC locker, a System.gc(), stats update, whitebox, or a hum allocation induced GC should start a cycle");
	//再次尝试申请内存`
  if (_word_size > 0) {
    // An allocation has been requested. So, try to do that first.
    //在安全点申请，关于安全点可以自行了解
    _result = g1h->attempt_allocation_at_safepoint(_word_size,
                                                   false /* expect_null_cur_alloc_region */);
    if (_result != NULL) {
      //如果能在暂停之前申请到内存，那么也认为本次暂停是成功，笔者认为这里的暂停可以认为就是YGC
      _pause_succeeded = true;
      return;
    }
  }

  GCCauseSetter x(g1h, _gc_cause);
  //这里是不是老年代并发GC，由于本次的重点是youngGC所以就暂且跳过
  if (_should_initiate_conc_mark) {
   ...
  }

  // 在安全点暂停的回收，这里就是我们核心的GC方法
  _pause_succeeded = g1h->do_collection_pause_at_safepoint(_target_pause_time_ms);

  //回收成功
  if (_pause_succeeded) {
    if (_word_size > 0) {
      //回收完毕之后尝试分配
      _result = g1h->satisfy_failed_allocation(_word_size, &_pause_succeeded);
    }
    //这里外部对象大小为0的情况就是外部故意传0，以此来触发full gc
    else {
      //full gc
      bool should_upgrade_to_full = !g1h->should_do_concurrent_full_gc(_gc_cause) &&
                                    !g1h->has_regions_left_for_allocation();
      if (should_upgrade_to_full) {
        // There has been a request to perform a GC to free some space. We have no
        // information on how much memory has been asked for. In case there are
        // absolutely no regions left to allocate into, do a maximally compacting full GC.
        log_info(gc, ergo)("Attempting maximally compacting collection");
        _pause_succeeded = g1h->do_full_collection(false, /* explicit gc */
                                                   true   /* clear_all_soft_refs */);
      }
    }
    guarantee(_pause_succeeded, "Elevated collections during the safepoint must always succeed.");
  } else {
    //GC失败
    assert(_result == NULL, "invariant");
    //GC不成功的唯一原因是，GC 锁定器处于活动状态（或在执行序幕后处于活动状态）。
    //处于活动状态（或在执行序章后处于活动状态）。在这种情况下
    //我们应该在等待 GC locker 处于非活动状态后再重试暂停。
    _should_retry_gc = true;
  }
}
```

所以在doit方法内的逻辑就是：

1、先尝试再次分配内存

2、是否执行老年代并发GC(mix gc)

3、执行YGC

4、尝试分配对象

5、如果外部传入的word_size是0那么久看是否能触发full gc，也就是说操作类初始化的时候如果待分配的大小为0就是触发full GC。

6、如果GC失败则等待重试

这里核心的GC代码就是：

```c++
_pause_succeeded = g1h->do_collection_pause_at_safepoint(_target_pause_time_ms);
```

可以看到这里代码又回到了我们的`g1CollectedHeap.cpp`中来了



## 总结

1、TLAB分配和TLAB外分配空间不足的时候会触发young gc

2、young gc是由`VM_G1CollectForAllocation`操作类负责的，hotspot的JVM操作类的基类是：`VM_Operation`，他有若干个子类分别代表执行不同的操作例如编译、GC等，这里`VM_G1CollectForAllocation`的继承关系是：

`VM_G1CollectForAllocation -> VM_CollectForAllocation -> VM_GC_Operation -> VM_Operation`

3、`VMThread::execute`是让jvm操作类得到执行的，这里用了生产消费者模型，需要执行的操作类放到链表中，VMThread表示一个JVM线程，没事的时候就自旋等待任务，有操作类来了就执行操作类（有点java线程池那个味道了），先执行doit_prologue为true的话就入队，然后等到被执行，执行的时候就是执行父类VM_Operation的evaluate方法，最后在evaluate中调用doit方法来执行操作类的核心逻辑

4、在doit方法中，会再次尝试再次分配内存然后是否需要mix gc，然后再是YGC，如果初始化的G1 GC操作类的分配对象大小为0则代表需要触发full gc，最后执行GC的代码就是：

```c++
g1h->do_collection_pause_at_safepoint(_target_pause_time_ms);
```



下一篇我们会从源码详细分析G1 的YGC过程，以前背过的很多八股老演员什么gc root、引用类型、三色标记法、GC算法等等都会出现只不过是以源码的方式出现。
