# 从dubbo线程池扩展看dubbo的spi扩展机制

## dubbo的线程池扩展

​	这周线上dubbo提供者频繁报错dubbo线程满了：`Thread pool is EXHAUSTED!` ，原因还在排查中但是排查过程中看了下dubbo线程池的源码感觉很有兴趣，然后又在官网上看到了线程池是可以自定义扩展的于是好奇心驱使着我研究一下这个dubbo线程池扩展是怎么实现的。dubbo线程池底层均使用的是 JDK 中的 ThreadPoolExecutor 线程池 ，默认自带了四种线程池策略：

- fixed：固定线程数量
- cached：线程空闲一分钟会被回收，当新请求到来时会创建新线程
- limited：线程个数随着任务增加而增加，但不会超过最大阈值。空闲线程不会被回收
- eager：当所有核心线程数都处于忙碌状态时，优先创建新线程执行任务

如果什么都不配置则默认的线程池是fixed（这四种线程池的源码在这里不赘述感兴趣的可以去看看源码，idea里搜索`FixedThreadPool`即可，其他实现都在同包下，看这四种线程池是对新建线程池七大参数的一个很好的复习和巩固），如果这四种线程池均不能满足项目上的需求的话可以自己扩展新建，dubbo官网上的扩展方法如下：

- 1、新建一个类实现`ThreadPool`接口，并实现`getExecutor`方法

- 2、在项目的`resources`目录下的`META-INF`目录下的`dubbo`目录下新建一个文件:`com.alibaba.dubbo.common.threadpool.ThreadPool`（duubo的2.7.X下是`org.apache.dubbo.common.threadpool.ThreadPool`)，在该文件里配置`xxx=你刚刚新建的类的全路径` 

- 3、然后在dubbo的xml配置文件里配置如`<dubbo:provider threadpool="xxx" />`，如果是properties配置的话，根据dubbo官方文档上的映射规则可以将 xml 的 tag 名和属性名组合起来，用 ‘.’ 分隔。每行一个属性，那么在你的配置文件里可以这样配置` dubbo.provider.threadpool=xxx`


## dubbo的SPI扩展机制

​	这个扩展方法让我非常好奇，dubbo究竟是怎么实现这个扩展的，接下来我们从源码入手（本文源码2.6.X）。首先找到默认的线程池`FixedThreadPool`然后搜索`getExecutor`的方法调用，我们可以看到新建线程池的方法是：
```  java
executor = (ExecutorService) ExtensionLoader
.getExtensionLoader(ThreadPool.class)
.getAdaptiveExtension()
.getExecutor(url);
```
`getExtensionLoader`这个是获取扩展加载器指定需要加载的类型是`ThreadPool`然后最后的`getExecutor`就是获取线程池的方法，所以我们需要关注的就是`getAdaptiveExtension`方法，我们点进去：
```  java
   Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            instance = createAdaptiveExtension();
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
              。。。
            }
        }
        return (T) instance;  
```
可以看看出来`instance`就是就是获取到的线程池的实现类，`cachedAdaptiveInstance`是一个自定义的存放值的`Holder`类，这里是一个缓存的操作如果没有则新建，我们进入到`createAdaptiveExtension`方法，可以看到他接着是调用的`getAdaptiveExtensionClass`来获取自适应扩展类然后new了一个instance，我们继续往下走可以看到`getAdaptiveExtensionClass`方法：

```java
 getExtensionClasses();
 if (cachedAdaptiveClass != null) {
      return cachedAdaptiveClass;
    }
 return cachedAdaptiveClass = createAdaptiveExtensionClass();
```

根据方法名第一步是获取扩展类，然后才是创建扩展类，我们先看`getExtensionClasses`，这里也是一个缓存操作，先去Holder里面取，如果没有话就调用`loadExtensionClasses`方法加载新建一个，然后重点关注`loadExtensionClasses`的代码：

```java
 final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }

        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
		//加载配置文件
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        loadDirectory(extensionClasses, DUBBO_DIRECTORY);
        loadDirectory(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
```

这部分代码分成两个部分：

- 1、先根据接口上的`SPI`注解来获取默认的实现，在`ThreadPool`接口上的是`@SPI("fixed")`，所以dubbo的线程池的默认实现是fixed，这里把注解上的值拿到给`cachedDefaultName`属性，这个属性在后面有用的。
- 2、加载配置文件，把其他的实现都加载出来。这里会加载三个路径：`META-INF/services/`,`META-INF/dubbo/`,`META-INF/dubbo/internal/`，在这三个路径下加载接口的全路径的文件，所以上文提到的扩展方式中的文件放在这三个文件下面都是可以的。2.7.X版本中这里资源的加载方式变了，用了`LoadingStrategy`接口，每个路径实现一次这个接口，然后在类加载的时候把这些类的路径下的资源加载加载器里好处就是不用在加载每个扩展类的时候都去项目路径下加载资源而是预先加载好一次。

这里加载完了之后，是一个Map结构，key就是文件里的xxx，value就是文件里配置的类的全路径，加载完了的Map会放在`cachedClasses`这个Holder缓存里，下次需要的话可以直接使用。加载完了下一步就是创建了，在`createAdaptiveExtensionClass`方法中：

```java
String code = createAdaptiveExtensionClassCode();
ClassLoader classLoader = findClassLoader();
com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader
.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class)
.getAdaptiveExtension();
 	return compiler.compile(code, classLoader);
```

分成4步：

- 1、创建一个适配器类，在代码里动态生成这个适配器的代码
- 2、找到类加载器
- 3、找到编译器（这里也是用的自适应扩展那一套）
- 4、编译，并且返回这个适配器的class

我们着重看第一步就可以了，`createAdaptiveExtensionClassCode`的代码太长了在2.6.X中，足足有200行，所以在2.7.X重构了这部分生成适配器代码的代码，比起2.6.X来说思路清晰也更简洁，所以这部分代码我们看2.7.X的。在2.7.X中生成适配器的代码是这样的:

```java
String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName)
			.generate();
```

使用了一个生成器来生成代码，构造方法内传入这个接口类型和刚刚获取到的默认实现：

```java
if (!hasAdaptiveMethod()) {
            throw new IllegalStateException("No adaptive method exist on extension " + type.getName() + ", refuse to create the adaptive class!");
        }
StringBuilder code = new StringBuilder();
code.append(generatePackageInfo());
code.append(generateImports());
code.append(generateClassDeclaration());
Method[] methods = type.getMethods();
for (Method method : methods) {
            code.append(generateMethod(method));
    }
code.append("}");
 if (logger.isDebugEnabled()) {
      ogger.debug(code.toString());
     }
     return code.toString();
```

首先先去判断这个接口的所有方法里面是否有`@Adaptive`注解，没有则抛出异常，这个注解的作用是指定获取URL上的参数名字，下面也会用到。然后是去拼接代码：

```java
code.append(generatePackageInfo());
code.append(generateImports());
code.append(generateClassDeclaration());
```

这三句代码是拼接一个Java类必须的package包信息、import引包信息、类名，内部实现很简单就是String.format（2.6.X是简单粗暴的append），然后这里有一个细节是这个适配器类的类名后缀写死了必有一个`$Adaptive`然后实现了扩展的接口，这个名字是为了和原来的接口区分开来防止加载类报错。下面就是加载方法的代码了:

```java
Method[] methods = type.getMethods();
for (Method method : methods) {
	code.append(generateMethod(method));
     }
```

这里是需要去凭借这个接口下面的所有方法，因为这个适配器类实现了这个接口所以为了编译通过必须要实现他的所有方法：

```java
String methodReturnType = method.getReturnType().getCanonicalName();
String methodName = method.getName();
String methodContent = generateMethodContent(method);
String methodArgs = generateMethodArguments(method);
String methodThrows = generateMethodThrows(method);
	return String.format(CODE_METHOD_DECLARATION, methodReturnType
	, methodName, methodArgs, methodThrows, methodContent);

```

生成方法的要复杂一点，一个方法必须的是：返回值、方法名、方法体、入参、可能的抛出异常，我们分开来看：

- 返回值：返回值非常简单直接用Java反射就可以获取到了
- 方法名：方法名也很简单和这个接口的方法名一样即可
- 方法参数：直接调用反射获取
- 抛出异常：仍然是反射

总之这四个都是和原方法保持一致即可，因为实现接口嘛。然后是最重要的生成方法体部分了，我们一步一步的看：

```java
Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
StringBuilder code = new StringBuilder(512);
if (adaptiveAnnotation == null) {
	return generateUnsupported(method);
    } else {
	int urlTypeIndex = getUrlTypeIndex(method);
	// found parameter in URL type
	if (urlTypeIndex != -1) {
	// Null Point check
	code.append(generateUrlNullCheck(urlTypeIndex));
     ...
	String[] value = getMethodAdaptiveValue(adaptiveAnnotation);
```

这里的代码就是用了刚刚说到的`@Adaptive`注解来获取注解上表明了的默认值，接下来的：

```java
boolean hasInvocation = hasInvocationArgument(method);
code.append(generateInvocationArgumentNullCheck(method));
...
code.append(generateExtNameNullCheck(value));
```

这两句是做校验用的用来判断入参是否为空或者配置的扩展实现类的名字是否为空，里面实现也很简单就是拼接if语句和thorw异常的语句，接下来最重要的是`generateExtNameAssignment`和`generateExtensionAssignment`这两个分别是生成获取extName也就是配置的扩展实现类名字和根据获取到的名字获取并加载这个实现类。我们先看`generateExtNameAssignment`:

```java
// TODO: refactor it
String getNameCode = null;
	for (int i = value.length - 1; i >= 0; --i) {
		if (i == value.length - 1) {
                if (null != defaultExtName) {
                    if (!"protocol".equals(value[i])) {
                        if (hasInvocation) {
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                        }
                    } else {
                        getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                    }
                } else {
                    if (!"protocol".equals(value[i])) {
                        if (hasInvocation) {
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                        }
                    } else {
                        getNameCode = "url.getProtocol()";
                    }
                }
            } else {
                if (!"protocol".equals(value[i])) {
                    if (hasInvocation) {
                        getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                    } else {
                        getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                    }
                } else {
                    getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                }
            }
        }

        return String.format(CODE_EXT_NAME_ASSIGNMENT, getNameCode);
```

首先传刚刚`@Adaptive`注解的值进来，这个值可能是多个，然后遍历他，如果是第一个值的话再判断是否是protocol这个，如果是protocol的话那就用`getProtocol`来获取，如果不是的话再判断是否是启动需要的如果都不是的话就用`getParameter`，传入两个参数，参数名和默认值，这里的默认值就是接口上的SPI注解的值，上文有提到过。然后是后面的参数，基本都一样唯一不一样的是`getParameter`这个分支里默认值的值不再是SPI注解的值了，而是第一个值获取到的值。这个代码比较乱，作者也知道这里需要重构，打了个todo标签。这个类生成的代码就是去URL也就是配置里获取到配置的实现类的名字如果没有配置的话就用默认值，拿到实现类的名字之后就是加载这个类了，`generateExtensionAssignment`方法：

```java
String.format("%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);\n", type.getName(), 
ExtensionLoader.class.getSimpleName(),
type.getName());
```

这里我处理了一下，第一个参数是常量的。我们可以很明显的看出来这里就是再用这个`ExtensionLoader`的方法`getExtension`去加载指定名字的类。最后是构造返回值:

```java
String returnStatement = method.getReturnType().equals(void.class) ? "" : "return ";
String args = IntStream.range(0, method.getParameters().length)
                .mapToObj(i -> String.format(CODE_EXTENSION_METHOD_INVOKE_ARGUMENT, i))
                .collect(Collectors.joining(", "));
 return returnStatement + String.format("extension.%s(%s);\n", method.getName(), args);
```

这里就是先判断是不是void如果不是则直接拼接`extension.`这种目标方法，可能参数有多个所以这里循环来拼接。所以最后生成的完整代码就是:

```java
package com.alibaba.dubbo.common.threadpool;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

public class ThreadPool$Adaptive implements com.alibaba.dubbo.common.threadpool.ThreadPool {
    
    public java.util.concurrent.Executor getExecutor(com.alibaba.dubbo.common.URL arg0) {
        if (arg0 == null) throw new IllegalArgumentException("url == null");
        com.alibaba.dubbo.common.URL url = arg0;
        String extName = url.getParameter("threadpool", "fixed");
        if (extName == null)
            throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.common.threadpool.ThreadPool) name from url(" + url.toString() + ") use keys([threadpool])");
        com.alibaba.dubbo.common.threadpool.ThreadPool extension = (com.alibaba.dubbo.common.threadpool.ThreadPool) ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.threadpool.ThreadPool.class).getExtension(extName);
        return extension.getExecutor(arg0);
    }
}
```

这里的关键代码应该就是:

```java
String extName = url.getParameter("threadpool", "fixed");
...
com.alibaba.dubbo.common.threadpool.ThreadPool extension = (com.alibaba.dubbo.common.threadpool.ThreadPool) ExtensionLoader
.getExtensionLoader(ThreadPool.class)
.getExtension(extName)
```

这两句，这个类的核心就是去URL也就是配置里找到配置然后再用扩展加载来根据名字加载。最后`getExtension`方法去加载实现类就是用到的刚刚的缓存里的内容来加载的。这个类的代码生成完了之后就编译然后编译成功之后就返回给外面，所以我们在刚刚开始哪里真正new Intance的是这个适配器类而不是我们的实现类。

最后用一张图来总结上述的流程：![](https://mmbiz.qpic.cn/mmbiz_png/uChmeeX1Fpw2oMzXxojtaJ9TARZP6z3RgiaKYdlGaHTkMdgoLg7YaBb5uGB9DY3Uyyq0B9GGlh7agyRR67Tvoqg/640?wx_fmt=png&tp=webp&wxfrom=5&wx_lazy=1&wx_co=1)
