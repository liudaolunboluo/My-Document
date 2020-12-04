![spring](http://images2015.cnblogs.com/blog/702767/201509/702767-20150907115314981-2080783917.jpg)

- **核心容器（Spring Core)**

Spring的核心容器，提供了Spring框架的基本功能；此模块包含的BeanFactory类是Spring的核心类，负责产生和管理Bean，是工程模式的实现；采用Factory(工厂模式)实现了IOC（控制反转）将应用的配置和依赖性规范与实际的应用程序代码分开；Spring以bean的方式组织和管理Java应用中发各个组件及其关系。

- **应用上下文（Spring Context）**

是一个配置文件，向Spring框架提供上下文信息；SpringContext模块继承BeanFactory类，添加了事件处理、国际化、资源装载、透明装载、以及数据校验等功能；还提供了框架式的Bean的访问方式和企业级的功能，如JNDI访问，支持EJB、远程调用、继承模板框架、Email和定时任务调度等；

- **面向切面编程（Spring AOP）**

Spring AOP直接将面向方面的编程功能集成到了Spring框架中，所以很容易的使Spring框架管理的任何对象支持AOP（Spring集成了所有AOP功能。通过事务管理可以使任意Spring管理的对象AOP化）；Spring AOP为基于Spring的应用程序中的对象提供了事务管理服务；通过使用Spring AOP，不用依赖EJB组件，就可以将声明性事务管集成到应用程序中。

- **JDBC和DAO模块（Spring DAO）**

DAO（DataAccessObject）模式思想是将业务逻辑代码与数据库交互代码分离，降低两者耦合；通过DAO模式可以使结构变得更为清晰，代码更为简；DAO模块中的JDBC的抽象层，提供了有意义的异常层次结构，用该结构来管理异常处理，和不同数据库供应商所抛出的错误信息；异常层次结构简化了数据库厂商的异常错误（不再从SQLException继承大批代码），极大的降低了需要编写的代码数量，并且提供了对声明式事务和编程式事务的支持；

- **对象实体映射（Spring ORM）**

SpringORM模块提供了对现有ORM框架的支持；提供了ORM对象的关系工具，其中包括了Hibernate、JDO和 IBatis SQL Map等，所有的这些都遵从Spring的通用事务和DAO异常层次结构；注意这里Spring是提供各类的接口（support），目前比较流行的下层数据库封闭映射框架，如ibatis,Hibernate等；

- **Web模块（Spring Web）**

此模块建立在SpringContext基础之上，提供了Servlet监听器的Context和Web应用的上下文；对现有的Web框架，如JSF、Tapestry、Structs等提供了集成；SpringWeb模块还简化了处理多部分请求以及将请求参数绑定到域对象的工作。

- **MVC模块（Spring Web MVC）**

SpringWebMVC模块建立在Spring核心功能之上，拥有Spring框架的所有特性，能够适应多种多视图、模板技术、国际化和验证服务，实现控制逻辑和业务逻辑的清晰分离；通过策略接口，MVC 框架变成为高度可配置的，MVC 容纳了大量视图技术，其中包括 JSP、Velocity、Tiles、iText 和 POI;
MVC模型:
Servlet控制器为应用程序提供了一个进行前-后端处理的中枢。一方面为输入数据的验证、身份认证、日志及实现国际化编程提供了一个合适的切入点；另一方面也提供了将业务逻辑从JSP文件剥离的可能;业务逻辑从JSP页面分离后，JSP文件蜕变成一个单纯完成显示任务的东西，这就是常说的View;而独立出来的事务逻辑变成人们常说的Model，再加上控制器Control本身，就构成了MVC模式