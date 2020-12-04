# SpringBoot的启动原理

##### 众所周知SpringBoot项目是有两种启动方法—可以用`mvn package` 命令打包成一个可执行的JAR包，然后用`java -jar` 命令来启动；也可以直接在源码目录下执行`mvn spring-boot:run`，这个是有别于传统web项目的war包+中间件启动区别，那么SpringBoot是如何可以同时来实现这两种方法启动的呢？

​	首先我们新建一个SpringBoot项目，可以在idea中新建也可以在[SpringBoot官方][https://start.spring.io/]上新建一个。然后我们在目录下执行`mvn package` 命令将刚刚新建的SpringBoot项目打包，在target目录下我们就可以看到打包过后的SpringBoot的Jar包，如图所示![微信截图_20190421135319.png](http://172.16.14.82:9000/ibed/2019/04/21/e162cce95b084eacab399bc313526937.png)

  demo-0.0.1-SNAPSHOT.jar 就是我们打包之后的SpringBoot的 JAR包，我们来看下JAR的目录结构分为三个文件夹——org、BOOT——INF、META——INFA三个文件夹，这三个文件夹的作用分别是

- BOOT-INF/classes 存放项目的代码编译后的class文件;

- BOOT-INF/lib 目录存放应用依赖的JAR包;

- META—INF/ 目录存放应用相关的原信息，比如很重要的MANIFEST.MF文件

- org/目录存放SpringBoot相关的一些class文件

  另外一个几乎是同名的文件demo-0.0.1-SNAPSHOT.jar.original里面则只有META—INF和项目自己的代码的calss文件，事实上demo-0.0.1-SNAPSHOT.jar.original 是原始Maven打包后的jar包文件，而demo-0.0.1-SNAPSHOT.jar则是通过SpringBoot加工过后的JAR包文件，有意思的是SpringBoot加工之后的JAR文件的目录结构和传统的WAR包方式相当相像，WAR包的编译后的class文件和依赖的JAR包是存放在WEB-INF下而SpringBoot是存在BOOT-INF下。

  打成JAR包之后我们就可以用`java -jar` 命令来启动JAR包了，我们知道`java -jar` 命令是java的命令并不是SpringBoot的命令，也就是说我们在使用java -jar命令时这个命令本身并不知道这个jar是不是SpringBoot项目，根据JAVA官方文档的规定，使用java -jar启动的jar内的启动类是配置在刚刚说道的MANIFEST.MF这个文件的Mamin-class属性中的，我们打开MANIFEST.MF文件找到Mamin-class属性，发现此时配置的值是`Main-Class: org.springframework.boot.loader.JarLauncher` 这个居然不是我们SpringBoot中配置的带有SpringBootApplication注解的启动类。那我们就来分析一下`JarLauncher` 这个SpringBoot的启动类。

​	首先 我们要来找到JarLauncher的源码，直接在我们刚刚新建的SpringBoot的应用中Ctrl+shift+t并不能搜到，说明我们并没有把该类的包引进来，我们进入刚刚的JAR包内的org文件下可以找到JarLauncher` .class，复制出来用反编译工具打开发现这个类在`org.springframework.boot.loader` 包下，我们在项目中中引进这个这个包，现在时候就能搜索到JarLauncher这个类了

```java
public class JarLauncher extends ExecutableArchiveLauncher {
    static final String BOOT_INF_CLASSES = "BOOT-INF/classes/";
    static final String BOOT_INF_LIB = "BOOT-INF/lib/";

    public JarLauncher() {
    }

    protected JarLauncher(Archive archive) {
        super(archive);
    }

    protected boolean isNestedArchive(Entry entry) {
        return entry.isDirectory() ? entry.getName().equals("BOOT-INF/classes/") : entry.getName().startsWith("BOOT-INF/lib/");
    }

    public static void main(String[] args) throws Exception {
        (new JarLauncher()).launch(args);
    }
}
```

这个类有一个main方法，在执行命令的时候实际上调用的是这个main方法，这个方法调用的是JarLauncher类的lauch方法，进入到这个方法内部发现这个方法是在JarLauncher类的父类的父类Launcher类中实现的。

```
/**
	 * Launch the application. This method is the initial entry point that should be
	 * called by a subclass {@code public static void main(String[] args)} method.
	 * @param args the incoming arguments
	 * @throws Exception if the application fails to launch
	 */
	protected void launch(String[] args) throws Exception {
		JarFile.registerUrlProtocolHandler();
		//创建类加载器
		ClassLoader classLoader = createClassLoader(getClassPathArchives());
		//调用重载的lauch方法
		launch(args, getMainClass(), classLoader);
	}
```

首先 我们可以看到在创建类加载器的时候会向createClassLoader放入一个getClassPathArchives方法的返回值，getClassPathArchives方法是在子类ExecutableArchiveLauncher中实现的

```
@Override
	protected List<Archive> getClassPathArchives() throws Exception {
		List<Archive> archives = new ArrayList<>(
				this.archive.getNestedArchives(this::isNestedArchive));
		postProcessClassPathArchives(archives);
		return archives;
	}

```

isNestedArchive方法是在子类JarLauncher中实现的

```
@Override
	protected boolean isNestedArchive(Archive.Entry entry) {
		if (entry.isDirectory()) {
			return entry.getName().equals(BOOT_INF_CLASSES);
		}
		return entry.getName().startsWith(BOOT_INF_LIB);
	}
```

作用是过过滤Archive.Entry对象即当前jar包或者文件夹下每个成员，entry。getName是获取的在当前的绝对路径，意思是只要这个对象是在BOOT-INF/classes/或者BOOT-INF/lib/下面这个类就是我们要加载的类。Archive是一个接口，这个有两种实现——基于文件系统的实现ExplodedArchive和基于JAR包方式的实现JarFileArchive，下文我们会讲到到底是用的哪种实现。

我们在调用重载的lauch方法里调用了一个getMainClass方法，这个方法在Launcher类中是一个抽象方法，具体实现是JarLauncher类的父类方法ExecutableArchiveLauncher类来实现的

```
@Override
	protected String getMainClass() throws Exception {
		//获取MAINIFREST文件
		Manifest manifest = this.archive.getManifest();
		String mainClass = null;
		if (manifest != null) {
			mainClass = manifest.getMainAttributes().getValue("Start-Class");
		}
		if (mainClass == null) {
			throw new IllegalStateException(
					"No 'Start-Class' manifest entry specified in " + this);
		}
		return mainClass;
	}
```

原来实际上非常简单就是获取MAINIFREST.MF文件然后获取到里面的start-class属性这个就是启动的mainclass。而这个start-calss就是我们在spingboot中的入口类Application。重点来分析下第一句代码获取MAINIFREST文件，调用的是this.archive.getManifest();而getManifest方法有两个实现JarFile和ExplodedArchive，那么用的是哪个实现呢？我们回到this.archive对象，这个是全局变量是在构造方法里赋值的

```
public ExecutableArchiveLauncher() {
		try {
			this.archive = createArchive();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
```

我们来看看createArchive();方法，这个方法是在父类Lauchaer类中实现的

```
protected final Archive createArchive() throws Exception {
		ProtectionDomain protectionDomain = getClass().getProtectionDomain();
		CodeSource codeSource = protectionDomain.getCodeSource();
		URI location = (codeSource != null) ? codeSource.getLocation().toURI() : null;
		String path = (location != null) ? location.getSchemeSpecificPart() : null;
		if (path == null) {
			throw new IllegalStateException("Unable to determine code source archive");
		}
		File root = new File(path);
		if (!root.exists()) {
			throw new IllegalStateException(
					"Unable to determine code source archive from " + root);
		}
		return (root.isDirectory() ? new ExplodedArchive(root)
				: new JarFileArchive(root));
	}
```

简单起见我们直接看最后一行

```
 (root.isDirectory() ? new ExplodedArchive(root)
				: new JarFileArchive(root));
```

意思是当前的路径生成的File对象是否文件夹，如果是则返回ExplodedArchive否则返回JarFileArchive，ExplodedArchive是文件夹系统的对于Archive的实现，JarFileArchive是jar包对于Archive的实现，**这也符合SpringBoot能够在jar包和文件夹中启动的事实，或者说这个也是能够在jar包中启动和文件夹中启动的原因之一**



拿到Mainclass之后然后调用Lauch方法

```
/**
	 * Launch the application given the archive file and a fully configured classloader.
	 * @param args the incoming arguments
	 * @param mainClass the main class to run
	 * @param classLoader the classloader
	 * @throws Exception if the launch fails
	 */
	protected void launch(String[] args, String mainClass, ClassLoader classLoader)
			throws Exception {
		Thread.currentThread().setContextClassLoader(classLoader);
		createMainMethodRunner(mainClass, args, classLoader).run();
	}

```

构造了一个MainMethodRunner对象调用MainMethodRunner对象的Run方法

```
public void run() throws Exception {
		Class<?> mainClass = Thread.currentThread().getContextClassLoader()
				.loadClass(this.mainClassName);
		Method mainMethod = mainClass.getDeclaredMethod("main", String[].class);
		mainMethod.invoke(null, new Object[] { this.args });
	}

```

在run方法中通过当前线程的类加载器把刚刚获取到的mainclass对象拿到，然后用反射调用其main方法，自此就完成了Spring-Boot的启动。**因为是在用java反射调用的main方法，实际上说明了在springboot 的启动类中的主方法不是一定得是main方法可以是其他的名字，调用main方法是为了java规范**

### 总结

启动的核心类是JarLauncher类，在该类中会调用其父类ExecutableArchiveLauncher和ExecutableArchiveLauncher的父类的Launcher的方法，这个其实就是策略模式，那么类图就是

![CoCall截图20190519150300.png](http://bed.thunisoft.com:9000/ibed/2019/05/19/acec274a6664443a88a2a4bf5756c1ed.png)

我们可以看到JarLaucher还有一个兄弟类——WarLaucher，实际上SpringBoot应用可以通过修改pom文件来生成war包的，这个WarLaucher就是生成的war包的启动类，下面我们来看下这两兄弟的差别。

JarLaucher的类成员变量

```
	static final String BOOT_INF_CLASSES = "BOOT-INF/classes/";

	static final String BOOT_INF_LIB = "BOOT-INF/lib/";

```

而WarLaucher

```
	private static final String WEB_INF = "WEB-INF/";

	private static final String WEB_INF_CLASSES = WEB_INF + "classes/";

	private static final String WEB_INF_LIB = WEB_INF + "lib/";

	private static final String WEB_INF_LIB_PROVIDED = WEB_INF + "lib-provided/";

```

这个成员变量的作用是在生成类构造器的时候判断当前文件对象是否在这个类变量制定的路径下，我们可以看到jarLaucher中指定的目录是Boot—INF而WarLaucher是指定的是Web—INF也就是我们传统war包下的这个装classes文件和lib依赖文件的目录。**实际上SpringBoot应用提供的war包打包方式只是一种兼容措施，既能被WarLauncher启动又能兼容Servlet容器环境，换言之，WarLauncher和JarLauncher并没有本质差别，所以建议SpringBoot应用尽可能的使用JAR方式打包启动**