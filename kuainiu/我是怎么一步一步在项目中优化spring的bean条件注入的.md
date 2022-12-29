# 我是怎么一步一步在项目中优化spring的bean条件注入的



## 1、需求背景

我们的系统的对象存储功能，因为某些原因，只能在测试环境使用minio，然后在生产上使用腾讯的cos，后面因为有海外业务可能会在海外部署的时候换成aws的S3也可能换成阿里云的OSS，所以就要求代码里提前适配好这几种对象存储。需求一提出来，我第一反应就：这不典型的策略方法的使用场景吗？于是我快速的写了第一版出来：

策略模式从我还刚毕业就开始写了，熟的不能再熟了。我们先需要一个接口来抽象定义我们的对象存储：

```java
public interface ModelStorageRepository {

    /**
     * 上传模型
     *
     * @param downloadUrl :模型下载路径
     * @param fileName    :模型文件名
     * @author zhangyunfan
     * @date 2022/11/23
     */
    boolean uploadModel(@NonNull String downloadUrl, @NonNull String fileName);

    /**
     * 下载模型
     *
     * @param fileName :模型文件名
     * @author zhangyunfan
     * @date 2022/11/23
     */
    boolean downloadModel(@NonNull String fileName);

}
```

然后就是怎么来获取我真正要使用到的这个接口的实现类了。



## 2、第一版方案——工厂模式

我们在刚刚的接口里新增一个方法：

```java
   /**
     * 存储仓库类型
     *
     * @return 处理的仓库类型
     * @author zhangyunfan
     * @date 2022/11/23
     */
    String storageRepositoryType();
```

方法的目的是为了让实现类来声明自己支持的存储库类型。

然后我们来写我们的工厂：

```java
@Component
@Slf4j
public class ModelStorageRepositoryFactory {

    @Autowired
    private List<ModelStorageRepository> storageRepositoryList;

    private Map<String, ModelStorageRepository> modelStorageRepositoryMap;

    @PostConstruct
    public void init() {
        if (CollectionUtils.isEmpty(storageRepositoryList)) {
            log.error("请注入模型存储仓库实现");
            return;
        }
        modelStorageRepositoryMap = storageRepositoryList.stream()
                .collect(Collectors.toMap(ModelStorageRepository::storageRepositoryType, a -> a, (k1, k2) -> k2));
    }

    public ModelStorageRepository getModelStorageRepository(String storageRepositoryType) {
        return modelStorageRepositoryMap.get(storageRepositoryType);
    }

}
```

首先我们用spring的特性，把所有实现了这个接口的类注入成一个list，然后再用`PostConstruct`注解来在spring加载完成之后把list转成一个map，key就是我们刚刚定义的第一个方法即每个实现类声明的类型。然后获取的时候直接传入我们在配置文件中定义好的当前环境的存储库类型就可以获取到实现类了。

### 2.1问题



这样写粗看起来什么问题，但是仔细一想：我每个环境都只用一个存储仓库，也就说这个工厂模式`getModelStorageRepository`方法始终返回值都是同一个，当然了我也可以在启动的时候就把这个固定的返回值写到内存里，但是我还是觉得不够完美，因为其他实现类也被我注入到spring里了，我完全可以只注入一个实现类到spring里不就完了吗？如果我只注入了一个实现类，那么我在使用的时候直接注解注入就完了就可以不用工厂模式来获取了嘛。



## 3、第二版方案——ConditionalOnProperty

既然我们的最终目的就是不让所有实现类都注入到spring里，那么我们是不是可以用到spring自己的特性来实现。不让所有实现类注入换句话说就是有条件的让指定的实现类注入搭配spring里，我们第一个想到的就是spring的注解——`ConditionalOnProperty`

我们定一个config类：

```java
@Configuration
public class ModelStorageRepositoryConfig {

    @Bean
    @ConditionalOnProperty(name = "storageRepositoryType", havingValue = "cos")
    public ModelStorageRepository getCosModelStorageRepository() {
        return new CosModelStorageRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "storageRepositoryType", havingValue = "minio")
    public ModelStorageRepository getMinioModelStorageRepository() {
        return new MinioModelStorageRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "storageRepositoryType", havingValue = "oss")
    public OssModelStorageRepository getOssModelStorageRepository() {
        return new OssModelStorageRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "storageRepositoryType", havingValue = "s3")
    public S3ModelStorageRepository getS3ModelStorageRepository() {
        return new S3ModelStorageRepository();
    }
}
```

我们把每个实现类都写上，然后用`ConditionalOnProperty`注解来表明当前环境我们需要注入的是哪个实现类。

`storageRepositoryType`是我配置在配置文件中的配置名，havingValue表示当这个配置的值是多少的时候才入职这个方法对应的bean，然后我们在使用的时候就远远比工厂方法简单太多了，我们直接：

```java
@Autowired
private ModelStorageRepository modelStorageRepository;
```

这样注入就可以使用了。

### 3.1 问题

这样看起来似乎是简单了点，但是这里有一个问题相比于上面工厂模式的在于：配置值和实现类的对应关系只在这个bean方法的上并不如上面工厂模式让实现类自己声明类型来的直接，也就是说后面我们在新增实现类的时候，只能在这个注解的bean方法上确定我们实现类和配置值的对应关系。



## 4、第三版方案——Conditional

那么我们可以结合第一版和第二版的优缺点，即把配置和实现类的对应关系写在实现类上，然后还是有条件的注入，那么这个时候我们从八股库里就可以找到我们的第三样武器——`Conditional`注解。

我们把刚刚的接口改为抽象类，然后实现spring的 `Conditional`接口，实现macth方法:

```java
public abstract class ModelStorageRepository implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StringUtils.equals(storageRepositoryType(), context.getEnvironment().getProperty("storageRepositoryType"));
    }


    /**
     * 存储仓库类型
     *
     * @return 处理的仓库类型
     * @author zhangyunfan
     * @date 2022/11/23
     */
    protected abstract String storageRepositoryType();

    /**
     * 上传模型
     *
     * @param downloadUrl :模型下载路径
     * @param fileName    :模型文件名
     * @author zhangyunfan
     * @date 2022/11/23
     */
    public abstract boolean uploadModel(@NonNull String downloadUrl, @NonNull String fileName);

    /**
     * 下载模型
     *
     * @param fileName :模型文件名
     * @author zhangyunfan
     * @date 2022/11/23
     */
    public abstract boolean downloadModel(@NonNull String fileName);

}
```

我们还是像工厂模式那样，新增一个方法声明实现类对应的类型，然后抽象类自己实现match方法，就是看实现类返回的处理类型和配置的是否一致，然后我们的config可以改造成：

```java
@Configuration
public class ModelStorageRepositoryConfig {

    @Bean
    @Conditional(CosModelStorageRepository.class)
    public ModelStorageRepository getCosModelStorageRepository() {
        return new CosModelStorageRepository();
    }

    @Bean
    @Conditional(MinioModelStorageRepository.class)
    public ModelStorageRepository getMinioModelStorageRepository() {
        return new MinioModelStorageRepository();
    }

    @Bean
    @Conditional(OssModelStorageRepository.class)
    public OssModelStorageRepository getOssModelStorageRepository() {
        return new OssModelStorageRepository();
    }

    @Bean
    @Conditional(S3ModelStorageRepository.class)
    public S3ModelStorageRepository getS3ModelStorageRepository() {
        return new S3ModelStorageRepository();
    }
}
```

这样相比于第二种方案，我们没有把实现类和配置值的对应关系写在注解里，而是放在每个实现里去声明，这样看起来简洁不少，也让对应关系更加清楚。



### 4.1 问题

这个方案比起上面的可以说是几乎是最终方案了，但是因为本人强迫症的原则又想了一下，这个方案的问题在于——我每新增一个新的实现类我都要在这个config里新增一个bean方法，也就是说如果我的实现类越来越多，那我这个config里的方法也就越来越多，而且都还差不多，这是我不能接受的（虽然目前业务场景下越来越多的可能性非常小，但是不排除说以后有类似的业务场景，而这个场景就非常可能实现类越来越多）



## 5、最终方案

我第一个反应是是不是可以这个config类的代码不用我们自己写，可以借鉴Dubbo的spi源码——在内存中动态生成我们的config代码，然后用javac编译(java自举，第三方封装好的javac工具包我都找到了：https://github.com/michaelliao/compiler)。但是在本人反复尝试中，宣告失败，失败的原因主要是：

1、我要获取这个接口所有的实习类来拼接代码，而这个只能用反射实现，在业务系统中用反射我是万万不能接受的，大家也最好别用，谁用了我是一定要拷打的，至于为什么不能用这里简单说一下：反射会让你的程序失去静态分析能力，何为静态分析能力？比如说我不启动项目，我在idea中可以直接查看哪些地方用了这个方法，如果我用了反射，我的程序不运行我就永远不知道这个地方还用了这个方法的？那如果我对这个方法有改动，那反射调用这里一定就会失败，这里的方法可以是属性可以是类，总之如果用了反射你的程序就失去了不运行就能分析的能力，java可是静态语言啊。

2、javac编译代码性能堪忧，会拖慢我的项目启动时间。dubbo那一套应该是有优化的，我自己用的原生javac编译感觉非常慢，完全不能接受。

所以我们接着思考，既然是不能写太多方法来注入，那我们是不是可以





顺着刚刚的思路，我不想在config类中写太多方法来注入，那么我是不是可以在一个方法里用代码来注入，类似于

```java
@Bean
public ModelStorageRepository getBean() {
     if(config.equls("oss")){
				...
			}
			if(config.equls("oss")){
				...
			}
}

```

当然了这样写太多了if else了，于是我想到了我们可以用枚举来封装整理我们的配置名和实现类的关系：

```java
@AllArgsConstructor
@Getter
public enum StorageRepositoryTypeEnum {

    /**
     * minio 存储
     */
    MINIO("minio", MinioModelStorageRepository.class),

    /**
     * 腾讯云COS
     */
    TENCENT_CLOUD_COS("cos", CosModelStorageRepository.class),

    /**
     * 阿里云 oss
     */
    ALIBABA_OSS("oss", OssModelStorageRepository.class),

    /**
     * aws S3
     */
    AWS_S3("s3", S3ModelStorageRepository.class);

    /**
     * 配置简称，对应配置文件里的storageRepository配置值
     */
    private String storageRepositoryType;

    /**
     * 存储类型对应的实现类
     */
    private Class<? extends ModelStorageRepository> implementClass;

    public static StorageRepositoryTypeEnum getStorageRepositoryTypeEnum(String storageRepositoryType) {
        for (StorageRepositoryTypeEnum ele : values()) {
            if (StringUtils.equals(ele.getStorageRepositoryType(), storageRepositoryType)) {
                return ele;
            }
        }
        return null;
    }

}
```

那么我们就可以直接用一个config的bean方法来搞定了：

```java
@Configuration
public class ModelStorageRepositoryConfig {

    @Value("${storageRepository}")
    private String storageRepository;

    @Bean
    public ModelStorageRepository getCosModelStorageRepository() throws Exception {
        StorageRepositoryTypeEnum storageRepositoryTypeEnum = StorageRepositoryTypeEnum.getStorageRepositoryTypeEnum(storageRepository);
        if (storageRepositoryTypeEnum == null) {
            throw new BeanCreationException("配置文件里的storageRepository配置" + storageRepository + " 没有在StorageRepositoryTypeEnum枚举中注册信息！");
        }
        return storageRepositoryTypeEnum.getImplementClass().getDeclaredConstructor().newInstance();
    }
}
```

利用枚举来封装整理配置名和实现类的对应关系，这样我们新增一个实现类只需要在枚举中新增一行就好了，比起上面的简洁不少。

当然了这里有一个伏笔，就是我们在最后还是不得不使用了反射来创建我们的实现类的实例，其实优化到这里差不多了，但是由于笔者强烈的强迫症，为了彻底消灭代码里的反射，于是笔者又想了一个方案。

首先我们可以整理一下spring bean的注入方式——注解注入、config方式注入，其实还有一种代码注入——使用`BeanFactory`来手动注入bean，这样我们就可以传入bean的类型即可，spring自己会创建bean。

这里还有个知识点，即spring的钩子函数，我们是想在spring刚启动的时候来创建这个bean，于是我们可以利用aware接口：

```java
public class ModelStorageRepositoryAware implements ApplicationContextAware {


    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        String storageRepository = applicationContext.getEnvironment().getProperty("storageRepository");
        StorageRepositoryTypeEnum storageRepositoryTypeEnum = StorageRepositoryTypeEnum.getStorageRepositoryTypeEnum(storageRepository);
        if (storageRepositoryTypeEnum == null) {
            throw new BeanCreationException("配置文件里的storageRepository配置" + storageRepository + " 没有在StorageRepositoryTypeEnum枚举中注册信息！");
        }
        GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
        beanDefinition.setBeanClass(storageRepositoryTypeEnum.getImplementClass());
        beanFactory.registerBeanDefinition(storageRepositoryTypeEnum.getImplementClass().getSimpleName(), beanDefinition);
    }
 }
```

关键代码还是和上文的差不多即用了枚举来确定是用哪个实现类，这里主要的区别是用了`beanFactory.registerBeanDefinition`来创建并注入我们的bean。

这里还有点小问题 ，就是bean的注入顺序，如果我们的这个`ModelStorageRepositoryAware`类在使用了存储仓库的类之后注入的话，spring就会报错没有实现类对应的bean。所以这里还有一个知识点是bean加载的顺序。我们都大概听过`order`注解可以控制bean加载顺序，实际上这个是错误的，`order`注解只能控制同一个父类的字类或者实现了同一个接口的类的bean在注入的List里的顺序并不能控制spring加载的顺序。所以这里有个方法是用`DependsOn`注解，表明我们当前这个bean必须要依赖于指定的bean加载，我们就在使用了`ModelStorageRepository`接口的类上面加一个注解：

```java
@DependsOn("modelStorageRepositoryAware")
```

这样可以保证我们的`ModelStorageRepositoryAware`类一定在使用的类之前加载，但是这样对调用者非常不友好。于是有第三种方法就是通过实现我们的`BeanFactoryAware`和`InstantiationAwareBeanPostProcessor`接口，来手动让`ModelStorageRepositoryAware`在第一个加载:

```java
    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
            throw new IllegalArgumentException("AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
        }
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }

    @Override
    public boolean postProcessAfterInstantiation(Object bean, String beanName) throws BeansException {
        if (FIRST_BEAN_NAME.equals(beanName)) {
            beanFactory.getBean(ModelStorageRepositoryAware.class);
        }
        return true;
    }
```

完整的`ModelStorageRepositoryAware`如下:

```java
@Component
@Slf4j
public class ModelStorageRepositoryAware implements ApplicationContextAware, BeanFactoryAware, InstantiationAwareBeanPostProcessor {

    private ConfigurableListableBeanFactory beanFactory;

    private static String FIRST_BEAN_NAME = "application";

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        String storageRepository = applicationContext.getEnvironment().getProperty("storageRepository");
        StorageRepositoryTypeEnum storageRepositoryTypeEnum = StorageRepositoryTypeEnum.getStorageRepositoryTypeEnum(storageRepository);
        if (storageRepositoryTypeEnum == null) {
            throw new BeanCreationException("配置文件里的storageRepository配置" + storageRepository + " 没有在StorageRepositoryTypeEnum枚举中注册信息！");
        }
        GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
        beanDefinition.setBeanClass(storageRepositoryTypeEnum.getImplementClass());
        beanFactory.registerBeanDefinition(storageRepositoryTypeEnum.getImplementClass().getSimpleName(), beanDefinition);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
            throw new IllegalArgumentException("AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
        }
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }

    @Override
    public boolean postProcessAfterInstantiation(Object bean, String beanName) throws BeansException {
        if (FIRST_BEAN_NAME.equals(beanName)) {
            beanFactory.getBean(ModelStorageRepositoryAware.class);
        }
        return true;
    }

}
```



最后就可以达到我们想要的效果，即：只有一个实现类注入到了spring中、配置名和实现类的对应关系较为清楚和直接、没有过多的方法和if else、没有使用反射。最后的好处就是扩展的时候相当于简单，只需要新增一个实现类然后在新增一个枚举就可以了。

### 6、总结

这里我们通过优化一个简单的策略模式的代码可以更好的理解spring的bean注入方式、bean条件注入、spring的钩子函数、spring的bean加载顺序等知识点。