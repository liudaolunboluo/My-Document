# 从排查一次服务器内存满了的原因引发对JVM的MetaSpace的思考



##  排查过程以及问题根源

今天上午测试反馈我们开发环境的某个服务挂了，于是习惯性的去重启结果重启失败，然后tomcat日志报错内存不足，于是我用`free -g`命令查看了了一下：

```shell
           total       used       free   
Mem:            31         30          1 
-/+ buffers/cache:         30          1
Swap:            3          3          0
```



  果然是满了，于是用命令查看目前内存占用最大的，是我们的一个对外接口服务，占了10多个G的内存，这个肯定不正常，然后测试告知刚刚正在对这个服务做压力测试本来想去后台管理系统改下配置的结果发现挂了，听到这我心中一整窃喜终于遇到活的JVM内存问题了。于是用`jmap -heap`命令查看目前的堆信息和jvm配置：

```shell
Heap Configuration:
   MinHeapFreeRatio         = 0
   MaxHeapFreeRatio         = 100
   MaxHeapSize              = 8403288064 (8014.0MB)
   NewSize                  = 175112192 (167.0MB)
   MaxNewSize               = 2800746496 (2671.0MB)
   OldSize                  = 351272960 (335.0MB)
   NewRatio                 = 2
   SurvivorRatio            = 8
   MetaspaceSize            = 21807104 (20.796875MB)
   CompressedClassSpaceSize = 1073741824 (1024.0MB)
   MaxMetaspaceSize         = 17592186044415 MB
   G1HeapRegionSize         = 0 (0.0MB)

Heap Usage:
PS Young Generation
Eden Space:
   capacity = 2556428288 (2438.0MB)
   used     = 1936392792 (1846.6880722045898MB)
   free     = 620035496 (591.3119277954102MB)
   75.74602429059023% used
From Space:
   capacity = 120061952 (114.5MB)
   used     = 0 (0.0MB)
   free     = 120061952 (114.5MB)
   0.0% used
To Space:
   capacity = 120586240 (115.0MB)
   used     = 0 (0.0MB)
   free     = 120586240 (115.0MB)
   0.0% used
PS Old Generation
   capacity = 5602541568 (5343.0MB)
   used     = 5602459824 (5342.92204284668MB)
   free     = 81744 (0.0779571533203125MB)
   99.99854094790716% used

```

  来分析一下：`MaxHeapSize`最大的堆大小是8014MB，不到8个G，然后目前使用情况是Eden区用了75%，from和to都是空的，老年代占用了99.9%，看来还没有满所以没有报OOM（小问题：此时大小最小为多大的对象进来会报OOM），然后此时堆所占内存是：Eden区的1846MB加上老年代的5342MB，这个大结果和刚刚命令查看的内存大小占用对不上。其次老年代快要满了，说明此前的Full GC没有回收这些对象，为什么这些对象没有被回收呢？由于测试停止压力测试了，所以没有人用这个系统了，目前来看应该是不会主动的GC的，于是调用命令`jmap -histo:live` 来手动触发一次Full GC，顺便看看当前存活对象信息（关于`jmap -histo:live`命令是否会主动触发Full GC可以看https://stackoverflow.com/questions/6418089/does-jmap-force-garbage-collection-when-the-live-option-is-used），然后我们再次用`jmap -heap`命令查看当前堆信息：

```
Heap Usage:
PS Young Generation
Eden Space:
   capacity = 2556428288 (2438.0MB)
   used     = 1150567360 (1097.2665405273438MB)
   free     = 1405860928 (1340.7334594726562MB)
   45.00683103065397% used
From Space:
   capacity = 120061952 (114.5MB)
   used     = 0 (0.0MB)
   free     = 120061952 (114.5MB)
   0.0% used
To Space:
   capacity = 120586240 (115.0MB)
   used     = 0 (0.0MB)
   free     = 120586240 (115.0MB)
   0.0% used
PS Old Generation
   capacity = 5602541568 (5343.0MB)
   used     = 5602454016 (5342.91650390625MB)
   free     = 87552 (0.08349609375MB)
   99.99843728067097% used
```

Eden Space的used从刚刚的75%下降到了45%（实践证明了`jmap -histo:live`可以触发Full GC）但是PS Old Generation老年代的大小还是99.9%，说明此时老年代的对象的确不能被回收（小问题：JVM怎么判断一个对象是否可以回收？）,这我就纳闷儿了，首先是堆大小并不是应用内存大小其次是为啥老年代的对象都不能回收呢？对接口的压力测试为啥会创建这么多对象呢？然后我用`jstat -gccapacity` 命令查看了应用详细的堆内存统计：

```shell
S0C    S1C    S0U    S1U      EC       EU        OC         OU       MC     MU    CCSC   CCSU   YGC     YGCT    FGC    FGCT     
117248.0 117760.0  0.0    0.0   2496512.0 1959753.9 5471232.0  1471152.2  95320.0 90721.7 12120.0 11339.5   4452  108.203  542
```

各个参数含义：

```shell
NGCMN：新生代最小容量
NGCMX：新生代最大容量
NGC：当前新生代容量
S0C：第一个幸存区大小
S1C：第二个幸存区的大小
EC：伊甸园区的大小
OGCMN：老年代最小容量
OGCMX：老年代最大容量
OGC：当前老年代大小
OC:当前老年代大小
MCMN:最小元数据容量
MCMX：最大元数据容量
MC：当前元数据空间大小
CCSMN：最小压缩类空间大小
CCSMX：最大压缩类空间大小
CCSC：当前压缩类空间大小
YGCT：年轻代gc次数
FGCT：老年代GC次数
```

 到这里不知道大家发现了什么没有，对！我们的MC MetaSpace有差不多1个G接近2个G的大小，然后现在MetaSpace加上堆内存差不多对得上了8个G的内存占用，但是为什么我们的MetaSpace这么大？？于是第一反应就是用`jmap`命令去dump堆的快照来分析，但是dump出来的快照有7个G，用`jhat`命令分析不出来因为太大了然后也没有办法很快的下载到我本地，我就只能反过去又去看了下刚刚`jmap -histo:live`命令输出的存活对象的信息：

```shell
num     #instances         #bytes  class name
----------------------------------------------
   1:      35457138     5091182312  [C
   2:      35454109      850898616  java.lang.String
   3:       5879372      376279808  com.sinoiov.usercenter.common.remote.dto.log.ApiServiceLogDto
   4:       5948644      190356608  java.util.concurrent.ConcurrentHashMap$Node
   5:       5879910      141117840  java.lang.Long
   6:           870       34205672  [Ljava.util.concurrent.ConcurrentHashMap$Node;
   7:         24164       20591328  [B
```

存活前三的对象依次是：char、String、ApiServiceLogDto；前面两个是和字符串有关，第三个是我们项目里自己的对象，于是也许这个是个突破口，我在代码里全局搜了一下`ApiServiceLogDto`这个对象，发现了以下代码：

```java
public static void saveRequestLog(ApiServiceLogDto requestLog) {
		...
            logMap.put(requestLog.getId(), requestLog);
		}
```

然后这个代码是那里调用的呢？是在一个有`@RestControllerAdvice`注解并且实现了`RequestBodyAdvice`接口的类里面用了，总之调用所有接口之前都会调用这段代码，这个`logMap`对象是这样定义的：

```java
private static Map<String, ApiServiceLogDto> logMap = new ConcurrentHashMap<>();
```

对！这个logMap是static的，他是放在MetaSpace里的，所以MetaSpace是被这个玩意儿占了，然后因为`requestLog`是在堆里new的，然后又有这个logMap的引用所以他就会一直在老年代和Eden区里不会被Full GC回收走，总之就是赖在我们的堆里了。然后刚刚的存活对象信息也可以支持这个分析，排名第三的可是`ConcurrentHashMap`的Node啊。



## 总结

总结一下以上问题的根源其实是因为我们的MetaSpace太大了引发的内存不足，因为堆里面存活的对象的引用可都是在MetaSpace里，所以罪魁祸首也就是这个MetaSpace了。

MetaSpace是Jdk8之后才引入的中文叫元空间，是存放静态变量和一些类加载信息的地方，关于元空间的详细讲解可以看这里：https://www.jianshu.com/p/a6f19189ec62   这个不是本文的重点。

JDK8之后JVM新增两个参数是`MetaspaceSize`元空间大小和`MaxMetaSpaceSize`元空间的最大大小，如果你不设置那这个最大值和最小值的话那么JVM默认的是多少呢？上面有，是：

```shell
  MetaspaceSize            = 21807104 (20.796875MB)
  MaxMetaspaceSize         = 17592186044415 MB
```

也就是说你不设置的话，元空间最大的值是无限大的也就是你服务器剩余的内存，元空间要在大小达到了最大值之后才会发生Full GC，所以如果你没有设置

`MaxMetaspaceSize`的话可能他永远都不会发生GC。并且如果`MetaspaceSize`默认20MB是过于小了，MetaSpace在从最小变成最大的时候是会stw的哦，所以这两个参数在设置的时候最好可以设置成一样的。



**所以啊，大家在优化JVM的时候一定要加`MaxMetaspaceSize`和`MetaspaceSize`这两个参数来控制一下元空间大小啊**