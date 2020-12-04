# Spring注解之模式注解

  写这个的原因是因为最近再研究东西的时候突然发现Springmvc的Controller其实也是spring的bean（主要是之前没有关心过他是不是bean），那么也就是说Controller注解和Service注解还有Repository注解一样可以把类注入到spring里面，那spring再注入的时候如何判断呢？难道是三个注解都要判断一次吗，spring 3.x 4.x以后新增了大量的注解比如Configuration等也要判断吗？spring的代码不会这么不优雅吧。带着疑问我点进了Controller注解内部看了一下，

```java
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Controller
```

然后又看了一下Service注解内部

```java
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Service
```

这两个注解居然差不多，前两个是注解的作用范围和生命周期，Documented是生成javadoc文档的，Component是什么呢？？众所周知Component注解也可以用于类上面让这个类变成spring的bean，难道Service和Controller注解能把类注入到 spring里面也是Component注解的功劳？好吧，让我们来验证一下。以下代码的spring版本都是2.5.6，原因是简单。

  新建一个注解

```java
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface ZyfComponent {

}
```

​                这个注解和Controller和Service一样，然后新建一个类，这个类应该新建在xml文件里context:component-scan的包扫描路径下的包里要不然spring也扫描不到。

```java

@ZyfComponent
public class StringComponent {

    public void test() {
        System.out.println("注入成功");
    }

}
```

然后我随便在一个类里面注入这个类

```java
    @Autowired
    private StringComponent stringComponent;
```

然后在下面调用`StringComponent.test()` 然后发现成功了![](http://bed.thunisoft.com:9000/ibed/2019/08/04/bacc8e40ef86418bae0a457d6da8874b.png)

到目前为止我们就破案了，**Component注解正是Controller和Service等注解可以把类注入到spring中的原因**，

然后我专门去[spring的官方网站上的说明文档上](<https://spring.io/projects>)找到了关于Component注解的说明（spring官网上最低版本的说明是4.3.25，但是这部分内容应该从2.5.6之后就没有变过了），连接在这里[说明文档](<https://docs.spring.io/spring/docs/4.3.25.RELEASE/spring-framework-reference/htmlsingle/#beans-stereotype-annotations>)![](http://bed.thunisoft.com:9000/ibed/2019/08/04/4163ca50c7a84b609a26f6a0eed147ff.png)

重点我帮大家勾出来了，

```
@Component is a generic stereotype for any Spring-managed component. @Repository, @Service, and @Controller are specializations of @Component for more specific use cases, for example, in the persistence, service, and presentation layers, respectively. Therefore, you can annotate your component classes with @Component
```

在spring官方文档中我们可以叫Component注解为**模式注解**，所以被Component注解注解了的Servce注解Controlle注解可以被Spring认为是候选组件的注解，还有我们自定义的注解。由于Component注解可以作用于注解上所以Component注解又可以叫元注解，当然了为了好理解其实也可以叫他“父注解”，他是Controller Service等注解的父注解，Controller Service注解是他的子注解或者派生注解。

## @Component注解的派生性原理

  了解到了Component注解的概念之后，我又很好奇spring源码之中是怎么来使用这个注解的。当然了我现在能力有限我并不能靠自己的力量来找出使用了这个注解的spring源码，所以我查阅了资料和谷歌之后，找到了地方。首先我们在刚刚新建类的时候要求新建在xml文件里context:component-scan的包扫描路径下的包里，context:component-scan这个元素是spring自己的元素，按照spring自己的可扩展XML编写的规范，这个自己的元素必须要和一个Java类做一个映射，配置是配置在spring的jar包里的/META—INF/Spring.handler里面

```
http\://www.springframework.org/schema/context=org.springframework.context.config.ContextNamespaceHandler
```

然后我们找到`ContextNamespaceHandler`这个类，找到他的init方法

```java
public void init() {
		registerBeanDefinitionParser("property-placeholder", new PropertyPlaceholderBeanDefinitionParser());
		registerBeanDefinitionParser("property-override", new PropertyOverrideBeanDefinitionParser());
		registerJava5DependentParser("annotation-config",
				"org.springframework.context.annotation.AnnotationConfigBeanDefinitionParser");
		registerJava5DependentParser("component-scan",
				"org.springframework.context.annotation.ComponentScanBeanDefinitionParser");
		registerBeanDefinitionParser("load-time-weaver", new LoadTimeWeaverBeanDefinitionParser());
		registerBeanDefinitionParser("spring-configured", new SpringConfiguredBeanDefinitionParser());
		registerBeanDefinitionParser("mbean-export", new MBeanExportBeanDefinitionParser());
		registerBeanDefinitionParser("mbean-server", new MBeanServerBeanDefinitionParser());
	}
```

找到这一行	`registerJava5DependentParser("component-scan",
			"org.springframework.context.annotation.ComponentScanBeanDefinitionParser");`

因为我们关心的是component-scan这个元素。点进去看到会把第二个参数解析器的名字实例化出来然后调用解析器的parse方法，那么我们就到`ComponentScanBeanDefinitionParser`这个类里面去看看

```java
public class ComponentScanBeanDefinitionParser implements BeanDefinitionParser {

	private static final String BASE_PACKAGE_ATTRIBUTE = "base-package";
    ...


	public BeanDefinition parse(Element element, ParserContext parserContext) {
		String[] basePackages =
				StringUtils.commaDelimitedListToStringArray(element.getAttribute(BASE_PACKAGE_ATTRIBUTE));

		// Actually scan for bean definitions and register them.
		ClassPathBeanDefinitionScanner scanner = configureScanner(parserContext, element);
		Set<BeanDefinitionHolder> beanDefinitions = scanner.doScan(basePackages);
		registerComponents(parserContext.getReaderContext(), beanDefinitions, element);

		return null;
	}
```

当`ComponentScanBeanDefinitionParser`读取了base-package属性后，属性值作为扫描根路径，传入`ClassPathBeanDefinitionScanner`的`doScan`方法内，并且返回`BeanDefinitionHolder`的集合。而`BeanDefinitionHolder`这个类包含了bean的定义和bean名称相关的内容

```java
public class BeanDefinitionHolder implements BeanMetadataElement {

	private final BeanDefinition beanDefinition;

	private final String beanName;

	private final String[] aliases;
```

那么我们可以推测出要注入的bean是放在了`Set<BeanDefinitionHolder> beanDefinitions` 这个集合里面了，那么就是在`ClassPathBeanDefinitionScanner`的`doScan` 里面就有我们所探寻的问题的答案了。

```java
	protected Set<BeanDefinitionHolder> doScan(String... basePackages) {
		Set<BeanDefinitionHolder> beanDefinitions = new LinkedHashSet<BeanDefinitionHolder>();
		for (int i = 0; i < basePackages.length; i++) {
			Set<BeanDefinition> candidates = findCandidateComponents(basePackages[i]);
			for (BeanDefinition candidate : candidates) {
				String beanName = this.beanNameGenerator.generateBeanName(candidate, this.registry);
				if (candidate instanceof AbstractBeanDefinition) {
					postProcessBeanDefinition((AbstractBeanDefinition) candidate, beanName);
				}
				ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(candidate);
				if (checkCandidate(beanName, candidate)) {
					BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(candidate, beanName);
					definitionHolder = applyScope(definitionHolder, scopeMetadata);
					beanDefinitions.add(definitionHolder);
					registerBeanDefinition(definitionHolder, this.registry);
				}
			}
		}
		return beanDefinitions;
	}
```

我们可以看到这个地方会在basePackages中调用`findCandidateComponents`然后找到候选的`BeanDefinition`集合。而`findCandidateComponents`方法我们点击去看发现是在其父类`ClassPathScanningCandidateComponentProvider`中实现的

```java
	public Set<BeanDefinition> findCandidateComponents(String basePackage) {
		Set<BeanDefinition> candidates = new LinkedHashSet<BeanDefinition>();
		try {
			String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
					resolveBasePackage(basePackage) + "/" + this.resourcePattern;
			Resource[] resources = this.resourcePatternResolver.getResources(packageSearchPath);
			boolean traceEnabled = logger.isTraceEnabled();
			boolean debugEnabled = logger.isDebugEnabled();
			for (int i = 0; i < resources.length; i++) {
				Resource resource = resources[i];
				if (traceEnabled) {
					logger.trace("Scanning " + resource);
				}
				if (resource.isReadable()) {
					MetadataReader metadataReader = this.metadataReaderFactory.getMetadataReader(resource);
					if (isCandidateComponent(metadataReader)) {
						ScannedGenericBeanDefinition sbd = new ScannedGenericBeanDefinition(metadataReader);
						sbd.setResource(resource);
						sbd.setSource(resource);
						if (isCandidateComponent(sbd)) {
							if (debugEnabled) {
								logger.debug("Identified candidate component class: " + resource);
							}
							candidates.add(sbd);
							....
```

看源码发现`String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
					resolveBasePackage(basePackage) + "/" + this.resourcePattern;`这一行先将xml里配置的java package路径转化成文件资源路径，比如com.thunisoft.jxjs换成com/thunisoft/jxjs，然后有趣 的是在`resolveBasePackage`方法里会去替换占位符，里面是

```java
protected String resolveBasePackage(String basePackage) {
		return ClassUtils.convertClassNameToResourcePath(SystemPropertyUtils.resolvePlaceholders(basePackage));
	}
```

有意思的是，`SystemPropertyUtils.resolvePlaceholders` 这个方法是spring里面去专门替换${}占位符的，在笔者的四月份的帖子[PropertiesFactoryBean是如何去获取环境变量中的值来替换${}中的值的。](http://artery.thunisoft.com/posts/detail/814645fc8bea4e688fb4e718bfb1b73e)中介绍了在Spring的`PropertiesFactoryBean`中也是用的`SystemPropertyUtils.resolvePlaceholders` 这个来替换的占位符的内容，刚好这个类的方法都是public的大家也可以在自己项目里直接用这个工具类来替换占位符。

 然后拿到`Resource[] resources = this.resourcePatternResolver.getResources(packageSearchPath);`通过这句代码拿到类资源的集合，当资源可读时`resource.isReadable()` 为true的时候就通过`MetadataReader metadataReader = this.metadataReaderFactory.getMetadataReader(resource);`获取该资源的`MetadataReader `对象。这个是一个接口，一般大家都接触不到，但是在这个接口内部有读取这个类的元注解的方法

```java
public interface MetadataReader {

	/**
	 * Read basic class metadata for the underlying class.
	 */
	ClassMetadata getClassMetadata();

	/**
	 * Read full annotation metadata for the underlying class.
	 */
	AnnotationMetadata getAnnotationMetadata();

}
```

下面继续看，可以看到这个候选的对象能不能成为真正的注入的对象是由两次`isCandidateComponent`方法决定的，这个方法内部

```java
	protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
		for (TypeFilter tf : this.excludeFilters) {
			if (tf.match(metadataReader, this.metadataReaderFactory)) {
				return false;
			}
		}
		for (TypeFilter tf : this.includeFilters) {
			if (tf.match(metadataReader, this.metadataReaderFactory)) {
				return true;
			}
		}
		return false;
	}

```

判断是根据类的`excludeFilters`和`includeFilters` 属性决定的，这两个属性在刚刚的代码里都没有初始化过，那么我们猜测是不是在构造方法里面初始化，所以我们倒回去找一下`ClassPathBeanDefinitionScanner` 是怎么初始化的。在刚刚代码里找到`ClassPathBeanDefinitionScanner scanner = configureScanner(parserContext, element);`也就是doScan方法上一行里面，

```java
protected ClassPathBeanDefinitionScanner configureScanner(ParserContext parserContext, Element element) {
		XmlReaderContext readerContext = parserContext.getReaderContext();

		boolean useDefaultFilters = true;
		if (element.hasAttribute(USE_DEFAULT_FILTERS_ATTRIBUTE)) {
			useDefaultFilters = Boolean.valueOf(element.getAttribute(USE_DEFAULT_FILTERS_ATTRIBUTE));
		}

		// Delegate bean definition registration to scanner class.
		ClassPathBeanDefinitionScanner scanner = createScanner(readerContext, useDefaultFilters);
		...
```

在`ClassPathBeanDefinitionScanner scanner = createScanner(readerContext, useDefaultFilters);`

里面new了一个`ClassPathBeanDefinitionScanner` ，然后我们去构造方法里看

```java
public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry, boolean useDefaultFilters) {
		super(useDefaultFilters);
		....
```

调用了父类的构造方法，那我们再进去看看，

```java
public ClassPathScanningCandidateComponentProvider(boolean useDefaultFilters) {
		if (useDefaultFilters) {
			registerDefaultFilters();
		}
```

```java
	protected void registerDefaultFilters() {
		this.includeFilters.add(new AnnotationTypeFilter(Component.class));
	}
```

大家看到了吗？！，果然在构造方法里初始化了`includeFilters`这个对象，然后放进去的还是`Component.class`这个注解，那么到这里我们的谜底就揭晓了，Spring里面是在这里把Component注解当成条件放到过滤器里，然后下面再判断的时候取出这个类的注解的元注解看是否有Component注解，如果有的话就成为候选注入的bean了。当然了`ClassPathBeanDefinitionScanner ` 也支持自定义规律规则，比如Dubbo的Service注解就是在没有标注Component注解的情况下也能注入到Spring中。那么我们再试试自定义过滤规则把，我们在Spring的XML文件里试试。我们在刚刚的xml配置文件里加上如下信息

```
<context:component-scan
		base-package="com.thunisoft.fy.jxjs.springmvc">
		<context:include-filter type="annotation"
			expression="com.thunisoft.fy.jxjs.springmvc.annotation.ZyfComponent" />
	</context:component-scan>
```

然后再刚刚的ZyfComponent注解里去掉Component注解，然后重启系统，继续再刚刚地方里继续调用`StringComponent.test()` 然后发现成功了

![](http://bed.thunisoft.com:9000/ibed/2019/08/04/f619a3c1d09d4752914e31553e5391d2.png)

下次会继续和大家分享Component注解的多层次派生性，这个就是高版本Spring的特性了，比如SpringBoot里面的哪些注解也是Component注解的子注解吗？想了解高版本Spring在过滤注解的时候和低版本Spring有哪些区别吗，敬请下一次把。