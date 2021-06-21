# 深入JVM源码分析Synchronized实现原理



## 前言

Synchronized是Java多线程同步中非常重要的一个关键字，并且Synchronized是JVM底层实现的，虽然网上很多资料都很详细的说明了他的原理和开锁的流程，但是笔者还是觉得不得劲儿，准备深入到JVM源码来看他的实现。

## 基础知识回顾

众所周知Synchronized的实现是根据字节码中的`monitorexit`和`monitorenter`两个指令来实现的，原理就是线程来争抢上锁对象的monitorObject监视器（无论是锁对象还是锁方法本质上都是锁对象），这个监视器对象的指针是存放在对象的对象头的MarkWord里面的。jdk6之后JVM引入了锁升级的概念，简单总结一下synchronized的执行过程：
1. 检测Mark Word里面是不是当前线程的ID，如果是，表示当前线程处于偏向锁
2. 如果不是，则使用CAS将当前线程的D替换Mard Word，如果成功则表示当前线程获得偏向锁，置偏向标志位1
3. 如果失败，则说明发生竞争，撤销偏向锁，进而升级为轻量级锁。
4. 当前线程使用CAS将对象头的Mark Word替换为锁记录指针，如果成功，当前线程获得锁
5. 如果失败，表示其他线程竞争锁，升级为重量级锁



## 深入源码


### 偏向锁和轻量级锁

本文源码部分基于openJdk8的hotspot虚拟机的源码。JVM将字节码加载到内存以后，它会对这两个指令进行解释执行， monitorenter和monitorexit的指令解析是通过 hotspot/src/share/vm/interpreter/InterpreterRuntime.cpp中的两个方法实现。我们先看`monitorenter`方法：

```c++
IRT_ENTRY_NO_ASYNC(void, InterpreterRuntime::monitorenter(JavaThread* thread, BasicObjectLock* elem))
  thread->last_frame().interpreter_frame_verify_monitor(elem);
  //开启偏向锁统计
  if (PrintBiasedLockingStatistics) {
    Atomic::inc(BiasedLocking::slow_path_entry_count_addr());
  }
...
  //启用偏向锁       
  if (UseBiasedLocking) {
    ObjectSynchronizer::fast_enter(h_obj, elem->lock(), true, CHECK);
  } else {
    ObjectSynchronizer::slow_enter(h_obj, elem->lock(), CHECK);
  }
...
```

看关键代码,thread就是当前要获取锁的线程，这里先是统计偏向锁，默认`PrintBiasedLockingStatistics`参数是为false的，说明一下hotspot源码这里if(XXX)的地方这个XXX就是jvm参数，所以要开启就设置`PrintBiasedLockingStatistics`这个jvm参数为true就可以了。然后是启用偏向锁的话是调用的`synchronizer.cpp`的`fast_enter`快速进入没有启用的话就是`slow_enter `。

```c++
void ObjectSynchronizer::fast_enter(Handle obj, BasicLock* lock, bool attempt_rebias, TRAPS) {
 //再次判断是否开启偏向锁
 if (UseBiasedLocking) {
   //是否处于全局安全点
    if (!SafepointSynchronize::is_at_safepoint()) {
      //获取偏向锁
      BiasedLocking::Condition cond = BiasedLocking::revoke_and_rebias(obj, attempt_rebias, THREAD);
      //如果是重新获取偏向锁即重入的情况直接返回
      if (cond == BiasedLocking::BIAS_REVOKED_AND_REBIASED) {
        return;
      }
    } else {
      //在安全点撤销偏向锁
      assert(!attempt_rebias, "can not rebias toward VM thread");
      BiasedLocking::revoke_at_safepoint(obj);
    }
    assert(!obj->mark()->has_bias_pattern(), "biases should be revoked by now");
 }
 //偏向锁升级
 slow_enter (obj, lock, THREAD) ;
}
```

这里就是先启用偏向锁，实际上调用的是JVM的`BiasedLocking`偏向锁对象的`revoke_and_rebias`方法来上偏向锁并且返回当前偏向锁的状态，但是这里先判断了是否处于安全点，如果不在则判断偏向锁状态如果在则撤销偏向锁，安全点在JVM源码`safepoint.hpp`中的定义是：` All Java threads are stopped at a safepoint. Only VM thread is running`，简单来说，安全点就是指当线程运行到这类位置时，堆对象状态是确定一致的，JVM可以安全地进行操作，如GC，偏向锁解除等。HotSpot中，安全点位置主要在：

1. 方法返回之前
2. 调用某个方法之后
3. 抛出异常的位置
4. 循环的末尾

`revoke_and_rebias`方法返回的是偏向锁状态，这个返回的状态是`biasedLocking.hpp`里定义的枚举：

```c++
 enum Condition {
    //表示该对象没有持有偏向锁
    NOT_BIASED = 1,
    //BIAS_REVOKED表示该对象的偏向锁已经被撤销了，即其对象头已经恢复成默认的不开启偏向锁时的状态
    BIAS_REVOKED = 2,
    //表示当前线程获取了该偏向锁
    BIAS_REVOKED_AND_REBIASED = 3
  };
```

在``revoke_and_rebias``方法里有很多判断，核心就是CAS去把当前线程ID写到对象头里，如果成功则获取成功偏向锁，失败则是有线程竞争通过`BiasedLocking::Condition cond = revoke_bias(obj(), false, false, (JavaThread*) THREAD);`来膨胀成轻量级锁或者撤销偏向锁，上偏向锁的核心代码是：

```c++
 		markOop biased_value       = mark;
        //生成一个新的偏向锁对象头，让当前线程占用该偏向锁
        markOop rebiased_prototype = markOopDesc::encode((JavaThread*) THREAD, mark->age(), prototype_header->bias_epoch());
        markOop res_mark = (markOop) Atomic::cmpxchg_ptr(rebiased_prototype, obj->mark_addr(), mark);
        if (res_mark == biased_value) {
          //如果修改成功
          return BIAS_REVOKED_AND_REBIASED;
        }
```

然后回到外层了，如果偏向锁失败了则调用`synchronizer.cpp`的`slow_enter`方法用轻量级锁。在轻量级锁里有三种情况：1、无锁状态直接上锁；2、加锁状态，但是锁的持有者是当前线程，则是重入情况；3、加锁失败，发生竞争，升级成重量级锁。

第一种情况：

```c++
if (mark->is_neutral()) {
    //markWord保存到BasicLock的displaced_header字段
    lock->set_displaced_header(mark);
    //用CAS把MarkWord更新为指向BasicLock对象的指针，若成功则获取到了轻量级锁
    if (mark == (markOop) Atomic::cmpxchg_ptr(lock, obj()->mark_addr(), mark)) {
      TEVENT (slow_enter: release stacklock) ;
      return ;
    }
   //失败就升级成重量级锁
  } 
```

这里`Atomic::cmpxchg_ptr`就是JVM底层的CAS了，再底层就是判断当前操作系统来执行CPU指令了。

第二种情况：

```c++
else
  if (mark->has_locker() && THREAD->is_lock_owned((address)mark->locker())) {
    assert(lock != mark->locker(), "must not re-lock the same lock");
    assert(lock != (BasicLock*)obj->mark(), "don't relock with same BasicLock");
    lock->set_displaced_header(NULL);
    return;
  }
```

虽然有锁但是锁的持有者是当前线程所以是可重入操作。可以看到轻量级锁在虚拟机内部，使用一个称为BasicObjecLock的对象实现，这个对象内部由一个BasicLock对象和一个持有该锁的Java对象指针组成。BasicobjectLock对象放置在Java栈的栈帧中。

最后如果是第三种情况就调用`ObjectSynchronizer::inflate(THREAD, obj())->enter(THREAD);`来升级成重量级锁：

```c++
lock->set_displaced_header(markOopDesc::unused_mark());
ObjectSynchronizer::inflate(THREAD, obj())->enter(THREAD);
```

把BasicLock锁标记成未使用的，然后升级到重量级锁

### 重量级锁

首先根据基础知识重量级锁的实现实际上就是线程争抢对象的`monitorObject`监视器，那再看具体代码之前我们先来看看这个监视器对象的结构，在`objectMonitor.hpp`中：

```c++
 ObjectMonitor() {
    _header       = NULL;
    _count        = 0;
    _waiters      = 0,
    _recursions   = 0;//可重入次数
    _object       = NULL;
    _owner        = NULL;//拥有者
    _WaitSet      = NULL;
    _WaitSetLock  = 0 ;
    _Responsible  = NULL ;
    _succ         = NULL ;
    _cxq          = NULL ;
    FreeNext      = NULL ;
    _EntryList    = NULL ;
    _SpinFreq     = 0 ;
    _SpinClock    = 0 ;
    OwnerIsThread = 0 ;
    _previous_owner_tid = 0;
  }
```

然后我们回到刚刚代码里，`ObjectSynchronizer::inflate(THREAD, obj())->enter(THREAD);`这个代码的意思就是先用`inflate`方法返回一个ObjectMonitor对象，然后调用enter方法。我们先看看`inflate`方法；在`inflate`方法中首先是`for(;;)`自旋，然后注释上写了有如下情况:

1、如果已经膨胀为重量级锁则直接返回

2、膨胀等待，其它线程正在从轻量级锁膨胀到重量级锁

3、存在轻量级锁，需要膨胀成重量级锁

4、无锁，直接上重量级锁

5、偏向锁，非法的情况

第一种情况：

```c++
 if (mark->has_monitor()) {
         //获取对象的监视器锁
          ObjectMonitor * inf = mark->monitor() ;
          assert (inf->header()->is_neutral(), "invariant");
          assert (inf->object() == object, "invariant") ;
          assert (ObjectSynchronizer::verify_objmon_isinpool(inf), "monitor is invalid");
          return inf ;
      }
```

如果此时有监视器了那么久直接返回这个监视器对象，重入情况。

第二种情况：

```c++
if (mark == markOopDesc::INFLATING()) {
         TEVENT (Inflate: spin while INFLATING) ;
         ReadStableMark(object) ;
         continue ;
      }
```

如果还不是重量级锁，就检查是否处于膨胀中状态（其他线程正在膨胀中），如果是膨胀中，就调用ReadStableMark方法进行等待，ReadStableMark方法执行完毕后再通过continue继续检查，ReadStableMark方法中还会调用os::NakedYield()释放CPU资源；

第三种情况：

```c++
    //存在轻量级锁
      if (mark->has_locker()) {
          //获取可用监视器锁
          ObjectMonitor * m = omAlloc (Self) ;
         //初始化监视器锁
          m->Recycle();
          m->_Responsible  = NULL ;
          m->OwnerIsThread = 0 ;
          m->_recursions   = 0 ;
          m->_SpinDuration = ObjectMonitor::Knob_SpinLimit ;   // Consider: maintain by type/class
          //CAS更新状态为膨胀中
          markOop cmp = (markOop) Atomic::cmpxchg_ptr (markOopDesc::INFLATING(), object->mark_addr(), mark) ;
          //CAS更新失败，则再次从第一种情况开始判断，自旋嘛
          if (cmp != mark) {
             omRelease (Self, m, true) ;
             continue ;      
          }
          //已经成功更新了markword状态为膨胀中，它是锁状态更新为0的唯一途径，只有成功更新状态的单线程可以进行锁膨胀。
          //获取栈中的markword
          markOop dmw = mark->displaced_mark_helper() ;
          assert (dmw->is_neutral(), "invariant") ;
          //将监视器字段设置为适当的值
          m->set_header(dmw) ;
          //设置拥有锁的线程
          m->set_owner(mark->locker());
          //设置监视器的对象
          m->set_object(object)
          guarantee (object->mark() == markOopDesc::INFLATING(), "invariant") ;
          object->release_set_mark(markOopDesc::encode(m));
          if (ObjectMonitor::_sync_Inflations != NULL) ObjectMonitor::_sync_Inflations->inc() ;
          TEVENT(Inflate: overwrite stacklock) ;
          if (TraceMonitorInflation) {
            if (object->is_instance()) {
              ResourceMark rm;
              tty->print_cr("Inflating object " INTPTR_FORMAT " , mark " INTPTR_FORMAT " , type %s",
                (void *) object, (intptr_t) object->mark(),
                object->klass()->external_name());
            }
          }
          return m ;
      }
```

简单来说，就是通过CAS将监视器对象OjectMonitor的状态设置为INFLATING，如果CAS失败，就在此循环，如果CAS设置成功，说明轻量级锁已经升级成了重量级锁，并且当前线程拥有这个锁，然后继续设置ObjectMonitor中的header、owner等字段，然后inflate方法返回监视器对象OjectMonitor；

最后是无锁情况：

```c++
	 assert (mark->is_neutral(), "invariant");
      //分配一个有效对象监视器
      ObjectMonitor * m = omAlloc (Self) ;
      //重置监视器
      m->Recycle();
      m->set_header(mark);
      m->set_owner(NULL);
      m->set_object(object);
      m->OwnerIsThread = 1 ;
      m->_recursions   = 0 ;
      m->_Responsible  = NULL ;
      m->_SpinDuration = ObjectMonitor::Knob_SpinLimit ;       
      if (Atomic::cmpxchg_ptr (markOopDesc::encode(m), object->mark_addr(), mark) != mark) {
          m->set_object (NULL) ;
          m->set_owner  (NULL) ;
          m->OwnerIsThread = 0 ;
          m->Recycle() ;
          omRelease (Self, m, true) ;
          m = NULL ;
          continue ; 
      }
      if (ObjectMonitor::_sync_Inflations != NULL) ObjectMonitor::_sync_Inflations->inc() ;
      TEVENT(Inflate: overwrite neutral) ;
      if (TraceMonitorInflation) {
        if (object->is_instance()) {
          ResourceMark rm;
          tty->print_cr("Inflating object " INTPTR_FORMAT " , mark " INTPTR_FORMAT " , type %s",
            (void *) object, (intptr_t) object->mark(),
            object->klass()->external_name());
        }
      }
      return m ;
  }
}
```

简单来说初始化一个监视器然后返回（剩下的也看不懂）。

然后就进入到了`objectMonitor.cpp`里面的`enter`方法了，这里就是获取到锁的方法了。根据上篇文章的知识积累，这里应该分为两种情况：1、成功获取到锁；2、获取锁失败进行某种机制等待锁。

第一种情况：

```c++
  Thread * const Self = THREAD ;
  void * cur ;
  //通过CAS把当前线程写入监视器的owner
  cur = Atomic::cmpxchg_ptr (Self, &_owner, NULL) ;
  //当前监视器没有owner，则获取锁成功直接返回
  if (cur == NULL) {
     //用C++的断言判断可重入次数为0和owner为当前线程，不为的话就设置为
     assert (_recursions == 0   , "invariant") ;
     assert (_owner      == Self, "invariant") ;
     return ;
  }
 //若当前线程就是拥有者就是可重入情况
  if (cur == Self) {
     // TODO-FIXME: check for integer overflow!  BUGID 6557169.
     //这里jvm作者说这里需要判断可重入次数是否超过了integer的最大值
     _recursions ++ ;
     return ;
  }
  //获取监视器锁成功，将_recursions设置为1，_owner设置为当前线程
  if (Self->is_lock_owned ((address)cur)) {
    assert (_recursions == 0, "internal state error");
    _recursions = 1 ;
    _owner = Self ;
    OwnerIsThread = 1 ;
    return ;
  }
```

第二种情况获取锁失败:

```c++
for (;;) {
      jt->set_suspend_equivalent();
        // 如果获取锁失败，则等待锁的释放；
      EnterI (THREAD) ;
      if (!ExitSuspendEquivalent(jt)) break ;
          _recursions = 0 ;
      _succ = NULL ;
      exit (false, Self) ;

      jt->java_suspend_self();
    }
    Self->set_current_pending_monitor(NULL);
  }
```

`EnterI`方法就是线程等待获取锁的了。根据上篇文章的储备知识，如果一个线程没有获取到锁的话，他应该先尝试再获取一次，然后自旋获取，最后是进入某种数据结构里排队阻塞获取。

```c++
    Thread * Self = THREAD ;
    assert (Self->is_Java_thread(), "invariant") ;
    assert (((JavaThread *) Self)->thread_state() == _thread_blocked   , "invariant") ;
    //尝试获取锁
    if (TryLock (Self) > 0) {
        assert (_succ != Self              , "invariant") ;
        assert (_owner == Self             , "invariant") ;
        assert (_Responsible != Self       , "invariant") ;
        // 如果获取成功则退出，避免 park unpark 系统调度的开销
        return ;
    }
    // 自旋获取锁
    if (TrySpin(Self) > 0) {
        assert (_owner == Self, "invariant");
        assert (_succ != Self, "invariant");
        assert (_Responsible != Self, "invariant");
        return;
    }
```

接下来，线程会被封装成`ObjectWaiter`对象并且通过CAS放入到`_cxq`列表里然后再次尝试获取锁：

```c++
  ObjectWaiter node(Self) ;
    Self->_ParkEvent->reset() ;
    node._prev   = (ObjectWaiter *) 0xBAD ;
    node.TState  = ObjectWaiter::TS_CXQ ;
    // 通过 CAS 把 node 节点 push 到_cxq 列表中
    ObjectWaiter * nxt ;
    for (;;) {
        node._next = nxt = _cxq ;
        if (Atomic::cmpxchg_ptr (&node, &_cxq, nxt) == nxt) break ;
        // 再次 tryLock成功
        if (TryLock (Self) > 0) {
            assert (_succ != Self         , "invariant") ;
            assert (_owner == Self        , "invariant") ;
            assert (_Responsible != Self  , "invariant") ;
            return ;
        }
    }
```

后面的`for(;;)`代码块中来是一段非常巧妙的代码，同一时刻可能有多个线程都竞争锁失败走进这个EnterI方法，所以在这个for循环中，用CAS将_cxq地址放入node的_next，也就是把node放到_cxq队列的首位，如果CAS失败，就表示其他线程把node放入到_cxq的首位了，所以通过for循环再放一次，只要成功，此node就一定在最新的_cxq队列的首位。

还是根据上一篇文章里的知识，节点在队列里应该是先再次获取锁失败则被阻塞：

```c++
for (;;) {
    	//尝试获取锁
        if (TryLock (Self) > 0) break ;
        assert (_owner != Self, "invariant") ;
        if ((SyncFlags & 2) && _Responsible == NULL) {
           Atomic::cmpxchg_ptr (Self, &_Responsible, NULL) ;
        }
        // 如果指定时间则挂起一段时间
        if (_Responsible == Self || (SyncFlags & 1)) {
            TEVENT (Inflated enter - park TIMED) ;
            Self->_ParkEvent->park ((jlong) RecheckInterval) ;
            RecheckInterval *= 8 ;
            if (RecheckInterval > 1000) RecheckInterval = 1000 ;
        } else {//否则只能等待其它事件唤醒
            TEVENT (Inflated enter - park UNTIMED) ;
            Self->_ParkEvent->park() ;
        }
        //线程唤醒后再次尝试获取锁，如果查工获取锁结束阻塞
        if (TryLock(Self) > 0) break ;
     	// 再次尝试自旋
        if ((Knob_SpinAfterFutile & 1) && TrySpin(Self) > 0) break;
    }
    return ;
```

那么再次根据上篇文章，那么这里获取失败的线程被阻塞了，就要等获取锁成功的线程执行完同步代码在开锁的时候唤醒了，那么我们就直接看exit方法:

```c++
void ATTR ObjectMonitor::exit(bool not_suspended, TRAPS) {
   Thread * Self = THREAD ;
   if (_recursions != 0) {
     _recursions--;        // this is simple recursive enter
     TEVENT (Inflated exit - recursive) ;
     return ;
   }
      ObjectWaiter * w = NULL ;
      int QMode = Knob_QMode ;
    // 直接绕过 EntryList 队列，从 cxq 队列中获取线程用于竞争锁
      if (QMode == 2 && _cxq != NULL) {
          w = _cxq ;
          assert (w != NULL, "invariant") ;
          assert (w->TState == ObjectWaiter::TS_CXQ, "Invariant") ;
          ExitEpilog (Self, w) ;
          return ;
      }
    // cxq 队列插入 EntryList 尾部
      if (QMode == 3 && _cxq != NULL) {
          w = _cxq ;
          for (;;) {
             assert (w != NULL, "Invariant") ;
             ObjectWaiter * u = (ObjectWaiter *) Atomic::cmpxchg_ptr (NULL, &_cxq, w) ;
             if (u == w) break ;
             w = u ;
          }
          ObjectWaiter * q = NULL ;
          ObjectWaiter * p ;
          for (p = w ; p != NULL ; p = p->_next) {
              guarantee (p->TState == ObjectWaiter::TS_CXQ, "Invariant") ;
              p->TState = ObjectWaiter::TS_ENTER ;
              p->_prev = q ;
              q = p ;
          }
          ObjectWaiter * Tail ;
          for (Tail = _EntryList ; Tail != NULL && Tail->_next != NULL ; Tail = Tail->_next) ;
          if (Tail == NULL) {
              _EntryList = w ;
          } else {
              Tail->_next = w ;
              w->_prev = Tail ;
          }
      }

    // cxq 队列插入到_EntryList 头部
      if (QMode == 4 && _cxq != NULL) {
          // 把 cxq 队列放入 EntryList
          // 此策略确保最近运行的线程位于 EntryList 的头部
          w = _cxq ;
          for (;;) {
             assert (w != NULL, "Invariant") ;
             ObjectWaiter * u = (ObjectWaiter *) Atomic::cmpxchg_ptr (NULL, &_cxq, w) ;
             if (u == w) break ;
             w = u ;
          }
          assert (w != NULL              , "invariant") ;

          ObjectWaiter * q = NULL ;
          ObjectWaiter * p ;
          for (p = w ; p != NULL ; p = p->_next) {
              guarantee (p->TState == ObjectWaiter::TS_CXQ, "Invariant") ;
              p->TState = ObjectWaiter::TS_ENTER ;
              p->_prev = q ;
              q = p ;
          }
          if (_EntryList != NULL) {
              q->_next = _EntryList ;
              _EntryList->_prev = q ;
          }
          _EntryList = w ;
      }
      w = _EntryList  ;
      if (w != NULL) {
          assert (w->TState == ObjectWaiter::TS_ENTER, "invariant") ;
          ExitEpilog (Self, w) ;
          return ;
      }
      w = _cxq ;
      if (w == NULL) continue ;

      for (;;) {
          assert (w != NULL, "Invariant") ;
          ObjectWaiter * u = (ObjectWaiter *) Atomic::cmpxchg_ptr (NULL, &_cxq, w) ;
          if (u == w) break ;
          w = u ;
      }

      if (QMode == 1) {
         // QMode == 1 : 把 cxq 倾倒入 EntryList 逆序
         ObjectWaiter * s = NULL ;
         ObjectWaiter * t = w ;
         ObjectWaiter * u = NULL ;
         while (t != NULL) {
             guarantee (t->TState == ObjectWaiter::TS_CXQ, "invariant") ;
             t->TState = ObjectWaiter::TS_ENTER ;
             u = t->_next ;
             t->_prev = u ;
             t->_next = s ;
             s = t;
             t = u ;
         }
         _EntryList  = s ;
         assert (s != NULL, "invariant") ;
      } else {
         // QMode == 0 or QMode == 2
         _EntryList = w ;
         ObjectWaiter * q = NULL ;
         ObjectWaiter * p ;
          // 将单向链表构造成双向环形链表；
         for (p = w ; p != NULL ; p = p->_next) {
             guarantee (p->TState == ObjectWaiter::TS_CXQ, "Invariant") ;
             p->TState = ObjectWaiter::TS_ENTER ;
             p->_prev = q ;
             q = p ;
         }
      }
      if (_succ != NULL) continue;
      w = _EntryList  ;
      if (w != NULL) {
          guarantee (w->TState == ObjectWaiter::TS_ENTER, "invariant") ;
          ExitEpilog (Self, w) ;
          return ;
      }
   }
}
```

这段代码很复杂， 简单总结就是先从`_cxq`队列中取出节点唤醒线程开始竞争锁，不行的话就把`_cxq`加入到`entryList`中，然后再从`entryList`中唤醒节点进行锁竞争，用一个图来总结队列协作的过程:

![](https://xiaomi-info.github.io/2020/03/24/synchronized/sync_2.png)



## 总结

本文通过openJdk的hotspot虚拟机源码来分析了Synchronized的实现原理和锁升级过程，可以看出Synchronized的重量级锁的设计思想和理念和AQS非常相似，所以知识都是互通的。因为笔者的C++可以说是0基础，这篇文章是粗略读了一下源码结合网上的文章写的，有不对之处还请多多执教。



参考:

[synchronized 实现原理]:https://xiaomi-info.github.io/2020/03/24/synchronized/
[Java Synchronized JVM实现分析]:https://sq.163yun.com/blog/article/188805878260191232
[Java的wait()、notify()学习三部曲之一：JVM源码分析]:https://zhuanlan.zhihu.com/p/75882528