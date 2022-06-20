# 使用Arthas的Http Api来打印压测的时候代码执行时间

## 1、背景

相信Arthas已经是大家耳熟能详的工具了，这里就不再赘述了，但是不知道大家有没有遇到过在非本地环境使用Arthas的情景，比如QA环境、线上环境出现了一个Bug然后又忘了在关键地方打日志，这个时候用Arthas的`watch`命令就可以很方便的查看了，但是因为是在非本地环境所以使用Arthas很麻烦，可能你需要远程到服务器上去启动arthas或者你们用的容器化部署根本就不方便使用传统Arthas，虽然Arthas官方提供了tunnl server这种远程使用Arthas的办法，但是由于安全问题有些公司（比如我司）在QA、生产环境禁止了websocket所以也不能用，这个时候就轮到Arthas的第三种方式出场了——Http Api方式，这里以压测这个场景为例来讲讲怎么使用Arthas的Http Api。



## 2、集成Arthas的Http Api

首先在2022年5月份这个时间点我们假设大部分服务都是SpringBoot服务（非SpringBoot服务也可以使用，具体请参看官方文档：https://arthas.aliyun.com/doc/spring-boot-starter.html#spring-boot），我们只需要集成Arthas的starter就可以了：

````xml
<dependency>
  <groupId>com.taobao.arthas</groupId>
  <artifactId>arthas-spring-boot-starter</artifactId>
  <version>${arthas.version}</version>
</dependency>
````

这样的话，应用启动后，spring会启动arthas，并且attach自身进程。我们在配置文件里需要配置Arthas的一些基本配置，这里只需要配置Arthas的Http端口即可：

```yml
arthas:
  httpPort: 8083
```

当然这里也支持tunnl-server这种方式来远程使用，具体可以参考官方文档：https://github.com/alibaba/arthas/blob/master/arthas-spring-boot-starter/src/main/java/com/alibaba/arthas/spring/ArthasProperties.java

这样配置之后我们就会在应用所在的环境上又启动了一个Http端口为8083的Arthas服务了，并且这个Arthas是在你服务启动的时候被Spring启动的，示意图如下：



![arthas的starter示意图](/Users/zhangyunfan/Desktop/arthas的starter示意图.png)

这个时候Arthas的Api的host地址就是:`http://你的服务ip`,当然了如果你像我司一样，容器对外只开放一个端口的话可以写一个controller来转发请求，把请求转发给`localhost:8083`，这样你直接访问你服务的端口就可以了。



## 3、使用Arthas的Api来记录每行代码的执行时间

我们现在的场景是我们在做压测的时候需要知道每行代码执行的时间来看瓶颈时在哪里，虽然有公司自研的链路图，但是链路图只能查看RPC和数据库请求的时间，我们只能排除掉RPC的影响，但是有时候我们需要清楚我们的方法里每一行代码的运行时间来定位到底是哪里慢，这个时候第一个想到的自然就是我们Arthas的trace命令了，trace命令可以很方便的打印出被观测方法中的每一行代码的执行时间，接下来我们就看看如何使用Arthas的Api来使用Trace命令：

Arthas的Api文档地址：https://arthas.aliyun.com/doc/http-api.html

通过查看文档可以知道，我们的Arthas的Api分为两类——直接返回的结果的和需要等待返回结果的，比如version命令和trace命令，众所周知，trace命令执行之后就会等待直到被观测到方法执行之后才会打印结果，而version命令直接就可以返回当前arthas版本信息。这里我们以Trace命令为例，我们可以配合Arthas的会话接口和一次性执行接口来同步执行trace和异步执行trace。

### 3.1、同步执行trace

同步执行很简单，参数就和version的一样：

```json
{
  "action":"exec",
  "command":"trace",
  "execTimeout":3000
}
```

只是这里需要新增一个参数`execTimeout`，超时时间，因为同步执行的效果就是发出Http请求之后就会一直等待直到被观测到方法有被执行一次然后就会返回trace到结果。



### 3.2、异步执行trace

异步执行麻烦一点，需要先开启一次会话，然后在这个开启的会话中执行trace命令然后在拉取这个trace命令在这次会话中的结果，所以我们需要调用三次接口。

第一次调用是开启一个会话：

```json
{
  "action":"init_session"
}
```

这次调用会返回sessionId和consumerId，调用结果：

```json
{
   "sessionId" : "b09f1353-202c-407b-af24-701b744f971e",
   "consumerId" : "5ae4e5fbab8b4e529ac404f260d4e2d1_1",
   "state" : "SUCCEEDED"
}
```

第二次执行是指定会话ID执行命令,sessionId使用上一步返回的:

```json
{
  "action":"async_exec",
  "command":"trace",
  "sessionId" : "b09f1353-202c-407b-af24-701b744f971e"
}
```

这里会返回jobId，如果在同一次会话中执行多次命令，那这些多次命令的结果就可以用jobId来过滤。

返回结果：

```json
{
   "sessionId" : "2b085b5d-883b-4914-ab35-b2c5c1d5aa2a",
   "state" : "SCHEDULED",
   "body" : {
      "jobStatus" : "READY",
      "jobId" : 3,
      "command" : "trace XXX "
   }
}
```

第三次执行就是拉取异步命令执行的结果，sessionId和consumerId使用第一步返回的：

```json
{
  "action":"pull_results",
 	"sessionId" : "b09f1353-202c-407b-af24-701b744f971e",
  "consumerId" : "5ae4e5fbab8b4e529ac404f260d4e2d1_1",
}
```

如果被观测的方法没有执行那么返回的结果的results就是为空：

```json
{
	"body": {
		"results": [],							
		"sessionId": "25808fd9-ec3f-4585-bcd6-736f2aa2ae60",
		"consumerId": "14b3a707601c4926add9d42e6c026215_1",
		"state": "SUCCEEDED"
}
```

如果执行了的话就会返回trace之后的结果：

![WX20220504-153703](/Users/zhangyunfan/Desktop/WX20220504-153703.png)

就像这样

### 3.3、解析trace命令结果

无论同步还是异步我们都需要对trace的结果进行解析，因为返回的是一个复杂的json，我们是不能直接看的，这里建议写代码来解析，他就是子节点结构，chilren里就是内部代码执行的时间，用递归就可以了，非常简单的。解析出来大概的结构如下：

```
线程名是traffic-2-exec:13:427,执行时间2022-04-24 16:20:43
[660.651129 ms] com.content.service.B$$EnhancerBySpringCGLIB$$acfa2627#getResultsV3
[660.50564 ms] org.springframework.cglib.proxy.MethodInterceptor#intercept
[660.353574 ms] com.fiture.content.taurus.training.resultpage.service.impl.TrainingResultsPageServiceImpl$$EnhancerBySpringCGLIB$$dcc0e34f#getResultsV3
[660.315358 ms] org.springframework.cglib.proxy.MethodInterceptor#intercept
[660.22655 ms] com.content.service.impl.B#getResultsV3
     +[23.734993 ms] com.content.service.impl.B#check lineNumber:154
     +[0.002262 ms] java.util.Objects#isNull lineNumber:155
```

基本上就是和传统使用方式那种的结果一样的了，我们就可以查看每行代码在压测之中的表现了。



拉取结果最好是轮训拉取，因为我们不知道什么时候我们的方法会被执行。

所以我们异步执行trace的流程就是：

![执行trace流程](file:///Users/zhangyunfan/Desktop/%E6%89%A7%E8%A1%8Ctrace%E6%B5%81%E7%A8%8B.png?lastModify=1651650732)

## 4、用笔者的工具直接使用Trace



从上一节可以看到如果用http api来使用trae 相当的麻烦，需要调用好几次接口还需要自己来解析结果，所以笔者自己用java和python写了一个小工具，来便捷使用，项目地址：https://github.com/liudaolunboluo/ArthasBootHelper

`server`目录内是一个jar包，引入之后就可以自动启动一个arthas，只需要配置http端口即可：

```yml
arthas:
  httpPort: 8083
```

实际上就是把Arthas的starter包装了一层，自己封装了一下arthas的Http接口中，让trace命令执行更简单

`client`目录内是用python写的调用刚刚包装好了的trace命令接口的程序，`ArthasBootHelperClient.py`是启动之后异步执行trace命令，然后每秒钟拉取接口并且解析结果成Arthas客户端那种便于查看的结果：

```
线程名是traffic-2-exec:13:427,执行时间2022-04-24 16:20:43
[660.651129 ms] com.content.service.B$$EnhancerBySpringCGLIB$$acfa2627#getResultsV3
[660.50564 ms] org.springframework.cglib.proxy.MethodInterceptor#intercept
[660.353574 ms] com.fiture.content.taurus.training.resultpage.service.impl.TrainingResultsPageServiceImpl$$EnhancerBySpringCGLIB$$dcc0e34f#getResultsV3
[660.315358 ms] org.springframework.cglib.proxy.MethodInterceptor#intercept
[660.22655 ms] com.content.service.impl.B#getResultsV3
     +[23.734993 ms] com.content.service.impl.B#check lineNumber:154
     +[0.002262 ms] java.util.Objects#isNull lineNumber:155
```

就像这样并且会把解析之后结果自动保存在指定的目录，在代码里配置即可：

```python
# 服务地址和端口就可以了，因为有转发
host = ''
# 要观测的方法
method = ''
# 结果生成文件的保存路径
result_path = ''
```

`AnalysisTime.py`是对上一步生成的结果做的分析的，目前是计算占用时间最多的行数和他们占用时间百分比的中位数:

```
一共捕获了213次运行
方法
com.content.impl.A#execute lineNumber:159
是最慢的方法次数91次，最慢的时候占总时间比例的中位数为27.7%

方法com.content.impl.A#load lineNumber:165
是最慢的方法次数52次，最慢的时候占总时间比例的中位数为37.6%

方法com.content.impl.B#asyncRun lineNumber:167
是最慢的方法次数29次，最慢的时候占总时间比例的中位数为25.3%

方法com.content.impl.A#process lineNumber:163
是最慢的方法次数25次，最慢的时候占总时间比例的中位数为38.9%

方法com.content.impl.C#process lineNumber:169
是最慢的方法次数7次，最慢的时候占总时间比例的中位数为43.9%

方法com.content.impl.D#loadContext lineNumber:161
是最慢的方法次数5次，最慢的时候占总时间比例的中位数为27.2%

方法com.content.impl.A#check lineNumber:154
是最慢的方法次数4次，最慢的时候占总时间比例的中位数为30.8%
```

如果有其他统计诉求可以自己改造。

因为笔者公司的服务的Docker容器只对外开放了一个端口所以笔者写了一个controller来进行转发然后再用Python拉取接轨解析结果，如果你没有这种只开放一个接口的问题那么直接用Python来开启会话执行命令也是可以的。



## 5、遇到的问题

目前笔者已经在QA环境开始使用了，效果还可以不过还有几个问题暂时无法解决：

1、域名+多节点下的拉取结果。如果有超过一个节点并且是用域名访问的话就会有问题，因为你执行三次接口都需要在一个节点上执行才行，如果是域名+多节点的话你三次接口执行可能都不是在一个节点上就会有问题，目前的解决方案是要么用IP+端口访问或者域名+单节点

2、QPS过高的话拉取不完全部的结果。比如以笔者为例，测试压测试250的QPS，但是笔者最后只拉取到了80次结果，拉取率很低，因为笔者是轮训一秒钟拉取一次，所以这里怀疑是拉取频率太低，拉取的时候就只记录了部分信息剩下的信息都没有拉取到，可以试试增大轮训时间比如0.5秒拉取一次试试。

