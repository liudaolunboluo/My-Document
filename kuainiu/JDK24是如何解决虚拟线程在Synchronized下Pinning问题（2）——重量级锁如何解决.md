# JDK24是如何解决虚拟线程在Synchronized下Pinning问题（2）——重量级锁如何解决

上文我们说到的Synchronized在轻量级锁的情况下如何解决Pinning问题

>在jdk24的Synchronized轻量级锁中，用到了锁栈这个数据结构，会存放锁定对象的oop指针，锁栈里有这个对象的oop指针就说明这个对象被锁定了，然后会在对象的对象头上写有这个对象锁记录的指针，注意这里不会记录任何锁，只是记录这个对象被锁定了，然后被谁锁定的要通过锁栈来，然后在虚拟线程中，阻塞的时候会把锁栈和栈一起freeze到chunk中，被唤醒之后一起又thaw到新的平台线程中

这篇文章中，我们继续探索在重量级锁的情况下如何解决Pinning问题，我们还是先深入重量级锁源码探清楚重量级锁原理

然后再看怎么解决Pinning问题，这个顺序来



## 1、重量级锁上锁流程

在上文中，我们知道了从轻量级锁膨胀成重量级的几个条件：

- 如果锁栈内存在这个对象但是在非栈顶位置，也就是锁嵌套的情况
- 轻量级锁获取失败，膨胀为重量级锁

那么我们就从轻量级锁开始找到重量级锁的入口

上述两种情况的源码如下：

锁嵌套升级（重入）：

```c++
if (lock_stack.contains(obj())) {
    //膨胀为重量级锁但是不获取锁
    ObjectMonitor *monitor = inflate_fast_locked_object(obj(), ObjectSynchronizer::inflate_cause_monitor_enter, current, current);
    //使用monitor的enter方法获取控制器
    bool entered = monitor->enter(current);
  //这种应该是可重入情况，无论如何都应该成功
    assert(entered, "recursive ObjectMonitor::enter must succeed");
    // 在获取重量级锁锁成功后，将锁信息缓存到线程本地
    cache_setter.set_monitor(monitor);
    return;
}
```

这里注意了，上文中笔者没有解释完整，即使是锁嵌套但是他也是获取过锁的，证据就是lock_stack中有这个对象，所以就算膨胀为重量级锁也应该获取成功

轻量级锁获取失败，升级膨胀：

```c++
//锁膨胀：如果轻量级锁获取失败，膨胀为重量级锁
        ObjectMonitor *monitor = inflate_and_enter(obj(), ObjectSynchronizer::inflate_cause_monitor_enter, current, current);
        if (monitor != nullptr) {
            // 在获取重量级锁锁成功后，将锁信息缓存到线程本地
            cache_setter.set_monitor(monitor);
            return;
        }
```

### 1.1、先膨胀生成监控器然后获取锁

我们这里先看：先膨胀生成监控器然后获取锁:

```c++
ObjectMonitor *LightweightSynchronizer::inflate_fast_locked_object(oop object, ObjectSynchronizer::InflateCause cause, JavaThread *locking_thread,
                                                                   JavaThread *current) {
    assert(LockingMode == LM_LIGHTWEIGHT, "only used for lightweight");
    VerifyThreadState vts(locking_thread, current);
    //验证在锁栈中
    assert(locking_thread->lock_stack().contains(object), "locking_thread must have object on its lock stack");

    ObjectMonitor *monitor;
    //如果不使用监视器表
    if (!UseObjectMonitorTable) {
        return inflate_into_object_header(object, cause, locking_thread, current);
    }

    // Inflating requires a hash code
    //膨胀需要hash code，这里获取hash code是当前线程和对象一起
    ObjectSynchronizer::FastHashCode(current, object);

    markWord mark = object->mark_acquire();
    assert(!mark.is_unlocked(), "Cannot be unlocked");
    //通过自旋从监视器表获取或插入新的 ObjectMonitor
    for (;;) {
        // Fetch the monitor from the table
        //从监视器表中获取或插入监视器
        monitor = get_or_insert_monitor(object, current, cause);

        // ObjectMonitors are always inserted as anonymously owned, this thread is
        // the current holder of the monitor. So unless the entry is stale and
        // contains a deflating monitor it must be anonymously owned.
        //检查获取的监视器是否为匿名状态（即刚创建的），为什么刚创建的就是匿名呢？因为在监视器表的监视器肯定是匿名的，如果不是说明正在被释放，就等待
        if (monitor->has_anonymous_owner()) {
            // The monitor must be anonymously owned if it was added
            assert(monitor == get_monitor_from_table(current, object), "The monitor must be found");
            // New fresh monitor
            break;
        }

        // If the monitor was not anonymously owned then we got a deflating monitor
        // from the table. We need to let the deflator make progress and remove this
        // entry before we are allowed to add a new one.
        //如果不是匿名状态，说明监视器正在被释放，线程会让出 CPU 并等待然后进入下一次循环
        os::naked_yield();
        assert(monitor->is_being_async_deflated(), "Should be the reason");
    }

    // Set the mark word; loop to handle concurrent updates to other parts of the mark word
    //使用 CAS 操作更新对象头的标记字，将其设置为指向监视器的状态
    while (mark.is_fast_locked()) {
        mark = object->cas_set_mark(mark.set_has_monitor(), mark);
    }

    // Indicate that the monitor now has a known owner
    //设置监视器的所有者为当前锁定线程，注意这里
    monitor->set_owner_from_anonymous(locking_thread);

    // Remove the entry from the thread's lock stack
    //从锁栈中移除对象并设置递归计数
    monitor->set_recursions(locking_thread->lock_stack().remove(object) - 1);

    //更新线程本地缓存的监视器
    if (locking_thread == current) {
        // Only change the thread local state of the current thread.
        locking_thread->om_set_monitor_cache(monitor);
    }

    return monitor;
}
```

这里有两种情况，启用监视器表和不启用，关于监视器表在上篇文章中介绍过了，这里不再赘述你可以认为就是一个缓存控制器monitor的一个数据结构，如果启用则在监视器表中获取或者新增监视器，如果不启用则使用`inflate_into_object_header`来获取控制器。由于默认配置都是启用监视器表的所以这个方法我们这里不赘述了，只需要知道在这个方法里获取监视器是通过：

```c++
  ObjectMonitor *monitor = new ObjectMonitor(object);
```

创建一个新的，而不是通过监视器表来获取的，然后就是正常膨胀流程，最后cas设置一个owner：

```c++
if (own) {
    // Owned by locking_thread.
    monitor->set_owner(locking_thread);
}
...
void set_owner(JavaThread* thread) { set_owner_from(NO_OWNER, thread); }
```

注意这里和启用监视器表的情况一样，没有拥有者才会设置成功也就是用了一个cas，也就是说如果监视器被其他线程抢到了的话就争抢失败了

那么如果启用监视器表的话，怎么来获取呢：

```c++
ObjectMonitor *LightweightSynchronizer::get_or_insert_monitor_from_table(oop object, JavaThread *current, bool *inserted) {
    assert(LockingMode == LM_LIGHTWEIGHT, "must be");
    //先从表里获取这个对象的监视器
    ObjectMonitor *monitor = get_monitor_from_table(current, object);
    //存在直接返回，并且标记为非插入
    if (monitor != nullptr) {
        *inserted = false;
        return monitor;
    }
    //不存在就新建一个
    ObjectMonitor *alloced_monitor = new ObjectMonitor(object);
    //设置为匿名状态
    alloced_monitor->set_anonymous_owner();
    // Try insert monitor
    //添加到监视器表中
    monitor = add_monitor(current, alloced_monitor, object);
    *inserted = alloced_monitor == monitor;
    if (!*inserted) {
        delete alloced_monitor;
    }

    return monitor;
}
```

就是一个简单的获取没有就新建并且插入的情况，并且这里也解释了为什么从表中获取的监视器一定是匿名，因为新建的时候就设置了是匿名状态，也就是没有拥有者

无论是否启用监视器表，最后我们都可以通过`inflate_fast_locked_object`获取到一个监视器对象，如果运气好没有争抢的情况甚至可以直接拿到这个监视器的所有权，所以方法名中有一个`fast_locked` 快速锁定的意思，这里cas只会执行一次，如果失败了的话就会在下面的` bool entered = monitor->enter(current);`里去执行争抢等待锁的流程了：

```c++
bool ObjectMonitor::enter(JavaThread* current) {
  assert(current == JavaThread::current(), "must be");
  // 尝试自旋获取锁
  if (spin_enter(current)) {
    return true;
  }
...

  // Keep is_being_async_deflated stable across the rest of enter
    // 标记锁正在被竞争，防止锁降级
  ObjectMonitorContentionMark contention_mark(this);

  // Check for deflation.
  //是否在降级
  if (enter_is_async_deflating()) {
    return false;
  }

  // At this point this ObjectMonitor cannot be deflated, finish contended enter
  // 执行带竞争的锁获
  enter_with_contention_mark(current, contention_mark);
  return true;
}
```

熟悉笔者文章的朋友都知道，其实java的锁机制，无论是jdk的锁还是jvm的锁，一定都会有一套争抢失败的机制，在这个机制下就是先自旋然后阻塞等待锁巴拉巴拉的，那么这里也不例外，也是首先自旋，然后去竞争锁，那么我们看看是如何自旋的呢：

```c++
bool ObjectMonitor::spin_enter(JavaThread* current) {
  // 检查是否是重入锁
  if (try_enter(current)) {
    return true;
  }
  //是否在降级
  if (enter_is_async_deflating()) {
    return false;
  }
  //自旋
  if (TrySpin(current)) {
    assert(has_owner(current), "must be current: owner=" INT64_FORMAT, owner_raw());
    assert(_recursions == 0, "must be 0: recursions=" INTX_FORMAT, _recursions);
    assert_mark_word_consistency();
    return true;
  }

  return false;
}
```

然后自旋中：

```c++
bool ObjectMonitor::TrySpin(JavaThread* current) {

  // Dumb, brutal spin.  Good for comparative measurements against adaptive spinning.
  // 固定自旋，而不是自适应自旋，这里是实验参数默认是0，所以可以忽略这里
  int knob_fixed_spin = Knob_FixedSpin;  // 0 (don't spin: default), 2000 good test
  if (knob_fixed_spin > 0) {
    return short_fixed_spin(current, knob_fixed_spin, false);
  }

  // Admission control - verify preconditions for spinning
  //
  // We always spin a little bit, just to prevent _SpinDuration == 0 from
  // becoming an absorbing state.  Put another way, we spin briefly to
  // sample, just in case the system load, parallelism, contention, or lock
  // modality changed.
  //固定次数的预自旋，默认是10，作者注释说的20到100也可以但是在他的测试中并不如10
  int knob_pre_spin = Knob_PreSpin; // 10 (default), 100, 1000 or 2000
  //短暂自旋
  //执行有限次数的自旋操作，避免立即进入阻塞状态，从而减少线程上下文切换的开销
  if (short_fixed_spin(current, knob_pre_spin, true)) {
    return true;
  }

  //
  // Consider the following alternative:
  // Periodically set _SpinDuration = _SpinLimit and try a long/full
  // spin attempt.  "Periodically" might mean after a tally of
  // the # of failed spin attempts (or iterations) reaches some threshold.
  // This takes us into the realm of 1-out-of-N spinning, where we
  // hold the duration constant but vary the frequency.
    // 获取当前自适应自旋次数
  int ctr = _SpinDuration;
  if (ctr <= 0) return false;

  // We're good to spin ... spin ingress.
  // CONSIDER: use Prefetch::write() to avoid RTS->RTO upgrades
  // when preparing to LD...CAS _owner, etc and the CAS is likely
  // to succeed.
  //如果没有继任者设置当前线程为继任者
  if (!has_successor()) {
    set_successor(current);
  }
  //没有拥有者
  int64_t prv = NO_OWNER;

    // 以下循环有三种退出方式：
    // 1. 成功完成旋转，此时该线程已获取到锁。
    // 2. 由于某种原因旋转失败。
    // 3. 旋转失败但未产生任何不利影响。
      //开始自旋，次数是自适应自旋次数
  while (--ctr >= 0) {

    // Periodic polling -- Check for pending GC
    // Threads may spin while they're unsafe.
    // We don't want spinning threads to delay the JVM from reaching
    // a stop-the-world safepoint or to steal cycles from GC.
    // If we detect a pending safepoint we abort in order that
    // (a) this thread, if unsafe, doesn't delay the safepoint, and (b)
    // this thread, if safe, doesn't steal cycles from GC.
    // This is in keeping with the "no loitering in runtime" rule.
    // We periodically check to see if there's a safepoint pending.
    //定期检查是否有待处理的安全点，准确说是每256次检查一次
    if ((ctr & 0xFF) == 0) {
      // Can't call SafepointMechanism::should_process() since that
      // might update the poll values and we could be in a thread_blocked
      // state here which is not allowed so just check the poll.
      //安全点检查
      if (SafepointMechanism::local_poll_armed(current)) {
        break;
      }
      //短暂暂停，避免cpu100%
      SpinPause();
    }

    // Probe _owner with TATAS
    // If this thread observes the monitor transition or flicker
    // from locked to unlocked to locked, then the odds that this
    // thread will acquire the lock in this spin attempt go down
    // considerably.  The same argument applies if the CAS fails
    // or if we observe _owner change from one non-null value to
    // another non-null value.   In such cases we might abort
    // the spin without prejudice or apply a "penalty" to the
    // spin count-down variable "ctr", reducing it by 100, say.
      // TATAS (Test-And-Test-And-Set) 算法检查锁状态
     //当前owner状态
    int64_t ox = owner_raw();
    //没有拥有者
    if (ox == NO_OWNER) {
        //cas获取锁
      ox = try_set_owner_from(NO_OWNER, current);
      //cas成功获取到锁
      if (ox == NO_OWNER) {
        // The CAS succeeded -- this thread acquired ownership
        // Take care of some bookkeeping to exit spin state.
        if (has_successor(current)) {
          clear_successor();
        }

        // Increase _SpinDuration :
        // The spin was successful (profitable) so we tend toward
        // longer spin attempts in the future.
        // CONSIDER: factor "ctr" into the _SpinDuration adjustment.
        // If we acquired the lock early in the spin cycle it
        // makes sense to increase _SpinDuration proportionally.
        // Note that we don't clamp SpinDuration precisely at SpinLimit.
        //新增自旋次数
        _SpinDuration = adjust_up(_SpinDuration);
        return true;
      }

      // The CAS failed ... we can take any of the following actions:
      // * penalize: ctr -= CASPenalty
      // * exit spin with prejudice -- abort without adapting spinner
      // * exit spin without prejudice.
      // * Since CAS is high-latency, retry again immediately.
      //cas失败进入下一次自旋直接
      break;
    }

    // Did lock ownership change hands ?
    //检查锁的持有者是否变化
    if (ox != prv && prv != NO_OWNER) {
      break;
    }
    prv = ox;

    if (!has_successor()) {
      set_successor(current);
    }
  }

  // Spin failed with prejudice -- reduce _SpinDuration.
  //自旋失败，减少自旋次数
  if (ctr < 0) {
    _SpinDuration = adjust_down(_SpinDuration);
  }

  // 退出前，最后尝试获取锁
  if (has_successor(current)) {
    clear_successor();
    OrderAccess::fence();
    if (TryLock(current) == TryLockResult::Success) {
      return true;
    }
  }

  return false;
}
```

这里代码很长，我保留了原作者的注释，因为注释都很清楚大家都可以结合着看，总之自旋这里会有如下步骤：

- 测试试验性质的固定自旋，可以忽略，默认是0
- 固定次数的预自旋，默认是10次
- 自适应自旋

这里着重说下自适应自旋，自适应自旋这里，自旋次数是在初始化`objectMonitor`的时候定义的：

```c++
ObjectMonitor::ObjectMonitor(oop object) :
...
  _SpinDuration(ObjectMonitor::Knob_SpinLimit),
...
```

`Knob_SpinLimit`是自旋上限，5000次，也就是说一开始自旋的次数是5000，然后根据成功或者失败去加减，所以自适应是这么来的（为什么成功就增加失败就减少呢？因为你自旋成功，那么jvm就认为自旋是有效的，那么后面的自旋可能会多尝试几次自旋就可以成功，如果失败了jvm就认为自旋是无效的，那么就少自旋几次尽快进入下一阶段），然后每256次检查一次，是否安全点这个和GC有关，然后就是短暂暂停让出CPU，因为我们都知道一直自旋CPU会100%，然后就是cas获取锁了，获取锁失败就进入下一次自旋，只到自旋完成都没有获取到锁，这里自旋成功和失败都会去相应的新增和减少自旋次数，但是自旋次数上限是5000次，最多不会超过这个数，每次新增或者减少的步长是`Knob_Bonus`，值是100

这里再介绍一下`TryLock`是怎么获取锁的：

```C++
ObjectMonitor::TryLockResult ObjectMonitor::TryLock(JavaThread* current) {
    //当前拥有者
  int64_t own = owner_raw();
  //初始拥有者，避免干扰
  int64_t first_own = own;

  for (;;) {
      //如果正在降级
    if (own == DEFLATER_MARKER) {
      // Block out deflation as soon as possible.
      //阻止降级
      ObjectMonitorContentionMark contention_mark(this);

      // Check for deflation.
      //检查是否异步降级
      if (enter_is_async_deflating()) {
        // Treat deflation as interference.
        //返回结果是有干扰
        return TryLockResult::Interference;
      }
      //在有竞争的情况下获取锁
      if (TryLockWithContentionMark(current, contention_mark)) {
        assert(_recursions == 0, "invariant");
        return TryLockResult::Success;
      } else {
        // Deflation won or change of owner; dont spin
        break;
      }
      //没有拥有者
    } else if (own == NO_OWNER) {
        //cas修改拥有者
      int64_t prev_own = try_set_owner_from(NO_OWNER, current);
      //cas成功
      if (prev_own == NO_OWNER) {
        assert(_recursions == 0, "invariant");
        return TryLockResult::Success;
      } else {
        // The lock had been free momentarily, but we lost the race to the lock.
        //cas失败进入下一次循环，把拥有者修改为当前占用的拥有者
        own = prev_own;
      }
    } else {
      // Retry doesn't make as much sense because the lock was just acquired.
      break;
    }
  }
  //根据当前拥有者是不是当前线程来判断状态是锁已被其他线程持有（状态未变）。还是干扰
  return first_own == own ? TryLockResult::HasOwner : TryLockResult::Interference;
}
```

其实就是简单的cas去获取锁，然后处理一下降级情况和有拥有者情况，总之外部只会用到成功的状态，如果这里是有拥有者或者干扰都视为获取锁失败，这里最底层获取锁的代码就是` try_set_owner_from(NO_OWNER, current);`:

```c++
inline int64_t ObjectMonitor::try_set_owner_from(int64_t old_value, JavaThread* current) {
  return try_set_owner_from_raw(old_value, owner_id_from(current));
}
...
  inline int64_t ObjectMonitor::owner_id_from(JavaThread* thread) {
  int64_t id = thread->monitor_owner_id();
  assert(id >= ThreadIdentifier::initial() && id < ThreadIdentifier::current(), "must be reasonable");
  return id;
}
...
int64_t monitor_owner_id() const { return _monitor_owner_id; }
```

这里我们的拥有者是我们线程对象的一个属性：`_monitor_owner_id`,**大家务必记住这里，我们文本要寻找的答案也许就在这里**

- 自旋完成都没有获取到锁那么就减少自旋次数然后再退出前最后再一次获取锁

我们可以看到自旋这里，也不是单纯的自旋，会有不同的处理，核心逻辑就是期望尽量在这个阶段就获取到锁避免进入下一个阶段，因为下一个阶段就涉及到线程状态的改变了，会加大负担

那么自旋完了之后就肯定会有一套阻塞等到唤醒的逻辑来获取锁了，就是`enter_with_contention_mark`方法：

```c++
void ObjectMonitor::enter_with_contention_mark(JavaThread *current, ObjectMonitorContentionMark &cm) {
 ...
  JFR_ONLY(JfrConditionalFlush<EventJavaMonitorEnter> flush(current);)
  //JVM事件：记录锁进入事件
  EventJavaMonitorEnter enter_event;
 ...
  //虚拟线程pinned事件
  EventVirtualThreadPinned vthread_pinned_event;
  //以上两个事件目的是用于监控锁竞争行为（如锁获取耗时、线程阻塞原因等），供性能分析或调试使用。

  //freeze 结果
  freeze_result result;

  { // Change java thread status to indicate blocked on monitor enter.
      //标记线程因获取锁而阻塞
    JavaThreadBlockedOnMonitorEnterState jtbmes(current, this);

    assert(current->current_pending_monitor() == nullptr, "invariant");
    //记录当前线程正在等待的锁
    current->set_current_pending_monitor(this);

...
    //虚拟线程特殊处理
    ContinuationEntry* ce = current->last_continuation();
    if (ce != nullptr && ce->is_virtual_thread()) {
      result = Continuation::try_preempt(current, ce->cont_oop(current));
      if (result == freeze_ok) {
        bool acquired = VThreadMonitorEnter(current);
        if (acquired) {
          // We actually acquired the monitor while trying to add the vthread to the
          // _cxq so cancel preemption. We will still go through the preempt stub
          // but instead of unmounting we will call thaw to continue execution.
          current->set_preemption_cancelled(true);
          if (JvmtiExport::should_post_monitor_contended_entered()) {
            // We are going to call thaw again after this and finish the VMTS
            // transition so no need to do it here. We will post the event there.
            current->set_contended_entered_monitor(this);
          }
        }
        current->set_current_pending_monitor(nullptr);
        DEBUG_ONLY(int state = java_lang_VirtualThread::state(current->vthread()));
        assert((acquired && current->preemption_cancelled() && state == java_lang_VirtualThread::RUNNING) ||
               (!acquired && !current->preemption_cancelled() && state == java_lang_VirtualThread::BLOCKING), "invariant");
        return;
      }
    }

    OSThreadContendState osts(current->osthread());

    assert(current->thread_state() == _thread_in_vm, "invariant");
    //核心获取锁
    for (;;) {
        //处理线程暂停请求：若线程被暂停，退出锁竞争
      ExitOnSuspend eos(this);
      {
        ThreadBlockInVMPreprocess<ExitOnSuspend> tbivs(current, eos, true /* allow_suspend */);
        //实际执行锁获取（
        EnterI(current);
        //清除等待标记
        current->set_current_pending_monitor(nullptr);
      }
      if (!eos.exited()) {
          // 若未因暂停退出，则锁已获取成功，跳出循环
        // ExitOnSuspend did not exit the OM
        assert(has_owner(current), "invariant");
        break;
      }
    }

    // We've just gotten past the enter-check-for-suspend dance and we now own
    // the monitor free and clear.
  }

  assert(contentions() >= 0, "must not be negative: contentions=%d", contentions());

  // Must either set _recursions = 0 or ASSERT _recursions == 0.
  //锁获取成功的处理
  assert(_recursions == 0, "invariant");
  assert(has_owner(current), "invariant");
  ...

    // 触发监控事件（如 JVMTI、DTrace），用于调试或性能分析。
  DTRACE_MONITOR_PROBE(contended__entered, this, object(), current);
  if (JvmtiExport::should_post_monitor_contended_entered()) {
    JvmtiExport::post_monitor_contended_entered(current, this);

...
  }
 ...
  //更新性能计数器（ContendedLockAttempts），记录锁竞争次数。
  OM_PERFDATA_OP(ContendedLockAttempts, inc());
}

```

 这里代码非常长，我去掉了一些不重要的校验和事件，这里我们可以把竞争获取锁的流程分成4个步骤：

- 刚进入的时候做一些必要的校验和初始化一些事件记录
- 虚拟线程的特殊处理
- 竞争获取锁
- 获取锁成功之后的处理主要是记录本次的耗时和竞争次数等等提供给性能分析工具使用

第一个和第四个我们可以不看，第二个部分是我们本篇文章寻找的答案暨——JDK24是如何解决虚拟线程在Synchronized的重量级锁下解决Pinning问题

第三个是这个方法竞争获取锁的核心逻辑

我们先看如何对虚拟线程特殊处理:

```c++
//虚拟线程特殊处理
ContinuationEntry* ce = current->last_continuation();
//是否是虚拟线程
if (ce != nullptr && ce->is_virtual_thread()) {
    //挂起虚拟线程也就是freeze
  result = Continuation::try_preempt(current, ce->cont_oop(current));
  //freeze成功
  if (result == freeze_ok) {
      // 尝试为虚拟线程获取锁
    bool acquired = VThreadMonitorEnter(current);
      // 虚拟线程成功获取锁
    if (acquired) {
      // 取消之前的“抢占”（无需继续暂停）
      current->set_preemption_cancelled(true);
      // 记录锁竞争结束事件（供JVMTI调试）
      if (JvmtiExport::should_post_monitor_contended_entered()) {
        current->set_contended_entered_monitor(this);
      }
    }
      // 清除等待标记
    current->set_current_pending_monitor(nullptr);
  ...
    //无论有没有获取锁，成功都直接返回，释放出平台线程
    return;
  }
}
```

实际上就是做了两件事，先把虚拟线程`freeze`挂起，然后为虚拟线程获取锁，那么是怎么为虚拟线程获取锁的呢？

我们看`VThreadMonitorEnter`方法：

```c++
bool ObjectMonitor::VThreadMonitorEnter(JavaThread* current, ObjectWaiter* waiter) {//再次尝试获取锁
  if (TryLock(current) == TryLockResult::Success) {
    assert(has_owner(current), "invariant");
    assert(!has_successor(current), "invariant");
    return true;
  }

  //获取当前虚拟线程对象指针
  oop vthread = current->vthread();
  //把虚拟线程包装为ObjectWaiter也就是等待节点
  ObjectWaiter* node = waiter != nullptr ? waiter : new ObjectWaiter(vthread, this);
  // 标记为未链接状态
  node->_prev   = (ObjectWaiter*) 0xBAD;
  //标记为竞争队列（Contention Queue）成员
  node->TState  = ObjectWaiter::TS_CXQ;

  // Push node associated with vthread onto the front of the _cxq.
    // CAS循环将节点插入_cxq队列头部
  ObjectWaiter* nxt;
  for (;;) {
    node->_next = nxt = _cxq;
    //cas成功，此时当前虚拟线程封装的等待节点已插入队列头部
    if (Atomic::cmpxchg(&_cxq, nxt, node) == nxt) break;

    // Interference - the CAS failed because _cxq changed.  Just retry.
    // As an optional optimization we retry the lock.
    //CAS失败时重试，然后再次尝试获取锁
    if (TryLock(current) == TryLockResult::Success) {
      assert(has_owner(current), "invariant");
      assert(!has_successor(current), "invariant");
      //获取成功清理临时节点
      if (waiter == nullptr) delete node;  // for Object.wait() don't delete yet
      return true;
    }
  }

  // We have to try once more since owner could have exited monitor and checked
  // _cxq before we added the node to the queue.
  //再次尝试获取锁
  if (TryLock(current) == TryLockResult::Success) {
      //获取成功，然后执行获取锁成功的逻辑
    assert(has_owner(current), "invariant");
    UnlinkAfterAcquire(current, node);
    if (has_successor(current)) clear_successor();
    if (waiter == nullptr) delete node;  // for Object.wait() don't delete yet
    return true;
  }

  assert(java_lang_VirtualThread::state(vthread) == java_lang_VirtualThread::RUNNING, "wrong state for vthread");
  //获取锁失败，但是成功进入到了cxq队列，此时将标记虚拟线程为阻塞状态
  java_lang_VirtualThread::set_state(vthread, java_lang_VirtualThread::BLOCKING);

  // We didn't succeed in acquiring the monitor so increment _contentions and
  // save ObjectWaiter* in the vthread since we will need it when resuming execution.
    // 记录锁竞争次数并保存等待节点
  add_to_contentions(1);
  java_lang_VirtualThread::set_objectWaiter(vthread, node);
  return false;
}
```

这里有一个`cxq`队列之前笔者讲`Synchronized`源码的时候有讲过，相信大家还有印象，这里就不过多展开了，反正就是`Synchronized`和JUC的`ReentrantLock`一样，有一个等待队列，但是`Synchronized`有wait的情况，所以他有两个队列`_WaitSet`和`cxq`，第一次竞争锁的是放在`cxq`队列里，调用了wait方法的是放在`_WaitSet`里。

然后虚拟线程在竞争锁的时候，是把虚拟线程封装成了一个等待节点放到`cxq`里，而不是把平台线程放进去，这意味着平台线程是不会阻塞的，平台线程就可以被调度执行另外的虚拟线程任务了。这里注意了，是会先把虚拟线程freeze起来，然后再用平台线程来为虚拟线程争抢锁，然后失败就把虚拟线程标记为阻塞状态,接下来平台线程就会被释放做其他的事情去了,但是！这里必须要是freeze成功的虚拟线程才会进入这个逻辑，如果freeze失败了，那么还是会进入`EnterI`进行锁的获取，所以我们还是需要看`EnterI`方法：

```c++
void ObjectMonitor::EnterI(JavaThread* current) {
  assert(current->thread_state() == _thread_blocked, "invariant");

  //再一次尝试获取锁
  if (TryLock(current) == TryLockResult::Success) {
    assert(!has_successor(current), "invariant");
    assert(has_owner(current), "invariant");
    return;
  }

  assert(InitDone, "Unexpectedly not initialized");

  //再次自旋
  if (TrySpin(current)) {
    assert(has_owner(current), "invariant");
    assert(!has_successor(current), "invariant");
    return;
  }
...

  //自旋还是失败，就进入cxq队列然后阻塞
  ObjectWaiter node(current);
  // 重置唤醒事件
  current->_ParkEvent->reset();
  // 标记为未链接状态
  node._prev   = (ObjectWaiter*) 0xBAD;
  node.TState  = ObjectWaiter::TS_CXQ;

  ObjectWaiter* nxt;
    // CAS循环将节点插入_cxq头部
  for (;;) {
    node._next = nxt = _cxq;
    if (Atomic::cmpxchg(&_cxq, nxt, &node) == nxt) break;

    // Interference - the CAS failed because _cxq changed.  Just retry.
    // As an optional optimization we retry the lock.
      // CAS失败时重试，可选优化：再次尝试获取锁
    if (TryLock(current) == TryLockResult::Success) {
      assert(!has_successor(current), "invariant");
      assert(has_owner(current), "invariant");
      return;
    }
  }

  //最大阻塞ms
  static int MAX_RECHECK_INTERVAL = 1000;
  //初始阻塞ms
  int recheck_interval = 1;
  //是否定时阻塞
  bool do_timed_parked = false;
  ContinuationEntry* ce = current->last_continuation();
  //如果是虚拟线程
  if (ce != nullptr && ce->is_virtual_thread()) {
      //定时阻塞
    do_timed_parked = true;
  }
    //循环尝试获取锁或阻塞
  for (;;) {

    if (TryLock(current) == TryLockResult::Success) {
      break;
    }
    assert(!has_owner(current), "invariant");
    if (do_timed_parked) {
        // 如果是虚拟线程就定时阻塞，然后起来检查，平台线程则是一直阻塞直到主动唤醒
      current->_ParkEvent->park((jlong) recheck_interval);
      // Increase the recheck_interval, but clamp the value.
        // 指数增长检查间隔
      recheck_interval *= 8;
      //达到最大检查间隔，就设置为最大检查间隔，这里是1000ms
      if (recheck_interval > MAX_RECHECK_INTERVAL) {
        recheck_interval = MAX_RECHECK_INTERVAL;
      }
    } else {
        //平台线程情况，直接一直阻塞直到被唤醒
      current->_ParkEvent->park();
    }
    //被唤醒了，再次尝试获取锁
    if (TryLock(current) == TryLockResult::Success) {
      break;
    }

    OM_PERFDATA_OP(FutileWakeups, inc());

    //被唤醒了还是没有获取到锁，就自旋获取，成功就推出循环，失败了就再次进入循环然后阻塞等待
    if (TrySpin(current)) {
      break;
    }

      // 清理继任者标记（避免重复唤醒）
    if (has_successor(current)) clear_successor();

    // Invariant: after clearing _succ a thread *must* retry _owner before parking.
      // 内存屏障，确保可见性
    OrderAccess::fence();
  }


  assert(has_owner(current), "invariant");
    // 从队列中移除当前节点
  UnlinkAfterAcquire(current, &node);
    // 清理继任者标记
  if (has_successor(current)) {
    clear_successor();
    // Note that we don't need to do OrderAccess::fence() after clearing
    // _succ here, since we own the lock.
  }

  return;
}
```

在这个方法里，可以看到，首先还是会去自旋一次，即使前面已经自旋过了，再一次自旋失败之后才会封装为wait节点然后进入`cxq`队列阻塞等待，这里对虚拟线程有特殊处理，也就是说如果是虚拟线程的话就是增长的定时的阻塞（1→8→64→512→1000ms））这样做的好处是避免虚拟线程一直阻塞影响性能，尽快执行完任务释放平台线程，如果是非虚拟线程的话就一直阻塞直到被唤醒，然后阻塞唤醒之后先获取一次，失败的话还是先自旋获取，自旋失败就进入 下一次阻塞等待循环直到获取到锁，我们可以看到整个重量级锁的流程会有最多三次自旋，看来阻塞流程给性能消耗非常大，jvm的开发们尽可能的让线程不要阻塞等待而是尽量自旋去获取，我想也就是为什么这种模式要想轻量级模式的原因。

### 1.2、直接获取监视器锁

然后我们第二种情况就是轻量级锁获取失败膨胀为重量级锁的情况就是`inflate_and_enter`：

```C++
ObjectMonitor *
LightweightSynchronizer::inflate_and_enter(oop object, ObjectSynchronizer::InflateCause cause, JavaThread *locking_thread, JavaThread *current) {
...

    ObjectMonitor *monitor = nullptr;
    //不使用监视器表的情况
    if (!UseObjectMonitorTable) {
       ...
    }
   //确保执行期间不进入安全点（避免线程挂起）
    NoSafepointVerifier nsv;

    // Lightweight monitors require that hash codes are installed first
    ObjectSynchronizer::FastHashCode(locking_thread, object);

    // Try to get the monitor from the thread-local cache.
    // There's no need to use the cache if we are locking
    // on behalf of another thread.
    //获取监视器
    if (current == locking_thread) {
        monitor = current->om_get_from_monitor_cache(object);
    }

    // Get or create the monitor
    if (monitor == nullptr) {
        monitor = get_or_insert_monitor(object, current, cause);
    }
    //快速尝试获取监视器
    if (monitor->try_enter(locking_thread)) {
        return monitor;
    }

    // Holds is_being_async_deflated() stable throughout this function.
    ObjectMonitorContentionMark contention_mark(monitor);

    /// First handle the case where the monitor from the table is deflated
    //异步降级
    if (monitor->is_being_async_deflated()) {
        // The MonitorDeflation thread is deflating the monitor. The locking thread
       ...
    }
    //循环获取
    for (;;) {
        const markWord mark = object->mark_acquire();
        // The mark can be in one of the following states:
        // *  inflated     - If the ObjectMonitor owner is anonymous
        //                   and the locking_thread owns the object
        //                   lock, then we make the locking_thread
        //                   the ObjectMonitor owner and remove the
        //                   lock from the locking_thread's lock stack.
        // *  fast-locked  - Coerce it to inflated from fast-locked.
        // *  neutral      - Inflate the object. Successful CAS is locked

        // CASE: inflated
        //已经处于重量级锁
        if (mark.has_monitor()) {
            LockStack &lock_stack = locking_thread->lock_stack();
            //监视器匿名也就是没有拥有者并且锁栈有这个对象说明当前线程在轻量级锁模式下获取到了锁那么膨胀过后毫无疑问也应该获取到
            if (monitor->has_anonymous_owner() && lock_stack.contains(object)) {
                // The lock is fast-locked by the locking thread,
                // convert it to a held monitor with a known owner.
                monitor->set_owner_from_anonymous(locking_thread);
                monitor->set_recursions(lock_stack.remove(object) - 1);
            }

            break; // Success
        }

        // CASE: fast-locked
        // Could be fast-locked either by locking_thread or by some other thread.
        //处于轻量级锁模式
        if (mark.is_fast_locked()) {
            markWord old_mark = object->cas_set_mark(mark.set_has_monitor(), mark);
            //cas失败，重试
            if (old_mark != mark) {
                // CAS failed
                continue;
            }

            // Success! Return inflated monitor.
            LockStack &lock_stack = locking_thread->lock_stack();
            //当前线程就是轻量级锁的拥有者
            if (lock_stack.contains(object)) {
                // The lock is fast-locked by the locking thread,
                // convert it to a held monitor with a known owner.
                monitor->set_owner_from_anonymous(locking_thread);
                monitor->set_recursions(lock_stack.remove(object) - 1);
            }

            break; // Success
        }

        // CASE: neutral (unlocked)

        // Catch if the object's header is not neutral (not locked and
        // not marked is what we care about here).
        assert(mark.is_neutral(), "invariant: header=" INTPTR_FORMAT, mark.value());
        //无锁状态，直接膨胀并且设置拥有者是当前线程
        markWord old_mark = object->cas_set_mark(mark.set_has_monitor(), mark);
        if (old_mark != mark) {
            // CAS failed
            continue;
        }

        // Transitioned from unlocked to monitor means locking_thread owns the lock.
        monitor->set_owner_from_anonymous(locking_thread);

        return monitor;
    }
    //是重量级锁，但是拥有者不是当前线程，就是竞争的情况
    if (current == locking_thread) {
        // One round of spinning
        //自旋获取
        if (monitor->spin_enter(locking_thread)) {
            return monitor;
        }

        // Monitor is contended, take the time before entering to fix the lock stack.
        // 竞争激烈，膨胀锁栈中所有相关锁，避免重复竞争
        LockStackInflateContendedLocks().inflate(current);
    }

    // enter can block for safepoints; clear the unhandled object oop
    PauseNoSafepointVerifier pnsv(&nsv);
    object = nullptr;
    //最终获取锁
    if (current == locking_thread) {
        monitor->enter_with_contention_mark(locking_thread, contention_mark);
    } else {
        monitor->enter_for_with_contention_mark(locking_thread, contention_mark);
    }

    return monitor;
}
```

这里大体上和第一种情况差不多，只是处理了几种不同的情况：

- 已经是重量级锁，如果监视器没有拥有者那么就设置当前线程是拥有者
- 轻量级锁情况，膨胀重量级锁，膨胀的就是cas修改对象头为有监视器，cas修改失败则重新进入循环处理不同的情况，cas成功的话如果当前轻量级锁的拥有者就是当前线程则获取成功
- 无锁情况，直接升级为重量级锁，升级过程还是cas修改对象头然后直接设置当前线程是拥有者
- 是重量级锁但是已经有拥有者了，就是竞争情况，这里和上文的第一种情况处理逻辑是一样的，先自旋然后在竞争的情况下获取锁，详情可以回顾上文。

这里比起第一种情况会有一个方法会多一个方法：`try_enter`，来快速获取锁：

```c++
bool ObjectMonitor::try_enter(JavaThread* current, bool check_for_recursion) {
  // TryLock avoids the CAS and handles deflation.
  //获取锁
  TryLockResult r = TryLock(current);
  //获取锁成功
  if (r == TryLockResult::Success) {
    assert(_recursions == 0, "invariant");
    return true;
  }

  // If called from SharedRuntime::monitor_exit_helper(), we know that
  // this thread doesn't already own the lock.
  if (!check_for_recursion) {
    return false;
  }
    //有拥有者，并且是自己，可重入的情况，重入次数+1
  if (r == TryLockResult::HasOwner && has_owner(current)) {
    _recursions++;
    return true;
  }
    //其他情况都是获取失败
  return false;
}
```

这里会用到`TryLock`的其他状态，这里重点看一下，怎么判断是不是可重入的情况，因为后面我们看虚拟线程的pinning问题的时候可能会用到：

```C++
bool has_owner(JavaThread* thread) const { return owner() == owner_id_from(thread); }
...
inline int64_t ObjectMonitor::owner_id_from(JavaThread* thread) {
  int64_t id = thread->monitor_owner_id();
  assert(id >= ThreadIdentifier::initial() && id < ThreadIdentifier::current(), "must be reasonable");
  return id;
}
...
int64_t monitor_owner_id() const { return _monitor_owner_id; }
```

这里就是判断拥有者是不是当前线程对应的监视器拥有者ID，如果能匹配就是可重入的情况，这里底层判断是不是当前线程拥有的时候和设置拥有者的时候一样实际上就是根据线程的`_monitor_owner_id`来判断



## 2、虚拟线程在阻塞释放平台线程的时候如何确保锁的安全性

以上就是`Synchronized`的重量级锁的上锁流程，那么这里面我们可以看到有部分关于虚拟线程的处理，比如虚拟线程获取锁的话只是把虚拟线程放到队列里等待，平台线程会释放出去和虚拟线程挂在平台线程上定时阻塞，这算是部分解决了`Pinning`暨不让虚拟线程一直挂在平台线程上，但是还是 有问题没有解决，那就是虚拟线程获取到锁之后自身有阻塞释放了平台线程这种情况怎么保证锁的安全性暨不会让调度到同样的平台线程的虚拟线程获取到锁？笔者这里大胆猜测，因为要解决pinning问题就要锁和平台线程解耦，那么只要让锁的持有者和虚拟线程绑定不就行了吗？

我们可以看到，最终获取到监视器锁的核心逻辑就是用线程的`_monitor_owner_id`来当做`owner`，也就是说这个就是锁持有者的标识，那么这个属性怎么来的呢：

```c++
set_monitor_owner_id(java_lang_Thread::thread_id(thread_oop()));
...
inline int64_t java_lang_Thread::thread_id(oop java_thread) {
  return java_thread->long_field(_tid_offset);
}
```

这里`java_thread->long_field(_tid_offset)`获取的就是Java的`Thread`类的`tid`就是线程ID,在Java中我们如此获取`tid`：

```java
 /**
     * Returns the identifier of this Thread.  The thread ID is a positive
     * {@code long} number generated when this thread was created.
     * The thread ID is unique and remains unchanged during its lifetime.
     *
     * @return this thread's ID
     * @since 19
     */
    public final long threadId() {
        return tid;
    }

```

我们看注释可以看到，这是一个jdk19新增的方法，然后这个`tid`标识在整个线程的生命周期里是不变的，也就是说这个`tid`对应的就是一个Java的`Thread`类，如果是虚拟线程那么对应的就是：`VirtualThread`类，也就是说这里的`tid`实际上是和虚拟线程绑定的，即使两个虚拟线程被绑定到了一个平台线程上他们的`tid`也不会改变！那么这里就解决了pinning问题！

那么为什么在jdk21中会有pinning问题呢？我们看看jdk21中上锁的代码：

```c++
bool ObjectMonitor::enter(JavaThread* current) {
  // The following code is ordered to check the most common cases first
  // and to reduce RTS->RTO cache line upgrades on SPARC and IA32 processors.

  void* cur = try_set_owner_from(nullptr, current);
  if (cur == nullptr) {
    assert(_recursions == 0, "invariant");
    return true;
  }
```

这里在jdk21中，线程的都拥有者是`current`也就是线程对象本身，在jvm里这个`JavaThread* current`是包含了平台线程和虚拟线程的，也就是说如果虚拟线程切换了平台线程那这个current肯定不是一样的，但是他们的tid是一样的！



## 3、总结

我们在探寻jdk24如何解决虚拟线程在Synchronized下Pinning问题的时候顺便看完了整个jdk24中Synchronized的上锁流程：

>jdk24默认是lightweight模式，在这个模式下，轻量级锁依赖于lock_stack这个和当前线程绑定的数据结构，每次上锁的时候会把需要上锁的对象放到lock_stack中，如果发生锁嵌套的情况例如锁定了A又锁定了B然后又重复的锁定A，此时会膨胀为重量级锁，如果轻量级锁锁定失败并且自适应自旋之后还是获取失败那么也会膨胀为重量级锁。
>
>重量级锁还是争抢对象的monitor，需要把线程的tid写到monitor的owner中，如果获取失败则会自旋，自旋也是自适应自旋，如果自旋中能获取到锁那么下一次自旋的次数就会增加如果获取失败自旋次数就减少，自旋之后还是无法获取锁，就会把等待的线程封装成wait node放到cxq队列里阻塞等待，如果是虚拟线程，会把虚拟线程包装成wait node然后把虚拟线程挂起平台线程就切出去，如果虚拟线程freeze失败，就会和平台线程一起封装成wait node阻塞获取锁，但是虚拟线程阻塞是定时唤醒，平台线程是被动唤醒，唤醒之后还是用自旋来获取锁，获取失败继续阻塞等待

那么在jdk24中是如何解决pinning问题的呢？

>轻量级锁情况下，保存锁记录的lock_stack会和虚拟线程一起freeze和thaw，这样轻量级锁就是跟着虚拟线程走了，无论载体的平台线程如何变化虚拟线程的lock_stack都不会变化也不会被其他虚拟线程获取到，在重量级锁情况下，monitor的拥有者是tid，是所有线程的唯一标识，在整个线程生命周期里都是唯一，无论虚拟线程切换多少次载体平台线程这个tid都是不变的，也是跟着虚拟线程走的，所以其他虚拟线程不会错误的获取到锁，而在jdk21中这个monitor的拥有者是线程本身而不是tid，线程本身包含了平台线程，所以会pinning
