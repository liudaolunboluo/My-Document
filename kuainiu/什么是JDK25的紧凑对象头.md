# 什么是JDK25的紧凑对象头

最新LTS版本jdk25会在下个月发布，在这个月也就是8月份，jdk25已经处于`Release Candidate phase`也就是发布冻结版本，也就是说jdk25所有的jep均已确定：https://openjdk.org/projects/jdk/25/

在发布的jep中笔者注意到一个很有意思的jep：

```java
519:	Compact Object Headers
```

紧凑对象头（这个特性是在jdk24作为实验特性发布的，jdk25中成为正式特性）

那今天笔者就和大家来看看压缩对象头这个新特性，没耐心读完全篇的读者可以直接拉到最后一节总结模块

在本篇文章中，笔者会先简单介绍什么是对象头，然后什么是紧凑对象头最后介绍紧凑对象头的实现原理



## 1、什么是Java对象头

在研究这个jep之前，我们需要一点预备知识也是陈年八股：什么是对象头？

我们这里问一问伟大的ChatGpt（笔者是保守派，别问为什么不用deepseek什么的）

>Java 对象头是每个 Java 对象在内存中存储的元数据部分。它包含了与对象相关的各种信息，这些信息对于 Java 虚拟机（JVM）管理对象的生命周期、内存分配和垃圾回收等操作至关重要。对象头通常包含以下几个部分：
>
>#### 1. **标记字段（Mark Word）**
>
>标记字段是对象头的主要部分，包含了以下信息：
>
>- **对象的哈希码**：用于支持 `hashCode()` 方法。
>- **GC 分代信息**：指示对象的年龄和分代信息，帮助垃圾回收器决定对象的存活状态。
>- **锁状态信息**：指示对象是否被锁定，以及锁的类型（如偏向锁、轻量级锁、重量级锁）。
>- **类指针**：指向对象的类元数据，帮助 JVM 确定对象的类型。
>
>#### 2. **类型指针（Class Pointer）**
>
>类型指针指向对象的类元数据，通常是对象头的第二部分。它允许 JVM 知道该对象属于哪个类，并获取该类的相关信息（如方法、字段等）。
>
>#### 3. **对齐填充（Padding）**
>
>为了提高内存访问的效率，JVM 可能会在对象头后面添加一些填充字节，以确保对象在内存中的大小是某个特定字节对齐的。例如，在 64 位 JVM 上，通常会将对象大小对齐到 8 字节。
>
>#### 对象头的结构
>
>在 HotSpot JVM 中，Java 对象头的结构通常如下（以 64 位 JVM 为例）：
>
>```
>+----------------+----------------+
>|   Mark Word    |  Class Pointer  |
>+----------------+----------------+
>|   Padding      |                |
>+----------------+----------------+
>```
>
>#### 影响
>
>- **内存占用**：对象头的大小（通常为 12 字节或 16 字节，具体取决于 JVM 和对象的状态）会影响对象的内存占用。
>- **性能**：对象头中的信息对于垃圾回收和线程同步等操作至关重要，因此在设计类和对象时，了解对象头的结构有助于优化性能。
>
>#### 总结
>
>Java 对象头是 JVM 用于管理对象的元数据部分，包含了与对象的状态、类型和内存管理相关的信息。理解对象头的结构有助于深入理解 JVM 的工作原理，特别是在性能优化和内存管理方面。

我们可以简单来说，对象头就是`markWord` 加类指针，源码里也是这样的(jdk 11)：

```c++
class oopDesc {
  friend class VMStructs;
  friend class JVMCIVMStructs;
 private:
  volatile markOop _mark;
  union _metadata {
    Klass*      _klass;
    narrowKlass _compressed_klass;
  } _metadata;
```

在jdk24中除了把`markOop`改成了`markWord`之外都是一样的

这里`_mark`就是我们上文介绍的`markWord`，下面的`_metadata`就是类指针，包含了两个成员：`_klass`普通的类指针，`_compressed_klass`开启了压缩指针的类指针，说到指针这里就不得不提一下对象头的压缩指针了：在 64 位的 Java 虚拟机中，默认情况下，指针的大小是 64 位（8 字节）。如果应用程序的堆内存小于 32 GB，JVM 可以使用压缩指针，将指针的大小减少到 32 位（4 字节）。在jdk6的u23版本已经默认开启了这个配置不用再额外的配置`-XX:+UseCompressedOops`参数了，如果是大于32GB的堆则可以使用`-XX:ObjectAlignmentInBytes`参数：

```java
-XX:ObjectAlignmentInBytes=alignment
Sets the memory alignment of Java objects (in bytes). By default, the value is set to 8 bytes. The specified value should be a power of 2, and must be within the range of 8 and 256 (inclusive). This option makes it possible to use compressed pointers with large Java heap sizes.

The heap size limit in bytes is calculated as:

4GB * ObjectAlignmentInBytes

Note: As the alignment value increases, the unused space between objects also increases. As a result, you may not realize any benefits from using compressed pointers with large Java heap sizes.
```

那么简单来说，我们对象头就分为markWord和类指针，类指针有压缩指针的优化，可以在64位JVM上让类指针从8个字节减少到4个字节

简单来说对象头就是分为`markWord` 加类指针，在64位的jvm中：`markWord`占64位，类指针占32到64位

## 2、什么是紧凑对象头



在jdk24中因为是实验性特性，紧凑对象头通过参数`-XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders`开启

jdk25中正式升级为正式特征，只需要通过 `-XX:+UseCompactObjectHeaders`开启

那么紧凑对象头有什么用？

首先我们要知道，一个java的空类的实例的对象占据多少大小？在64位JVM中, 对象头占据的空间是 12字节=96位=64位(markWord)+32位(开启了压缩指针的类指针)到16字节=128位=64位(markWord)+64位(没有压缩指针的类指针),所以一个空类的实例至少占用12字节到16字节，但是Java 程序中的对象通常较小。Lilliput[项目进行的实验](https://wiki.openjdk.org/display/lilliput/Lilliput+Experiment+Results)表明，许多工作负载的平均对象大小为 256 到 512 位（32 到 64 字节）。这意味着仅凭对象头就能获取超过 20% 的内存占用。因此，即使对象头大小略有改进，也能显著减少内存占用、数据局部性，还能降低 GC 压力。Lilliput 项目的早期采用者已在实际应用中进行了尝试，证实内存占用通常可以减少 10% 到 20%。

**所以紧凑对象头的目的是通过减少对象头的占用从而降低jvm的内存占用，降低GC压力降低cpu负载**

各种实验也表明，启用紧凑的对象头确实可以提高性能：

- 在一种设置中，SPECjbb2015 基准测试使用的[堆空间减少了 22%，CPU 时间减少了 8%](https://github.com/rkennke/talks/blob/master/Lilliput-FOSDEM-2025.pdf)。
- 在另一种环境下，SPECjbb2015使用 G1 和并行收集器执行的垃圾收集次数[减少了 15% 。](https://bugs.openjdk.org/browse/JDK-8350457?focusedId=14766358&page=com.atlassian.jira.plugin.system.issuetabpanels%3Acomment-tabpanel#comment-14766358)
- 高度并行的 JSON 解析器基准测试[运行时间减少了 10%](https://www.reddit.com/r/scala/comments/1jptiv3/xxusecompactobjectheaders_is_your_new_turbo/?rdt=40432)。

## 3、紧凑对象头实现原理

那么紧凑对象头是怎么来压缩我们的对象头的呢？

我们先看当前markWord的结构：

```sh
Mark Word:
 64                     39                              8    3  0
  [.......................HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH.AAAA.TT]
         (Unused)                      (Hash Code)     (GC Age)(Tag)
```

然后类指针：

```sh
Class Word (没有压缩指针):
64                                                               0
 [cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc]
                          (Class Pointer)

Class Word (压缩指针):
32                               0
 [CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC]
     (Compressed Class Pointer)
```

然后我们把markWord和类指针放在一起，相信聪明的读者马上就知道压缩对象头是怎么实现的了:

```sh
  64                     39                              8    3  0
  [.......................HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH.AAAA.TT]
         (Unused)                      (Hash Code)     (GC Age)(Tag)

  32                               0
  [CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC]
     (Compressed Class Pointer)
```

markWord里有相当一部分大概25位是没有使用的，类指针在启用了压缩指针的前提下是32位，如果我们把类指针在压缩一下然后放到markWord的未使用空间里不就完了吗？恭喜你！能想到这一点的读者已经和JVM的开发者一样聪明了

事实上对于紧凑的对象头，确实是通过将压缩形式的类指针纳入标记字中来消除markWord和类指针之间的划分：

```sh
Header (compact):
64                    42                             11   7   3  0
 [CCCCCCCCCCCCCCCCCCCCCCHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHVVVVAAAASTT]
 (Compressed Class Pointer)       (Hash Code)         /(GC Age)^(Tag)
                              (Valhalla-reserved bits) (Self Forwarded Tag)
```

jvm通过改变压缩类指针编码将压缩类指针的大小从 32 位减少到 22 位这样就可以把类指针放到markWord里了，这样可以直接减少32位也就是4个字节的大小，并且哈希码的大小保持不变，那么就还有3位空白的，所以开发者抽了tag的一位一共保留了4 位来供[Valhalla 项目](https://openjdk.org/projects/valhalla/)（简单来说这个项目是为了增强 Java 对象模型）将来使用。

那么我们都知道对象头和`synchronized`、GC都有密切联系，那么紧凑对象头对这些老的功能都兼容性如何呢？我们分开来看：

 #### synchronized

`synchronized`分为轻量级锁和重量级锁，轻量级锁又分为`lightweight`轻量级锁模式和传统模式，`lightweight`模式是jdk21新增的轻量级`synchronized`，在jdk24开始变成默认的`synchronized`模式

- `lightweight`模式轻量级锁：

在上文[JDK24是如何解决虚拟线程在Synchronized下Pinning问题（1）——轻量级锁如何解决](https://mp.weixin.qq.com/s/k-58gUX_RU9K0c1-sD3SBg)中笔者详细分析了这个模式下轻量级锁的上锁过程等，大家不清楚的可以去看看这篇，总之在`lightweight`模式下轻量级锁是通过改写对象头中的markword来实现的：

```c++
/构造轻量级锁标记，也就是一个新的markWord值，就是在原 Mark Word 的内容中追加了指向锁记录的指针
    //这里不会传入任何值给markword，所以这里只是标记了被锁定了，不会给对象头写是被谁锁定的
    markWord locked_mark = mark.set_fast_locked();
    //原始的markWord
    markWord old_mark = mark;
    //cas的方式去把新的markWord值写入到对象头中
    mark = obj->cas_set_mark(locked_mark, old_mark);
```

这里修改markword就是：**标记位（上面图示中的Tag）** 从 `01`（未锁定）翻转到 `00`（轻量级锁定）。

这里无需额外数据结构，也不占用对象头的其他位，因此与紧凑对象头兼容。

- 传统模式下的轻量级锁

笔者之前介绍过老版的`synchronized`源码，老版里面轻量级锁就是栈锁——`basicLockObject`:通过将对象头markword复制到线程栈中，并将原对象头markword覆盖为指向栈中副本的指针，实现对象与锁线程的关联。注意这里会把原来对象头的markword内容都覆盖哦，所以无法兼容紧凑对象头，因为紧凑对象头中的markword包含了类指针，这会影响到类指针的工作，所以传统模式的 `synchronized`和紧凑对象头无法兼容，若 JVM 同时配置了传统`synchronized`和紧凑对象头，紧凑对象头会被自动禁用。

- 重量级锁

在前面的文章中笔者分析了，在重量级锁下传统模式和`lightweight`没有区别还是用`monitor`监视器来实现（新版本新增了`monitorTable`这种数据结构来缓存`monitor`但是不影响底层实现原理只是优化）,`monitor`监视器对对象头的影响就是通过原子操作将对象头的标记位（Tag）从 `01`（未锁定）或 `00`（轻量级锁定）翻转到 `10`（监视器锁定），并创建新的数据结构表示对象的监视器。所以重量级锁下和紧凑对象头也是兼容的

#### GC

在GC中使用到对象头的有两个阶段：`GC forwarding`和`GC walking`简单来说就是对象重定位和扫描堆这两个阶段

##### `GC forwarding` 

 会发生对象重定位的情况主要是两种：复制和移动，对应我们GC算法的复制算法和标记整理算法

所以我们从复制和移动两个角度来看：

- 复制：GC 复制对象到新空间时，会在旧对象的头部存储指向新对象的转发指针，新对象保留原始对象头。访问旧对象头时，会自动跟随转发指针到新对象，这对于紧凑对象头来说没有问题，因为对象头是全部保留的，如果没有足够空间来复制的话就会转换为自转发（转发指针指向自身），这就有问题了因为自转发会覆盖整个对象头，导致紧凑对象头中的类型信息丢失，主要是类指针被覆盖，那么解决方案就是设置对象头的**第三位（third bit）** 标记自转发，而非覆盖整个头部，保留类型信息。
- 移动：堆内存耗尽时，GC 会将对象 移动 到更低地址（同一空间内），分四阶段：标记存活对象→计算新地址（存在对象头中）→更新引用→复制对象。第 2 阶段（计算新地址）会覆盖原始对象头，而紧凑对象头包含所有对象的关键类信息（传统对象头仅部分对象有 “重要信息”），若用传统 “侧边表（side table）” 保存头部，会消耗大量原生堆内存，解决方案是转发指针编码在对象头的**低 42 位**，支持最大 8TB 堆内存。若使用 ZGC 以外的 GC 且堆内存超过 8TB，紧凑对象头会被自动禁用。

##### `GC walking` 

GC的时候需要扫描堆内存中的对象，通过访问对象的**类指针**确定对象大小，在紧凑对象头中我们把类指针通过编码压缩的方式从32位修改为了22位，所以在访问类指针的时候我们需要把编码的类指针还原为32位的类指针，这个解码需通过简单运算，但是开销远小于内存访问成本，所以这个也是兼容的



## 4、总结

一句话总结紧凑对象头就是：

**把类指针从32位通过编码压缩为了22位，然后放入了`markWord`中，这样我们的对象头就从96bit 12字节减少到了64位 8字节，并且紧凑对象头不兼容传统模式的`synchronized`和超过8TB的非ZGC的堆**

