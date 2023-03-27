# 强大的渲染和统计整个调用链路上的所有性能开销和追踪调用链路工具——tracer

## tracer是什么

tracer（追踪者）是强大的渲染和统计整个调用链路上的所有性能开销和追踪调用链路工具，他可以在0代码侵入的基础上观测和打印你的方法的调用链路的性能开销，并且提供前端界面打印结果。

tracer源码地址：https://github.com/liudaolunboluo/tracer

请大家多多试用，欢迎多提issue和pr



## 使用

### 1.1 springboot使用：

只支持 spring boot 2

1、pom引入：

```xml
<dependency>
  <groupId>io.github.liudaolunboluo.tracer</groupId>
  <artifactId>tracer-spring-boot-starter</artifactId>
  <version>1.1.0</version>
</dependency>
```

2、在项目的配置文件中配置：

```yaml
spring:
  tracer:
    enabled: true
```

开启tracer，请注意如果不配置此配置项默认为false也就是不启动tracer。

3、在项目的配置文件中配置tracer：

```yaml
tracer:
	#是否开启jdk自带方法的追踪，默认为false
  isSkipJdk: true
  #要追踪的类的集合
  target-class-list:
  	#类的全路径
    - full-class-name: com.xxx.yyy.AService
    #要追踪的方法集合
      target-method-list:
      	#方法名
        - methodName: test
        	#是否保存原始trace之后的json格式数据，默认为否
          isSaveOriginalResult: true
          # 耗时上限，即超过该配置值的耗时的结果才会打印
          cost-more-than: 10
          # 最大打印次数，默认为1000
          max-output: 3
        - methodName: test2
          isSaveOriginalResult: true
        - methodName: hgetall
    - full-class-name: com.xxx.yyy.BService
      target-method-list:
        - methodName: testA
        - methodName: testB
        - methodName: testC
```

4、启动你的项目，日志里有类似于如下打印：

```
 attach 20221 success!
 tracer launch success
```

则代表tracer启动成功。

5、打开页面：

```json
http://ip:port/{context-path}/view.html?baseUrl={context-path}
```

注：{context-path}是你spring配置里：

```yaml
server:
  servlet:
    context-path: {context-path}
```

配置的值，也就是你的服务公共前缀。

6、运行你配置的方法，即可以在页面上实时显示trace之后的结果（不用刷新界面）:

![image-20230209200101854](/Users/zhangyunfan/Documents/tracer/demo.png)

#### 1.1.1、自定义回调

如果你想自己处理trace之后的结果，那么可以自定义回调

新建一个类继承`TraceCallback`这个抽象类并且自定义实现`callback`方法，最后加上`@Component`注入到spring 中即可

注意，回调不宜过多，因为最后调用回调的都是被观测方法主线程同步串行调用，如果回调过多或者单个回调用时过长就会严重影响主方法的性能，所以建议不要自定义太多回调并且如果单个回调耗时太长建议再回调实现里换成异步实现。

### 1.2、非spring boot使用：

pom中引入:

```
<dependency>
  <groupId>io.github.liudaolunboluo.tracer</groupId>
  <artifactId>tracer-launcher</artifactId>
  <version>1.1.0</version>
</dependency>
```

然后再代码中启动tracer：

```java
TracerLauncher tracerLauncher = new TracerLauncher();
        TracerAttachParam param = new TracerAttachParam();
        param.setIsSkipJdk(true);
        param.setPid("pid");
param.setTargetClassList(List.of(TargetClass.builder().fullClassName("com.XXX.AService")
                .targetMethodList(List.of(TargetMethod.builder().methodName("process").build(), 	TargetMethod.builder().methodName("invoke").build()))
                .build()));
tracerLauncher.launch(param, List.of(new XXTraceCallback()));
```

注意：如果是以这种方式启动的话是没有默认的回调实现的， 也就是说结果不会自己打印在页面上，需要自己在启动的时候:`tracerLauncher.launch(param, List.of(new XXTraceCallback()));`来传入自己的回调实现，



## 实现原理

本项目基于Java agent和Java字节码增强技术，**核心逻辑字节码增强插拔方法参考了Arthas的trace实现**。

大概的原理如下图：

![原理图](/Users/zhangyunfan/Documents/tracer/原理图.png)

