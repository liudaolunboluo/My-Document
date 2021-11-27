# 使用EasyTestcontainers来做你的数据库、redis、es等中间件的单元测试

 ## 1、什么是Testcontainers

Testcontainers 是一个 Java 库，支持 JUnit 测试，提供常见数据库、Selenium Web 浏览器或任何其他可以在 Docker 容器中运行的轻量级、一次性实例。

测试容器使以下类型的测试更容易：

- **数据访问层集成测试**：使用 MySQL、PostgreSQL 或 Oracle 数据库的容器化实例来测试您的数据访问层代码以实现完全兼容性，但无需在开发人员的机器上进行复杂的设置，并且知道您的测试将始终以安全开始一个已知的数据库状态。也可以使用可以容器化的任何其他数据库类型。
- **应用程序集成测试**：用于在具有依赖项（例如数据库、消息队列或 Web 服务器）的短期测试模式下运行您的应用程序。
- **UI/验收测试**：使用与 Selenium 兼容的容器化 Web 浏览器进行自动化 UI 测试。每个测试都可以获得浏览器的新实例，无需担心浏览器状态、插件变化或浏览器自动升级。你会得到每个测试会话的视频记录，或者只是测试失败的每个会话。



## 2、为什么要使用Testcontainers

想问一下，大家平时在对数据库、redis有关的类做单元测试的时候是怎么做的呢？笔者之前有以下几种方式：

1、和第三方中间件有关的一把全mock，完全忽略掉第三方中间件

2、数据库相关的使用Java自带的H2数据库

3、在单元测试中直连测试环境



第一种我觉得和不做数据存储层单元测试没啥区别，全部mock就是不做单元测试了。第二种其实不错但是有个问题是H2数据库不支持其它数据库等方言，比如mysql的json字段、索引、一些特有函数等等都无法支持，意味着如果使用2的话只能覆盖部分场景。第三种方案则更不好，单元测试的数据会污染环境上的数据。所以这个时候就需要使用Testcontainers来做我们的数据库、redis等场景下的单元测试了，他可以完完全全模拟你的测试环境，让问题更早暴露。当然了testcontainers的性能肯定比不上H2。



## 3、使用EasyTestContainers来做你的单元测试

EasyTestContainers是笔者本人编写的基于TestContainers的单元测试小工具，集成了springTest和MockitoJUnit这两个比较主流的单元测试框架。对你的单元测试代码无侵入，使用非常简单快捷方便。目前只支持SpringBoot 2.X或以上版本，后面如果有人用的话会支持其它spring版本。

### 3.1电脑环境准备：

1、首先使用testcontainers的前提就是本机安装docker，mac上安装很简单，直接`brew install --cask --appdir=/Applications docker`使用brew安装就可以了，windows麻烦一下，建议windows环境使用WLS。

2、安装docker之后运行`docker run testcontainers/ryuk:0.3.0`启动这个testcontainers依赖的容器

### 3.2 代码中使用

首先在pom文件里：

```xml
<dependency>
  <groupId>io.github.liudaolunboluo</groupId>
  <artifactId>easytestcontainers</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

引入笔者的easytestcontainers

然后在你的单元测试类上加上注解

```java
@RunWith(MysqlSpringTestContainerRunner.class)
@MysqlTestContainerConfig(dbScript="init.sql")
```

`@RunWith(MysqlSpringTestContainerRunner.class)`表明的是你用的是哪个runner在进行单元测试，目前暂时支持mysql、redis、es三个中间件，后面笔者会不断完善支持的中间件的，如果你想用其他中间件的那么可以看后面的3.2节扩展相关内容

`@MysqlTestContainerConfig(dbScript="init.sql")`

是配置类，dbScript是你的该单元测试类的数据库初始化脚本的存放路径，应该存放在你的test目录的resource目录下

mysql还可以配置你的mysql镜像，缺省值是5.7.28这个版本的镜像，特别的因为mysql的镜像不支持arm架构，只有oracle维护的mysql镜像支持arm架构，考虑到部分同学是使用的是arm架构的M1的mac book电脑，所以加了个配置`armImage`，来指定在arm架构下的mysql镜像，同样的缺省版本还是5.7.28，大家可以根据自己的需求来改变。最后，还可以配置的你的数据库的url的扩展参数，比如说`erverTimezone=GMT%2b8`这样来设置时区，缺省值的就是比较全的配置，大家可以根据自己项目的情况来更改。

然后在此单元测试类下你就可以正常的使用你的数据库操作相关类来做单元测试了，例如：

```java
@SpringBootTest(classes = TestApplication.class)
@RunWith(MysqlSpringTestContainerRunner.class)
@MysqlTestContainerConfig(dbScript="user.sql")
public class UserRepositoryImplTest extends BaseTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    public void test() {
      UserBO user = new UserBO();
      user.setName("Tom");
      user.setAge(15);
      userRepository.save(user);
      UserBO userNew = userRepository.getByName("Tom");
      assertNotNull(userNew);
      assertEquals(15, userNew.getAge());
    }
}
```

对就这么简单，下面的test方法中的sava、getByName方法都是真正的货真价实的对数据库在操作了。

目前有三个spring test的runner：`MysqlSpringTestContainerRunner`,`EsSpringTestContainerRunner`,`RedisSpringTestContainerRunner`，分别对应mysql、es、redis环境的单元测试。

如果是非spring下的单元测试，目前只支持redis环境：`RedisJunitTestContainerRunner`，因为mysql和es基本都是在spring中使用，而redis可能有的同学会不使用spring而直接使用jdis。

RedisJunitTestContainerRunner中，需要使用`RedisJunitTestContainerContext`来获取redis的端口和host来初始化你的jdis客户端：

```java
@RunWith(RedisJunitTestContainerRunner.class)
public class RedisJunitTestContainerRunnerTest {

    @Test
    public void testContext() {
        assertNotNull(RedisJunitTestContainerContext.getRedisHost());
        assertNotNull(RedisJunitTestContainerContext.getRedisPort());
    }
}
```

如果你不想在构建中运行testcontainers有关的单元测试，比如说在你的jenkins服务器上没有docker环境，你也不想在构建中运行相关的单元测试，那么可以在maven构建的时候加上参数`maven.testcontainer.skip=true`就可以忽略掉啦。

### 3.3扩展

如果你想使用其他的testcontainers支持的中间件而笔者的easytestcontainers又没有的话，则可以自己扩展之。

新建一个runner继承`AbstractSpringTestContainerRunner`或者`AbstractJunitTestContainerRunner`这两个类，然后实现方法就可以了。使用的话就和上面的一样。



## 4、后言

源码地址：https://github.com/liudaolunboluo/easyTestcontainer

希望大家多多给star、多多提issue。