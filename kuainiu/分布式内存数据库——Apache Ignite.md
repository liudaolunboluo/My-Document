# 分布式内存数据库——Apache Ignite

 最近在互联网上看到介绍Ignite的文章，说他可以替代redis来做为缓存中间件，并且还支持sql查询，于是笔者对ignite产生了兴趣，这篇文章就来好好介绍一下Ignite



## 1、Apache Ignite是什么

Ignite 来源于尼基塔·伊万诺夫于 2007 年创建的 GridGain 系统公司开发的 GridGain ，2014 年 3 月，GridGain 公司将该软件 90% 以上的功能和代码开源，仅在商业版中保留了高端企业级功能，如安全性，数据中心复制，先进的管理和监控等。 2015 年 1 月，GridGain 通过 Apache 2.0 许可进入 Apache 的孵化器进行孵化，很快就于 8 月 25 日毕业并且成为 Apache 的顶级项目

在Ignite的官网，他们是这样介绍Ignite的：

>Apache Ignite is a distributed database for high-performance computing with in-memory speed.

很简单就是一个数据库且是分布式，并且运行在内存中的

他的架构图如下：

![image-20241226101309974](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20241226101309974.png)

可以看Ignite和Redis一样是以内存作为存储并且在内存中计算的分布式数据库，从这点来说和redis还挺像的（只是这单像，其实差别挺大的）

有意思的是Ignite和Redis都是欧洲人开发的（Redis是意大利人Salvatore Sanfilippo在2008年给自己的初创公司开发的）

在ignite的二进制安装包里的README中写明了Ignite有这些功能：

>* Memory-Centric Storage
>* Advanced Clustering
>* Distributed Key-Value
>* Distributed SQL
>* Compute Grid
>* Service Grid
>* Distributed Data Structures
>* Distributed Messaging
>* Distributed Events
>* Streaming & CEP

接下来笔者会挑几个感兴趣的功能来详细介绍ignite是如何使用的



## 2、部署安装

Ignite支持多种部署方式，比如最简单的二进制安装包安装以及docker、K8S等方式安装，安装方式在官网都有详细的介绍，这里笔者选择最简单的二进制安装包部署安装，如果你想以docker或者K8S来安装可以移步官网的介绍：

https://ignite.apache.org/docs/latest/installation/installing-using-docker

https://ignite.apache.org/docs/latest/installation/kubernetes/amazon-eks-deployment

由于我们是实验性质的，所以我们部署一个单节点，集群的话，ignite是节点之后自动发现的，也就是不存在master节点，这样的好处是可以无感知的增减节点而不用重启集群，并且ignite还可以根据节点的增减自动分配数据

首先我们去官网上下载二进制的安装包：

https://ignite.apache.org/download.cgi

选择下面的`BINARY RELEASES`模块中的包，左边是对应的版本，这里笔者选择的是最新的2.16.0版本，下载下载之后直接解压即可，注意ignite依赖于java，最低版本是jdk8，所以你的服务器或者本地必须要有Java环境才行。

解压之后的目录结构如下:

```shell
.
├── LICENSE
├── MIGRATION_GUIDE.txt
├── NOTICE
├── README.txt
├── RELEASE_NOTES.txt
├── benchmarks
├── bin
├── config
├── docs
├── examples
├── libs
└── platforms
```

这里要关注三个目录：`bin`、 `config` 、`libs`。

`bin`是存放启动脚本的，目录下的`ignite.sh`就是我们的启动脚本，注意这个脚本会在当前上下文中启动ignite，如果ctrl+c了那么ignite就会停止，如果你想在后台启动可以配合nohup命令使用。

`config`是存放配置文件的，我们主要关注`default-config.xml`这个文件，我们后面的改动都在这个文件里

`libs`是ignite的一些依赖的，比如说要是想启用ignite的rest接口模块，就需要把`libs/optional/ignite-rest-http`目录下的都拷贝到`libs`目录下，这样启动ignite之后就可以在8080端口启动一个jetty服务了，默认端口8080，这样就有了rest接口功能了。如果你启用了rest模块，他的默认监听端口是8080，可以通过在启动脚本中增加参数` -v -J-DIGNITE_JETTY_PORT=9099`来修改.

当界面上出现如下内容说明ignite启动成功:

![image-20241226112220163](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20241226112220163.png)

如果你启用了rest模块，可以访问：`/ignite?cmd=version`来确认是否启动成功，如果访问这个地址显示:

```json
{"successStatus":0,"sessionToken":null,"securitySubjectId":null,"error":null,"response":"2.16.0"}
```

也同样说明启动成功





## 3、客户端连接



远端启动了ignite之后，我们下一步就是需要我们的服务去连接他，目前来说ignite支持两种连接方式：thin clinet和rest http，第二种不用多说，大家熟的不能再熟，第一种thin client，官网是这样介绍的：

>A thin client is a lightweight Ignite client that connects to the cluster via a standard socket connection. It does not become a part of the cluster topology, never holds any data, and is not used as a destination for compute grid calculations. What it does is simply establish a socket connection to a standard Ignite node and perform all operations through that node.
>
>Thin clients are based on the binary client protocol, which makes it possible to support Ignite connectivity from any programming language.

也就是一个基于二进制协议和服务端通信的客户端，目前支持Java、.Net、C++、python、NodeJs和php

我们这里只介绍java，如果读者对其他语言的客户端感兴趣可以自行去官网了解。

### 3.1、服务端配置



首先我们想用thin client的话需要在刚刚说到的`default-config.xml`这个文件中修改，大家打开这个xml文件就可以看到这个就是一个spring的xml，我们去配置就是配置spring的bean（在这里笔者不得不惊叹，没想到2024年了还有机会手动去配置spring的xml，给人一种丰田手动挡的美感）

```xml
<?xml version="1.0" encoding="UTF-8"?>

<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="
       http://www.springframework.org/schema/beans
       http://www.springframework.org/schema/beans/spring-beans.xsd">
    <bean id="ignite.cfg" class="org.apache.ignite.configuration.IgniteConfiguration">
        <property name="discoverySpi">
            <bean class="org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi">
                <property name="ipFinder">
                    <bean class="org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder">
                        <property name="addresses">
                            <list>
                                <value>x.x.x.x:47500..47509</value>
                            </list>
                        </property>
                    </bean>
                </property>
            </bean>
        </property>
    </bean>
</beans>
```

这里的意思是注入一个bean id为`ignite.cfg`且class为`org.apache.ignite.configuration.IgniteConfiguration`的bean，然后这个bean的一个属性——`discoverySpi`设置为类型是`org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi`并且这个属性的类的属性的`idFinder`设置为类型`org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder`，然后这个最底层的属性的属性`addresses`设置为`x.x.x.x:47500..47509`，这里如果你是在本地启动的话ip写127.0.0.1就行了，如果你是在服务器上启动的话需要写服务器的IP，`47500..47509`的意思是监听47500端口到`47509`端口，这个端口就是我们下面配置在客户端上的端口配置

### 3.2、客户端配置

首先我们java客户端需要用到如下依赖:

```xml
<dependency>
  <groupId>org.apache.ignite</groupId>
  <artifactId>ignite-core</artifactId>
  <version>2.16.0</version>
</dependency>
<dependency>
  <groupId>org.apache.ignite</groupId>
  <artifactId>ignite-spring</artifactId>
  <version>2.16.0</version>
</dependency>
```

version和当前ignite的版本保持一致

然后我们配置一个ignite的bean：

```java
@Configuration
public class IgniteConfig {

    @Bean
    public Ignite igniteInstance() {
        IgniteConfiguration cfg = new IgniteConfiguration();
        //代表是服务器模式
        cfg.setClientMode(true);
        TcpDiscoverySpi tcpDiscoverySpi = new TcpDiscoverySpi();
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        //这里就配置刚刚在ignite config里配置的ip和端口
        ipFinder.setAddresses(Collections.singletonList("x.x.x.x:47500..47509"));
        tcpDiscoverySpi.setIpFinder(ipFinder);
        cfg.setDiscoverySpi(tcpDiscoverySpi);
        final Ignite ignite = Ignition.start(cfg);
        ignite.cluster().state(ClusterState.ACTIVE);
        return ignite;
    }
```

如此我们就在项目中注入了一个ignite的客户端，使用的时候只需要注入这个bean即可

服务启动之后成功连接到ignite的话会显示如下内容：

![image-20241227100030153](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20241227100030153.png)

那么前期工作就完成了，下面我们就简单介绍了一下ignite的部分功能



## 4、功能演示

### 4.1、缓存

```java
CacheConfiguration<String, String> cfg = new CacheConfiguration<>("myCache");
//设置超时时间
cfg.setExpiryPolicyFactory(FactoryBuilder.factoryOf(new CreatedExpiryPolicy(new Duration(TimeUnit.SECONDS, 5))));
IgniteCache<String, String> cache = ignite.getOrCreateCache(cfg);
// 写入缓存
cache.put("test111", "success");
// 读取缓存
cache.get("test111");
// 等待超过 1 分钟
Thread.sleep(TimeUnit.SECONDS.toMillis(5));
// 再次读取缓存
System.out.println(cache1.get("1"));
```

就这么简单，这里"myCache"就是你的缓存区域，有点类似于database 的概念

这里`IgniteCache`是一个泛型，自然你就可以设置不同的类型例如可以把value设置为一个对象:

```java
// 创建缓存
CacheConfiguration<String, StudentDTO> cfg = new CacheConfiguration<>("myCacheObj");
IgniteCache<String, StudentDTO> cache1 = ignite.getOrCreateCache(cfg);
cache.put("1", StudentDTO.builder().name("test").build());
cache1.get("1")
```

比起redis那繁琐的序列化来说确实简单了不少，当然也有缺点例如没有redis那么多灵活的数据结构比如Zset等，但是你只是单纯的想做一个缓存的话，其实ignite会简单一点



### 4.2、sql功能

刚刚说到了ignite支持sql，完全可以当成一个关系型数据库来用，只是数据都是在内存里，我们接下来看看如何使用ignite的sql

首先加入如下依赖：

```xml
<dependency>
    <groupId>org.apache.ignite</groupId>
    <artifactId>ignite-sql-engine</artifactId>
    <version>3.0.0-beta1</version>
</dependency>
<dependency>
    <groupId>org.apache.ignite</groupId>
    <artifactId>ignite-spring-data-commons</artifactId>
    <version>1.1.0</version>
</dependency>
<dependency>
    <groupId>org.apache.ignite</groupId>
    <artifactId>ignite-spring-data-ext</artifactId>
    <version>2.0.0</version>
</dependency>
```

然后我们新建一个entity来代表一个表的实体（注意因为我们引入了spring-data依赖，所以我们接下来的用法类似于JPA，当然你也可以引入原生的jdbc来使用，这里只是笔者自己的使用习惯）：

```java
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String sex;

    private String classs;
}
```

除了没有Table注解之外其他都和jpa一样

然后我们需要在我们的springboot启动类上加上注解：

```java
@EnableIgniteRepositories(basePackages = { "com.XXX" })
```

包名就是你entity所在的包名，这样是告诉系统要扫描的路径

然后我们新建一个Repository：

```java
@RepositoryConfig(cacheName = "myCache", autoCreateCache = true)
public interface StudentIgniteRepository extends IgniteRepository<Student, Long> {}
```

这里`autoCreateCache`默认是false，如果不设置为true那必须要这个cacheName被创建了你的服务才能启动成功

然后使用就是和jpa一摸一样了：

```java
studentIgniteRepository.save(1L, Student.builder().name("tom").sex("boy").classs("A").build());

final Optional<Student> byId = studentIgniteRepository.findById(1L);
```

完全没有难点。

不过这里需要注意的是ignite默认是没有事务的，虽然他支持事务，需要你去项目里手动配置一下启用事务才行，还是刚刚说到的config里,在刚刚的`ignite.cfg`这个bean下再加2个property：

```xml
<property name="cacheConfiguration">
  <!-- 这里是配置哪个缓存空间从原子变为事务型 -->
    <bean class="org.apache.ignite.configuration.CacheConfiguration">
          <property name="name" value="myTranCache"/>
           <property name="atomicityMode" value="TRANSACTIONAL"/>
     </bean>
</property>

<!--这里是说明使用哪个事务配置-->
<property name="transactionConfiguration">
    <bean class="org.apache.ignite.configuration.TransactionConfiguration">
    </bean>
</property>
```

注意需要一个缓存空间一个缓存空间的配置，而不是统一配置。

更麻烦的是，如果你先就创建了这个缓存空间，那么他是无法修改为支持事务的，这个点在官网上没有标明，笔者在无数次尝试修改失败的时候才去stackoverflow上看到答案:

![image-20241227150105159](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20241227150105159.png)



这个sql特性笔者能想到的是，当然不可能直接用来做数据库，虽然快但是毕竟在内存中（而且在官网文档上没有介绍到持久化，这让笔者怀疑是否有持久化），但是还是可以用来做缓存啊，比如说你可以给你的ORM框架做拦截器，拦截到你要往实际上到数据库执行的sql，然后让其先在ignite中执行一次，这样就做了一层缓存了，好处就是代码侵入性几乎没有，你的上层代码根本不用去配置缓存什么的，缺点就是不好排查问题容易埋坑。当然了肯定也有其他用法这点留给读者朋友们去挖掘了



### 4.3、分布式锁

对你没看错，ignite还可以用来做分布式锁：

```java
IgniteCache<String, Integer> cache = ignite.cache("testLock");
Lock lock = cache.lock("keyLock");
try {
    lock.lock();
    Thread.sleep(60 * 1000);
    cache.put("Hello", 11);
    cache.put("World", 22);
} catch (Exception e) {
    throw new RuntimeException(e);
} finally {
    lock.unlock();
}
```

虽然说，redis也能做分布式锁但是需要客户端自己去实现，而ignite是天然支持的，他自己就提供了api，用起来非常顺手，不过使用分布式锁只能在支持事务的缓存空间

这点来说，笔者觉得ignite完爆redis



### 4.4、计算网格

Ignite 提供了一个 API，用于以平衡和容错的方式在集群节点之间分配计算。您可以提交单个任务以供执行，也可以实现具有自动任务拆分的 MapReduce 模式，因为是在节点之间计算顾名思义为网格。

首先我需要新建一个计算类，来实现`IgniteRunnable`接口然后在`run`方法中实现自己的计算逻辑：

```java
public class MyComputeTask implements IgniteRunnable {

    @Override
    public void run() {
        int a = 10;
        int b = 200;
        for (int i = 0; i < 1000; i++) {
            int c = a * b;
        }
        System.out.println("已经完成计算");
    }
}
```

然后就是要在ignite中计算，这个就很简单了：

```java
ignite.compute().run(new MyComputeTask());
```

不过注意了！此时我们的`MyComputeTask`类只在我们的服务中定义了，ignite中并没有这个类，此时直接调用会报错没有这个类，那么我们有两种方式来把这个类交给ignite：

1、将类添加到节点的类路径中

2、启用对等类加载

第一个方法，我说实话，官网确实也没写怎么添加到节点中起码在计算网格章节没有写到，也可能是笔者英文太烂了没找到，但是这种类似于livy+spark到用法，笔者觉得实用性不大，毕竟livy好歹也是把要执行的jar或者class放到hdfs上

第二个方法，需要在ignite的config中配置，还是给ignite.cfg这个bean加一个property：

```xml
<property name="peerClassLoadingEnabled" value="true"/>
```

如此就开启了对等类加载，然后在我们客户端中需要新增一句代码：

```java
IgniteConfiguration cfg = new IgniteConfiguration();
...
cfg.setPeerClassLoadingEnabled(true);
```

注意客户端和服务端的这个配置一定要一致，不一致的话启动就会报错提示配置不一致。

对等类加载的意思就是客户端会把class作为二进制数据传递给服务端，服务端用classloader加载。

然后我们执行刚刚的远端计算代码之后，就可以在ignite的日志中看到我们打印的内容啦：

![image-20241227152504228](/Users/zhangyunfan/Library/Application Support/typora-user-images/image-20241227152504228.png)

这个功能其实对crud仔用处不大，可能大数据的同学用处会多一点。



### 4.5、其他功能

ignite还有服务发现和调用的服务网格功能，可以监听发送事件的事件传递功能，这两个功能笔者没有介绍，因为笔者觉得用处不大，因为服务治理有相当多成熟的工具了，或者可以直接使用K8S的svc了，事件传递就更多了，甚至redis也能做还有两种方式来做（pub/sub和stream），而且ignite对实现这两个功能的方式笔者不太喜欢，或者说比较难用，所以感觉没必要介绍了，只能说ignite确实有这两个功能



## 5、总结

笔者在使用ignite的过程中总结了ignite这几个优点和缺点

- 优点

1、天然分布式，并且部署多节点不用考虑master都是自动发现的，部署压力较小

2、纯内存操作和reids一样，速度快

3、功能完备，基本上有的功能他都有

4、api相对简单和易用没有过多的学习成本

- 缺点

1、除了官网之外资料较少，特别是中文资料简直就是少之又少（本文很多内容都是笔者自己研究出来的，甚至GPT对ignite的一些回答也是错的，比如使用sql部分需要用到的依赖，gpt回答的全是错的，笔者只能自己去maven中央仓库上一个一个找一个一个试），这个可能和大多数使用ignite都是老外且都是付费用户有关，一言蔽之就是开源社区不活跃

2、配置麻烦，启用很多功能都要去修改配置，而且没有提供界面修改只能修改xml

总之，如果你确实对redis感到厌倦并且对redis使用程度也不深，可以考虑看看用ignite来代替

