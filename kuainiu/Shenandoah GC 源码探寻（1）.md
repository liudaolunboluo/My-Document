# Shenandoah GC 源码探寻（1）——新对象分配

在上一篇文章中，我们简单介绍了一下jdk17新推出的Shenandoah GC的基本概念和一些参数，下面我们就从源码角度再来深入的介绍一下Shenandoah GC，本文源码基于JDK21，作者会从新对象分配、GC大概这两个角度来简单介绍一下Shenandoah GC的源码，本文会先从新对象分配入手



## 1、对象在堆上的分配

在笔者之前的文章：[G1新对象分配源码分析](https://mp.weixin.qq.com/s/w8FWloLhgEnobHEViRz5Jg)中，详细介绍了新对象分配的源码路径，这里不多赘述，不太清楚的读者可以阅读这篇文章。

这里简单复习一下：在不考虑TLAB分配的情况下，我们新对象的分配的源码路径是从`bytecodeInterpreter.cpp`中开始的，先是对字节码的解释执行：

```c++
 CASE(_new): {
        u2 index = Bytes::get_Java_u2(pc+1);
        ConstantPool* constants = istate->method()->constants();
        if (UseTLAB && !constants->tag_at(index).is_unresolved_klass()) {
         //tlab逻辑
          ...
            }
          }
        }
        // Slow case allocation
        CALL_VM(InterpreterRuntime::_new(THREAD, METHOD->constants(), index),
                handle_exception);
       ...
      }
```

然后就是到`interpreterRuntime.cpp`的`_new`方法中，在这里会完成klass的处理以及计算新对象的大小，然后有TLAB逻辑，在不考虑TLAB分配（其实TLAB分配最后也是走堆的分配，不过涉及到了TLAB的逻辑链路，就说复杂了，所以从直观的角度来说我们可以先忽略TLAB就简单粗暴的从对象在堆上的直接分配看起，至于为什么不走TLAB分配而是直接去堆上分配笔者关于tlab的文章:[从jvm源码看TLAB](https://mp.weixin.qq.com/s/a0-kB4vwsZYlkNf0YvXLig) ；其实就是：如果当前大小大于最大浪费大小，则不丢弃这个TLAB，回到堆上的去分配）的情况下，最后新分配对象的代码就是在`memAllocator.cpp`中：

```c++
HeapWord* MemAllocator::mem_allocate_outside_tlab(Allocation& allocation) const {
  allocation._allocated_outside_tlab = true;
  HeapWord* mem = Universe::heap()->mem_allocate(_word_size, &allocation._overhead_limit_exceeded);
  if (mem == nullptr) {
    return mem;
  }

  size_t size_in_bytes = _word_size * HeapWordSize;
  _thread->incr_allocated_bytes(size_in_bytes);

  return mem;
}
```

这里重点就在` HeapWord* mem = Universe::heap()->mem_allocate`，在笔者的[如何自己手动实现一个JVM GC](https://mp.weixin.qq.com/s/-mML0oQwCGPmoCLA7YWhRQ)中可以看到这个`heap()`其实就是当前jvm的堆实现，当前我们使用的是Shenandoa，所以自然而然我们就去看`shenandoahHeap.cpp`:

```c++
HeapWord *ShenandoahHeap::mem_allocate(size_t
size,
bool *gc_overhead_limit_was_exceeded
) {
ShenandoahAllocRequest req = ShenandoahAllocRequest::for_shared(size);
return
allocate_memory(req);
}
```

可以看到就是封装了一个分配请求参数，然后调用了`allocate_memory`,所以我们直接看`allocate_memory`:
```c++
HeapWord *ShenandoahHeap::allocate_memory(ShenandoahAllocRequest &req) {
    intptr_t pacer_epoch = 0;
    bool in_new_region = false;
    HeapWord *result = nullptr;
    //如果请求是由 mutator（即应用线程，与垃圾收集线程相对）发起的
    if (req.is_mutator_alloc()) {
        //如果启用了-XX:+ShenandoahPacing来控制分配速率
        if (ShenandoahPacing) {
            pacer()->pace_for_alloc(req.size());
            pacer_epoch = pacer()->epoch();
        }
        //测试用
        if (!ShenandoahAllocFailureALot || !should_inject_alloc_failure()) {
            result = allocate_memory_under_lock(req, in_new_region);
        }

        size_t tries = 0;
        //如果是才进行了GC那么就会尝试重新分配内存直到成功为止
        while (result == nullptr && _progress_last_gc.is_set()) {
            tries++;
            control_thread()->handle_alloc_failure(req);
            result = allocate_memory_under_lock(req, in_new_region);
        }
        //尝试分配内存。如果分配失败，那么会尝试多次，直到成功或者达到全局分配失败阈值。在每次尝试之间，都会调用 handle_alloc_failure 方法来处理分配失败。
        while (result == nullptr && tries <= ShenandoahFullGCThreshold) {
            tries++;
            control_thread()->handle_alloc_failure(req);
            result = allocate_memory_under_lock(req, in_new_region);
        }

    } else {
        //如果是GC线程直接分配
        result = allocate_memory_under_lock(req, in_new_region);
    }
    //如果在新的区域分配了内存，那么需要通知控制线程堆已经改变。
    if (in_new_region) {
        control_thread()->notify_heap_changed();
    }
    //如果分配成功，那么需要进行一些后处理。例如，如果请求是由 mutator 发起的，那么需要调用 notify_mutator_alloc_words 方法通知 mutator 已经分配了一些内存。如果请求的内存大小大于实际分配的内存大小，那么需要调用 unpace_for_alloc 方法给 pacer 归还多余的内存
    if (result != nullptr) {
        size_t requested = req.size();
        size_t actual = req.actual_size();
        if (req.is_mutator_alloc()) {
            notify_mutator_alloc_words(actual, false);

            // If we requested more than we were granted, give the rest back to pacer.
            // This only matters if we are in the same pacing epoch: do not try to unpace
            // over the budget for the other phase.
            if (ShenandoahPacing && (pacer_epoch > 0) && (requested > actual)) {
                pacer()->unpace_for_alloc(pacer_epoch, requested - actual);
            }
        } else {
            increase_used(actual * HeapWordSize);
        }
    }

    return result;
}
```

可以看到这其实就是：

1、根据是否是应用线程区分，GC线程直接分配，应用线程的话会重试分配和记录失败以及可能的控制分配速率

2、分配对象就是使用的`allocate_memory_under_lock`方法，如果是控制速率分配则是`pace_for_alloc`

所以这里分配对象的代码就是`pace_for_alloc`和`allocate_memory_under_lock`，我们先看`allocate_memory_under_lock`：

```c++
HeapWord *ShenandoahHeap::allocate_memory_under_lock(ShenandoahAllocRequest &req, bool &in_new_region) {
    ShenandoahHeapLocker locker(lock());
    return _free_set->allocate(req, in_new_region);
}
```

这里引申出一个`free set`的概念：

>在 ShenandoahGC 中，FreeSet 是一个特殊的数据结构，用于跟踪堆中的空闲内存块。FreeSet 记录了哪些内存块是空闲的，可以用于新的对象分配。在进行垃圾收集时，ShenandoahGC 会将无用的对象释放，这些空间会被添加回 FreeSet，以便在后续的对象分配中重复使用。
>
>这种方式可以帮助 ShenandoahGC 高效地管理内存，尤其是在需要快速分配和释放大量对象的应用中，如大数据处理和实时系统。通过这种方式，ShenandoahGC 能够在大堆中实现低延迟的垃圾收集，同时保持良好的吞吐量性能。

所以分配新对象肯定是在`FreeSet`中进行的，我们看`shenandoahFreeSet.cpp`:

```c++
HeapWord* ShenandoahFreeSet::allocate(ShenandoahAllocRequest& req, bool& in_new_region) {
  shenandoah_assert_heaplocked();
  assert_bounds();
	//分配的大小大于大对象阈值，就是大对象
  if (req.size() > ShenandoahHeapRegion::humongous_threshold_words()) {
    switch (req.type()) {
      case ShenandoahAllocRequest::_alloc_shared:
      case ShenandoahAllocRequest::_alloc_shared_gc:
         //普通大对象配连续的内存空间
        in_new_region = true;
        return allocate_contiguous(req);
      case ShenandoahAllocRequest::_alloc_gclab:
      case ShenandoahAllocRequest::_alloc_tlab:
         //普通大对象配连续的内存空间
        in_new_region = false;
        assert(false, "Trying to allocate TLAB larger than the humongous threshold: " SIZE_FORMAT " > " SIZE_FORMAT,
               req.size(), ShenandoahHeapRegion::humongous_threshold_words());
        return nullptr;
      default:
        ShouldNotReachHere();
        return nullptr;
    }
  } else {
    //普通对象直接分配
    return allocate_single(req, in_new_region);
  }
}
```

我们先看大对象的连续分配空间`allocate_contiguous`:

```c++
HeapWord *ShenandoahFreeSet::allocate_contiguous(ShenandoahAllocRequest &req) {
    shenandoah_assert_heaplocked();

    size_t words_size = req.size();
    //计算需要多少个区域region来满足分配对象的大小
    size_t num = ShenandoahHeapRegion::required_regions(words_size * HeapWordSize);

    //需要的数量大于可用的，表示无法分配返回null
    if (num > mutator_count()) {
        return nullptr;
    }

    //在 FreeSet 中找到一个连续的区域序列，这个序列的长度等于需要的region数量
    size_t beg = _mutator_leftmost;
    size_t end = beg;

    while (true) {
        if (end >= _max) {
            // 找到末尾了还没有连续的满足条件的region分配失败
            return nullptr;
        }


        // 如果不连续，则没有用，可以跳过。
        // 如果区域不完全空闲，即使连续也不能用，可以跳过。
        if (!is_mutator_free(end) || !can_allocate_from(_heap->get_region(end))) {
            end++;
            beg = end;
            continue;
        }
        //找到了连续区域且完全空闲区域
        if ((end - beg + 1) == num) {
            // found the match
            break;
        }
        //本次没有找到，偏移量加一继续找
        end++;
    };

    size_t remainder = words_size & ShenandoahHeapRegion::region_size_words_mask();

    // 初始化 regions:将这些区域标记为已用，设置他们的 top 指针，以及更新 FreeSet 的位图。
    for (size_t i = beg; i <= end; i++) {
        ShenandoahHeapRegion *r = _heap->get_region(i);
        try_recycle_trashed(r);

        assert(i == beg || _heap->get_region(i - 1)->index() + 1 == r->index(), "Should be contiguous");
        assert(r->is_empty(), "Should be empty");

        if (i == beg) {
            r->make_humongous_start();
        } else {
            r->make_humongous_cont();
        }

        // Trailing region may be non-full, record the remainder there
        size_t used_words;
        if ((i == end) && (remainder != 0)) {
            used_words = remainder;
        } else {
            used_words = ShenandoahHeapRegion::region_size_words();
        }

        r->set_top(r->bottom() + used_words);

        _mutator_free_bitmap.clear_bit(r->index());
    }

    //更新 FreeSet 的使用统计数据，调整 FreeSet 的边界
    increase_used(ShenandoahHeapRegion::region_size_bytes() * num);

    if (remainder != 0) {
        // Record this remainder as allocation waste
        _heap->notify_mutator_alloc_words(ShenandoahHeapRegion::region_size_words() - remainder, true);
    }

    // Allocated at left/rightmost? Move the bounds appropriately.
    if (beg == _mutator_leftmost || end == _mutator_rightmost) {
        adjust_bounds();
    }
    assert_bounds();

    req.set_actual_size(words_size);
    //返回分配的内存块的地址
    return _heap->get_region(beg)->bottom();
}

```

这里比较有意思的是`required_regions`也就是计算需要多个region来存放我们的大对象：

```c++
 inline static size_t required_regions(size_t bytes) {
    return (bytes + ShenandoahHeapRegion::region_size_bytes() - 1) >> ShenandoahHeapRegion::region_size_bytes_shift();
  }
```

 `bytes`表示需要分配的内存的字节大小。

`region_size_bytes` 返回每个 ShenandoahHeapRegion 区域的字节大小。

`region_size_bytes_shift()` 返回的是一个偏移量，它是基于 2 的对数，用于快速计算除法和取模。例如，如果每个区域的大小是 1024 字节（即 1KB），那么 `region_size_bytes_shift()` 就会返回 10。

整个计算过程是这样的：首先将 需要分配的大小`bytes` 加上一个区域的大小减一，然后右移 `region_size_bytes_shift()` 位。这个操作等价于将 `bytes` 除以每个区域的大小并向上取整，即计算出需要多少个区域来存储 `bytes` 大小的内存。

这种计算方式利用了位移操作的特性，提高了计算效率。同时，通过加上 `region_size_bytes() - 1`，实现了向上取整的效果，确保有足够的区域来存储 `bytes` 大小的内存。

总结来说大对象分配就是先计算需要的region数量，然后遍历freeset来找到空闲且连续且符合大小要求的region来分配。

然后我们看看普通对象分配

```c++
HeapWord *ShenandoahFreeSet::allocate_single(ShenandoahAllocRequest &req, bool &in_new_region) {
    switch (req.type()) {
        //如果是对象分配
        case ShenandoahAllocRequest::_alloc_tlab:
        case ShenandoahAllocRequest::_alloc_shared: {

            // Try to allocate in the mutator view
            //在应用视图
            //从左到右寻找可以分配的地方
            for (size_t idx = _mutator_leftmost; idx <= _mutator_rightmost; idx++) {
                if (is_mutator_free(idx)) {
                    HeapWord *result = try_allocate_in(_heap->get_region(idx), req, in_new_region);
                    if (result != nullptr) {
                        return result;
                    }
                }
            }

            // There is no recovery. Mutator does not touch collector view at all.
            break;
        }
        //如果是GC相关
        case ShenandoahAllocRequest::_alloc_gclab:
        case ShenandoahAllocRequest::_alloc_shared_gc: {
            // size_t is unsigned, need to dodge underflow when _leftmost = 0

            // Fast-path: try to allocate in the collector view first
            //在回收视图
            for (size_t c = _collector_rightmost + 1; c > _collector_leftmost; c--) {
                size_t idx = c - 1;
                if (is_collector_free(idx)) {
                    HeapWord *result = try_allocate_in(_heap->get_region(idx), req, in_new_region);
                    if (result != nullptr) {
                        return result;
                    }
                }
            }

            // No dice. Can we borrow space from mutator view?
            //如果配置了ShenandoahEvacReserveOverflow这个参数（默认为true）为false，则说明在GC的时候回收视图满了就不能向应用空间窃取空间了
            if (!ShenandoahEvacReserveOverflow) {
                return nullptr;
            }

            // Try to steal the empty region from the mutator view
            //可以向应用空间窃取
            for (size_t c = _mutator_rightmost + 1; c > _mutator_leftmost; c--) {
                size_t idx = c - 1;
                if (is_mutator_free(idx)) {
                    ShenandoahHeapRegion *r = _heap->get_region(idx);
                    if (can_allocate_from(r)) {
                        flip_to_gc(r);
                        HeapWord *result = try_allocate_in(r, req, in_new_region);
                        if (result != nullptr) {
                            return result;
                        }
                    }
                }
            }
            //还是分配失败就算了

            break;
        }
        default:
            //既不是应用分配也不是GC分配，那么就是异常情况
            ShouldNotReachHere();
    }
    return nullptr;
}
```

这里有两个点：

1、Shenandoah GC把堆分为了应用空间和回收空间，分别用来分配应用的对象和GC（GC有对象分配是因为涉及到对象的移动和复制），所以Shenandoah GC没有分代，只会把堆分为应用空间和回收空间

2、根据参数，GC分配也可能在应用空间上分配的

最后分配的代码就是`try_allocate_in`，会在传入一个空闲区域的region指针，和分配参数，`in_new_region`是外层传递的，目的是外层要判断这里是不是分配在新region上，然后做相应的处理：

```c++
   //如果在新的区域分配了内存，那么需要通知控制线程堆已经改变。
    if (in_new_region) {
        control_thread()->notify_heap_changed();
    }
```

然后我们来看看这个方法：

```c++
HeapWord *ShenandoahFreeSet::try_allocate_in(ShenandoahHeapRegion *r, ShenandoahAllocRequest &req, bool &in_new_region) {
    assert (!has_no_alloc_capacity(r), "Performance: should avoid full regions on this path: " SIZE_FORMAT, r->index());

    //堆正在执行并发弱根进程，并且给定的region被标定了是垃圾，分配失败
    if (_heap->is_concurrent_weak_root_in_progress() &&
        r->is_trash()) {
        return nullptr;
    }
    //如果region是垃圾区域则回收
    try_recycle_trashed(r);
    //如果region是空的则标记将本次分配标记为新regoin上分配
    in_new_region = r->is_empty();

    //分配的结果
    HeapWord *result = nullptr;
    //本次分配的大小
    size_t size = req.size();
    //如果使用了可变化的TLAB并且本次分配是tlab或者GCLAB分配
    //GCLAB（Garbage Collection Thread-Local Allocation Buffer）是一个用于垃圾收集线程的本地分配缓冲区。这是一个优化内存分配性能的技术，类似于 TLAB（Thread-Local Allocation Buffer），但是专门用于垃圾收集器线程。
    //在垃圾收集过程中，经常需要分配内存来存储临时数据，例如在对象复制或对象移动等操作中。如果每次分配都直接从堆中进行，可能会引发竞争和同步开销。为了提高分配效率，垃圾收集器线程可以使用 GCLAB 来进行快速、无锁的内存分配。
    if (ShenandoahElasticTLAB && req.is_lab_alloc()) {
        //向下对齐到最小对象对齐大小 MinObjAlignment，意思就是剩余大小和最小对象大小哪个小返回哪个,这是为了确保分配的内存块满足对象对齐的要求。
        size_t free = align_down(r->free() >> LogHeapWordSize, MinObjAlignment);
        //如果请求的大小大于了剩余大小，则设置size就是剩余大小，这样确保不会溢出
        if (size > free) {
            size = free;
        }
        //大于或等于请求的最小大小 那么尝试在region 中分配 size 大小的内存
        if (size >= req.min_size()) {
            result = r->allocate(size, req.type());
            assert (result != nullptr, "Allocation must succeed: free " SIZE_FORMAT ", actual " SIZE_FORMAT, free, size);
        }
    } else {
        //直接分配
        result = r->allocate(size, req.type());
    }
    //直接分配成功
    if (result != nullptr) {
        // Allocation successful, bump stats:
        //如果是应用区域分配
        if (req.is_mutator_alloc()) {
            //更新堆使用空间
            increase_used(size * HeapWordSize);
        }

        // Record actual allocation size
        //设置实际分配大小
        req.set_actual_size(size);
        //如果是GC分配
        if (req.is_gc_alloc()) {
            //设置更新水印
            r->set_update_watermark(r->top());
        }
    }
    //分配失败或者region没有足够的剩余空间则标记当前region为退役状态
    if (result == nullptr || has_no_alloc_capacity(r)) {
        // Region cannot afford this or future allocations. Retire it.
        //
        // While this seems a bit harsh, especially in the case when this large allocation does not
        // fit, but the next small one would, we are risking to inflate scan times when lots of
        // almost-full regions precede the fully-empty region where we want allocate the entire TLAB.
        // TODO: Record first fully-empty region, and use that for large allocations

        // Record the remainder as allocation waste
        //如果是应用分配
        if (req.is_mutator_alloc()) {
            //当前region剩余空间就是浪费空间
            size_t waste = r->free();
            //如果当前region还有剩余的空间
            if (waste > 0) {
                //虽然没有使用，也把大小添加到使用大小里，尽管这部分内存实际上没有被使用，但是由于该堆区域已经被标记为 "退役"，所以这部分内存将无法被未来的分配请求使用，因此被视为已经被 "使用"。
                increase_used(waste);
                //通知堆 waste 大小的内存已经被分配出去。这可能是为了更新相关的统计信息，例如已分配的内存量、未使用的内存量等。
                _heap->notify_mutator_alloc_words(waste >> LogHeapWordSize, true);
            }
        }
        //获取当前region的索引
        size_t num = r->index();
        //在收集器的空闲记录（free bitmap）中清除对应于当前堆区域的位。这表示该区域不再被视为空闲。
        _collector_free_bitmap.clear_bit(num);
        //在应用层的空闲记录（free bitmap）中清除对应于当前堆区域的位。这表示该区域不再被视为空闲。
        _mutator_free_bitmap.clear_bit(num);
        // Touched the bounds? Need to update:
        //如果当前region的索引 num 触及了空闲记录的边界（即 num 是空闲位图中的最大或最小位），那么调整边界。这是为了确保空闲记录的边界始终表示的是当前空闲的区域。
        if (touches_bounds(num)) {
            adjust_bounds();
        }
        assert_bounds();
    }
    return result;
}
```



信息量非常的大，简单总结就是根据当前region的剩余大小来进行分配，如果能直接分配就分配之，如果不行就把region设置为退役。不过这样做是有点问题的，比如说当前分配的大小没有达到大对象的要求，但是也比较大，然后这个region刚好没有剩余空间分配，但是如果是普通对象则可以分配，但是这样的话这个region也会被淘汰，并且有很多浪费空间，甚至极端情况下，这个region是空的，但是由于分配的对象比较大，导致整个region的浪费。所以作者给了一个todo：

```c++
// TODO: Record first fully-empty region, and use that for large allocations
```

记录一下空的region用来分配大对象。

然后最后在这里分配的代码就是

```c++
result = r->allocate(size, req.type());
```

也就是让region进行分配。

到这里堆分配的部分就完了，下面就是我们的region如何分配的。

在讲region如何分配之前，我们有必要详细介绍一下Shenandoah的region



## 2、Shenandoah的region

在官网的描述中Shenandoah是**regionalized GC**，也就是一个区域化的gc，这一点和G1是一样的，那么region就是我们Shenandoah的一个重要组成部分；（你看，在G1中笔者就没有详细的去介绍G1的region，后来笔者在总结的时候发现region是G1的重要组成部分，这是不能忽略的，所以在Shenandoah中的时候我们就要详细的了解一下Shenandoah的region）

首先region的代码在三个文件里：`shenandoahHeapRegion.hpp`、`shenandoahHeapRegion.cpp`、`shenandoahHeapRegion.inline.hpp`，在`shenandoahHeapRegion.hpp`有关于region状态的枚举：

```c++
enum RegionState {
  _empty_uncommitted,       // 区域是空的，并且内存未提交
  _empty_committed,         // 区域是空的，并且内存已提交
  _regular,                 // 区域用于常规分配
  _humongous_start,         // 区域是巨大对象的开始部分
  _humongous_cont,          // 区域是巨大对象的连续部分
  _pinned_humongous_start,  // 区域既是巨大对象的开始部分，也是固定的（不会被移动）
  _cset,                    // 区域在集合中（可能是垃圾收集的目标）
  _pinned,                  // 区域是固定的
  _pinned_cset,             // 区域是固定的并且在集合中（可能是垃圾收集的目标，但由于是固定的，所以不能移动）
  _trash,                   // ：区域只包含垃圾
  _REGION_STATES_NUM        // 这是一个特殊的值，表示状态的数量，通常用于数组的大小或循环的边界
};
```

这几种状态的流转，作者在代码注释里写的很清楚：

```c++
"Empty":
.................................................................
.                                                               .
.                                                               .
.         Uncommitted  <-------  Committed <------------------------\
.              |                     |                          .   |
.              \---------v-----------/                          .   |
.                        |                                      .   |
.........................|.......................................   |
                         |                                          |
"Active":                |                                          |
.........................|.......................................   |
.                        |                                      .   |
.      /-----------------^-------------------\                  .   |
.      |                                     |                  .   |
.      v                                     v    "Humongous":  .   |
.   Regular ---\-----\     ..................O................  .   |
.     |  ^     |     |     .                 |               .  .   |
.     |  |     |     |     .                 *---------\     .  .   |
.     v  |     |     |     .                 v         v     .  .   |
.    Pinned  Cset    |     .  HStart <--> H/Start   H/Cont   .  .   |
.       ^    / |     |     .  Pinned         v         |     .  .   |
.       |   /  |     |     .                 *<--------/     .  .   |
.       |  v   |     |     .                 |               .  .   |
.  CsetPinned  |     |     ..................O................  .   |
.              |     |                       |                  .   |
.              \-----\---v-------------------/                  .   |
.                        |                                      .   |
.........................|.......................................   |
                         |                                          |
"Trash":                 |                                          |
.........................|.......................................   |
.                        |                                      .   |
.                        v                                      .   |
.                      Trash ---------------------------------------/
.                                                               .
.                                                               .
.................................................................
```

`Uncommitted`或者`Committed`是属于空的状态，然后被激活的标志是变成常规分配的状态或者大对象分配，常规分配之后就是被被“钉住”，不能被移动或删除，可能是因为它正在被某个线程使用，使用完毕之后就会被标记为垃圾，等待回收，等待回收之后会放入Cset（收集集）中等待被回收，在收集集中的对象也会被钉住，不能移动，回收完毕之后又回到`Uncommitted`或者`Committed`的空状态中，等待下一次分配

所以region的生命周期就是从`Uncommitted`或者`Committed`开始的。

所以分配对象，就是region生命周期的起点，我们直接来看代码。

上文中我们知道，最终的分配代码是：

```c++
result = r->allocate(size, req.type());
```

也就是在region中的allocate方法：

```c++
HeapWord* ShenandoahHeapRegion::allocate(size_t size, ShenandoahAllocRequest::Type type) {
  //判断当前状态是否存在堆锁或者在安全点
  shenandoah_assert_heaplocked_or_safepoint();
  assert(is_object_aligned(size), "alloc size breaks alignment: " SIZE_FORMAT, size);
	//当前region的起点
  HeapWord* obj = top();
  //判断当前堆是否有足够的空间来进行当前分配
  if (pointer_delta(end(), obj) >= size) {
    //修改region的状态
    make_regular_allocation();
    //调整分配元数据
    adjust_alloc_metadata(type, size);
		//指针碰撞分配
    HeapWord* new_top = obj + size;
    set_top(new_top);
		//再次检查新的堆顶和分配的对象是否符合对齐要求
    assert(is_object_aligned(new_top), "new top breaks alignment: " PTR_FORMAT, p2i(new_top));
    assert(is_object_aligned(obj),     "obj is not aligned: "       PTR_FORMAT, p2i(obj));

    return obj;
  } else {
    //分配失败，返回空
    return nullptr;
  }
}
```

这里分配的代码很有意思，我们先看看状态改变：

```c++
void ShenandoahHeapRegion::make_regular_allocation() {
  shenandoah_assert_heaplocked();
 	//根据当前region的状态
  switch (_state) {
     //如果是空的未提交，就先提交内存
    case _empty_uncommitted:
      do_commit();
     //如果内存已经提交就修改状态为常规分配
    case _empty_committed:
      set_state(_regular);
    //如果已经是常规分配或者被钉住了，那么就返回
    case _regular:
    case _pinned:
      return;
    //异常情况
    default:
      report_illegal_transition("regular allocation");
  }
}
```

可以看到，这段代码和我们刚刚到region生命周期一致，这里` set_state(_regular);`就只是把状态简单改为常规分配，有意思的是上面的`do_commit();`也就是提交内存；

```c++
void ShenandoahHeapRegion::do_commit() {
  //获取当前堆
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  //RegionSizeBytes就是配置的region大小，如果当前堆的大小不足，就OOM
  if (!heap->is_heap_region_special() && !os::commit_memory((char *) bottom(), RegionSizeBytes, false)) {
    report_java_out_of_memory("Unable to commit region");
  }
  //更新相关位图，比如空闲位图，失败还是OOM
  if (!heap->commit_bitmap_slice(this)) {
    report_java_out_of_memory("Unable to commit bitmaps for region");
  }
  //如果配置了AlwaysPreTouch，则先就把内存加载好而不是虚拟内存
  if (AlwaysPreTouch) {
    os::pretouch_memory(bottom(), end(), heap->pretouch_heap_page_size());
  }
  //增加已提交的内存量的计数
  heap->increase_committed(ShenandoahHeapRegion::region_size_bytes());
}
```

可以看到这里我们之前非常眼熟的的`AlwaysPreTouch`参数，整个region提交其实就是申请内存，如果没有配置`AlwaysPreTouch`则是虚拟内存，然后更新整个堆堆内存数。

看到这里相信有读者很奇怪了，`make_regular_allocation`只是修改状态或者申请region的内存的话，那在哪里分配我们的新对象呢？其实就是这一行代码：

```c++
HeapWord* new_top = obj + size;
```

这行代码将top位置向上移动了 `size` ，这就相当于在堆中"预留"了 `size` 大小的空间。这个空间就是新分配的对象将要占用的内存。

这里的关键是：`obj` 指向的是分配开始的位置，而 `new_top` 指向的是分配结束后的位置。所以，`obj` 实际上是新分配对象的指针，因为它指向的是新分配的内存的开始位置。

然后下面的`set_top(new_top);` 这一行更新了堆的顶部位置，将其设置为新的 `new_top`，也就是说，下一次的分配将从这个新的位置开始。

这就是种称为"bump-the-pointer"（指针碰撞）的内存分配，这是一种非常高效的内存分配策略。在这种策略中，只需要将指针向上"碰"一下，就可以快速地分配一块连续的内存空间。



## 3、最终对象的分配

看完上文，其实都是内存的分配，也就是说上文只是在堆中划分了一块内存给当前堆对象， 那么对象最终是如何分配到堆上的呢？这就是要回朔到代码一开始的地方,在`memAllocator中`：

```c++
oop MemAllocator::allocate() const {
  oop obj = nullptr;
  {
    Allocation allocation(*this, &obj);
    HeapWord* mem = mem_allocate(allocation);
    if (mem != nullptr) {
      obj = initialize(mem);
    } else {
      // The unhandled oop detector will poison local variable obj,
      // so reset it to null if mem is null.
      obj = nullptr;
    }
  }
  return obj;
}
```

`mem_allocate`底层就是上文里讲解的地方，最后是返回了一个划分好的内存区域，然后这块区域划分好之后，就是是调用`initialize`方法把我们的对象分配好：

```c++
oop ObjAllocator::initialize(HeapWord* mem) const {
  mem_clear(mem);
  return finish(mem);
}
```
`mem_clear`就是清理一下内存

```c++
void MemAllocator::mem_clear(HeapWord* mem) const {
  assert(mem != nullptr, "cannot initialize null object");
  //获取对象头
  const size_t hs = oopDesc::header_size();
  assert(_word_size >= hs, "unexpected object size");
  //设置指向的内存的类空隙（可能是对象头和实际数据之间的空隙）为 0。
  oopDesc::set_klass_gap(mem, 0);
  //填充内存，加上对象头的大小
  Copy::fill_to_aligned_words(mem + hs, _word_size - hs);
}
```

重点在finish:

```c++
oop MemAllocator::finish(HeapWord* mem) const {
  assert(mem != nullptr, "null object pointer");
  // May be bootstrapping
  oopDesc::set_mark(mem, markWord::prototype());
  // Need a release store to ensure array/class length, mark word, and
  // object zeroing are visible before setting the klass non-null, for
  // concurrent collectors.
  oopDesc::release_set_klass(mem, _klass);
  return cast_to_oop(mem);
}
```

这里需要复习一下基础知识：

>JVM中，Klass代表一个Java类，oopDesc代表一个Java对象（其实只代表其头部信息），oop代表一个指向oopDesc的指针（即指向Java对象的指针）。

了解这个知识点，以上代码就很简单了，就是给对象头设置好指向的内存的标记，然后在这个内存区域上存储类型是`_klass`类型也就是我们的java类的类型的对象引用，然后把内存区域指针转位java对象指针返回，所以重点就是`release_set_klass`：

```c++
void oopDesc::release_set_klass(HeapWord* mem, Klass* k) {
  assert(Universe::is_bootstrapping() || (k != nullptr && k->is_klass()), "incorrect Klass");
  //设置内存位置，就是堆上内存位置加上kclass偏移量，我理解的是kclass本身会有一些内存空间来记录类信息
  char* raw_mem = ((char*)mem + klass_offset_in_bytes());
  //是否使用压缩指针
  if (UseCompressedClassPointers) {
    //使用一个原子的 release store 操作来把对象存储到指定内存中
    Atomic::release_store((narrowKlass*)raw_mem,
                          CompressedKlassPointers::encode_not_null(k));
  } else {
    Atomic::release_store((Klass**)raw_mem, k);
  }
}
```



最后实际上把对象存储到内存的操作就是：

```c++
 Atomic::release_store((Klass**)raw_mem, k);
```

其实也很简单对吧

## 4、总结

我们可以看到Shenandoah GC和G1从分配来说差不多，都是以region为单位，并且有专门的大对象region，不过区别是Shenandoah没有分代的概念（分代Shenandoah gc已经在开发的路上的了：https://openjdk.org/jeps/404）

然后我们可以看到堆执行的操作其实就是开辟一块内存给新的对象，最后把对象放入内存的操作还是jvm自己来完成的

下一篇文章笔者就会给大家介绍Shenandoah GC的垃圾回收过程，涉及到了Shenandoah GC核心的对象转发指针、SATB兵并发标记算法以及目的空间不变性涉及，大家敬请期待！
