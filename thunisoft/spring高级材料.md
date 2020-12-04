# Spring中Bean的生命周期

在spring中，从BeanFactory或ApplicationContext取得的实例为Singleton，也就是预设为每一个Bean的别名只能维持一个实例，而不是每次都产生一个新的对象使用Singleton模式产生单一实例，对单线程的程序说并不会有什么问题，但对于多线程的程序，就必须注意安全(Thread-safe)的议题，防止多个线程同时存取共享资源所引发的数据不同步问题。

然而在spring中 可以设定每次从BeanFactory或ApplicationContext指定别名并取得Bean时都产生一个新的实例：例如：

 

在spring中，singleton属性默认是true，只有设定为false，则每次指定别名取得的Bean时都会产生一个新的实例

一个Bean从创建到销毁，如果是用BeanFactory来生成,管理Bean的话，会经历几个执行阶段![](http://images.51cto.com/files/uploadimg/20110419/0930070.png)

​	1、实例化 当我们的程序加载beans.xml文件的时候发生，把我们的bean(前提是scope = singleton)实例化到内存中
​        2、设置属性 调用set方法设置属性
​        3、如果实现了BeanNameAware接口的setBeanName(String arg0)方法 则可以通过setBeanName获取bean的id号
​        4、如果是实现了BeanFactoryAware接口的setBeanFactory(BeanFactory arg0) 则可以获得bean工厂
​        5、如果实现了ApplicationContextAware接口的setApplicationContext(ApplicationContext arg0) 则可以获得上下文容器ApplicationContext对象
​        6、如果map和一个后置处理器关联了 则自动调用后置处理器的postProcessBeforeInitialization()方法(BeanPostProcessor的预初始化方法postProcessBeforeInitialization) 体现了aop 针对所有对象编程
​        7、如果实现了InitializatingBean的afterPropertiesSet()方法 则调用它
​        8、如果定制了初始化方法 则调用初始化方法（在xml的bean标签中写到init="想要配置的方法" 然后实现这个方法即可）
​        9、如果实现了BeanPostProcessor的后初始化方法(postProcessAfterInitialization) 则调用它
​        10、可以使用Bean了
​            使用....
​        11、容器关闭
​        12、如果实现了DisposableBean接口下的destroy方法 则调用它
​        13、如果xml中的bean标签配置了destory-method="想要执行的销毁函数" 则调用对应的销毁方法

#  ioc和aop容器原理

## IOC（Inversion of Control）  

  (1). IOC（Inversion of Control）是指容器控制程序对象之间的关系，而不是传统实现中，由程序代码直接操控。控制权由应用代码中转到了外部容器，控制权的转移是所谓反转。 对于Spring而言，就是由Spring来控制对象的生命周期和对象之间的关系；IOC还有另外一个名字——“依赖注入（Dependency Injection）”。从名字上理解，所谓依赖注入，即组件之间的依赖关系由容器在运行期决定，即由容器动态地将某种依赖关系注入到组件之中。  

(2). 在Spring的工作方式中，所有的类都会在spring容器中登记，告诉spring这是个什么东西，你需要什么东西，然后spring会在系统运行到适当的时候，把你要的东西主动给你，同时也把你交给其他需要你的东西。所有的类的创建、销毁都由 spring来控制，也就是说控制对象生存周期的不再是引用它的对象，而是spring。对于某个具体的对象而言，以前是它控制其他对象，现在是所有对象都被spring控制，所以这叫控制反转。

(3). 在系统运行中，动态的向某个对象提供它所需要的其他对象。  

(4). **依赖注入的思想是通过反射机制实现的，在实例化一个类时，它通过反射调用类中set方法将事先保存在HashMap中的类属性注入到类中**。 总而言之，在传统的对象创建方式中，通常由调用者来创建被调用者的实例，而在Spring中创建被调用者的工作由Spring来完成，然后注入调用者，即所谓的依赖注入或者控制反转。 **注入方式有两种：依赖注入和设置注入；** IOC的优点：降低了组件之间的耦合，降低了业务对象之间替换的复杂性，使之能够灵活的管理对象。

## AOP（Aspect Oriented Programming）

AOP（Aspect Oriented Programming），即面向切面编程，通过预编译方式和运行期动态代理实现程序功能的统一维护的一种技术。AOP是OOP的延续，是软件开发中的一个热点，也是Spring框架中的一个重要内容，是函数式编程的一种衍生范型。利用AOP可以对业务逻辑的各个部分进行隔离，从而使得业务逻辑各部分之间的耦合度降低，提高程序的可重用性，同时提高了开发的效率。

AOP一般拥有的功能为：

前置增强，Before事件
后置增强，After事件
环绕增强，Around事件
所谓的Around事件其实就是在方法前后分别加上Before与After事件。

Spring AOP实现逻辑

  Spring在初始化Bean的时候会判断BeanPostProcessor接口，然后根据其实现的方法为Bean实现一些前置、后置操作。同样的，Spring AOP 也是基于BeanPostProcessor实现的。在Spring中，有一个抽象类，名叫AbstractAutoProxyCreator，它实现了BeanPostProcessor接口，在postProcessAfterInitialization方法中，调用wrapIfNecessary方法对Bean进行代理包装。

wrapIfNecessary方法时序图如下：![](http://bed.thunisoft.com:9000/ibed/2019/08/27/c78819c38bd84827a5f0157d1b9cc6b5.png)


在上述时序图中可见，Spring会根据代理类的实际情况去动态选择JDK代理与CGLIB代理，其中createAopProxy源码如下：

```java
public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
		if (config.isOptimize() || config.isProxyTargetClass() || hasNoUserSuppliedProxyInterfaces(config)) {
			Class<?> targetClass = config.getTargetClass();
			if (targetClass == null) {
				throw new AopConfigException("TargetSource cannot determine target class: " +
						"Either an interface or a target is required for proxy creation.");
			}
			if (targetClass.isInterface() || Proxy.isProxyClass(targetClass)) {
				return new JdkDynamicAopProxy(config);
			}
			return new ObjenesisCglibAopProxy(config);
		}
		else {
			return new JdkDynamicAopProxy(config);
		}
	}

```

通过源码可以看出，Spring选择JDK代理的条件为：

代理的Bean实现了接口
没有为Bean指定直接代理
反之，Spring则会选择CGLIB代理。

在Spring为Bean创建好代理对象后，我们在调用Bean时，首先Spring会找到代理对象的invoke方法，然后在该方法中会去查找拦截器，然后执行拦截器方法，最后才执行Bean的方法。


AOP执行代理Bean时序图（以JDK代理方式为例）：![](http://bed.thunisoft.com:9000/ibed/2019/08/27/e711cceed8a4424da2d24a0cc4e4a838.png)



#  spring框架设计理念和优缺点分析

Spring 是一个轻量级的应用开发框架（平台）。

传统的 EJB 开发需要依赖按照 J2EE 规范实现的 J2EE 应用服务器，应用在设计、实现时，往往需要遵循一系列的接口标准，才能在应用服务器的环境中得到测试和部署。这种开发方式，使得应用在可测试性和部署上受到一定影响。Spring 的设计理念采用了相对 EJB 而言的轻量级开发思想，即使有 POJO 的开发方式，只需要使用简单的 Java 对象就能进行 Java EE 开发，使得开发的入门、测试、应用部署都得到了简化。

在应用开发中，往往会涉及复杂的对象耦合关系，如果在 Java 代码中维护这些耦合关系，对代码的维护性和应用扩展性会带来诸多不便。而 Spring 作为应用开发平台，提供了 IoC 容器，可以对这些耦合关系实现一个文本化、外部化的工作：通过 xml 文件配置，可以方便地对应用对象的耦合关系进行浏览、修改、维护，极大地简化了应用的开发。

控制反转模式（IOC，Inversion of Control）的基本概念是：不创建对象，但是描述创建它们的方式。在代码中不直接与对象和服务连接，但在配置文件中描述哪一个组件需要哪一项服务。容器 （在 Spring 框架中是 IOC 容器） 负责将这些联系在一起。

作为平台，Spring 框架的核心是 IoC 容器和 AOP 模块。使用 Ioc 容器来管理 POJO 对象和它们之间的耦合关系，使业务信息可以用简单的 Java 语言来抽象和描述。通过使用 AOP，可以以动态和非侵入的方式来增强服务的功能。作为核心，它们代表了最为基础的底层抽象，同时也是 Spring 其他模块实现的基础。

Spring 体系中已经包含了我们在应用开发中经常用到的许多服务了，比如事务处理、Web MVC、JDBC、ORM等等，这些服务就像 Linux 驱动之于 Linux 内核一样重要。如果仅仅只有这些的话，Spring 还不能称之为平台，Spring 坚持面向接口编程的设计理念，结合其核心的 IoC 机制，可以轻松地对不同模块的实现进行替换，比如用户既可以使用 Hibernate 作为 ORM 工具，也可以使用 iBatis、MyBatic，还可以使用其他类似工具。

总结一下，Spring 的设计理念是： 
1. IoC 控制反转：降低了对象之前的耦合关系，简化了应用的开发。 
2. AOP 面向切面编程：方便以动态的、非侵入的方式，增强服务的功能。 
3. 面向接口编程：作为平台，核心功能之外服务，都可以选择不同的技术实现。
    

Spring 框架是一个分层架构，如下图所示：

![](http://bed.thunisoft.com:9000/ibed/2019/08/27/0288aed73067472b8932f56501a36f8b.gif)

- Spring Core：核心容器，提供 Spring 框架的基本功能，其主要组件是 BeanFactory，是工程模式的实现，BeanFactory 使用控制反转 IoC 模式将应用程序的配置和依赖性规范与实际的应用程序代码分开。
-   Spring AOP：通过此模块，将面向切面编程的的功能集成到了 Spring 框架中。通过使用 Spring AOP，不用依赖 EJB 组件，就可以将声明性事务管理集成到应用程序中。
- Spring Context：Spring Context模块继承BeanFactory（或者说Spring核心）类，并且添加了事件处理、国际化、资源装载、透明装载、以及数据校验等功能。它还提供了框架式的Bean的访问方式和很多企业级的功能，如JNDI访问、支持EJB、远程调用、集成模板框架、Email和定时任务调度等。
- Spring Dao：JDBC DAO 抽象层提供了有意义的异常层次结构，可用该结构来管理异常处理和不同数据库供应商抛出的错误消息。异常层次结构简化了错误处理，并且极大地降低了需要编写的异常代码数量（例如打开和关闭连接）。Spring DAO 的面向 JDBC 的异常遵从通用的 DAO 异常层次结构。
- Spring ORM：Spring 框架插入了若干个 ORM 框架，从而提供了 ORM 的对象关系工具，其中包括 JDO、Hibernate 和 iBatis SQL Map。所有这些都遵从 Spring 的通用事务和 DAO 异常层次结构。
- Spring Web：Web 上下文模块建立在应用程序上下文模块之上，为基于 Web 的应用程序提供了上下文。所以，Spring 框架支持与 Jakarta Struts 的集成。Web 模块还简化了处理多部分请求以及将请求参数绑定到域对象的工作。
- Spring MVC：MVC 框架是一个全功能的构建 Web 应用程序的 MVC 实现。通过策略接口，MVC 框架变成为高度可配置的，MVC 容纳了大量视图技术，其中包括 JSP、Velocity、Tiles、iText 和 POI。
  


Spring的优点：

1.底侵入式的设计，

2.独立于各种应用服务器，

3.依赖注入的特性将组件关系透明化降低了耦合
4.面向切面编程的特性允许通用任务进行集中式处理，

5.与第三方框架的良好整合